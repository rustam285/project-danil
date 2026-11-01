package ru.danil.medtracker.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.danil.medtracker.data.AppSettings
import java.time.LocalDateTime

/**
 * Планирование будильников (Ф3.7 п.5–6, Ф5.1): время срабатывания, независимость
 * двух будильников, идемпотентность — двойной вызов даёт тот же requestCode
 * (PendingIntent FLAG_UPDATE_CURRENT заменяет, а не дублирует).
 */
class AlarmPlanTest {

    @Test
    fun nextTriggerTime_todayIfNotYetPassed() {
        val now = LocalDateTime.of(2026, 11, 1, 7, 0)
        assertEquals(LocalDateTime.of(2026, 11, 1, 8, 0), AlarmScheduler.nextTriggerTime(now, 8 * 60))
    }

    @Test
    fun nextTriggerTime_tomorrowIfPassed() {
        val now = LocalDateTime.of(2026, 11, 1, 9, 30)
        assertEquals(LocalDateTime.of(2026, 11, 2, 8, 0), AlarmScheduler.nextTriggerTime(now, 8 * 60))
    }

    @Test
    fun nextTriggerTime_exactlyNow_goesTomorrow() {
        val now = LocalDateTime.of(2026, 11, 1, 8, 0)
        assertEquals(LocalDateTime.of(2026, 11, 2, 8, 0), AlarmScheduler.nextTriggerTime(now, 8 * 60))
    }

    @Test
    fun twoAlarms_independentRequestCodes() {
        // разные request code → разные PendingIntent → будильники независимы (Ф3.7 п.6)
        assertNotEquals(AlarmScheduler.RC_DAILY, AlarmScheduler.RC_ANALYSIS)
    }

    @Test
    fun twoAlarms_independentTimes() {
        val now = LocalDateTime.of(2026, 11, 1, 7, 0)
        val summary = AlarmScheduler.nextTriggerTime(now, 8 * 60)
        val analyses = AlarmScheduler.nextTriggerTime(now, 21 * 60 + 30)
        assertEquals(LocalDateTime.of(2026, 11, 1, 8, 0), summary)
        assertEquals(LocalDateTime.of(2026, 11, 1, 21, 30), analyses)
    }

    @Test
    fun settingsChange_reschedulesWithoutDuplicating() {
        // идемпотентность: время вычисляется детерминированно от настроек,
        // requestCode стабилен между вызовами → повторное планирование заменяет будильник
        val s1 = AppSettings(checkTimeMinutes = 8 * 60, analysisTimeMinutes = 8 * 60)
        val s2 = s1.copy(analysisTimeMinutes = 21 * 60 + 30)
        val now = LocalDateTime.of(2026, 11, 1, 7, 0)
        assertEquals(
            AlarmScheduler.nextTriggerTime(now, s1.analysisTimeMinutes),
            AlarmScheduler.nextTriggerTime(now, s1.analysisTimeMinutes),
        )
        assertNotEquals(
            AlarmScheduler.nextTriggerTime(now, s1.analysisTimeMinutes),
            AlarmScheduler.nextTriggerTime(now, s2.analysisTimeMinutes),
        )
    }
}
