package ru.danil.medtracker.data

import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import java.time.LocalDate

/**
 * Быстрая выдача из «Срочного» (Ф6): подбор количества по умолчанию.
 * Решение по умолчанию (ТЗ §13 п.12 + уточнение): штучная норма, приведённая
 * к месяцу — типовая выдача «на месяц»; если штучной части нет — как в последней
 * выдаче. Целочисленно, округление вверх, без double (п. 4.1 ТЗ).
 */
object QuickIssue {

    /** Месячная потребность по штучной части действующей нормы, иначе последняя выдача. */
    fun defaultPieces(norms: List<Norm>, today: LocalDate, lastIssue: Issue?): Int? {
        fun fallback(): Int? = lastIssue?.totalPieces?.takeIf { it > 0 }
        val norm = CalcService.currentNorm(norms, today) ?: return fallback()
        if (norm.noCalc) return fallback() // «без расчёта срока» — месячную норму не вычислить
        val kind = norm.pieceKind ?: return fallback()
        val amount = norm.pieceAmount ?: return fallback()
        val monthly = when (kind) {
            "PER_DAY" -> amount * 30
            "PER_MONTH" -> amount
            "PER_YEAR" -> (amount + 11) / 12 // округление вверх без дробей
            "EVERY_X_DAYS" -> {
                val x = norm.everyXDays ?: return fallback()
                if (x <= 0) return fallback()
                (amount * 30 + x - 1) / x // ceil(amount * 30 / x)
            }
            else -> return fallback()
        }
        return monthly.takeIf { it > 0 } ?: fallback()
    }
}
