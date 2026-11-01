package ru.danil.medtracker.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Миграция v2→v3 (§9 ТЗ 1.2.0): неразрушающая, данные сохраняются, новые колонки
 * получают значения по умолчанию, встроенный HbA1c засеян. Данные вымышленные.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate2To3_preservesData_defaultsAndSeed() {
        helper.createDatabase(DB_NAME, 2).apply {
            // вымышленные данные в структуре v2
            execSQL(
                "INSERT INTO patients (fio, birthDate, address, school, illnessStart, benefitsJson, notes, archived, createdAt, updatedAt) " +
                    "VALUES ('Иванов Иван Иванович', 5000, 'г. Тест', NULL, NULL, '[]', '', 0, 1, 1)",
            )
            execSQL(
                "INSERT INTO drugs (name, category, unit, piecesPerPack, concMePerMl, volumeMl, concPercent, pieceNounForms, archived, updatedAt) " +
                    "VALUES ('Тест-препарат', 'Лекарство', 'упаковка', 5, NULL, NULL, NULL, NULL, 0, 1)",
            )
            execSQL(
                "INSERT INTO prescriptions (patientId, drugId, status, awaitingRestock, createdAt, updatedAt) " +
                    "VALUES (1, 1, 'ACTIVE', 0, 1, 1)",
            )
            execSQL(
                "INSERT INTO norms (prescriptionId, validFrom, pieceKind, pieceAmount, everyXDays, meAmount, noCalc, createdAt, updatedAt) " +
                    "VALUES (1, 0, 'PER_DAY', 1, NULL, NULL, 0, 1, 1)",
            )
            execSQL(
                "INSERT INTO issues (prescriptionId, date, enteredPacks, enteredPieces, piecesPerPackAtIssue, totalPieces, note, rxId, isInitialBalance, shownUntil, createdAt, updatedAt) " +
                    "VALUES (1, 100, 1, 0, 5, 5, NULL, NULL, 0, NULL, 1, 1)",
            )
            execSQL(
                "INSERT INTO rxs (patientId, prescriptionId, series, number, issueDate, validDays, validUntil, status, ocrText, photoPaths, createdAt, updatedAt) " +
                    "VALUES (1, 1, '12345', '9876543210', 100, 30, NULL, 'ISSUED', NULL, '[]', 1, 1)",
            )
            close()
        }

        // миграция + сверка со схемой 3.json
        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, AppDatabase.MIGRATION_2_3)

        // данные сохранены
        db.query("SELECT fio FROM patients").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Иванов Иван Иванович", c.getString(0))
        }
        db.query("SELECT totalPieces FROM issues").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(5, c.getInt(0))
        }
        // новые колонки — значения по умолчанию
        db.query("SELECT isReserve FROM prescriptions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT kind FROM drugs").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("DRUG", c.getString(0))
        }
        db.query("SELECT isDiabetic FROM patients").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT doctor FROM rxs").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }
        // встроенный HbA1c засеян ровно один раз
        db.query("SELECT name, unit, periodMonths, isBuiltIn FROM analysis_types").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(AnalysisType.HBA1C_NAME, c.getString(0))
            assertEquals("%", c.getString(1))
            assertEquals(3, c.getInt(2))
            assertEquals(1, c.getInt(3))
            assertTrue(!c.moveToNext())
        }
        db.query("SELECT COUNT(*) FROM analysis_records").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    /** БД с нуля (fresh install v1.2.0): onCreate засевает HbA1c. */
    @Test
    fun freshDatabase_seedsHbA1c() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(FRESH_DB)
        val db = AppDatabase.buildForTest(context, FRESH_DB)
        try {
            val builtIns = runBlocking { db.analysisTypeDao().all().filter { it.isBuiltIn } }
            assertEquals(1, builtIns.size)
            assertEquals(AnalysisType.HBA1C_NAME, builtIns.first().name)
            assertEquals(3, builtIns.first().periodMonths)
        } finally {
            db.close()
            context.deleteDatabase(FRESH_DB)
        }
    }

    companion object {
        private const val DB_NAME = "migration-test.db"
        private const val FRESH_DB = "fresh-test.db"
    }
}
