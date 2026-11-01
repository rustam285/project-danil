package ru.danil.medtracker.docx

import ru.danil.medtracker.data.UrgentKind
import ru.danil.medtracker.data.UrgentState
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * «Срочная выдача» — экспорт вкладки «Срочное» в Word (Ф7).
 * Строится по образцу [SummaryDoc] тем же [DocxBuilder].
 * Резервные назначения не включаются: они исключены уже при построении [UrgentState] (Ф2.3).
 */
object UrgentDoc {

    fun build(state: UrgentState, today: LocalDate): ByteArray {
        val b = DocxBuilder()
        b.para("СРОЧНАЯ ВЫДАЧА — ${Dates.formatRu(today)}", bold = true, align = "center", size = 28, after = 200)

        val header = listOf(
            listOf(DocxBuilder.Run("№", true)),
            listOf(DocxBuilder.Run("Пациент", true)),
            listOf(DocxBuilder.Run("Препарат / устройство", true)),
            listOf(DocxBuilder.Run("Обеспечен до", true)),
            listOf(DocxBuilder.Run("Примечание", true)),
        )
        val rows = mutableListOf(header)

        // порядок и сортировка — как на экране: Закончилось → В ожидании → Осталось мало → Рецепты
        var n = 0
        fun addRows(items: List<ru.danil.medtracker.data.UrgentItem>, noteOf: (ru.danil.medtracker.data.UrgentItem) -> String) {
            for (item in items) {
                n++
                val position = when (item.kind) {
                    UrgentKind.RX -> item.drug?.name
                        ?: "Рецепт ${item.rx?.series ?: ""} ${item.rx?.number ?: ""}".trim()
                    else -> item.drug?.name ?: ""
                }
                rows.add(
                    listOf(
                        listOf(DocxBuilder.Run("$n")),
                        listOf(DocxBuilder.Run(item.patient.fio)),
                        listOf(DocxBuilder.Run(position)),
                        listOf(DocxBuilder.Run(item.date?.let { Dates.formatRu(it) } ?: "—")),
                        listOf(DocxBuilder.Run(noteOf(item))),
                    ),
                )
            }
        }
        addRows(state.ended) { "закончилось" }
        addRows(state.waiting) { "ожидается поставка" }
        addRows(state.low) { "осталось мало" }
        addRows(state.rx) {
            val expired = (it.date ?: today).isBefore(today)
            if (expired) "рецепт истёк" else "истекает рецепт"
        }

        if (n == 0) {
            b.para("Срочных позиций нет.")
            return b.build()
        }

        b.table(DocxBuilder.Table(rows, widths = listOf(500, 2500, 2500, 1600, 1900), header = true))
        b.para("Документ сформирован ${Dates.formatRu(today)}.")
        return b.build()
    }
}
