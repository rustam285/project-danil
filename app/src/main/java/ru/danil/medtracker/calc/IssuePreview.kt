package ru.danil.medtracker.calc

import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import java.time.LocalDate

/**
 * Предпросмотр «обеспечен до» в форме выдачи (Ф10): расчёт на чтение поверх
 * сохранённых данных — движок [SupplyEngine] не мутируется и не меняется,
 * виртуальная выдача просто добавляется к событиям запаса.
 */
object IssuePreview {

    /**
     * Результат, который показал бы движок, если бы выдача [newDate]/[newPieces] шт
     * уже была сохранена. При редактировании существующей выдачи ([replaceIssueId])
     * она исключается из истории и заменяется новой.
     */
    fun afterIssue(
        issues: List<Issue>,
        norms: List<Norm>,
        drug: Drug?,
        replaceIssueId: Long?,
        newDate: LocalDate,
        newPieces: Int,
    ): SupplyResult {
        val events = CalcService.stockEvents(issues.filter { it.id != replaceIssueId }).toMutableList()
        if (newPieces > 0) events.add(StockEvent(date = newDate, totalPieces = newPieces))
        if (events.isEmpty()) return SupplyResult(SupplyOutcome.NoEvents)
        return SupplyEngine.supplyEnd(events, CalcService.normRecs(norms), drug?.mePerPiece())
    }
}
