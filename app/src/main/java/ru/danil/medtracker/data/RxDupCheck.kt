package ru.danil.medtracker.data

import ru.danil.medtracker.data.db.Rx
import java.time.LocalDate

/**
 * Дубль-проверка рецепта по серии и номеру (Ф13.5, ТЗ 1.4.0 §6). Чистая часть:
 * нормализация аргументов, guard от пустых серии/номера (запрос по пустым строкам
 * заматчил бы все рецепты без серии/номера) и решение по найденным дублям.
 * Запрос [ru.danil.medtracker.data.db.RxDao.bySeriesNumber] делает вызывающая сторона.
 */
object RxDupCheck {

    /** Серия сравнивается как есть, без surrounding-пробелов. */
    fun normSeries(s: String): String = s.trim()

    /** Номер — без пробелов и дефисов (REPLACE в SQL убирает только пробелы). */
    fun normNumber(n: String): String = n.replace(Regex("[\\s-]"), "").trim()

    /** Найденный дубль: рецепт + контекст для решения. */
    data class Dup(
        val rx: Rx,
        /** ФИО пациента дубля — для предупреждения «уже сохранён у пациента X» (§6.2) */
        val patientFio: String,
        /** есть ли выдачи, связанные с рецептом ([ru.danil.medtracker.data.db.IssueDao.forRx]) */
        val hasIssues: Boolean,
        /** дата первой выдачи по этому рецепту — блокировка §5, если выдача есть */
        val firstIssueDate: LocalDate?,
    )

    sealed interface Result {
        /** Серия или номер пусты — проверка отключена; предупреждение в диалог (§6.2). */
        data class Skip(val warning: String) : Result

        /** Дублей нет — рецепт сохраняется как новый. */
        object None : Result

        /** Найден рецепт с той же серией/номером. */
        data class Found(
            /** рецепт для переиспользования (§6.4); null, если по дублю уже есть выдача */
            val reuse: Rx?,
            /** дубль принадлежит другому пациенту — предупреждение без блокировки (§6.2) */
            val otherPatientWarning: String?,
            /** дата существующей выдачи по этому бланку — блокировка из §5 */
            val existingIssueDate: LocalDate?,
        ) : Result
    }

    fun check(currentPatientId: Long, series: String, number: String, dups: List<Dup>): Result {
        val s = normSeries(series)
        val n = normNumber(number)
        if (s.isBlank() || n.isBlank()) {
            return Result.Skip("Серия и номер не заполнены — повторную выдачу по этому бланку не удастся распознать")
        }
        if (dups.isEmpty()) return Result.None
        val otherWarning = dups.filter { it.rx.patientId != currentPatientId }
            .minByOrNull { it.rx.id }
            ?.let { "Рецепт с этой серией/номером уже сохранён у пациента ${it.patientFio}" }
        val withIssues = dups.filter { it.hasIssues }
        if (withIssues.isNotEmpty()) {
            // есть выдача по этому бланку — блокировка (§2 п.4, §5)
            val earliest = withIssues.mapNotNull { it.firstIssueDate }.minOrNull()
            return Result.Found(reuse = null, otherPatientWarning = otherWarning, existingIssueDate = earliest)
        }
        // сохранён без выдачи — переиспользуем самую свежую запись, новой не создаём (§6.4)
        val reuse = dups.maxByOrNull { it.rx.id }!!.rx
        return Result.Found(reuse = reuse, otherPatientWarning = otherWarning, existingIssueDate = null)
    }
}
