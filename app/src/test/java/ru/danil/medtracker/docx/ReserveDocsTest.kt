package ru.danil.medtracker.docx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.danil.medtracker.calc.Rational
import ru.danil.medtracker.calc.SupplyOutcome
import ru.danil.medtracker.calc.SupplyResult
import ru.danil.medtracker.data.PrescriptionView
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.util.Dates
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipInputStream

/**
 * Пометки/исключения резервных назначений в документах (Ф2.7):
 * «Сводная потребность» — без резерва; «План ДН» — с пометкой «(резерв)».
 * Данные вымышленные (Приложение Б ТЗ).
 */
class ReserveDocsTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 1)

    private fun documentXml(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    return String(zip.readBytes(), Charsets.UTF_8)
                }
                e = zip.nextEntry
            }
        }
        error("word/document.xml не найден — docx повреждён")
    }

    private fun patient() = Patient(id = 1, fio = "Иванов Иван Иванович", birthDate = 0, address = "г. Тест")

    private fun norm(pid: Long) = Norm(
        prescriptionId = pid,
        validFrom = Dates.toEpochDay(today.minusYears(1)),
        pieceKind = "PER_DAY",
        pieceAmount = 1,
    )

    /** Назначение «без расчёта срока» — для «Плана ДН» движок не нужен. */
    private fun noCalcView(pid: Long, drug: Drug, reserve: Boolean) = PrescriptionView(
        prescription = Prescription(id = pid, patientId = 1, drugId = drug.id, isReserve = reserve),
        drug = drug,
        norms = listOf(norm(pid).copy(noCalc = true)),
        issues = emptyList(),
        supply = SupplyResult(SupplyOutcome.NoEvents),
        noCalc = true,
        remainder = Rational.ZERO,
    )

    @Test
    fun planDn_marksReservePrescription() {
        val bytes = PlanDnDoc.build(
            patient(),
            listOf(
                noCalcView(1, Drug(id = 1, name = "Тест-препарат"), reserve = false),
                noCalcView(2, Drug(id = 2, name = "Резервный препарат"), reserve = true),
            ),
        )
        val xml = documentXml(bytes)
        assertTrue("План ДН должен содержать пометку «(резерв)»", xml.contains("(резерв)"))
        assertTrue(xml.contains("Резервный препарат (резерв)"))
        assertFalse("Обычное назначение помечаться не должно", xml.contains("Тест-препарат (резерв)"))
    }

    @Test
    fun summary_excludesReservePrescription() {
        val input = SummaryDoc.Input(
            patients = listOf(patient()),
            prescriptions = listOf(
                Prescription(id = 1, patientId = 1, drugId = 1),
                Prescription(id = 2, patientId = 1, drugId = 2, isReserve = true),
            ),
            drugs = listOf(
                Drug(id = 1, name = "Тест-препарат", unit = "штука", piecesPerPack = 1),
                Drug(id = 2, name = "Резервный препарат", unit = "штука", piecesPerPack = 1),
            ),
            norms = listOf(norm(1), norm(2)),
            issues = emptyList(),
        )
        val xml = documentXml(SummaryDoc.build(input, YearMonth.of(2026, 12)))
        assertTrue("Обычный препарат должен присутствовать", xml.contains("Тест-препарат"))
        assertFalse("Резервный препарат не должен попадать в «Сводную потребность»", xml.contains("Резервный препарат"))
    }
}
