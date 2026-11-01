package ru.danil.medtracker.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.docx.IssuesReportDoc.Row
import ru.danil.medtracker.util.Dates
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Дымовой тест отчёта по выдачам за период (Ф9, ТЗ §10 v1.3.0):
 * ZIP открывается, таблица содержит ожидаемые строки, сортировка по дате
 * и пациенту, начальные остатки — с пометкой. Данные вымышленные.
 */
class IssuesReportDocTest {

    private val from: LocalDate = LocalDate.of(2026, 11, 1)
    private val to: LocalDate = LocalDate.of(2026, 11, 30)

    private fun documentXml(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") return String(zip.readBytes(), Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        error("word/document.xml не найден — docx повреждён")
    }

    @Test
    fun zipContainsTable_rowsAndInitialBalanceMark() {
        val rows = listOf(
            Row(date = LocalDate.of(2026, 11, 20), fio = "Петров Пётр Петрович", drugName = "Тест-препарат", pieces = 13, packs = 2, perPack = 5),
            Row(date = LocalDate.of(2026, 11, 5), fio = "Иванов Иван Иванович", drugName = "Тест-устройство", pieces = 30, packs = 0, perPack = 1, isInitialBalance = true),
            Row(date = LocalDate.of(2026, 11, 5), fio = "Иванов Иван Иванович", drugName = "Тест-препарат", pieces = 7, packs = 1, perPack = 5),
        )
        val xml = documentXml(IssuesReportDoc.build(rows, from, to))
        assertTrue(xml.contains("<w:tbl>"))
        // заголовок с датами выбранного периода (Ф9.2)
        assertTrue(xml.contains("ВЫДАЧИ ЗА ПЕРИОД"))
        assertTrue(xml.contains("${Dates.formatRu(from)} — ${Dates.formatRu(to)}"))
        assertTrue(xml.contains("Иванов Иван Иванович"))
        // упаковочная расшифровка: 2 уп × 5 + 3 = 13 шт
        assertTrue(xml.contains("2 уп × 5 + 3 = 13 шт"))
        // начальный остаток — пометкой в строке (Ф9.2)
        assertTrue(xml.contains("30 шт (нач. остаток)"))
        assertTrue(xml.contains("Всего выдач за период: 3"))
    }

    @Test
    fun sortedByDateThenPatient() {
        val rows = listOf(
            Row(LocalDate.of(2026, 11, 5), "Яковлев Яков Яковлевич", "А-препарат", 1, 0, 1),
            Row(LocalDate.of(2026, 11, 5), "Иванов Иван Иванович", "Б-препарат", 1, 0, 1),
            Row(LocalDate.of(2026, 11, 2), "Сидоров Сидор Сидорович", "В-препарат", 1, 0, 1),
        )
        val xml = documentXml(IssuesReportDoc.build(rows, from, to))
        val iSidorov = xml.indexOf("Сидоров Сидор Сидорович")
        val iIvanov = xml.indexOf("Иванов Иван Иванович")
        val iYakovlev = xml.indexOf("Яковлев Яков Яковлевич")
        assertTrue(iSidorov in 0 until iIvanov)
        assertTrue(iIvanov in 0 until iYakovlev)
    }

    @Test
    fun quantityText_variants() {
        assertEquals("5 шт", Row(LocalDate.of(2026, 11, 1), "Т", "Т", 5, 0, 1).quantityText())
        assertEquals("2 уп × 5 = 10 шт", Row(LocalDate.of(2026, 11, 1), "Т", "Т", 10, 2, 5).quantityText())
        assertEquals("2 уп × 5 + 3 = 13 шт", Row(LocalDate.of(2026, 11, 1), "Т", "Т", 13, 2, 5).quantityText())
        assertEquals("30 шт (нач. остаток)", Row(LocalDate.of(2026, 11, 1), "Т", "Т", 30, 0, 1, isInitialBalance = true).quantityText())
    }
}
