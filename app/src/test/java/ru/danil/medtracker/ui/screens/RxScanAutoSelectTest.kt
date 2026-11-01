package ru.danil.medtracker.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
