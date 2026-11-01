package ru.danil.medtracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Чистая логика плана выдачи по отсканированному рецепту (Ф13.4, ТЗ 1.4.0 §5).
 * Все данные вымышленные.
 */
class RxIssuePlanTest {

    private val today = LocalDate.of(2026, 11, 1)
    private val rxDate = LocalDate.of(2026, 10, 29)

    private fun input(
        units: Int? = 5,
        packSize: Int? = 50,
        drugPiecesPerPack: Int = 50,
        rxDate: LocalDate? = this.rxDate,
        validUntil: LocalDate? = null,
        deferred: Boolean = false,
        existingIssueDate: LocalDate? = null,
        laterIssueDate: LocalDate? = null,
    ) = RxIssuePlan.Input(units, packSize, drugPiecesPerPack, rxDate, today, validUntil, deferred, existingIssueDate, laterIssueDate)

    /** 5 × №50 при упаковке 50 → упаковками, без предупреждений. */
    @Test
    fun packMatches_issuedByPacks() {
        val p = RxIssuePlan.build(input())
        assertEquals(5, p.packs)
        assertEquals(0, p.pieces)
        assertEquals(250, p.total)
        assertTrue(p.blocks.isEmpty())
        assertTrue(p.warnings.isEmpty())
    }

    /** Упаковка справочника = 1 (штука): 5 × №1 → тоже упаковками-штуками. */
    @Test
    fun unitPacks_issuedByPacks() {
        val p = RxIssuePlan.build(input(packSize = 1, drugPiecesPerPack = 1))
        assertEquals(5, p.packs)
        assertEquals(0, p.pieces)
        assertEquals(5, p.total)
        assertTrue(p.warnings.isEmpty())
    }

    /** 5 × №50 при упаковке 1 → штуками с предупреждением о несовпадении. */
    @Test
    fun packMismatch_issuedByPieces() {
        val p = RxIssuePlan.build(input(drugPiecesPerPack = 1))
        assertEquals(0, p.packs)
        assertEquals(250, p.pieces)
        assertEquals(250, p.total)
        assertEquals(1, p.warnings.size)
        assertTrue(p.warnings.first().contains("не равна рецепту"))
        assertTrue(p.warnings.first().contains("250 шт"))
    }

    /** По рецепту уже есть выдача → блок. */
    @Test
    fun existingIssue_blocks() {
        val p = RxIssuePlan.build(input(existingIssueDate = LocalDate.of(2026, 10, 30)))
        assertEquals(listOf("По этому рецепту уже есть выдача от 30.10.2026"), p.blocks)
    }

    /** Будущая дата рецепта → блок. */
    @Test
    fun futureRxDate_blocks() {
        val p = RxIssuePlan.build(input(rxDate = today.plusDays(1)))
        assertEquals(listOf("Дата рецепта в будущем"), p.blocks)
    }

    /** units / packSize / rxDate = null → блок на каждое отсутствующее поле. */
    @Test
    fun nullFields_blockEach() {
        val p = RxIssuePlan.build(input(units = null, packSize = null, rxDate = null))
        assertEquals(
            listOf("Укажите «Количество единиц»", "Укажите размер упаковки (№)", "Укажите дату рецепта"),
            p.blocks,
        )
        assertEquals(0, p.total)
    }

    /** units=0 / packSize=0 — обрабатываются как null. */
    @Test
    fun zeros_treatedAsNull() {
        val p = RxIssuePlan.build(input(units = 0, packSize = 0))
        assertEquals(listOf("Укажите «Количество единиц»", "Укажите размер упаковки (№)"), p.blocks)
        assertEquals(0, p.total)
    }

    /** Отсроченное обеспечение → предупреждение, план строится. */
    @Test
    fun deferred_warns_planBuilt() {
        val p = RxIssuePlan.build(input(deferred = true))
        assertEquals(listOf("Рецепт поставлен на отсроченное обеспечение: выдача записывается датой рецепта"), p.warnings)
        assertEquals(5, p.packs)
        assertEquals(250, p.total)
        assertTrue(p.blocks.isEmpty())
    }

    /** Срок рецепта истёк вчера → предупреждение; сегодня — ещё действителен. */
    @Test
    fun expiredRx_warns() {
        val expired = RxIssuePlan.build(input(validUntil = today.minusDays(1)))
        assertEquals(1, expired.warnings.size)
        assertTrue(expired.warnings.first().contains("истёк 31.10.2026"))
        val stillValid = RxIssuePlan.build(input(validUntil = today))
        assertTrue(stillValid.warnings.isEmpty())
    }

    /** Выдача по назначению позже даты рецепта → предупреждение без блокировки. */
    @Test
    fun laterIssue_warns() {
        val p = RxIssuePlan.build(input(laterIssueDate = LocalDate.of(2026, 10, 31)))
        assertEquals(1, p.warnings.size)
        assertTrue(p.warnings.first().contains("31.10.2026"))
        assertTrue(p.blocks.isEmpty())
    }

    /** Все блоки сразу — полный список в стабильном порядке. */
    @Test
    fun allBlocksAtOnce_stableOrder() {
        val p = RxIssuePlan.build(
            input(units = null, packSize = null, rxDate = null, existingIssueDate = LocalDate.of(2026, 10, 30)),
        )
        assertEquals(
            listOf(
                "Укажите «Количество единиц»",
                "Укажите размер упаковки (№)",
                "Укажите дату рецепта",
                "По этому рецепту уже есть выдача от 30.10.2026",
            ),
            p.blocks,
        )
        assertTrue(p.warnings.isEmpty())
    }
}
