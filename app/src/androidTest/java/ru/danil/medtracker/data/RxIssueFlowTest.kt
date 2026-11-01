package ru.danil.medtracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.danil.medtracker.data.db.AppDatabase
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * Транзакция «Сохранить и выдать» и отмена (Ф13.6 §7 ТЗ 1.4.0, тесты §11) —
 * инструментально, по образцу миграционных. Все данные вымышленные.
 */
@RunWith(AndroidJUnit4::class)
class RxIssueFlowTest {

    private val rxDate: Long = Dates.toEpochDay(LocalDate.of(2026, 10, 29))

    /** Свежая БД + засев пациента/препарата/назначения (вымышленные данные). */
    private fun freshRepo(name: String): Pair<AppDatabase, Repo> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        val db = AppDatabase.buildForTest(context, name)
        val repo = Repo(db, SettingsStore(context))
        runBlocking {
            val t = System.currentTimeMillis()
            val pid = db.patientDao().insert(Patient(fio = "Тестов Тест Тестович", birthDate = 5000, address = "г. Тест", createdAt = t, updatedAt = t))
            val did = db.drugDao().insert(Drug(name = "Тест-полоски Ван Тач Селект", piecesPerPack = 50, updatedAt = t))
            val prescriptionId = db.prescriptionDao().insert(Prescription(patientId = pid, drugId = did, createdAt = t, updatedAt = t))
            db.normDao().insert(Norm(prescriptionId = prescriptionId, validFrom = 0, pieceKind = "PER_DAY", pieceAmount = 1, createdAt = t, updatedAt = t))
        }
        return db to repo
    }

    private fun close(db: AppDatabase, name: String) {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
    }

    private val plan = RxIssuePlan.Plan(packs = 5, pieces = 0, total = 250, blocks = emptyList(), warnings = emptyList())

    /** Новый рецепт: выдача датой рецепта с rxId и примечанием, статус «отоварен». */
    @Test
    fun issueFromRx_newRx_createsIssueAndRedeems() {
        val (db, repo) = freshRepo("rxissue-new.db")
        try {
            val rx = Rx(patientId = 1, series = "75464", number = "25222011880", issueDate = rxDate, status = "ISSUED")
            val issue = runBlocking { repo.issueFromRx(rx, prescriptionId = 1, plan = plan, piecesPerPack = 50) }
            assertEquals(rxDate, issue.date)
            assertEquals(250, issue.totalPieces)
            assertEquals(5, issue.enteredPacks)
            assertEquals("Рецепт 75464 № 25222011880", issue.note)
            assertNotNull(issue.rxId)
            val saved = runBlocking { db.rxDao().byId(issue.rxId!!) }
            assertNotNull(saved)
            assertEquals("REDEEMED", saved!!.status)
            assertEquals(1L, saved.prescriptionId)
            assertEquals(1, runBlocking { db.issueDao().forRx(issue.rxId!!).size })
        } finally {
            close(db, "rxissue-new.db")
        }
    }

    /** Переиспользование: rx с готовым id обновляется, новой записи не появляется. */
    @Test
    fun issueFromRx_existingRx_reusedNotDuplicated() {
        val (db, repo) = freshRepo("rxissue-reuse.db")
        try {
            val t = System.currentTimeMillis()
            val id = runBlocking { db.rxDao().insert(Rx(patientId = 1, series = "75464", number = "25222011880", issueDate = rxDate, createdAt = t, updatedAt = t)) }
            val issue = runBlocking {
                repo.issueFromRx(
                    Rx(id = id, patientId = 1, series = "75464", number = "25222011880", issueDate = rxDate, createdAt = t, updatedAt = t),
                    prescriptionId = 1, plan = plan, piecesPerPack = 50,
                )
            }
            assertEquals(1, runBlocking { db.rxDao().all().size })
            assertEquals(id, issue.rxId)
            assertEquals("REDEEMED", runBlocking { db.rxDao().byId(id) }!!.status)
            assertEquals(1L, runBlocking { db.rxDao().byId(id) }!!.prescriptionId)
        } finally {
            close(db, "rxissue-reuse.db")
        }
    }

    /** Отмена: выдача удалена (+ tombstone), статус вернулся «выписан». */
    @Test
    fun undo_deletesIssueWritesTombstone_returnsIssued() {
        val (db, repo) = freshRepo("rxissue-undo.db")
        try {
            val rx = Rx(patientId = 1, series = "75464", number = "25222011880", issueDate = rxDate)
            val issue = runBlocking { repo.issueFromRx(rx, prescriptionId = 1, plan = plan, piecesPerPack = 50) }
            runBlocking { repo.undoIssueFromRx(issue.id, issue.rxId!!) }
            assertNull(runBlocking { db.issueDao().byId(issue.id) })
            assertTrue(runBlocking { db.issueDao().forRx(issue.rxId!!).isEmpty() })
            assertTrue(runBlocking { db.tombstoneDao().all().any { it.entityType == "issue" && it.entityId == issue.id } })
            assertEquals("ISSUED", runBlocking { db.rxDao().byId(issue.rxId!!) }!!.status)
        } finally {
            close(db, "rxissue-undo.db")
        }
    }

    /** Дубль-запрос: номер в БД с пробелами находится по нормализованному аргументу (§6.1). */
    @Test
    fun bySeriesNumber_matchesNumberWithSpaces() {
        val (db, _) = freshRepo("rxissue-dup.db")
        try {
            val t = System.currentTimeMillis()
            runBlocking {
                db.rxDao().insert(Rx(patientId = 1, series = "75464", number = "25 222 011 880", issueDate = rxDate, createdAt = t, updatedAt = t))
            }
            val found = runBlocking { db.rxDao().bySeriesNumber("75464", "25222011880") }
            assertEquals(1, found.size)
            assertEquals("75464", found.first().series)
        } finally {
            close(db, "rxissue-dup.db")
        }
    }
}
