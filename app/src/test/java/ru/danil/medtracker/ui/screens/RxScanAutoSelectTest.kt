package ru.danil.medtracker.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.ocr.TextMatch

/**
 * Автоподбор назначения из скан-потока (Ф1.3): единственный явный лидер выбирается,
 * при неоднозначности (несколько с равным максимальным баллом) — ручной выбор.
 */
class RxScanAutoSelectTest {

    @Test
    fun noCandidates_aboveThreshold() {
        assertNull(RxScanViewModel.autoSelectPrescription(mapOf(1L to 10, 2L to 59)))
    }

    @Test
    fun singleLeader_selected() {
        assertEquals(5L, RxScanViewModel.autoSelectPrescription(mapOf(5L to 80, 6L to 30)))
    }

    @Test
    fun tieAboveThreshold_noAutoSelect() {
        // два назначения с равным баллом — неоднозначно, пользователь выбирает сам
        assertNull(RxScanViewModel.autoSelectPrescription(mapOf(5L to 80, 6L to 80)))
    }

    @Test
    fun strictLeaderAmongSeveral_selected() {
        assertEquals(7L, RxScanViewModel.autoSelectPrescription(mapOf(5L to 80, 6L to 79, 7L to 95, 8L to 61)))
    }

    @Test
    fun emptyScores() {
        assertNull(RxScanViewModel.autoSelectPrescription(emptyMap()))
    }

    // ── Ф13.3: покрытие текста рецепта (coverage) в балле назначения ────────────

    /** coverage ≥ порога подтягивает кандидата даже без совпавших строк препарата. */
    @Test
    fun coverageAboveThreshold_pullsCandidate() {
        // 2 из 3 токенов названия во всём тексте = 66 ≥ 60
        val score = RxScanViewModel.prescriptionScore(emptyList(), "глюкометр контур один", "Глюкометр Контур Плюс")
        assertEquals(66, score)
        assertEquals(5L, RxScanViewModel.autoSelectPrescription(mapOf(5L to score)))
    }

    /** coverage < порога кандидата не подтягивает (маскируется в 0). */
    @Test
    fun coverageBelowThreshold_notPulled() {
        // 1 из 3 токенов = 33 < 60 → в балл не идёт
        assertEquals(33, TextMatch.coverage("глюкометр один два", "Глюкометр Контур Плюс"))
        val score = RxScanViewModel.prescriptionScore(emptyList(), "глюкометр один два", "Глюкометр Контур Плюс")
        assertEquals(0, score)
        assertNull(RxScanViewModel.autoSelectPrescription(mapOf(5L to score)))
    }

    /** Прежний путь — сходство распознанных строк — работает как раньше. */
    @Test
    fun drugLineScore_stillWorks() {
        assertEquals(
            100,
            RxScanViewModel.prescriptionScore(listOf("Глюкометр Контур Плюс"), null, "Глюкометр Контур Плюс"),
        )
    }

    // ── Ф13.7 §8: предзаполнение диалога позиции из OCR ───────────────────────

    /** Название: «Выписано Rp./Вр.» приоритетнее, запас — первая строка препарата. */
    @Test
    fun ocrPositionName_rpFirst_thenDrugLine() {
        val draft = ru.danil.medtracker.ocr.OcrEngine.parse(
            "Выписано Вр.\nТест-полоски Ван Тач Селект\nКоличество единиц: 5",
        )
        assertEquals("Тест-полоски Ван Тач Селект", RxScanViewModel.ocrPositionName(draft))
        assertEquals("", RxScanViewModel.ocrPositionName(null))
        assertEquals(
            "Первая строка",
            RxScanViewModel.ocrPositionName(ru.danil.medtracker.ocr.OcrEngine.Draft(drugLines = listOf("Первая строка"))),
        )
        assertEquals("", RxScanViewModel.ocrPositionName(ru.danil.medtracker.ocr.OcrEngine.Draft()))
    }

    /** Эвристика «устройство»: маркеры в форме выпуска или названии (§8). */
    @Test
    fun deviceKindHint_markers() {
        assertTrue(RxScanViewModel.deviceKindHint("Тест-полоски", "Ван Тач"))
        assertTrue(RxScanViewModel.deviceKindHint(null, "Набор для проверки сахара"))
        assertTrue(RxScanViewModel.deviceKindHint("шприц-ручка", ""))
        assertTrue(RxScanViewModel.deviceKindHint(null, "Сенсор для мониторинга"))
        assertEquals(false, RxScanViewModel.deviceKindHint("Таблетки", "Тестулин 100 ЕД"))
        assertEquals(false, RxScanViewModel.deviceKindHint(null, ""))
    }
}
