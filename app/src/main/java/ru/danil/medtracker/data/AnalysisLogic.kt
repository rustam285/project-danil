package ru.danil.medtracker.data

import ru.danil.medtracker.data.db.AnalysisRecord
import ru.danil.medtracker.data.db.AnalysisType
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.util.Dates
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Чистая логика вкладки «Анализы» (Ф3): определение диабетика, срок следующей
 * сдачи и правила напоминаний Ф3.7. Без Android — покрыта юнит-тестами.
 * Даты — java.time, без double (п. 4.1 ТЗ).
 */

/** «Диабетик» = пометка вручную ИЛИ МКБ E10/E11 (Ф3.6), с учётом кириллических двойников (Е10 → E10). */
object Diabetes {

    /** Кириллические двойники латинских букв — как в OcrEngine (МКБ печатают в обеих раскладках). */
    private val cyrLookalikes = mapOf(
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H',
        'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X',
    )

    private fun toLatin(s: String): String = buildString(s.length) {
        for (c in s.uppercase()) append(cyrLookalikes[c] ?: c)
    }

    fun isDiabetic(patient: Patient): Boolean {
        if (patient.isDiabetic) return true
        val dx = toLatin(patient.diagnosis?.trim().orEmpty())
        if (dx.isEmpty()) return false
        return Regex("""\bE1[01](\.\d+)?\b""").containsMatchIn(dx)
    }
}

/** Статус срока сдачи анализа (Ф3.4). */
sealed interface AnalysisDue {
    /** записей этого вида у пациента нет */
    object NoData : AnalysisDue

    /** следующая сдача (= последняя запись + период, LocalDate.plusMonths) наступила или прошла;
     *  в день срока — overdueDays = 0 («пора сдавать») */
    data class Due(val overdueDays: Long, val dueDate: LocalDate) : AnalysisDue

    /** сдача в срок: dueDate ещё впереди */
    data class InTime(val dueDate: LocalDate) : AnalysisDue
}

object AnalysisLogic {

    /** Следующая сдача: дата последней записи + период в месяцах (календарно, Ф3.4). */
    fun nextDue(lastRecordDate: LocalDate, periodMonths: Int): LocalDate =
        lastRecordDate.plusMonths(periodMonths.toLong())

    fun statusFor(records: List<AnalysisRecord>, type: AnalysisType, today: LocalDate): AnalysisDue {
        val last = records.filter { it.typeId == type.id }.maxByOrNull { it.date }
            ?: return AnalysisDue.NoData
        return statusOfLast(Dates.fromEpochDay(last.date), type.periodMonths, today)
    }

    fun statusOfLast(lastDate: LocalDate, periodMonths: Int, today: LocalDate): AnalysisDue {
        val due = nextDue(lastDate, periodMonths)
        return if (!due.isAfter(today)) {
            AnalysisDue.Due(ChronoUnit.DAYS.between(due, today), due)
        } else {
            AnalysisDue.InTime(due)
        }
    }
}

/** Одно напоминание на пациента в день: список видов к сдаче (Ф3.7 п.4). */
data class AnalysisReminder(
    val patientId: Long,
    val fio: String,
    /** строки вида «Гликированный гемоглобин (HbA1c), последний 12.08» */
    val lines: List<String>,
    /** среди строк есть «нет данных» по HbA1c — фиксируем дату напоминания (п.2) */
    val hasNoData: Boolean,
)

/**
 * Правила напоминаний Ф3.7 (чистая функция):
 * 1) вид с ≥1 записью и наступившим сроком — строка в напоминании пациента (ежедневно);
 * 2) диабетик без единой записи HbA1c — строка «нет данных» не чаще раза в 7 дней;
 * 3) не-диабетики и виды без записей — напоминаний нет;
 * 4) несколько просроченных видов — одно уведомление на пациента.
 */
object AnalysisReminders {

    const val NO_DATA_INTERVAL_DAYS = 7L

    fun compute(
        patients: List<Patient>,
        types: List<AnalysisType>,
        records: List<AnalysisRecord>,
        today: LocalDate,
        /** patientId → epochDay последнего напоминания «нет данных» */
        lastNoDataReminderDay: Map<Long, Long> = emptyMap(),
    ): List<AnalysisReminder> {
        val result = mutableListOf<AnalysisReminder>()
        val todayEpoch = Dates.toEpochDay(today)
        for (p in patients) {
            if (p.archived) continue
            if (!Diabetes.isDiabetic(p)) continue // п.3: не-диабетикам — без напоминаний
            val mine = records.filter { it.patientId == p.id }
            val lines = mutableListOf<String>()
            var hasNoData = false

            for (t in types) {
                val of = mine.filter { it.typeId == t.id }
                if (of.isEmpty()) {
                    // п.2: только встроенный HbA1c напоминает об отсутствии данных, раз в 7 дней
                    if (!t.isBuiltIn) continue // п.3: пользовательские виды без записей молчат
                    val last = lastNoDataReminderDay[p.id] ?: 0L
                    if (todayEpoch - last < NO_DATA_INTERVAL_DAYS) continue
                    lines.add("${t.name} — ещё нет записей")
                    hasNoData = true
                } else {
                    val lastRec = of.maxByOrNull { it.date }!!
                    val due = AnalysisLogic.statusOfLast(
                        Dates.fromEpochDay(lastRec.date), t.periodMonths, today,
                    )
                    if (due is AnalysisDue.Due) {
                        lines.add("${t.name}, последний ${Dates.formatShort(lastRec.date)}")
                    }
                }
            }
            if (lines.isNotEmpty()) {
                result.add(AnalysisReminder(p.id, p.fio, lines, hasNoData))
            }
        }
        return result
    }
}
