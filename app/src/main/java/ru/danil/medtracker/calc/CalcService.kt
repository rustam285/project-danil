package ru.danil.medtracker.calc

import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/** Уровень подсветки дат «обеспечен до» и сроков рецептов (п. 7.4). */
enum class Level { RED, YELLOW, GREEN }

object CalcService {

    fun normRecs(norms: List<Norm>): List<NormRec> = norms.map { n ->
        NormRec(
            validFrom = Dates.fromEpochDay(n.validFrom),
            pieceKind = n.pieceKind?.let { runCatching { PieceKind.valueOf(it) }.getOrNull() },
            pieceAmount = n.pieceAmount,
            everyXDays = n.everyXDays,
            meAmount = n.meAmount,
            noCalc = n.noCalc,
        )
    }

    fun stockEvents(issues: List<Issue>): List<StockEvent> = issues.map { i ->
        StockEvent(
            date = Dates.fromEpochDay(i.date),
            totalPieces = i.totalPieces,
            isInitialBalance = i.isInitialBalance,
            id = i.id,
        )
    }

    fun supplyFor(issues: List<Issue>, norms: List<Norm>, drug: Drug?): SupplyResult =
        SupplyEngine.supplyEnd(stockEvents(issues), normRecs(norms), drug?.mePerPiece())

    /** Текущая (действующая сегодня) запись нормы; null, если истории нет. */
    fun currentNorm(norms: List<Norm>, today: LocalDate = Dates.today()): Norm? =
        norms.filter { !Dates.fromEpochDay(it.validFrom).isAfter(today) }
            .maxByOrNull { it.validFrom }

    /** Назначение «без расчёта срока», если действующая запись помечена noCalc (п. 4.4). */
    fun isNoCalc(norms: List<Norm>, today: LocalDate = Dates.today()): Boolean =
        currentNorm(norms, today)?.noCalc == true

    /** Уровень подсветки по дате «обеспечен до» (п. 7.4): сегодня — жёлтый. */
    fun level(until: LocalDate?, thresholdDays: Int, today: LocalDate = Dates.today()): Level {
        if (until == null) return Level.GREEN
        return when {
            until.isBefore(today) -> Level.RED
            !until.isAfter(today.plusDays(thresholdDays.toLong())) -> Level.YELLOW
            else -> Level.GREEN
        }
    }

    /** Срок действия рецепта: validUntil либо issueDate + validDays (п. 3.5). */
    fun rxValidUntil(issueDate: Long?, validDays: Int?, validUntil: Long?): LocalDate? {
        validUntil?.let { return Dates.fromEpochDay(it) }
        if (issueDate != null && validDays != null) {
            return Dates.fromEpochDay(issueDate).plusDays(validDays.toLong())
        }
        return null
    }

    fun rxLevel(rxValidUntil: LocalDate?, thresholdDays: Int, today: LocalDate = Dates.today()): Level {
        if (rxValidUntil == null) return Level.GREEN
        return when {
            rxValidUntil.isBefore(today) -> Level.RED
            !rxValidUntil.isAfter(today.plusDays(thresholdDays.toLong())) -> Level.YELLOW
            else -> Level.GREEN
        }
    }

    // ── Форматирование норм ──────────────────────────────────────────────────

    /** «8 шт в день», «10 шт в месяц», «1 шт раз в 7 дней», «4 ЕД/сутки», «без расчёта срока». */
    fun normShort(norm: Norm?): String {
        if (norm == null) return "норма не задана"
        if (norm.noCalc) return "без расчёта срока"
        if (norm.pieceKind != null && norm.pieceAmount != null) {
            val n = norm.pieceAmount
            return when (norm.pieceKind) {
                "PER_DAY" -> "$n шт в день"
                "PER_MONTH" -> "$n шт в месяц"
                "PER_YEAR" -> "$n шт в год"
                "EVERY_X_DAYS" -> "$n шт раз в ${norm.everyXDays} дн"
                else -> "$n шт"
            }
        }
        if (norm.meAmount != null) return "${norm.meAmount} ЕД/сутки"
        return "—"
    }

    /** «10 картриджей в месяц» для документов — с падежами названия штуки. */
    fun normDoc(norm: Norm?, pieceForms: Triple<String, String, String>?): String {
        if (norm == null) return ""
        fun pieces(n: Int): String = pieceForms?.let {
            val w = when {
                n % 10 == 1 && n % 100 != 11 -> it.first
                n % 10 in 2..4 && (n % 100 < 12 || n % 100 > 14) -> it.second
                else -> it.third
            }
            "$n $w"
        } ?: "$n шт"
        if (norm.noCalc) return "резерв / разовые выдачи (без расчёта срока)"
        if (norm.pieceKind != null && norm.pieceAmount != null) {
            val n = norm.pieceAmount
            return when (norm.pieceKind) {
                "PER_DAY" -> "${pieces(n)} в день"
                "PER_MONTH" -> "${pieces(n)} в месяц"
                "PER_YEAR" -> "${pieces(n)} в год"
                "EVERY_X_DAYS" -> "${pieces(n)} раз в ${norm.everyXDays} дней"
                else -> pieces(n)
            }
        }
        if (norm.meAmount != null) return "${norm.meAmount} ЕД в сутки"
        return ""
    }

    /** «уп. №5», «уп. по 50 шт», «флакон 100 мл», «картридж 3 мл × 100 МЕ/мл, уп. №5». */
    fun drugForm(drug: Drug): String {
        val parts = mutableListOf<String>()
        val unit = drug.unit.lowercase().trim()
        val ppp = drug.piecesPerPack
        val noun = pieceNounForms(drug)?.second ?: "шт"
        when {
            unit.contains("упак") -> parts.add(if (ppp == 1) "уп. №1" else "уп. по $ppp $noun")
            unit.contains("флак") -> parts.add("флакон" + (drug.volumeMl?.let { " $it мл" } ?: "") + if (ppp > 1) ", уп. по $ppp фл" else "")
            unit.contains("банк") -> parts.add("банка" + if (ppp > 1) ", уп. по $ppp банк" else "")
            else -> parts.add(noun)
        }
        drug.concMePerMl?.let { c -> drug.volumeMl?.let { v -> parts[0] = "картридж/флакон $v мл × $c МЕ/мл" + if (ppp > 1) ", уп. №$ppp" else "" } }
        drug.concPercent?.let { parts.add("$it %") }
        if (drug.concMePerMl == null) drug.volumeMl?.let { if (!unit.contains("флак")) parts.add("$it мл") }
        return parts.joinToString(", ")
    }

    /** Тройная форма названия штуки «картридж/картриджа/картриджей». */
    fun pieceNounForms(drug: Drug): Triple<String, String, String>? {
        val forms = drug.pieceNounForms ?: return null
        val p = forms.split("/").map { it.trim() }
        return if (p.size == 3 && p.all { it.isNotEmpty() }) Triple(p[0], p[1], p[2]) else null
    }
}
