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

    @Test
    fun bestMatchesNoGibberish() {
        val patients = listOf(Patient(id = 1, fio = "Иванов Иван Иванович", birthDate = 0, address = ""))
        val best = TextMatch.bestMatches(listOf("!!! ??? 12345"), patients, nameOf = { it.fio })
        assertTrue(best.isEmpty() || best.first().second < TextMatch.AUTO_SELECT_MIN)
    }
}
