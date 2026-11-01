package ru.danil.medtracker.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.data.db.AnalysisRecord
import ru.danil.medtracker.data.db.AnalysisType
import ru.danil.medtracker.data.db.DrugKind
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription

/**
 * Совместимость формата копий (§9 ТЗ 1.2.0):
 * копия v1.1.2 (без новых полей/таблиц/настроек) восстанавливается на v1.2.0,
 * круговой тест кодирования, дедуп HbA1c при склейке дельт.
 * Данные вымышленные (Приложение Б ТЗ).
 */
class BackupCompatTest {

    /** db.json в формате v1.1.2: нет kind/isReserve/doctor/isDiabetic, таблиц анализов и блока settings. */
    private val oldDbJson = """
        {
            "patients": [
                {"id": 1, "fio": "Иванов Иван Иванович", "birthDate": 5000, "address": "г. Тест",
                 "benefitsJson": "[]", "notes": "", "archived": false, "createdAt": 1, "updatedAt": 1}
            ],
            "drugs": [
                {"id": 1, "name": "Тест-препарат", "unit": "упаковка", "piecesPerPack": 5,
                 "archived": false, "updatedAt": 1}
            ],
            "prescriptions": [
                {"id": 1, "patientId": 1, "drugId": 1, "status": "ACTIVE", "awaitingRestock": false,
                 "createdAt": 1, "updatedAt": 1}
            ],
            "norms": [], "issues": [], "rxs": [], "tombstones": []
        }
    """.trimIndent()

    @Test
    fun oldFormat_decodes_withDefaults() {
        val db = BackupJson.decodeDb(oldDbJson)
        // старые поля прочитаны
        assertEquals("Иванов Иван Иванович", db.patients.first().fio)
        assertEquals("Тест-препарат", db.drugs.first().name)
        // новые поля — значения по умолчанию
        assertEquals(DrugKind.DRUG, db.drugs.first().kind)
        assertFalse(db.prescriptions.first().isReserve)
        assertTrue(db.analysisTypes.isEmpty() && db.analysisRecords.isEmpty())
        assertNull(db.settings)
    }

    @Test
    fun roundTrip_newFieldsAndSettings_preserved() {
        val db = DbJson(
            patients = listOf(Patient(id = 1, fio = "Тестов Тест Тестович", birthDate = 0, address = "", isDiabetic = true)),
            drugs = listOf(Drug(id = 2, name = "Глюкометр Тест", kind = DrugKind.DEVICE, unit = "штука")),
            prescriptions = listOf(Prescription(id = 3, patientId = 1, drugId = 2, isReserve = true)),
            analysisTypes = listOf(
                AnalysisType(id = 1, name = AnalysisType.HBA1C_NAME, unit = "%", periodMonths = 3, isBuiltIn = true),
                AnalysisType(id = 2, name = "Свой анализ", periodMonths = 1),
            ),
            analysisRecords = listOf(
                AnalysisRecord(id = 10, patientId = 1, typeId = 1, date = 20000, value = 7.4),
            ),
            settings = BackupSettings(
                notificationsEnabled = false,
                checkTimeMinutes = 21 * 60 + 30,
                analysisTimeMinutes = 9 * 60,
                drugThresholdDays = 10,
                rxThresholdDays = 21,
            ),
        )
        val restored = BackupJson.decodeDb(BackupJson.encodeDb(db))
        assertEquals(db, restored)
        assertTrue(restored.drugs.first().isDevice)
        assertTrue(restored.patients.first().isDiabetic)
        assertTrue(restored.prescriptions.first().isReserve)
        assertEquals(21 * 60 + 30, restored.settings!!.checkTimeMinutes)
    }

    @Test
    fun dedupBuiltIn_keepsNewest_andRemapsRecords() {
        val db = DbJson(
            analysisTypes = listOf(
                AnalysisType(id = 1, name = AnalysisType.HBA1C_NAME, isBuiltIn = true, updatedAt = 10),
                AnalysisType(id = 2, name = AnalysisType.HBA1C_NAME, isBuiltIn = true, updatedAt = 20),
                AnalysisType(id = 3, name = "Свой анализ"),
            ),
            analysisRecords = listOf(
                AnalysisRecord(id = 10, patientId = 1, typeId = 1, date = 0, value = 7.0),
                AnalysisRecord(id = 11, patientId = 1, typeId = 3, date = 0, value = 1.0),
            ),
        )
        val out = BackupMerge.dedupBuiltIn(db)
        assertEquals(listOf(2L, 3L), out.analysisTypes.map { it.id })
        assertEquals(2L, out.analysisRecords.first { it.id == 10L }.typeId)
        assertEquals(3L, out.analysisRecords.first { it.id == 11L }.typeId)
    }

    @Test
    fun dedupBuiltIn_singleBuiltIn_unchanged() {
        val db = DbJson(
            analysisTypes = listOf(AnalysisType(id = 1, name = AnalysisType.HBA1C_NAME, isBuiltIn = true)),
        )
        assertEquals(db, BackupMerge.dedupBuiltIn(db))
    }

    @Test
    fun merge_appliesTombstonesAndNewestSettings() {
        val full = DbJson(
            patients = listOf(Patient(id = 1, fio = "Иванов Иван Иванович", birthDate = 0, address = "", updatedAt = 10)),
            analysisTypes = listOf(AnalysisType(id = 1, name = AnalysisType.HBA1C_NAME, isBuiltIn = true, updatedAt = 10)),
            analysisRecords = listOf(
                AnalysisRecord(id = 10, patientId = 1, typeId = 1, date = 0, value = 7.0, updatedAt = 10),
            ),
            tombstones = emptyList(),
            settings = BackupSettings(checkTimeMinutes = 8 * 60),
        )
        val delta = DbJson(
            patients = listOf(Patient(id = 1, fio = "Иванов Иван Иванович (обновлён)", birthDate = 0, address = "", updatedAt = 50)),
            analysisRecords = emptyList(),
            tombstones = listOf(
                ru.danil.medtracker.data.db.Tombstone(entityType = "analysis_record", entityId = 10, deletedAt = 60),
            ),
            settings = BackupSettings(checkTimeMinutes = 21 * 60 + 30),
        )
        val merged = BackupMerge.merge(full, listOf(delta))
        assertEquals("Иванов Иван Иванович (обновлён)", merged.patients.first().fio)
        assertTrue("запись, удалённая в дельте, не должна воскресать", merged.analysisRecords.isEmpty())
        assertEquals(1, merged.analysisTypes.size) // встроенный HbA1c не дублирован
        assertEquals(21 * 60 + 30, merged.settings!!.checkTimeMinutes)
    }
}
