package ru.danil.medtracker.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import ru.danil.medtracker.BuildConfig
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.data.db.Tombstone
import ru.danil.medtracker.ocr.PhotoStore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * «Умное сохранение» (раздел 9): полная копия + цепочки быстрых копий в каталог SAF.
 * Одна полная копия хранит базу целиком; быстрые — только изменения (п. 9.2).
 */
class BackupManager(private val container: AppContainer, private val context: Context) {

    private val repo = container.repo
    private val db get() = container.db
    private val settings get() = container.settings

    class BackupException(message: String) : Exception(message)

    // ── Каталог копий (п. 9.1) ────────────────────────────────────────────────

    fun tree(): DocumentFile {
        val uriStr = kotlinx.coroutines.runBlocking { settings.current() }.backupTreeUri
        if (uriStr.isBlank()) throw BackupException("Каталог копий не выбран")
        val uri = Uri.parse(uriStr)
        return DocumentFile.fromTreeUri(context, uri)
            ?: throw BackupException("Каталог копий недоступен — выберите папку заново")
    }

    fun setTree(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        kotlinx.coroutines.runBlocking { settings.setBackupTree(uri.toString()) }
    }

    // ── Создание копий ────────────────────────────────────────────────────────

    private fun timestampName(prefix: String, ext: String): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        return "MedTracker_${prefix}_${fmt.format(Date())}.$ext"
    }

    /** Полная копия: база целиком в JSON + все фото (п. 9.1). */
    suspend fun createFull(): String {
        val tree = tree()
        val name = timestampName("полная", "mtbackup")
        val file = tree.createFile("application/zip", name)
            ?: throw BackupException("Не удалось создать файл копии (нет места или доступа)")
        val fullId = settings.newFullId()
        val createdAt = System.currentTimeMillis()
        val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val patients = repo.patientDao.all()
            val drugs = repo.drugDao.all()
            val prescriptions = repo.prescriptionDao.all()
            val norms = repo.normDao.all()
            val issues = repo.issueDao.all()
            val rxs = repo.rxDao.all()
            val tombstones = repo.tombstoneDao.all()
            val dbj = DbJson(patients, drugs, prescriptions, norms, issues, rxs, tombstones)
            buildZip(
                manifest = BackupManifest(
                    schemaVersion = ru.danil.medtracker.data.db.AppDatabase.SCHEMA_VERSION,
                    appVersion = BuildConfig.VERSION_NAME,
                    type = "FULL",
                    fullId = fullId,
                    createdAt = createdAt,
                    counts = mapOf(
                        "patients" to patients.size,
                        "drugs" to drugs.size,
                        "prescriptions" to prescriptions.size,
                        "norms" to norms.size,
                        "issues" to issues.size,
                        "rxs" to rxs.size,
                        "photos" to collectAllPhotoNames(rxs).size,
                    ),
                ),
                db = dbj,
                photos = collectAllPhotoNames(rxs).map { it to PhotoStore.file(context, it) },
            )
        }
        writeUri(file.uri, data)
        settings.markFullBackup(fullId, createdAt)
        return name
    }

    /** Быстрая копия: записи с updatedAt > lastFullAt + tombstones + новые фото (п. 9.2). */
    suspend fun createDelta(): String {
        val tree = tree()
        val s = settings.current()
        if (s.lastFullId.isBlank()) throw BackupException("Сначала создайте полную копию")
        if (s.lastFullAt == 0L) throw BackupException("Сначала создайте полную копию")
        val name = timestampName("быстрая", "mtdelta")
        val file = tree.createFile("application/zip", name)
            ?: throw BackupException("Не удалось создать файл копии")
        val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val since = s.lastFullAt
            val patients = repo.patientDao.all().filter { it.updatedAt > since }
            val drugs = repo.drugDao.all().filter { it.updatedAt > since }
            val prescriptions = repo.prescriptionDao.all().filter { it.updatedAt > since }
            val norms = repo.normDao.all().filter { it.updatedAt > since }
            val issues = repo.issueDao.all().filter { it.updatedAt > since }
            val rxs = repo.rxDao.all().filter { it.updatedAt > since }
            val tombstones = repo.tombstoneDao.since(since)
            val photoNames = rxs.flatMap { it.photos() }.distinct()
                .filter { PhotoStore.file(context, it).lastModified() > since }
            val dbj = DbJson(patients, drugs, prescriptions, norms, issues, rxs, tombstones)
            val seq = (readDeltasInChain(tree, s.lastFullId).maxOfOrNull { it.seq ?: 0 } ?: 0) + 1
            buildZip(
                manifest = BackupManifest(
                    schemaVersion = ru.danil.medtracker.data.db.AppDatabase.SCHEMA_VERSION,
                    appVersion = BuildConfig.VERSION_NAME,
                    type = "DELTA",
                    fullId = s.lastFullId,
                    parentFullId = s.lastFullId,
                    seq = seq,
                    createdAt = System.currentTimeMillis(),
                    counts = mapOf(
                        "patients" to patients.size,
                        "issues" to issues.size,
                        "photos" to photoNames.size,
                        "tombstones" to tombstones.size,
                    ),
                ),
                db = dbj,
                photos = photoNames.map { it to PhotoStore.file(context, it) },
            )
        }
        writeUri(file.uri, data)
        settings.markDeltaBackup(System.currentTimeMillis())
        return name
    }

    private fun collectAllPhotoNames(rxs: List<Rx>): List<String> =
        rxs.flatMap { it.photos() }.distinct()

    private fun buildZip(
        manifest: BackupManifest,
        db: DbJson,
        photos: List<Pair<String, java.io.File>>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val dbBytes = BackupJson.encodeDb(db).toByteArray(Charsets.UTF_8)
        val sums = mutableMapOf<String, String>()
        sums["db.json"] = sha256(dbBytes)
        for ((name, f) in photos) {
            if (f.exists()) sums["photos/$name"] = sha256(f.readBytes())
        }
        val manifestFinal = manifest.copy(checksums = sums)
        ZipOutputStream(out).use { zip ->
            fun entry(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("manifest.json", BackupJson.encodeManifest(manifestFinal).toByteArray(Charsets.UTF_8))
            entry("db.json", dbBytes)
            for ((name, f) in photos) {
                if (f.exists()) entry("photos/$name", f.readBytes())
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeUri(uri: Uri, data: ByteArray) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                ?: throw BackupException("Нет доступа к каталогу копий")
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("Не удалось записать копию: ${e.message}")
        }
    }

    // ── Чтение контейнеров ────────────────────────────────────────────────────

    data class ParsedBackup(
        val manifest: BackupManifest,
        val db: DbJson,
        val photos: Map<String, ByteArray>,
    )

    fun readBackup(uri: Uri): ParsedBackup {
        val zipBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupException("Не удалось открыть файл копии")
        val manifest: BackupManifest
        val dbJson: DbJson
        val photos = mutableMapOf<String, ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            val manifestBytes = ByteArrayOutputStream()
            var manifestFound = false
            val dbBytes = ByteArrayOutputStream()
            var dbFound = false
            while (entry != null) {
                when (entry.name) {
                    "manifest.json" -> {
                        manifestBytes.write(zip.readBytes()); manifestFound = true
                    }
                    "db.json" -> {
                        dbBytes.write(zip.readBytes()); dbFound = true
                    }
                    else -> if (entry.name.startsWith("photos/")) {
                        photos[entry.name.removePrefix("photos/")] = zip.readBytes()
                    }
                }
                entry = zip.nextEntry
            }
            if (!manifestFound) throw BackupException("Файл не является копией Med Tracker (нет манифеста)")
            if (!dbFound) throw BackupException("Копия повреждена: нет базы данных")
            manifest = runCatching { BackupJson.decodeManifest(manifestBytes.toString("UTF-8")) }
                .getOrElse { throw BackupException("Манифест копии повреждён") }
            // Контрольная сумма db.json по исходным байтам (п. 9.5)
            val dbRaw = dbBytes.toByteArray()
            val expectedDb = manifest.checksums["db.json"]
            if (expectedDb != null && expectedDb != sha256(dbRaw)) {
                throw BackupException("Копия повреждена: контрольная сумма базы не совпадает")
            }
            dbJson = runCatching { BackupJson.decodeDb(dbRaw.toString("UTF-8")) }
                .getOrElse { throw BackupException("База в копии повреждена") }
        }
        // Контрольные суммы фото (п. 9.5)
        for ((name, bytes) in photos) {
            val expected = manifest.checksums["photos/$name"]
            if (expected != null && expected != sha256(bytes)) {
                throw BackupException("Фото $name в копии повреждено")
            }
        }
        // Копия из более новой версии приложения отклоняется (п. 9.11)
        if (manifest.schemaVersion > ru.danil.medtracker.data.db.AppDatabase.SCHEMA_VERSION) {
            throw BackupException("Копия создана более новой версией приложения — обновите приложение")
        }
        return ParsedBackup(manifest, dbJson, photos)
    }

    private fun readManifest(uri: Uri): BackupManifest? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "manifest.json") {
                    return BackupJson.decodeManifest(zip.readBytes().toString("UTF-8"))
                }
                e = zip.nextEntry
            }
        }
        null
    }.getOrNull()

    private data class DeltaInfo(val uri: Uri, val seq: Int, val createdAt: Long)

    private fun readDeltasInChain(tree: DocumentFile, fullId: String): List<DeltaInfo> {
        return tree.listFiles().mapNotNull { f ->
            if (!f.name.orEmpty().endsWith(".mtdelta")) return@mapNotNull null
            val m = readManifest(f.uri) ?: return@mapNotNull null
            if (m.parentFullId == fullId) DeltaInfo(f.uri, m.seq ?: 0, m.createdAt) else null
        }
    }

    // ── Восстановление (п. 9.3) ──────────────────────────────────────────────

    data class RestorePreview(
        val fullManifest: BackupManifest,
        val deltaManifests: List<BackupManifest>,
        val patients: Int,
        val issues: Int,
        val photos: Int,
    )

    fun prepareRestore(fullUri: Uri, deltaUris: List<Uri>): RestorePreview {
        val full = readBackup(fullUri)
        val deltas = deltaUris.map { readBackup(it) }.sortedBy { it.manifest.createdAt }
        for (d in deltas) {
            if (d.manifest.parentFullId != full.manifest.fullId) {
                throw BackupException("Быстрая копия от другой полной — восстановление невозможно (п. 9.9)")
            }
        }
        val merged = merge(full, deltas)
        return RestorePreview(
            fullManifest = full.manifest,
            deltaManifests = deltas.map { it.manifest },
            patients = merged.patients.size,
            issues = merged.issues.size,
            photos = (full.photos.keys + deltas.flatMap { it.photos.keys }).size,
        )
    }

    private fun merge(full: ParsedBackup, deltas: List<ParsedBackup>): DbJson {
        var db = full.db
        val allTombstones = (full.db.tombstones + deltas.flatMap { it.db.tombstones })
        for (delta in deltas) {
            fun <T> mergeList(old: List<T>, new: List<T>, idOf: (T) -> Long, updatedOf: (T) -> Long): List<T> {
                val map = old.associateBy(idOf).toMutableMap()
                for (n in new) {
                    val cur = map[idOf(n)]
                    if (cur == null || updatedOf(n) > updatedOf(cur)) map[idOf(n)] = n
                }
                return map.values.toList()
            }
            db = db.copy(
                patients = mergeList(db.patients, delta.db.patients, { it.id }, { it.updatedAt }),
                drugs = mergeList(db.drugs, delta.db.drugs, { it.id }, { it.updatedAt }),
                prescriptions = mergeList(db.prescriptions, delta.db.prescriptions, { it.id }, { it.updatedAt }),
                norms = mergeList(db.norms, delta.db.norms, { it.id }, { it.updatedAt }),
                issues = mergeList(db.issues, delta.db.issues, { it.id }, { it.updatedAt }),
                rxs = mergeList(db.rxs, delta.db.rxs, { it.id }, { it.updatedAt }),
            )
        }
        // tombstones: удаляем воскресшее (п. 9.8)
        val dead = allTombstones.map { it.entityType to it.entityId }.toSet()
        fun <T> drop(list: List<T>, type: String, idOf: (T) -> Long) =
            list.filter { ("$type" to idOf(it)) !in dead }
        return db.copy(
            patients = drop(db.patients, "patient") { it.id },
            drugs = drop(db.drugs, "drug") { it.id },
            prescriptions = drop(db.prescriptions, "prescription") { it.id },
            norms = drop(db.norms, "norm") { it.id },
            issues = drop(db.issues, "issue") { it.id },
            rxs = drop(db.rxs, "rx") { it.id },
            tombstones = allTombstones.distinctBy { it.entityType to it.entityId },
        )
    }

    /** Полное восстановление: предпросмотр уже подтверждён пользователем. */
    suspend fun restore(fullUri: Uri, deltaUris: List<Uri>) {
        val full = readBackup(fullUri)
        val deltas = deltaUris.map { readBackup(it) }.sortedBy { it.manifest.createdAt }
        val merged = merge(full, deltas)
        val photos = full.photos + deltas.flatMap { it.photos }

        // Страховая копия текущего состояния (п. 9.3); при неудаче — отмена
        try {
            createSafetyBackup()
        } catch (e: Exception) {
            throw BackupException("Страховая копия не создана (${e.message}) — восстановление отменено, данные не тронуты")
        }

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.withTransaction {
                db.openHelper.writableDatabase.apply {
                    execSQL("DELETE FROM patients")
                    execSQL("DELETE FROM drugs")
                    execSQL("DELETE FROM prescriptions")
                    execSQL("DELETE FROM norms")
                    execSQL("DELETE FROM issues")
                    execSQL("DELETE FROM rxs")
                    execSQL("DELETE FROM tombstones")
                }
                merged.patients.forEach { db.patientDao().insert(it) }
                merged.drugs.forEach { db.drugDao().insert(it) }
                merged.prescriptions.forEach { db.prescriptionDao().insert(it) }
                merged.norms.forEach { db.normDao().insert(it) }
                merged.issues.forEach { db.issueDao().insert(it) }
                merged.rxs.forEach { db.rxDao().insert(it) }
                merged.tombstones.forEach { db.tombstoneDao().insert(it) }
            }
            // Фото: очистить и восстановить
            PhotoStore.dir(context).listFiles()?.forEach { it.delete() }
            for ((name, bytes) in photos) {
                java.io.File(PhotoStore.dir(context), name).writeBytes(bytes)
            }
        }
        settings.markFullBackup(full.manifest.fullId, full.manifest.createdAt)
    }

    /** Страховая полная копия перед восстановлением (п. 9.3). */
    private suspend fun createSafetyBackup() {
        val s = settings.current()
        if (s.backupTreeUri.isBlank()) throw BackupException("не выбран каталог")
        createFull()
    }

    // ── Список/удаление копий (п. 9.10) ──────────────────────────────────────

    data class BackupEntry(
        val uri: Uri,
        val name: String,
        val type: String, // FULL / DELTA
        val createdAt: Long,
    )

    fun list(): List<BackupEntry> = tree().listFiles().mapNotNull { f ->
        val name = f.name ?: return@mapNotNull null
        val m = readManifest(f.uri) ?: return@mapNotNull null
        BackupEntry(f.uri, name, m.type, m.createdAt)
    }.sortedByDescending { it.createdAt }

    fun delete(uri: Uri): Boolean = DocumentFile.fromSingleUri(context, uri)?.delete() ?: false

    /** Предложить удалить старые цепочки: остаются последние 3 полные (п. 9.10). */
    fun oldChains(): List<BackupEntry> {
        val all = list()
        val fulls = all.filter { it.type == "FULL" }
        val keep = fulls.take(3)
        val keepIds = keep.map { readManifest(it.uri)?.fullId }.toSet()
        val oldFulls = fulls.drop(3)
        val oldDeltas = all.filter { it.type == "DELTA" && readManifest(it.uri)?.parentFullId !in keepIds }
        return oldFulls + oldDeltas
    }
}
