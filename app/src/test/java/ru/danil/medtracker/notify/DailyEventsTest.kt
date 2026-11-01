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
}
