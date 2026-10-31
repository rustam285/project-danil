package ru.danil.medtracker.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Дата хранится в БД как epochDay (Long) — точное представление без таймзон. */
object Dates {
    private val ru = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val ruShort = DateTimeFormatter.ofPattern("dd.MM")
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE

    fun today(): LocalDate = LocalDate.now()

    fun toEpochDay(d: LocalDate): Long = d.toEpochDay()
    fun fromEpochDay(v: Long): LocalDate = LocalDate.ofEpochDay(v)

    /** «15.12.2026» */
    fun formatRu(d: LocalDate): String = d.format(ru)
    fun formatRu(epochDay: Long): String = formatRu(fromEpochDay(epochDay))

    /** «15.12» для пометок перерывов */
    fun formatShort(d: LocalDate): String = d.format(ruShort)
    fun formatShort(epochDay: Long): String = formatShort(fromEpochDay(epochDay))

    fun formatIso(d: LocalDate): String = d.format(iso)

    /** Парсинг «15.12.2026», «15.12.26», «15.12». Возвращает null при ошибке. */
    fun parseFlexible(s: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val t = s.trim().replace(Regex("[^0-9.]"), "")
        if (t.isEmpty()) return null
        val parts = t.split(".")
        return try {
            when (parts.size) {
                3 -> {
                    var (d, m, y) = parts.map { it.toInt() }
                    if (y < 100) y += if (y > 70) 1900 else 2000
                    LocalDate.of(y, m, d)
                }
                2 -> {
                    val (d, m) = parts.map { it.toInt() }
                    var base = LocalDate.of(today.year, m, d)
                    // если такая дата в текущем году уже далеко в прошлом — берём следующий год
                    if (base.isBefore(today.minusDays(7))) base = base.plusYears(1)
                    base
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun daysInMonth(d: LocalDate): Int = d.lengthOfMonth()
    fun daysInYear(d: LocalDate): Int = d.lengthOfYear()

    /** Возраст «7 лет» / «11 мес» / «5 лет 3 мес» (для списков, п. 3.1). */
    fun ageString(birth: LocalDate, today: LocalDate = LocalDate.now()): String {
        var years = today.year - birth.year
        var months = today.monthValue - birth.monthValue
        if (today.dayOfMonth < birth.dayOfMonth) months--
        if (months < 0) {
            years--
            months += 12
        }
        val yWord = plural(years, "год", "года", "лет")
        val mWord = plural(months, "месяц", "месяца", "месяцев")
        return when {
            years > 0 && months > 0 -> "$years $yWord $months $mWord"
            years > 0 -> "$years $yWord"
            months > 0 -> "$months $mWord"
            else -> "новорождённый"
        }
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && (m100 < 12 || m100 > 14) -> few
            else -> many
        }
    }
}
