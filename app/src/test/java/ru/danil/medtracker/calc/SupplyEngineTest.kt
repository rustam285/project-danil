package ru.danil.medtracker.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Эталонные примеры Приложения А (обязательны к совпадению, п. 4.5)
 * и контрольные примеры Приложения Б.
 */
class SupplyEngineTest {

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    // ── Приложение А ──────────────────────────────────────────────────────────

    /** №1: выдача 06.10.2026 — 10 шт, норма 1 шт в 7 дней → 15.12.2026 (реальный файл заказчика). */
    @Test
    fun case1_oneIssue_everySevenDays() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.EVERY_X_DAYS, pieceAmount = 1, everyXDays = 7))
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-10-06"), totalPieces = 10)),
            norms, null,
        )
        assertEquals(d("2026-12-15"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** №2: смена нормы 6→8 шт/день с 18.06.2026; выдано 18.08.2026 400 шт → 07.10.2026. */
    @Test
    fun case2_normChange() {
        val norms = listOf(
            NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_DAY, pieceAmount = 6),
            NormRec(validFrom = d("2026-06-18"), pieceKind = PieceKind.PER_DAY, pieceAmount = 8),
        )
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-08-18"), totalPieces = 400)),
            norms, null,
        )
        assertEquals(d("2026-10-07"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** №3: инсулин, норма 4 ЕД/сутки, картридж 300 МЕ, выдано 5 карт → 11.11.2027. */
    @Test
    fun case3_insulinMeNorm() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), meAmount = 4))
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-11-01"), totalPieces = 5)),
            norms, mePerPiece = 300,
        )
        assertEquals(d("2027-11-11"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** №4: в одной записи обе части («4 ЕД/сутки» + «1 картридж в месяц») — штучная приоритетнее → 01.04.2027. */
    @Test
    fun case4_piecePartWinsOverMe() {
        val norms = listOf(
            NormRec(
                validFrom = d("2026-01-01"),
                pieceKind = PieceKind.PER_MONTH, pieceAmount = 1,
                meAmount = 4,
            ),
        )
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-11-01"), totalPieces = 5)),
            norms, mePerPiece = 300,
        )
        assertEquals(d("2027-04-01"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** №5: норма 1 шт/день; 01.11.2026 — 30 шт, 15.11.2026 — 20 шт → 21.12.2026. */
    @Test
    fun case5_twoIssues() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_DAY, pieceAmount = 1))
        val res = SupplyEngine.supplyEnd(
            listOf(
                StockEvent(id = 1, date = d("2026-11-01"), totalPieces = 30),
                StockEvent(id = 2, date = d("2026-11-15"), totalPieces = 20),
            ),
            norms, null,
        )
        assertEquals(d("2026-12-21"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** №6: норма 31 банка в месяц; выдано 29.06.2026 90 банок → 26.09.2026 (совпадает с файлом заказчика). */
    @Test
    fun case6_monthlyBanks() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_MONTH, pieceAmount = 31))
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-06-29"), totalPieces = 90)),
            norms, null,
        )
        assertEquals(d("2026-09-26"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** №7: перерыв — 01.11 10 шт, 20.11 5 шт → 25.11.2026, перерыв 12.11–20.11. */
    @Test
    fun case7_gap() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_DAY, pieceAmount = 1))
        val res = SupplyEngine.supplyEnd(
            listOf(
                StockEvent(id = 1, date = d("2026-11-01"), totalPieces = 10),
                StockEvent(id = 2, date = d("2026-11-20"), totalPieces = 5),
            ),
            norms, null,
        )
        assertEquals(d("2026-11-25"), (res.outcome as SupplyOutcome.UntilDate).date)
        assertEquals(1, res.gaps.size)
        assertEquals(d("2026-11-12"), res.gaps[0].first)
        assertEquals(d("2026-11-20"), res.gaps[0].second)
    }

    // ── Приложение Б (контрольные примеры) ───────────────────────────────────

    /** Б1: инсулин 1 картридж в месяц; 01.11.2026 выдано 2 уп №5 (10 карт) → 01.09.2027. */
    @Test
    fun caseB1_insulinMonthly() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_MONTH, pieceAmount = 1))
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-11-01"), totalPieces = 10)),
            norms, null,
        )
        assertEquals(d("2027-09-01"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** Б2: тест-полоски 6 шт/день; 01.11.2026 выдано 3 уп по 50 (150 шт) → 26.11.2026. */
    @Test
    fun caseB2_strips() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_DAY, pieceAmount = 6))
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-11-01"), totalPieces = 150)),
            norms, null,
        )
        assertEquals(d("2026-11-26"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** Б3: спирт 1 флакон в месяц; 01.11.2026 выдано 2 фл → 01.01.2027. */
    @Test
    fun caseB3_spirit() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_MONTH, pieceAmount = 1))
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-11-01"), totalPieces = 2)),
            norms, null,
        )
        assertEquals(d("2027-01-01"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    // ── Дополнительные граничные случаи ──────────────────────────────────────

    /** Выдачи нет → NoEvents. */
    @Test
    fun noIssues_noResult() {
        val res = SupplyEngine.supplyEnd(emptyList(), emptyList(), null)
        assertTrue(res.outcome is SupplyOutcome.NoEvents)
    }

    /** Запас не тратится (без расчёта срока / нет нормы) → Unlimited за горизонт. */
    @Test
    fun noCalc_unlimited() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), noCalc = true))
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-11-01"), totalPieces = 3)),
            norms, null,
        )
        assertTrue(res.outcome is SupplyOutcome.Unlimited)
    }

    /** Дефицит сразу после выдачи → «обеспечен до» = дата выдачи (п. 4.2.5). */
    @Test
    fun deficitRightAfterIssue_returnsIssueDate() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_DAY, pieceAmount = 5))
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-11-01"), totalPieces = 3)),
            norms, null,
        )
        assertEquals(d("2026-11-01"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** Начальный остаток по дате: piecesNeeded на Б1-сценарий = ровно 10 шт (29/30 + 9 мес + 1/30), covered = введённая дата. */
    @Test
    fun initialBalanceByDate_piecesNeededExact() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_MONTH, pieceAmount = 1))
        val need = SupplyEngine.piecesNeeded(d("2026-11-02"), d("2027-09-01"), norms, null)
        assertEquals(10L, need)
        val res = SupplyEngine.supplyEnd(
            listOf(StockEvent(date = d("2026-11-01"), totalPieces = need.toInt(), isInitialBalance = true)),
            norms, null,
        )
        assertEquals(d("2027-09-01"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** Остаток на дату: Б1, остаток на 01.12.2026 (п. 4.2.7). */
    @Test
    fun remainderAtDate() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_MONTH, pieceAmount = 1))
        val rem = SupplyEngine.remainderAt(
            d("2026-12-01"),
            listOf(StockEvent(date = d("2026-11-01"), totalPieces = 10)),
            norms, null,
        )
        // ноябрь: 29 дней по 1/30, затем 01.12 по 1/31: 10 − 29/30 − 1/31 = 8371/930
        assertEquals("8371/930", rem.toString())
    }

    /** Приоритет выдачи перед остатком не зависит от порядка в списке. */
    @Test
    fun orderIndependent() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_DAY, pieceAmount = 1))
        val events = listOf(
            StockEvent(id = 2, date = d("2026-11-15"), totalPieces = 20),
            StockEvent(id = 1, date = d("2026-11-01"), totalPieces = 30),
        )
        val res = SupplyEngine.supplyEnd(events, norms, null)
        assertEquals(d("2026-12-21"), (res.outcome as SupplyOutcome.UntilDate).date)
    }

    /** supplyEndAsOf: дата на момент каждой выдачи (п. 4.6). */
    @Test
    fun asOfHistoricalDates() {
        val norms = listOf(NormRec(validFrom = d("2026-01-01"), pieceKind = PieceKind.PER_DAY, pieceAmount = 1))
        val e1 = StockEvent(id = 1, date = d("2026-11-01"), totalPieces = 30)
        val e2 = StockEvent(id = 2, date = d("2026-11-15"), totalPieces = 20)
        val r1 = SupplyEngine.supplyEndAsOf(e1, listOf(e1, e2), norms, null)
        assertEquals(d("2026-12-01"), r1.coveredUntil())
        val r2 = SupplyEngine.supplyEndAsOf(e2, listOf(e1, e2), norms, null)
        assertEquals(d("2026-12-21"), r2.coveredUntil())
    }
}
