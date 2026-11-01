package ru.danil.medtracker.docx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.data.UrgentItem
import ru.danil.medtracker.data.UrgentKind
import ru.danil.medtracker.data.UrgentState
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.util.Dates
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Дымовой тест документа «Срочная выдача» (Ф7, ТЗ §10 v1.3.0):
 * ZIP открывается, таблица на месте, дата заголовка = дата формирования.
 * Данные вымышленные.
 */
class UrgentDocTest {

    private val today: LocalDate = LocalDate.now()

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

    private fun patient() = Patient(id = 1, fio = "Иванов Иван Иванович", birthDate = 0, address = "г. Тест")
    private fun drug(id: Long, name: String) = Drug(id = id, name = name, unit = "штука", piecesPerPack = 1)

    private fun item(
        kind: UrgentKind,
        date: LocalDate,
        drug: Drug? = drug(1, "Тест-препарат"),
        prescription: Prescription? = Prescription(id = 1, patientId = 1, drugId = 1),
        rx: Rx? = null,
    ) = UrgentItem(patient = patient(), drug = drug, prescription = prescription, rx = rx, date = date, kind = kind)

    @Test
    fun zipContainsTable_andHeaderDateIsToday() {
        val state = UrgentState(
            ended = listOf(item(UrgentKind.ENDED, today.minusDays(3))),
            waiting = listOf(item(UrgentKind.WAITING, today.minusDays(2))),
            low = listOf(item(UrgentKind.LOW, today.plusDays(3))),
            rx = listOf(
                item(
                    UrgentKind.RX, today.plusDays(5),
                    drug = null, prescription = null,
                    rx = Rx(patientId = 1, series = "75464", number = "26222016100"),
                ),
            ),
        )
        val xml = documentXml(UrgentDoc.build(state, today))
        // заголовок с текущей датой (Ф7.1)
        assertTrue(xml.contains("СРОЧНАЯ ВЫДАЧА — ${Dates.formatRu(today)}"))
        // таблица и все секции как на экране
        assertTrue(xml.contains("<w:tbl>"))
        assertTrue(xml.contains("Иванов Иван Иванович"))
        assertTrue(xml.contains("закончилось"))
        assertTrue(xml.contains("ожидается поставка"))
        assertTrue(xml.contains("осталось мало"))
        assertTrue(xml.contains("истекает рецепт"))
        // рецепт без позиции — фолбэк с серией/номером
        assertTrue(xml.contains("75464"))
    }

    @Test
    fun expiredRx_markedAsExpired() {
        val state = UrgentState(
            rx = listOf(
                item(
                    UrgentKind.RX, today.minusDays(1),
                    prescription = null, rx = Rx(patientId = 1),
                ),
            ),
        )
        val xml = documentXml(UrgentDoc.build(state, today))
        assertTrue(xml.contains("рецепт истёк"))
    }

    @Test
    fun emptyState_noTable() {
        val xml = documentXml(UrgentDoc.build(UrgentState(), today))
        assertTrue(xml.contains("Срочных позиций нет"))
        assertFalse(xml.contains("<w:tbl>"))
    }
}
