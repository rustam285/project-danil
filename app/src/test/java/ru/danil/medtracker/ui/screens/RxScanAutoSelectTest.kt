package ru.danil.medtracker.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
