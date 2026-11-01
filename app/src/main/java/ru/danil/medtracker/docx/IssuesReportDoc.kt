package ru.danil.medtracker.docx

import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * Отчёт по выдачам за период (Ф9): таблица дата / пациент / позиция / количество
 * из журнала выдач. Начальные остатки включаются с пометкой в строке (ТЗ §13 п.14).
 * Строится по образцу [SummaryDoc] тем же [DocxBuilder].
 */
object IssuesReportDoc {

    data class Row(
        val date: LocalDate,
        val fio: String,
        val drugName: String,
        val pieces: Int,
        val packs: Int,
        val perPack: Int,
        val isInitialBalance: Boolean = false,
    ) {
        /** «2 уп × 5 + 3 = 13 шт», у начального остатка — пометка. */
        fun quantityText(): String {
            val base = when {
                packs > 0 && pieces % perPack > 0 && perPack > 1 ->
                    "$packs уп × $perPack + ${pieces % perPack} = $pieces шт"
                packs > 0 && perPack > 1 -> "$packs уп × $perPack = $pieces шт"
                else -> "$pieces шт"
            }
            return if (isInitialBalance) "$base (нач. остаток)" else base
        }
    }

    fun build(rows: List<Row>, from: LocalDate, to: LocalDate): ByteArray {
        val b = DocxBuilder()
        b.para("ВЫДАЧИ ЗА ПЕРИОД", bold = true, align = "center", size = 28, after = 40)
        b.para(
            "${Dates.formatRu(from)} — ${Dates.formatRu(to)}",
            align = "center", after = 200,
        )

        val header = listOf(
            listOf(DocxBuilder.Run("Дата", true)),
            listOf(DocxBuilder.Run("Пациент", true)),
            listOf(DocxBuilder.Run("Позиция", true)),
            listOf(DocxBuilder.Run("Количество", true)),
        )
        // сортировка по дате, затем по пациенту (Ф9.2)
        val sorted = rows.sortedWith(compareBy({ it.date }, { it.fio }))
        val tableRows = mutableListOf(header)
        sorted.forEach { r ->
            tableRows.add(
                listOf(
                    listOf(DocxBuilder.Run(Dates.formatRu(r.date))),
                    listOf(DocxBuilder.Run(r.fio)),
                    listOf(DocxBuilder.Run(r.drugName)),
                    listOf(DocxBuilder.Run(r.quantityText())),
                ),
            )
        }
        b.table(DocxBuilder.Table(tableRows, widths = listOf(1500, 3000, 2800, 1700), header = true))
        b.para("Всего выдач за период: ${sorted.size}.")
        b.para("Документ сформирован ${Dates.formatRu(LocalDate.now())}.")
        return b.build()
    }
}
