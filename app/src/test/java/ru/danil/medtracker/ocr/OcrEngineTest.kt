package ru.danil.medtracker.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("01.03.2015", d.birthDate)
        assertEquals("Тестов Тест Тестович", d.fio)
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
        // кириллическая Е в МКБ — двойник латинской E, приводится автоматически
        assertEquals("E10", d.diagnosis)
        val d2 = OcrEngine.parse("Диагноз: E10\nсрок 30 дней")
        assertNotNull(d2.diagnosis)
    }

    /** Дата рождения в родительном падеже («Родившегося 01.03.2015»). */
    @Test
    fun parseBirthDateGenitive() {
        val d = OcrEngine.parse("Тестова Теста Тестовича, родившегося 01.03.2015\nсрок 30 дней")
        assertEquals("01.03.2015", d.birthDate)
    }

    /** Серия/номер в строке без слова «Серия» — запасный шаблон в середине текста. */
    @Test
    fun parseSeriesMidText() {
        val d = OcrEngine.parse("Какой-то header\n75464 №25222011880\nпациент\nсрок 60 дней")
        assertEquals("75464", d.series)
        assertEquals("25222011880", d.number)
    }

    /** Дата выдачи не путается с датой рождения при обратном порядке в тексте. */
    @Test
    fun parseIssueDateNotBirthDate() {
        val d = OcrEngine.parse("Дата рождения: 01.03.2015\nвыдан 15.11.2026\nсрок 30 дней")
        assertEquals("15.11.2026", d.issueDate)
        assertEquals("01.03.2015", d.birthDate)
    }

    /** Оценка читаемости: мусор — низкий балл, русский текст — высокий. */
    @Test
    fun scoreTextSeparatesGarbageFromText() {
        org.junit.Assert.assertTrue(OcrEngine.scoreText("!!! ??? @@@ (())") < OcrEngine.MIN_TEXT_SCORE)
        org.junit.Assert.assertTrue(OcrEngine.scoreText("РЕЦЕПТ Серия 75464 пациент Тестов") >= OcrEngine.MIN_TEXT_SCORE)
    }

    // ── Ф13.2 (ТЗ 1.4.0 §3): поля для «Сохранить и выдать» ──────────────────────

    /** «Количество единиц: 5»; мусор ≤6 симв. между меткой и числом допускается. */
    @Test
    fun parseUnitsCount() {
        assertEquals(5, OcrEngine.parse("Количество единиц: 5").unitsCount)
        assertEquals(3, OcrEngine.parse("Количество единиц : () 3").unitsCount)
        // больше 6 символов мусора — число к метке уже не относится
        assertNull(OcrEngine.parse("Количество единиц: слишком далеко 5").unitsCount)
    }

    /** «№» из строки Dtd.: та же или следующая строка; варианты D.t.d. / N. */
    @Test
    fun parsePackSize() {
        assertEquals(50, OcrEngine.parse("Дозировка\nDtd.\n№ 50").packSize)
        assertEquals(30, OcrEngine.parse("Dtd. 2 мг №30").packSize)
        assertEquals(10, OcrEngine.parse("D.t.d. N 10").packSize)
        assertNull(OcrEngine.parse("Дозировка Dtd. без номера").packSize)
    }

    /** «Выписано Вр.» (встречается на реальных бланках) и «Выписано Rp.: …». */
    @Test
    fun parseRpName() {
        assertEquals(
            "Тест-полоски Ван Тач Селект",
            OcrEngine.parse("Выписано Вр.\nТест-полоски Ван Тач Селект").rpName,
        )
        assertEquals("Тестулин 100 ЕД/мл", OcrEngine.parse("Выписано Rp.: Тестулин 100 ЕД/мл").rpName)
    }

    /** «Форма выпуска» — значение на следующей строке; Signa — на той же. */
    @Test
    fun parseFormNameAndSigna() {
        assertEquals("Таблетки 100 мг", OcrEngine.parse("Форма выпуска\nТаблетки 100 мг").formName)
        assertEquals("4 таб в сутки", OcrEngine.parse("Signa: 4 таб в сутки").signa)
    }

    /** Штамп отсроченного обеспечения (детект «ОТСРОЧ» без учёта регистра). */
    @Test
    fun parseDeferred() {
        org.junit.Assert.assertTrue(OcrEngine.parse("РЕЦЕПТ ПОСТАВЛЕН НА ОТСРОЧЕННОЕ ОБЕСПЕЧЕНИЕ").deferred)
        org.junit.Assert.assertFalse(OcrEngine.parse("Количество единиц: 5").deferred)
    }

    /** Все новые поля с одного бланка одновременно. */
    @Test
    fun parseFullFormNewFields() {
        val text = """
            РЕЦЕПТ
            Серия 75464 № 25 222 011 880 от 29.07.2026
            Ф.И.О. пациента: Тестов Тест Тестович
            Выписано Вр.: Тест-полоски Ван Тач Селект
            Количество единиц: 5
            Dtd. №50
            Signa: по 1 полоске в сутки
            РЕЦЕПТ ПОСТАВЛЕН НА ОТСРОЧЕННОЕ ОБЕСПЕЧЕНИЕ
        """.trimIndent()
        val d = OcrEngine.parse(text)
        assertEquals(5, d.unitsCount)
        assertEquals(50, d.packSize)
        assertEquals("по 1 полоске в сутки", d.signa)
        assertEquals("Тест-полоски Ван Тач Селект", d.rpName)
        assertTrue(d.deferred)
        // регресс: прежние поля не пострадали
        assertEquals("75464", d.series)
        assertEquals("25222011880", d.number)
    }
}
