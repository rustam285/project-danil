package ru.danil.medtracker.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Patient

/** Сопоставление OCR-строк с базой (скан рецепта → пациент/препарат). */
class TextMatchTest {

    @Test
    fun normalizeLowercasesAndStripsPunctuation() {
        assertEquals("тест полоски ван тач", TextMatch.normalize("Тест-полоски,  ВАН!!   тач."))
        // ё приводится к е
        assertEquals("сергеева", TextMatch.normalize("СергЕёва"))
    }

    @Test
    fun similarityExactAndContains() {
        assertEquals(100, TextMatch.similarity("Тестов Тест Тестович", "тестов тест тестович"))
        // фамилия против полного ФИО — считается вхождением
        assertTrue(TextMatch.similarity("Тестов", "Тестов Тест Тестович") >= 80)
        assertEquals(0, TextMatch.similarity("Тестов", "Иванов Иван Иванович"))
    }

    @Test
    fun similarityPartialDrugName() {
        val score = TextMatch.similarity("Тест-полоски Ван Тач Селект Плюс", "Тест-полоски Ван Тач Селект")
        assertTrue("ожидали высокий балл, получили $score", score >= 70)
    }

    @Test
    fun bestMatchesFindsPatientByFio() {
        val patients = listOf(
            Patient(id = 1, fio = "Иванов Иван Иванович", birthDate = 0, address = ""),
            Patient(id = 2, fio = "Тестов Тест Тестович", birthDate = 0, address = ""),
        )
        val best = TextMatch.bestMatches(listOf("Ф.И.О. пациента: Тестов Тест Тестович"), patients, nameOf = { it.fio })
        assertEquals(2L, best.first().first.id)
        assertTrue(best.first().second >= TextMatch.AUTO_SELECT_MIN)
    }

    @Test
    fun bestMatchesFindsDrug() {
        val drugs = listOf(
            Drug(id = 1, name = "Тест-полоски Ван Тач Селект"),
            Drug(id = 2, name = "Глюкометр Сателлит"),
        )
        val best = TextMatch.bestMatches(listOf("РЕЦЕПТ", "Тест-полоски Ван Тач Селект Плюс"), drugs, nameOf = { it.name })
        assertEquals(1L, best.first().first.id)
    }

    /** Ф4.2: подбор работает и по устройствам (вид позиции не влияет на сопоставление). */
    @Test
    fun bestMatchesFindsDevice() {
        val devices = listOf(
            Drug(id = 5, name = "Глюкометр Контур ТС", kind = ru.danil.medtracker.data.db.DrugKind.DEVICE),
            Drug(id = 6, name = "Тест-полоски Контур Плюс", kind = ru.danil.medtracker.data.db.DrugKind.DEVICE),
        )
        val best = TextMatch.bestMatches(listOf("ГЛЮКОМЕТР КОНТУР ТС №1"), devices, nameOf = { it.name })
        assertEquals(5L, best.first().first.id)
        assertTrue(best.first().first.isDevice)
    }

    /** Ф4.2: при нескольких кандидатах — список для ручного выбора, а не пустой результат. */
    @Test
    fun ambiguousCandidates_returnSeveral() {
        val items = listOf(
            Drug(id = 1, name = "Тест-полоски Ван Тач"),
            Drug(id = 2, name = "Тест-полоски Ван Тач Селект"),
            Drug(id = 3, name = "Тест-полоски Ван Тач Плюс"),
        )
        val best = TextMatch.bestMatches(listOf("Тест-полоски Ван Тач Селект Плюс"), items, nameOf = { it.name })
        assertTrue("ожидали несколько кандидатов, получили ${best.size}", best.size >= 2)
        // лидер — самое полное совпадение
        assertEquals(2L, best.first().first.id)
    }

    @Test
    fun bestMatchesNoGibberish() {
        val patients = listOf(Patient(id = 1, fio = "Иванов Иван Иванович", birthDate = 0, address = ""))
        val best = TextMatch.bestMatches(listOf("!!! ??? 12345"), patients, nameOf = { it.fio })
        assertTrue(best.isEmpty() || best.first().second < TextMatch.AUTO_SELECT_MIN)
    }

    // ── Ф13.3 (ТЗ 1.4.0 §4): покрытие названия токенами всего текста рецепта ────

    /** Название целиком в тексте → 100. */
    @Test
    fun coverageFullNameInText() {
        assertEquals(100, TextMatch.coverage("выписано Глюкометр Контур Плюс №1", "Глюкометр Контур Плюс"))
    }

    /** 2 из 3 токенов → 66. */
    @Test
    fun coverageTwoOfThreeTokens() {
        assertEquals(66, TextMatch.coverage("глюкометр контур один", "Глюкометр Контур Плюс"))
    }

    /** 1 из 3 токенов → 33. */
    @Test
    fun coverageOneOfThreeTokens() {
        assertEquals(33, TextMatch.coverage("глюкометр один два", "Глюкометр Контур Плюс"))
    }

    /** Пустое название (или без значимых токенов) → 0. */
    @Test
    fun coverageEmptyName() {
        assertEquals(0, TextMatch.coverage("любой текст рецепта", ""))
        assertEquals(0, TextMatch.coverage("любой текст рецепта", "—"))
    }
}
