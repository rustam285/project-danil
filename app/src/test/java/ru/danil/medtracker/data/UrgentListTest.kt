package ru.danil.medtracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * Фильтрация резервных назначений (isReserve, Ф2.3): из «Срочного» —
 * из списка закончившихся, из «В ожидании поступления» и из «осталось мало».
 * Данные вымышленные (Приложение Б ТЗ).
 */
class UrgentListTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 1)
    private val settings = AppSettings(drugThresholdDays = 7)

    private fun patient() = Patient(id = 1, fio = "Иванов Иван Иванович", birthDate = 0, address = "")
    private fun drug(id: Long, name: String) = Drug(id = id, name = name, unit = "штука", piecesPerPack = 1)
    private fun norm(pid: Long) = Norm(
        prescriptionId = pid,
        validFrom = Dates.toEpochDay(today.minusYears(1)),
        pieceKind = "PER_DAY",
        pieceAmount = 1,
    )

    /** Выдача totalPieces штук N дней назад: 1 шт/день → запас кончился (total - N - 1) дней назад. */
    private fun issue(pid: Long, daysAgo: Long, pieces: Int) = Issue(
        prescriptionId = pid,
        date = Dates.toEpochDay(today.minusDays(daysAgo)),
        enteredPieces = pieces,
        piecesPerPackAtIssue = 1,
        totalPieces = pieces,
    )

    @Test
    fun reservePrescription_excludedFromEnded() {
        val state = UrgentList.build(
            patients = listOf(patient()),
            prescriptions = listOf(
                Prescription(id = 1, patientId = 1, drugId = 1),
                Prescription(id = 2, patientId = 1, drugId = 2, isReserve = true),
            ),
            norms = listOf(norm(1), norm(2)),
            issues = listOf(issue(1, 20, 5), issue(2, 20, 5)),
            drugs = listOf(drug(1, "Тест-препарат"), drug(2, "Резервный препарат")),
            rxs = emptyList(),
            settings = settings,
            today = today,
        )
        assertEquals(1, state.ended.size)
        assertEquals("Тест-препарат", state.ended.first().drug?.name)
        assertFalse(state.ended.any { it.prescription?.id == 2L })
        assertTrue(state.waiting.isEmpty() && state.low.isEmpty())
    }

    @Test
    fun reservePrescription_awaitingRestock_notShownInWaiting() {
        val state = UrgentList.build(
            patients = listOf(patient()),
            prescriptions = listOf(
                Prescription(id = 2, patientId = 1, drugId = 2, isReserve = true, awaitingRestock = true),
            ),
            norms = listOf(norm(2)),
            issues = listOf(issue(2, 20, 5)),
            drugs = listOf(drug(2, "Резервный препарат")),
            rxs = emptyList(),
            settings = settings,
            today = today,
        )
        assertTrue(state.ended.isEmpty() && state.waiting.isEmpty() && state.low.isEmpty())
    }

    @Test
    fun reservePrescription_excludedFromLow() {
        // выдача 34 шт 30 дней назад при 1 шт/день → обеспечен ещё на 4 дня (жёлтый порог 7)
        val state = UrgentList.build(
            patients = listOf(patient()),
            prescriptions = listOf(
                Prescription(id = 1, patientId = 1, drugId = 1),
                Prescription(id = 2, patientId = 1, drugId = 2, isReserve = true),
            ),
            norms = listOf(norm(1), norm(2)),
            issues = listOf(issue(1, 30, 34), issue(2, 30, 34)),
            drugs = listOf(drug(1, "Тест-препарат"), drug(2, "Резервный препарат")),
            rxs = emptyList(),
            settings = settings,
            today = today,
        )
        assertEquals(1, state.low.size)
        assertEquals(1L, state.low.first().prescription?.id)
        assertTrue(state.ended.isEmpty() && state.waiting.isEmpty())
    }

    @Test
    fun nonReserve_regression_behavesAsBefore() {
        val state = UrgentList.build(
            patients = listOf(patient()),
            prescriptions = listOf(Prescription(id = 1, patientId = 1, drugId = 1)),
            norms = listOf(norm(1)),
            issues = listOf(issue(1, 20, 5)),
            drugs = listOf(drug(1, "Тест-препарат")),
            rxs = emptyList(),
            settings = settings,
            today = today,
        )
        assertEquals(1, state.ended.size)
        assertTrue(state.ended.first().date!!.isBefore(today))
    }
}
