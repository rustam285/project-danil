package ru.danil.medtracker.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.data.db.Tombstone

/**
 * Формат копий (п. 9.6, 9.7). JSON человекочитаем; базу можно прочитать без приложения.
 * Контейнер — ZIP: manifest.json + db.json + photos/*.
 */
@Serializable
data class BackupManifest(
    val schemaVersion: Int,
    val appVersion: String,
    /** FULL или DELTA */
    val type: String,
    /** UUID полной копии (цепочки) */
    val fullId: String,
    /** для быстрой копии — родительская полная (п. 9.9) */
    val parentFullId: String? = null,
    /** порядковый номер быстрой копии в цепочке */
    val seq: Int? = null,
    val createdAt: Long,
    val counts: Map<String, Int> = emptyMap(),
    /** SHA-256 файлов внутри контейнера (п. 9.5) */
    val checksums: Map<String, String> = emptyMap(),
)

@Serializable
data class DbJson(
    val patients: List<Patient> = emptyList(),
    val drugs: List<Drug> = emptyList(),
    val prescriptions: List<Prescription> = emptyList(),
    val norms: List<Norm> = emptyList(),
    val issues: List<Issue> = emptyList(),
    val rxs: List<Rx> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
)

object BackupJson {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeDb(db: DbJson): String = json.encodeToString(DbJson.serializer(), db)
    fun decodeDb(s: String): DbJson = json.decodeFromString(DbJson.serializer(), s)
    fun encodeManifest(m: BackupManifest): String = json.encodeToString(BackupManifest.serializer(), m)
    fun decodeManifest(s: String): BackupManifest = json.decodeFromString(BackupManifest.serializer(), s)
}
