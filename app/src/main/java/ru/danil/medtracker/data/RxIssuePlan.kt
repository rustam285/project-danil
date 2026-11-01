package ru.danil.medtracker.data

import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * Чистая логика плана выдачи по отсканированному рецепту (Ф13.4, ТЗ 1.4.0 §5):
 * штуки = «Количество единиц» × «№»; при совпадении «№» с упаковкой справочника
 * выдача запишется упаковками, иначе — штуками с предупреждением. Блокировки
 * (пустые поля, будущая дата рецепта, уже существующая выдача по рецепту)
 * делают кнопку выдачи недоступной; предупреждения выдачу разрешают.
 */
object RxIssuePlan {

    data class Input(
        val units: Int?, val packSize: Int?, val drugPiecesPerPack: Int,
        val rxDate: LocalDate?, val today: LocalDate, val validUntil: LocalDate?,
        val deferred: Boolean, val existingIssueDate: LocalDate?, val laterIssueDate: LocalDate?,
    )

    data class Plan(
        val packs: Int, val pieces: Int, val total: Int,
        val blocks: List<String>, val warnings: List<String>,
    )

    fun build(i: Input): Plan {
        val blocks = mutableListOf<String>(); val warn = mutableListOf<String>()
        val u = i.units?.takeIf { it > 0 }
        val p = i.packSize?.takeIf { it > 0 }
        if (u == null) blocks += "Укажите «Количество единиц»"
        if (p == null) blocks += "Укажите размер упаковки (№)"
        if (i.rxDate == null) blocks += "Укажите дату рецепта"
        else if (i.rxDate.isAfter(i.today)) blocks += "Дата рецепта в будущем"
        i.existingIssueDate?.let { blocks += "По этому рецепту уже есть выдача от ${Dates.formatRu(it)}" }
        val total = if (u != null && p != null) u * p else 0
        var packs = 0; var pieces = 0
        if (u != null && p != null) {
            if (p == i.drugPiecesPerPack) packs = u
            else {
                pieces = total
                warn += "Упаковка в справочнике (${i.drugPiecesPerPack}) не равна рецепту (№$p): выдача запишется штуками ($total шт)"
            }
        }
        if (i.deferred) warn += "Рецепт поставлен на отсроченное обеспечение: выдача записывается датой рецепта"
        i.validUntil?.takeIf { it.isBefore(i.today) }?.let { warn += "Срок действия рецепта истёк ${Dates.formatRu(it)}" }
        i.laterIssueDate?.let { warn += "По назначению уже есть выдача от ${Dates.formatRu(it)} (позже даты рецепта): «обеспечен до» пересчитается с её учётом" }
        return Plan(packs, pieces, total, blocks, warn)
    }
}
