package ru.danil.medtracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.data.db.Rx
import java.time.LocalDate

/**
 * Чистая часть дубль-проверки по серии/номеру (Ф13.5 §6.2, ТЗ 1.4.0 §11).
 * Все данные вымышленные.
 */
class RxDupCheckTest {

    private fun rx(id: Long = 1, patientId: Long = 1, series: String = "75464", number: String = "25222011880") =
        Rx(id = id, patientId = patientId, series = series, number = number)

    private fun dup(
        rx: Rx,
        fio: String = "Тестов Тест Тестович",
        hasIssues: Boolean = false,
        firstIssueDate: LocalDate? = null,
    ) = RxDupCheck.Dup(rx, fio, hasIssues, firstIssueDate)

    /** Нормализация: серия — trim, номер — без пробелов и дефисов. */
    @Test
    fun normalization() {
        assertEquals("75464", RxDupCheck.normSeries(" 75464 "))
        assertEquals("25222011880", RxDupCheck.normNumber("25 222 011-880"))
        assertEquals("25222011880", RxDupCheck.normNumber(" 25222011880 "))
        assertEquals("", RxDupCheck.normNumber(" - "))
    }

    /** Серия или номер пусты → проверка отключена (Skip, не «нет дубля»). */
    @Test
    fun blankSeriesOrNumber_skipsCheck() {
        assertTrue(RxDupCheck.check(1, "", "25222011880", emptyList()) is RxDupCheck.Result.Skip)
        assertTrue(RxDupCheck.check(1, "   ", "25222011880", emptyList()) is RxDupCheck.Result.Skip)
        assertTrue(RxDupCheck.check(1, "75464", "", emptyList()) is RxDupCheck.Result.Skip)
        // номер из одних пробелов/дефисов после нормализации пуст
        assertTrue(RxDupCheck.check(1, "75464", " - ", emptyList()) is RxDupCheck.Result.Skip)
        val skip = RxDupCheck.check(1, "", "", emptyList()) as RxDupCheck.Result.Skip
        assertTrue(skip.warning.contains("не удастся распознать"))
    }

    /** Дублей нет → None (проверка выполнена, рецепт сохранится как новый). */
    @Test
    fun noDups_none() {
        assertEquals(RxDupCheck.Result.None, RxDupCheck.check(1, "75464", "25222011880", emptyList()))
    }

    /** Дубль без выдач у того же пациента → переиспользование без предупреждений. */
    @Test
    fun savedWithoutIssues_samePatient_reused() {
        val r = RxDupCheck.check(1, "75464", "25222011880", listOf(dup(rx(id = 7, patientId = 1))))
        assertTrue(r is RxDupCheck.Result.Found)
        r as RxDupCheck.Result.Found
        assertEquals(7L, r.reuse?.id)
        assertNull(r.otherPatientWarning)
        assertNull(r.existingIssueDate)
    }

    /** Дубль у другого пациента → предупреждение без блокировки. */
    @Test
    fun dupAtOtherPatient_warns() {
        val r = RxDupCheck.check(
            1, "75464", "25222011880",
            listOf(dup(rx(id = 7, patientId = 2), fio = "Иванов Иван Иванович")),
        ) as RxDupCheck.Result.Found
        assertTrue(r.otherPatientWarning!!.contains("уже сохранён у пациента Иванов Иван Иванович"))
        // выдачи нет — переиспользуется
        assertEquals(7L, r.reuse?.id)
        assertNull(r.existingIssueDate)
    }

    /** Есть выдача по бланку → блокировка: reuse=null, дата выдачи уходит в план (§5). */
    @Test
    fun dupWithIssue_blocks() {
        val r = RxDupCheck.check(
            1, "75464", "25222011880",
            listOf(dup(rx(id = 7), hasIssues = true, firstIssueDate = LocalDate.of(2026, 10, 30))),
        ) as RxDupCheck.Result.Found
        assertNull(r.reuse)
        assertEquals(LocalDate.of(2026, 10, 30), r.existingIssueDate)
    }

    /** Несколько дублей: блокировка по самой ранней выдаче; без выдач — самый свежий на переиспользование. */
    @Test
    fun multipleDups_earliestIssueAndFreshestReuse() {
        val blocked = RxDupCheck.check(
            1, "75464", "25222011880",
            listOf(
                dup(rx(id = 7), hasIssues = true, firstIssueDate = LocalDate.of(2026, 10, 31)),
                dup(rx(id = 9), hasIssues = true, firstIssueDate = LocalDate.of(2026, 10, 30)),
            ),
        ) as RxDupCheck.Result.Found
        assertEquals(LocalDate.of(2026, 10, 30), blocked.existingIssueDate)

        val reuse = RxDupCheck.check(
            1, "75464", "25222011880",
            listOf(dup(rx(id = 7)), dup(rx(id = 9))),
        ) as RxDupCheck.Result.Found
        assertEquals(9L, reuse.reuse?.id)
    }
}
