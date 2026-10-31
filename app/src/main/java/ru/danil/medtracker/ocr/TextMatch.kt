package ru.danil.medtracker.ocr

/**
 * Сопоставление распознанных OCR строк (ФИО, наименования препаратов) с записями базы.
 * Чистые функции — покрыты юнит-тестами (TextMatchTest).
 */
object TextMatch {

    /** Минимальный балл, при котором совпадение предлагается автоматически. */
    const val AUTO_SELECT_MIN = 70

    /** Нормализация: нижний регистр, ё→е, без пунктуации, пробелы схлопнуты. */
    fun normalize(s: String): String = buildString {
        var prevSpace = true
        for (c in s.lowercase()) {
            val mapped = when {
                c == 'ё' -> 'е'
                c.isLetterOrDigit() -> c
                c == '-' || c == '_' -> ' '
                else -> ' '
            }
            if (mapped == ' ') {
                if (!prevSpace) append(' ')
                prevSpace = true
            } else {
                append(mapped)
                prevSpace = false
            }
        }
    }.trim()

    /** Значимые токены (длиннее одного символа). */
    fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.length > 1 }.toSet()

    /**
     * Сходство двух названий 0..100: пересечение токенов (Жаккар) + бонус за вхождение
     * одной строки в другую (фамилия против полного ФИО, короткое название и т.п.).
     */
    fun similarity(a: String, b: String): Int {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0
        if (na == nb) return 100
        val ta = tokens(a)
        val tb = tokens(b)
        val union = ta.union(tb).size
        var score = if (union == 0) 0 else ta.intersect(tb).size * 100 / union
        if (nb.contains(na) || na.contains(nb)) score = maxOf(score, 80)
        return score
    }

    /**
     * Лучшие кандидаты по набору распознанных строк: балл кандидата — максимум
     * по всем строкам. Возвращает пары (кандидат, балл), убывание, только score > 0.
     */
    fun <T> bestMatches(
        queries: List<String>,
        candidates: List<T>,
        nameOf: (T) -> String,
        limit: Int = 3,
    ): List<Pair<T, Int>> = candidates
        .map { c -> c to (queries.maxOfOrNull { q -> similarity(q, nameOf(c)) } ?: 0) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(limit)
}
