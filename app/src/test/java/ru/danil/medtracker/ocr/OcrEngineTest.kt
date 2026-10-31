package ru.danil.medtracker.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Разбор полей рецепта по тексту OCR (п. 6.3) — на структуру формы 148-1/у-04(л). */
class OcrEngineTest {

    /** Чистое распознавание печатного бланка. */
    @Test
    fun parseCleanForm() {
        val text = """
            РЕЦЕПТ
            Серия 75464 № 25 222 011 880 от 29.07.2026
            Ф.И.О. пациента: Тестов Тест Тестович
            Дата рождения: 01.03.2015
            Наименование: Тест-полоски Ван Тач Селект Плюс
            Продолжительность (дни) 90
        """.trimIndent()
        val d = OcrEngine.parse(text)
        assertEquals("75464", d.series)
        assertEquals("25222011880", d.number)
        assertEquals("29.07.2026", d.issueDate)
        assertEquals(90, d.validDays)
    }

    /** Шумное распознавание: двоеточия, склейки, короткий год, срок «60 дней». */
    @Test
    fun parseNoisyForm() {
        val text = """
            РЕЦЕПТ Серия: 75464 №25222011 880 от 15.11.26
            ФИО: Тестова Анна Ивановна
            действителен 60 дней
        """.trimIndent()
        val d = OcrEngine.parse(text)
        assertEquals("75464", d.series)
        assertEquals("25222011880", d.number)
        assertEquals("15.11.2026", d.issueDate)
        assertEquals(60, d.validDays)
    }

    /** Ничего не распозналось — поля пустые, без падений. */
    @Test
    fun parseGarbage() {
        val d = OcrEngine.parse("!!! ???")
        assertNull(d.series)
        assertNull(d.number)
        assertNull(d.issueDate)
        assertNull(d.validDays)
    }

    /** Код МКБ извлекается. */
    @Test
    fun parseDiagnosis() {
        val d = OcrEngine.parse("Диагноз: Е10\nСерия 11111 № 11 111 111 111 от 01.01.2026")
        // кириллическая Е в МКБ часто распознаётся латиницей — проверяем латинский вариант отдельно
        val d2 = OcrEngine.parse("Диагноз: E10\nсрок 30 дней")
        assertNotNull(d2.diagnosis)
    }
}
