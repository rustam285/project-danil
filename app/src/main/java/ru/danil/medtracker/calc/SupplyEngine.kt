package ru.danil.medtracker.calc

import java.time.LocalDate

/** Вид штучной части нормы (п. 3.3 ТЗ). */
enum class PieceKind { PER_DAY, PER_MONTH, PER_YEAR, EVERY_X_DAYS }

/**
 * Запись нормы — одна «версия» с датой начала действия (п. 3.3 ТЗ).
 * Чистые данные без Room — чтобы движок тестировался независимо (Приложение А).
 */
data class NormRec(
    val validFrom: LocalDate,
    val pieceKind: PieceKind? = null,
    val pieceAmount: Int? = null,
    val everyXDays: Int? = null,
    val meAmount: Int? = null, // МЕ (ЕД) в сутки
    val noCalc: Boolean = false,
) {
    /** Штучная часть есть и приоритетнее МЕ-части (п. 3.3). */
    fun hasPiecePart(): Boolean = pieceKind != null && pieceAmount != null &&
        (pieceKind != PieceKind.EVERY_X_DAYS || (everyXDays ?: 0) > 0)

    fun hasMePart(): Boolean = meAmount != null
}

/** Событие запаса: выдача или начальный остаток (п. 3.4, п. 10). */
data class StockEvent(
    val date: LocalDate,
    val totalPieces: Int,
    val isInitialBalance: Boolean = false,
    val id: Long = 0,
)

sealed interface SupplyOutcome {
    /** Выдач/остатков нет — считать нечего. */
    data object NoEvents : SupplyOutcome
    /** «Обеспечен до» конкретного дня. */
    data class UntilDate(val date: LocalDate) : SupplyOutcome
    /** Запас не исчерпывается за горизонт 5 лет (п. 4.2.6). */
    data object Unlimited : SupplyOutcome
}

data class SupplyResult(
    val outcome: SupplyOutcome,
    /** Перерывы (дни дефицита) между выдачами — для журнала «перерыв ДД.ММ–ДД.ММ» (п. 4.2.5). */
    val gaps: List<Pair<LocalDate, LocalDate>> = emptyList(),
    val lastEventDate: LocalDate? = null,
) {
    fun coveredUntil(): LocalDate? = (outcome as? SupplyOutcome.UntilDate)?.date
}

/**
 * Ядро расчёта «обеспечен до» — реализация Приложения Г ТЗ.
 * Чистая функция от истории (выдачи, нормы) — пересчёт целиком (п. 4.2.8).
 */
object SupplyEngine {

    const val HORIZON_YEARS = 5L

    /** Расход в штуках за день d по правилам п. 4.1. */
    fun rate(d: LocalDate, norms: List<NormRec>, mePerPiece: Int?): Rational {
        val rec = applicableNorm(d, norms) ?: return Rational.ZERO
        return rateForDay(rec, d, mePerPiece)
    }

    /** Запись нормы с наибольшим validFrom ≤ d (п. 3.3); поздняя запись заменяет прежнюю целиком. */
    fun applicableNorm(d: LocalDate, norms: List<NormRec>): NormRec? =
        norms.filter { !it.noCalc && !it.validFrom.isAfter(d) }
            .maxByOrNull { it.validFrom }

    private fun rateForDay(rec: NormRec, d: LocalDate, mePerPiece: Int?): Rational {
        if (rec.noCalc) return Rational.ZERO
        if (rec.hasPiecePart()) {
            val n = rec.pieceAmount!!.toLong()
            return when (rec.pieceKind) {
                PieceKind.PER_DAY -> Rational.of(n)
                PieceKind.PER_MONTH -> Rational.of(n, d.lengthOfMonth().toLong())
                PieceKind.PER_YEAR -> Rational.of(n, d.lengthOfYear().toLong())
                PieceKind.EVERY_X_DAYS -> Rational.of(n, rec.everyXDays!!.toLong())
                null -> Rational.ZERO
            }
        }
        if (rec.meAmount != null && mePerPiece != null && mePerPiece > 0) {
            return Rational.of(rec.meAmount.toLong(), mePerPiece.toLong())
        }
        return Rational.ZERO
    }

    /**
     * «Обеспечен до» по Приложению Г.
     * Запас доступен со следующего дня; день дефицита обнуляет запас;
     * выдача после перерыва считается с нуля (п. 4.2.2–4).
     */
    fun supplyEnd(
        events: List<StockEvent>,
        norms: List<NormRec>,
        mePerPiece: Int?,
    ): SupplyResult {
        if (events.isEmpty()) return SupplyResult(SupplyOutcome.NoEvents)
        val sorted = events.sortedBy { it.date }
        val last = sorted.last().date
        val byDate = HashMap<LocalDate, Long>()
        sorted.forEach { byDate[it.date] = (byDate[it.date] ?: 0L) + it.totalPieces }

        var stock = Rational.ZERO
        var covered: LocalDate? = null
        val gapRuns = mutableListOf<Pair<LocalDate, LocalDate>>()
        var gapStart: LocalDate? = null

        val first = sorted.first().date
        val horizon = last.plusYears(HORIZON_YEARS)
        var d = first.plusDays(1)
        var exhausted = false
        while (!exhausted && d <= horizon) {
            val added = byDate[d.minusDays(1)]
            if (added != null) stock += Rational.of(added)
            val r = rate(d, norms, mePerPiece)
            if (stock >= r) {
                stock -= r
                val gs = gapStart
                if (gs != null) {
                    // перерыв закрыт новой выдачей: с gs по d-1
                    gapRuns.add(gs to d.minusDays(1))
                    gapStart = null
                }
                covered = d
            } else {
                stock = Rational.ZERO
                if (gapStart == null) gapStart = d
                if (d > last) exhausted = true // дефицит после последней выдачи — «закончилось», не перерыв
            }
            if (!exhausted) d = d.plusDays(1)
        }
        if (!exhausted) {
            return SupplyResult(SupplyOutcome.Unlimited, gapRuns, last)
        }
        val result = covered?.takeIf { it.isAfter(last) } ?: last
        return SupplyResult(SupplyOutcome.UntilDate(result), gapRuns, last)
    }

    /**
     * Остаток на дату X — запас после обработки дня X (п. 4.2.7), отрицательным не бывает.
     */
    fun remainderAt(
        date: LocalDate,
        events: List<StockEvent>,
        norms: List<NormRec>,
        mePerPiece: Int?,
    ): Rational {
        val sorted = events.filter { !it.date.isAfter(date) }.sortedBy { it.date }
        if (sorted.isEmpty()) return Rational.ZERO
        val byDate = HashMap<LocalDate, Long>()
        sorted.forEach { byDate[it.date] = (byDate[it.date] ?: 0L) + it.totalPieces }
        var stock = Rational.ZERO
        var d = sorted.first().date.plusDays(1)
        while (d <= date) {
            val added = byDate[d.minusDays(1)]
            if (added != null) stock += Rational.of(added)
            val r = rate(d, norms, mePerPiece)
            stock -= r
            if (stock.isNegative()) stock = Rational.ZERO
            d = d.plusDays(1)
        }
        return stock
    }

    /**
     * Сколько штук нужно, чтобы покрыть расход с [from] по [to] включительно (п. 4.2.9):
     * для начального остатка, введённого только датой «обеспечен до». Округление вверх.
     */
    fun piecesNeeded(
        from: LocalDate,
        to: LocalDate,
        norms: List<NormRec>,
        mePerPiece: Int?,
    ): Long {
        if (to.isBefore(from)) return 0
        var sum = Rational.ZERO
        var d = from
        while (!d.isAfter(to)) {
            sum += rate(d, norms, mePerPiece)
            d = d.plusDays(1)
        }
        val whole = sum.truncate()
        return if (!sum.minus(Rational.of(whole, java.math.BigInteger.ONE)).isZero()) whole.toLong() + 1 else whole.toLong()
    }

    /**
     * «Требуется на период» для сводной потребности (п. 8.3): точная сумма, округление вверх
     * выполняет вызывающий после суммирования по пациентам.
     */
    fun requirementForPeriod(
        norm: NormRec,
        periodDays: Int,
    ): Rational {
        val days = periodDays.toLong()
        if (norm.noCalc) return Rational.ZERO
        if (norm.hasPiecePart()) {
            val n = norm.pieceAmount!!.toLong()
            return when (norm.pieceKind) {
                PieceKind.PER_DAY -> Rational.of(n) * Rational.of(days)
                PieceKind.PER_MONTH -> Rational.of(n)
                PieceKind.PER_YEAR -> Rational.of(n * days, norm.validFrom.lengthOfYear().toLong())
                PieceKind.EVERY_X_DAYS -> Rational.of(n * days, norm.everyXDays!!.toLong())
                null -> Rational.ZERO
            }
        }
        return Rational.ZERO
    }

    /** Требование по МЕ-норме: (МЕ/сутки ÷ МЕ-в-штуке) × дни периода (п. 3.3, 8.3). */
    fun meRequirementForPeriod(meAmount: Int, mePerPiece: Int, periodDays: Int): Rational =
        Rational.of(meAmount.toLong(), mePerPiece.toLong()) * Rational.of(periodDays.toLong())

    /**
     * «Обеспечен до» на момент конкретной выдачи (п. 4.6): учитываются выдачи до неё включительно
     * (по дате и порядку), история норм — текущая. Для строк журнала и плана ДН.
     */
    fun supplyEndAsOf(
        event: StockEvent,
        events: List<StockEvent>,
        norms: List<NormRec>,
        mePerPiece: Int?,
    ): SupplyResult {
        val sorted = events.sortedWith(compareBy({ it.date }, { it.id }))
        val idx = sorted.indexOfFirst { it.id == event.id && it.date == event.date }
        if (idx < 0) return SupplyResult(SupplyOutcome.NoEvents)
        return supplyEnd(sorted.subList(0, idx + 1), norms, mePerPiece)
    }
}
