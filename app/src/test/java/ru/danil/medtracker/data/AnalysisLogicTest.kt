package ru.danil.medtracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.data.db.AnalysisRecord
import ru.danil.medtracker.data.db.AnalysisType
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * Логика анализов (Ф3): срок следующей сдачи, «диабетик», правила напоминаний Ф3.7.
 * Данные вымышленные (Приложение Б ТЗ).
 */
class AnalysisLogicTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 1)

    private fun patient(id: Long = 1, diagnosis: String? = null, isDiabetic: Boolean = false) =
        Patient(id = id, fio = "Иванов Иван Иванович", birthDate = 0, address = "", diagnosis = diagnosis, isDiabetic = isDiabetic)

    private fun hba1c(id: Long = 1) = AnalysisType(id = id, name = AnalysisType.HBA1C_NAME, unit = "%", periodMonths = 3, isBuiltIn = true)

    private fun rec(typeId: Long, daysAgo: Long, patientId: Long = 1) = AnalysisRecord(
        id = typeId * 100 + daysAgo,
        patientId = patientId,
        typeId = typeId,
        date = Dates.toEpochDay(today.minusDays(daysAgo)),
        value = 7.0,
    )

    // ── Срок следующей сдачи (Ф3.4) ──────────────────────────────────────────

    @Test
    fun dueExactlyToday_isDueWithZeroDays() {
        // последняя сдача ровно 3 месяца назад → срок наступил сегодня («пора сдавать», 0 дн.)
        val last = today.minusMonths(3)
        val s = AnalysisLogic.statusOfLast(last, 3, today)
        assertTrue(s is AnalysisDue.Due)
        assertEquals(0L, (s as AnalysisDue.Due).overdueDays)
        assertEquals(today, s.dueDate)
    }

    @Test
    fun overdueByNDays() {
        // 3 месяца и 5 дней назад → просрочено на 5 дней
        val s = AnalysisLogic.statusOfLast(today.minusMonths(3).minusDays(5), 3, today)
        assertTrue(s is AnalysisDue.Due)
        assertEquals(5L, (s as AnalysisDue.Due).overdueDays)
    }

    @Test
    fun inTime_whenDueDateAhead() {
        // месяц назад при периоде 3 → сдача в срок
        val s = AnalysisLogic.statusOfLast(today.minusMonths(1), 3, today)
        assertTrue(s is AnalysisDue.InTime)
        assertEquals(today.minusMonths(1).plusMonths(3), (s as AnalysisDue.InTime).dueDate)
    }

    @Test
    fun noRecords_isNoData() {
        val s = AnalysisLogic.statusFor(emptyList(), hba1c(), today)
        assertTrue(s is AnalysisDue.NoData)
    }

    @Test
    fun periodChange_movesDueDate() {
        // смена периода вида: 1 месяц вместо 3 — срок считается от той же записи
        val last = today.minusMonths(2)
        assertTrue(AnalysisLogic.statusOfLast(last, 3, today) is AnalysisDue.InTime)
        assertTrue(AnalysisLogic.statusOfLast(last, 1, today) is AnalysisDue.Due)
    }

    @Test
    fun plusMonths_calendarEndOfMonth() {
        // 31.08 + 3 мес = 30.11 (календарные месяцы, LocalDate.plusMonths)
        assertEquals(LocalDate.of(2026, 11, 30), AnalysisLogic.nextDue(LocalDate.of(2026, 8, 31), 3))
        // 31.01 + 1 мес = 28.02 (не 3 марта!)
        assertEquals(LocalDate.of(2026, 2, 28), AnalysisLogic.nextDue(LocalDate.of(2026, 1, 31), 1))
    }

    // ── «Диабетик» (Ф3.6) ────────────────────────────────────────────────────

    @Test
    fun diabeticByManualFlag() {
        assertTrue(Diabetes.isDiabetic(patient(isDiabetic = true)))
    }

    @Test
    fun diabeticByIcdE10E11() {
        assertTrue(Diabetes.isDiabetic(patient(diagnosis = "E10")))
        assertTrue(Diabetes.isDiabetic(patient(diagnosis = "E11.9")))
        assertTrue(Diabetes.isDiabetic(patient(diagnosis = "Сахарный диабет 1 типа, E10")))
    }

    @Test
    fun diabeticByCyrillicIcd() {
        // кириллические двойники: Е10 → E10 (грабли §7)
        assertTrue(Diabetes.isDiabetic(patient(diagnosis = "Е10")))
        assertTrue(Diabetes.isDiabetic(patient(diagnosis = "диабет е11")))
    }

    @Test
    fun notDiabetic_otherIcdOrEmpty() {
        assertFalse(Diabetes.isDiabetic(patient(diagnosis = null)))
        assertFalse(Diabetes.isDiabetic(patient(diagnosis = "")))
        assertFalse(Diabetes.isDiabetic(patient(diagnosis = "E12")))
        assertFalse(Diabetes.isDiabetic(patient(diagnosis = "J45.0")))
        assertFalse(Diabetes.isDiabetic(patient(diagnosis = "Сахарный диабет 2 типа, Е14")))
    }

    // ── Правила напоминаний (Ф3.7) ───────────────────────────────────────────

    @Test
    fun overdueTypeWithRecord_producesLineOnceADay() {
        val r = AnalysisReminders.compute(
            patients = listOf(patient(isDiabetic = true)),
            types = listOf(hba1c()),
            records = listOf(rec(1, 95)), // ~3 месяца и 4 дня назад → просрочено
            today = today,
        )
        assertEquals(1, r.size)
        assertEquals(1, r.first().lines.size)
        assertTrue(r.first().lines.first().contains(AnalysisType.HBA1C_NAME))
        assertTrue(r.first().lines.first().contains("последний"))
        assertFalse(r.first().hasNoData)
    }

    @Test
    fun severalOverdueTypes_singleReminderPerPatient() {
        val custom = AnalysisType(id = 2, name = "Свой анализ", periodMonths = 1)
        val r = AnalysisReminders.compute(
            patients = listOf(patient(isDiabetic = true)),
            types = listOf(hba1c(), custom),
            records = listOf(rec(1, 95), rec(2, 40)),
            today = today,
        )
        assertEquals(1, r.size)
        assertEquals(2, r.first().lines.size)
    }

    @Test
    fun diabeticWithoutRecords_noDataAtMostOncePer7Days() {
        val p = patient(id = 5, isDiabetic = true)
        // никогда не напоминали → есть строка «нет данных»
        val first = AnalysisReminders.compute(listOf(p), listOf(hba1c()), emptyList(), today, emptyMap())
        assertEquals(1, first.size)
        assertTrue(first.first().hasNoData)
        // напоминали сегодня → нет напоминания
        val sameDay = AnalysisReminders.compute(
            listOf(p), listOf(hba1c()), emptyList(), today,
            mapOf(5L to Dates.toEpochDay(today)),
        )
        assertTrue(sameDay.isEmpty())
        // напоминали 3 дня назад → всё ещё рано (< 7 дней)
        val threeDays = AnalysisReminders.compute(
            listOf(p), listOf(hba1c()), emptyList(), today,
            mapOf(5L to Dates.toEpochDay(today.minusDays(3))),
        )
        assertTrue(threeDays.isEmpty())
        // напоминали 7 дней назад → снова пора
        val weekAgo = AnalysisReminders.compute(
            listOf(p), listOf(hba1c()), emptyList(), today,
            mapOf(5L to Dates.toEpochDay(today.minusDays(7))),
        )
        assertEquals(1, weekAgo.size)
    }

    @Test
    fun nonDiabetic_noRemindersAtAll() {
        // не-диабетик даже с просроченной записью не получает напоминаний (п.3)
        val r = AnalysisReminders.compute(
            patients = listOf(patient(isDiabetic = false)),
            types = listOf(hba1c()),
            records = listOf(rec(1, 95)),
            today = today,
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun customTypeWithoutRecords_isSilent() {
        val custom = AnalysisType(id = 2, name = "Свой анализ", periodMonths = 1)
        val r = AnalysisReminders.compute(
            patients = listOf(patient(isDiabetic = true)),
            types = listOf(hba1c(), custom),
            records = emptyList(),
            today = today,
            // HbA1c «нет данных» недавно напоминался → и он молчит
            lastNoDataReminderDay = mapOf(1L to Dates.toEpochDay(today)),
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun inTimeType_notIncluded() {
        val r = AnalysisReminders.compute(
            patients = listOf(patient(isDiabetic = true)),
            types = listOf(hba1c()),
            records = listOf(rec(1, 30)), // месяц назад, период 3 → в срок
            today = today,
        )
        assertTrue(r.isEmpty())
    }
}
