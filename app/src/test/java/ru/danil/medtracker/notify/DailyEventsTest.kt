package ru.danil.medtracker.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.data.AppSettings
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/** Резервные назначения не попадают в ежедневную сводку (Ф2.5). Данные вымышленные. */
class DailyEventsTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 1)
    private val settings = AppSettings(drugThresholdDays = 7, rxThresholdDays = 14)

    private fun patient() = Patient(id = 1, fio = "Иванов Иван Иванович", birthDate = 0, address = "")
    private fun drug(id: Long, name: String) = Drug(id = id, name = name, unit = "штука", piecesPerPack = 1)
    private fun norm(pid: Long) = Norm(
        prescriptionId = pid,
        validFrom = Dates.toEpochDay(today.minusYears(1)),
        pieceKind = "PER_DAY",
        pieceAmount = 1,
    )

    private fun issue(pid: Long, daysAgo: Long, pieces: Int) = Issue(
        prescriptionId = pid,
        date = Dates.toEpochDay(today.minusDays(daysAgo)),
        enteredPieces = pieces,
        piecesPerPackAtIssue = 1,
        totalPieces = pieces,
    )

    @Test
    fun reservePrescription_notMentionedInSummary() {
        val events = DailyEvents.compute(
            patients = listOf(patient()),
            prescriptions = listOf(
                Prescription(id = 1, patientId = 1, drugId = 1),
                Prescription(id = 2, patientId = 1, drugId = 2, isReserve = true),
            ),
            drugs = listOf(drug(1, "Тест-препарат"), drug(2, "Резервный препарат")),
            norms = listOf(norm(1), norm(2)),
            issues = listOf(issue(1, 20, 5), issue(2, 20, 5)),
            rxs = emptyList(),
            settings = settings,
            today = today,
        )
        assertEquals(1, events.lowStock.size)
        assertTrue(events.lowStock.first().contains("Тест-препарат"))
        assertTrue(events.lowStock.none { it.contains("Резервный препарат") })
        assertTrue(events.rxBelow.isEmpty())
    }

    @Test
    fun nonReserve_regression_stillReported() {
        val events = DailyEvents.compute(
            patients = listOf(patient()),
            prescriptions = listOf(Prescription(id = 1, patientId = 1, drugId = 1)),
            drugs = listOf(drug(1, "Тест-препарат")),
            norms = listOf(norm(1)),
            issues = listOf(issue(1, 20, 5)),
            rxs = emptyList(),
            settings = settings,
            today = today,
        )
        assertEquals(1, events.lowStock.size)
        assertTrue(events.lowStock.first().contains("Иванов Иван Иванович"))
    }

    // ── Ф8: напоминание о давности копии ──────────────────────────────────────

    private val day = 24L * 3600 * 1000

    @Test
    fun backupReminder_exactlyThreshold_reminds() {
        val now = System.currentTimeMillis()
        val line = DailyEvents.backupReminderLine(lastBackupAt = now - 14 * day, thresholdDays = 14, now = now)
        assertTrue("При возрасте ровно N дней — напоминание", line != null)
        assertTrue(line!!.startsWith("Резервная копия: последняя "))
        assertTrue(line.contains("пора сделать новую"))
    }

    @Test
    fun backupReminder_dayBeforeThreshold_silent() {
        val now = System.currentTimeMillis()
        val line = DailyEvents.backupReminderLine(lastBackupAt = now - 13 * day, thresholdDays = 14, now = now)
        assertEquals(null, line)
    }

    @Test
    fun backupReminder_thresholdZero_disabled() {
        val now = System.currentTimeMillis()
        assertEquals(null, DailyEvents.backupReminderLine(lastBackupAt = now - 365 * day, thresholdDays = 0, now = now))
    }

    @Test
    fun backupReminder_noBackupEver() {
        val line = DailyEvents.backupReminderLine(lastBackupAt = 0, thresholdDays = 14, now = System.currentTimeMillis())
        assertEquals("Резервная копия: ещё не создавалась", line)
    }

    // ── Ф12: просроченные рецепты ─────────────────────────────────────────────

    private fun rx(validUntil: LocalDate, prescriptionId: Long? = null, status: String = "ISSUED") =
        ru.danil.medtracker.data.db.Rx(
            patientId = 1,
            prescriptionId = prescriptionId,
            validUntil = Dates.toEpochDay(validUntil),
            status = status,
        )

    @Test
    fun expiredRx_todayIsValid_yesterdayExpired() {
        val events = DailyEvents.compute(
            patients = listOf(patient()),
            prescriptions = emptyList(),
            drugs = emptyList(),
            norms = emptyList(),
            issues = emptyList(),
            rxs = listOf(
                rx(today),          // срок = сегодня — ещё действителен
                rx(today.minusDays(1)), // срок = вчера — просрочен
                rx(today.minusDays(1), status = "REDEEMED"), // отоварен — не считается
            ),
            settings = settings,
            today = today,
        )
        assertEquals(1, events.expiredRxCount)
    }

    @Test
    fun expiredRx_reservePrescription_notCounted() {
        val events = DailyEvents.compute(
            patients = listOf(patient()),
            prescriptions = listOf(
                Prescription(id = 1, patientId = 1, drugId = 1),
                Prescription(id = 2, patientId = 1, drugId = 2, isReserve = true),
            ),
            drugs = listOf(drug(1, "Тест-препарат"), drug(2, "Резервный препарат")),
            norms = emptyList(),
            issues = emptyList(),
            rxs = listOf(
                rx(today.minusDays(1), prescriptionId = 1), // обычное — считается
                rx(today.minusDays(1), prescriptionId = 2), // резервное — не считается (Ф12.2)
            ),
            settings = settings,
            today = today,
        )
        assertEquals(1, events.expiredRxCount)
    }
}
