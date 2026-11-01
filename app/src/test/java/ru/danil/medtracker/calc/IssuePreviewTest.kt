package ru.danil.medtracker.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * Предпросмотр «обеспечен до» в форме выдачи (Ф10): виртуальная выдача поверх
 * истории; редактирование заменяет существующую запись. Данные вымышленные.
 * Эталоны согласованы с правилами Приложения Г (запас доступен со следующего дня).
 */
class IssuePreviewTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 1)
    private val drug = Drug(id = 1, name = "Тест-препарат", unit = "штука", piecesPerPack = 1)
    private val norm = Norm(
        prescriptionId = 1,
        validFrom = Dates.toEpochDay(today.minusYears(1)),
        pieceKind = "PER_DAY",
        pieceAmount = 1,
    )

    private fun issue(id: Long, date: LocalDate, pieces: Int) = Issue(
        id = id,
        prescriptionId = 1,
        date = Dates.toEpochDay(date),
        enteredPieces = pieces,
        piecesPerPackAtIssue = 1,
        totalPieces = pieces,
    )

    @Test
    fun newIssue_extendsSupply() {
        // было: выдача 01.10 на 10 шт → запас до 11.10; новая выдача 01.11 на 30 шт → до 01.12
        val base = listOf(issue(1, today.minusDays(31), 10))
        val preview = IssuePreview.afterIssue(
            issues = base, norms = listOf(norm), drug = drug,
            replaceIssueId = null, newDate = today, newPieces = 30,
        )
        assertEquals(LocalDate.of(2026, 12, 1), preview.coveredUntil())
    }

    @Test
    fun editingIssue_replacesOldEntry() {
        // та же запись (id=2) правится: 10 шт → 30 шт, дата та же
        val base = listOf(issue(1, today.minusDays(31), 5), issue(2, today, 10))
        val preview = IssuePreview.afterIssue(
            issues = base, norms = listOf(norm), drug = drug,
            replaceIssueId = 2, newDate = today, newPieces = 30,
        )
        // 01.10: +5 → покрывает 02–06.10; 01.11: +30 → 02.11–01.12
        assertEquals(LocalDate.of(2026, 12, 1), preview.coveredUntil())
    }

    @Test
    fun zeroPieces_emptyHistory_noEvents() {
        val preview = IssuePreview.afterIssue(
            issues = emptyList(), norms = listOf(norm), drug = drug,
            replaceIssueId = null, newDate = today, newPieces = 0,
        )
        assertTrue(preview.outcome is SupplyOutcome.NoEvents)
    }

    @Test
    fun deficit_showsActualEnd() {
        // выдача 1 шт при норме 1/день — запаса хватает ровно на день выдачи+1... по правилам:
        // запас доступен со следующего дня; 1 шт покрывает один день
        val preview = IssuePreview.afterIssue(
            issues = emptyList(), norms = listOf(norm), drug = drug,
            replaceIssueId = null, newDate = today, newPieces = 1,
        )
        assertEquals(today.plusDays(1), preview.coveredUntil())
    }
}
