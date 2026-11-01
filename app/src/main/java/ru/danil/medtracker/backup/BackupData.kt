package ru.danil.medtracker.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.danil.medtracker.data.db.AnalysisRecord
import ru.danil.medtracker.data.db.AnalysisType
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.data.db.Tombstone

/**
 * Формат копий (п. 9.6, 9.7). JSON человекочитаем; базу можно прочитать без приложения.
 * Контейнер — ZIP: manifest.json + db.json + каталог photos с фото рецептов.
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

/**
 * Переносимые настройки в копии (Ф5.3). Необязательный блок: копии v1.1.2 его не содержат —
 * при отсутствии применяются значения по умолчанию. Служебные ключи устройства
 * (каталог копий, lastFullId, даты последних напоминаний) сюда не входят.
 */
@Serializable
data class BackupSettings(
    val notificationsEnabled: Boolean = true,
    /** минуты от полуночи */
    val checkTimeMinutes: Int = 8 * 60,
    val analysisTimeMinutes: Int = 8 * 60,
    val drugThresholdDays: Int = 7,
    val rxThresholdDays: Int = 14,
)

@Serializable
data class DbJson(
    val patients: List<Patient> = emptyList(),
    val drugs: List<Drug> = emptyList(),
    val prescriptions: List<Prescription> = emptyList(),
    val norms: List<Norm> = emptyList(),
    val issues: List<Issue> = emptyList(),
    val rxs: List<Rx> = emptyList(),
    val analysisTypes: List<AnalysisType> = emptyList(),
    val analysisRecords: List<AnalysisRecord> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
    val settings: BackupSettings? = null,
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

/** Чистые преобразования копии при восстановлении (§9 ТЗ 1.2.0) — покрыты юнит-тестами. */
object BackupMerge {

    /**
     * Встроенный HbA1c не должен дублироваться (§9): если встроенных видов несколько
     * (теоретически возможно при склейке дельт), остаётся самый свежий по updatedAt,
     * typeId записей перепривязываются к нему.
     */
    fun dedupBuiltIn(db: DbJson): DbJson {
        val builtIns = db.analysisTypes.filter { it.isBuiltIn }
        if (builtIns.size <= 1) return db
        val kept = builtIns.maxByOrNull { it.updatedAt } ?: return db
        val droppedIds = (builtIns - kept).map { it.id }.toSet()
        return db.copy(
            analysisTypes = db.analysisTypes.filter { it.id !in droppedIds },
            analysisRecords = db.analysisRecords.map { r ->
                if (r.typeId in droppedIds) r.copy(typeId = kept.id) else r
            },
        )
    }

    /** Склейка полной копии с дельтами (по updatedAt), удаление воскресшего по tombstones. */
    fun merge(full: DbJson, deltas: List<DbJson>): DbJson {
        var db = full
        val allTombstones = (full.tombstones + deltas.flatMap { it.tombstones })
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
                patients = mergeList(db.patients, delta.patients, { it.id }, { it.updatedAt }),
                drugs = mergeList(db.drugs, delta.drugs, { it.id }, { it.updatedAt }),
                prescriptions = mergeList(db.prescriptions, delta.prescriptions, { it.id }, { it.updatedAt }),
                norms = mergeList(db.norms, delta.norms, { it.id }, { it.updatedAt }),
                issues = mergeList(db.issues, delta.issues, { it.id }, { it.updatedAt }),
                rxs = mergeList(db.rxs, delta.rxs, { it.id }, { it.updatedAt }),
                analysisTypes = mergeList(db.analysisTypes, delta.analysisTypes, { it.id }, { it.updatedAt }),
                analysisRecords = mergeList(db.analysisRecords, delta.analysisRecords, { it.id }, { it.updatedAt }),
                // настройки — из самой свежей копии цепочки
                settings = delta.settings ?: db.settings,
            )
        }
        // tombstones: удаляем воскресшее (п. 9.8)
        val dead = allTombstones.map { it.entityType to it.entityId }.toSet()
        fun <T> drop(list: List<T>, type: String, idOf: (T) -> Long) =
            list.filter { ("$type" to idOf(it)) !in dead }
        val merged = db.copy(
            patients = drop(db.patients, "patient") { it.id },
            drugs = drop(db.drugs, "drug") { it.id },
            prescriptions = drop(db.prescriptions, "prescription") { it.id },
            norms = drop(db.norms, "norm") { it.id },
            issues = drop(db.issues, "issue") { it.id },
            rxs = drop(db.rxs, "rx") { it.id },
            analysisTypes = drop(db.analysisTypes, "analysis_type") { it.id },
            analysisRecords = drop(db.analysisRecords, "analysis_record") { it.id },
            tombstones = allTombstones.distinctBy { it.entityType to it.entityId },
        )
        // встроенный HbA1c не дублируется (§9)
        return dedupBuiltIn(merged)
    }
}
