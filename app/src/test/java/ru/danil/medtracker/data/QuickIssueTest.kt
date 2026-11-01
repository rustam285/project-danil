package ru.danil.medtracker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * Подбор количества по умолчанию для быстрой выдачи из «Срочного» (Ф6.1):
 * штучная норма, приведённая к месяцу; без штучной части — как в последней выдаче.
 * Данные вымышленные.
 */
class QuickIssueTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 1)

    private fun norm(kind: String, amount: Int, everyX: Int? = null, noCalc: Boolean = false) = Norm(
        prescriptionId = 1,
        validFrom = Dates.toEpochDay(today.minusMonths(2)),
        pieceKind = kind,
        pieceAmount = amount,
        everyXDays = everyX,
        noCalc = noCalc,
    )

    private fun lastIssue(pieces: Int) = Issue(
        prescriptionId = 1,
        date = Dates.toEpochDay(today.minusDays(30)),
        enteredPieces = pieces,
        piecesPerPackAtIssue = 1,
        totalPieces = pieces,
    )

    @Test
    fun perDay_norm_times30() {
        assertEquals(90, QuickIssue.defaultPieces(listOf(norm("PER_DAY", 3)), today, null))
    }

    @Test
    fun perMonth_norm_asIs() {
        assertEquals(90, QuickIssue.defaultPieces(listOf(norm("PER_MONTH", 90)), today, null))
    }

    @Test
    fun perYear_norm_twelfthRoundedUp() {
        // 100 в год → 100/12 = 8,(3) → 9 (округление вверх, без double)
        assertEquals(9, QuickIssue.defaultPieces(listOf(norm("PER_YEAR", 100)), today, null))
        // 24 в год → ровно 2
        assertEquals(2, QuickIssue.defaultPieces(listOf(norm("PER_YEAR", 24)), today, null))
    }

    @Test
    fun everyXDays_norm_perMonthCeil() {
        // 10 шт раз в 7 дней → 10*30/7 = 42,86 → 43
        assertEquals(43, QuickIssue.defaultPieces(listOf(norm("EVERY_X_DAYS", 10, everyX = 7)), today, null))
    }

    @Test
    fun noPiecePart_fallsBackToLastIssue() {
        // только МЕ-часть — берём последнюю выдачу
        assertEquals(15, QuickIssue.defaultPieces(listOf(norm("PER_MONTH", 90).copy(pieceKind = null, pieceAmount = null, meAmount = 40)), today, lastIssue(15)))
        // и норм нет вовсе
        assertEquals(15, QuickIssue.defaultPieces(emptyList(), today, lastIssue(15)))
    }

    @Test
    fun noCalcNorm_fallsBackToLastIssue() {
        // «без расчёта срока» — месячную норму не вычислить
        assertEquals(20, QuickIssue.defaultPieces(listOf(norm("PER_DAY", 3, noCalc = true)), today, lastIssue(20)))
    }

    @Test
    fun noNormsNoIssues_null() {
        assertEquals(null, QuickIssue.defaultPieces(emptyList(), today, null))
    }

    @Test
    fun futureNormOnly_ignored() {
        // норма ещё не действует — нет данных для расчёта
        val future = norm("PER_DAY", 3).copy(validFrom = Dates.toEpochDay(today.plusDays(1)))
        assertEquals(null, QuickIssue.defaultPieces(listOf(future), today, null))
    }
}
