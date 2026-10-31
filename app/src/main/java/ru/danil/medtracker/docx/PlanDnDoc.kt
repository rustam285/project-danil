package ru.danil.medtracker.docx

import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.calc.SupplyEngine
import ru.danil.medtracker.data.PrescriptionView
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * «План диспансерного наблюдения» по пациенту (п. 8.2).
 * Структура повторяет рабочие файлы заказчика.
 */
object PlanDnDoc {

    fun build(patient: Patient, views: List<PrescriptionView>): ByteArray {
        val b = DocxBuilder()
        val today = LocalDate.now()

        b.para("ПЛАН ДИСПАНСЕРНОГО НАБЛЮДЕНИЯ", bold = true, align = "center", size = 28, after = 40)
        patient.diagnosis?.let { b.para("Диагноз: $it", align = "center", after = 200) } ?: b.empty()

        // Шапка
        b.para("ФИО: ${patient.fio}")
        b.para("Дата рождения: ${Dates.formatRu(patient.birthDate)}")
        b.para("Адрес: ${patient.address}")
        patient.school?.let { b.para("Школа, класс: $it") }
        patient.illnessStart?.let { b.para("Начало заболевания: ${Dates.formatRu(it)}") }
        val benefits = patient.benefits()
        if (benefits.isNotEmpty()) {
            b.para(
                "Льгота, с какого числа: " + benefits.joinToString(", ") {
                    (if (it.type == "federal") "федеральная" else "региональная") + " с ${Dates.formatRu(it.since)}"
                },
            )
        }
        val rep = patient.repName
        if (rep != null) {
            val phones = listOfNotNull(patient.phone1, patient.phone2).joinToString(", ")
            b.para("Представитель: $rep${if (phones.isNotEmpty()) " $phones" else ""}")
        }
        patient.cardNo?.let { b.para("№ амбулаторной карты: $it") }
        patient.doctor?.let { b.para("Лечащий врач: $it") }
        b.empty()

        // ПОТРЕБНОСТЬ
        b.para("ПОТРЕБНОСТЬ:", bold = true, after = 120)
        if (views.isEmpty()) {
            b.para("Активных назначений нет.")
        }
        views.forEachIndexed { idx, v ->
            val forms = CalcService.pieceNounForms(v.drug)
            b.para("${idx + 1}. ${v.drug.name}, ${CalcService.drugForm(v.drug)}", bold = true, after = 40)

            // Нормы с периодами действия (п. 8.2.3)
            val normsDesc = v.norms.sortedBy { it.validFrom }.joinToString("; ") { n ->
                val period = Dates.formatRu(n.validFrom)
                "с $period — ${CalcService.normDoc(n, forms)}"
            }
            if (normsDesc.isNotBlank()) b.para(normsDesc, after = 60)

            // История выдач построчно с датами «на момент выдачи» (п. 4.6)
            val sortedIssues = v.issues.sortedWith(compareBy({ it.date }, { it.id }))
            val events = CalcService.stockEvents(sortedIssues)
            val normRecs = CalcService.normRecs(v.norms)
            for (issue in sortedIssues) {
                val qty = if (issue.isInitialBalance) {
                    "начальный остаток ${issue.totalPieces} шт" + (issue.shownUntil?.let { " (обеспечен до ${Dates.formatRu(it)})" } ?: "")
                } else {
                    val parts = mutableListOf<String>()
                    if (issue.enteredPacks > 0) parts.add("${issue.enteredPacks} уп по ${issue.piecesPerPackAtIssue} шт")
                    if (issue.enteredPieces > 0) parts.add("${issue.enteredPieces} шт")
                    parts.joinToString(" + ") + " = ${issue.totalPieces} шт"
                }
                val line = if (issue.isInitialBalance) {
                    "Остаток на ${Dates.formatRu(issue.date)} — $qty"
                } else {
                    "Выдано ${Dates.formatRu(issue.date)} — $qty"
                }
                if (v.noCalc) {
                    b.para(line)
                } else {
                    val res = SupplyEngine.supplyEndAsOf(
                        events.first { it.id == issue.id }, events, normRecs, v.drug.mePerPiece(),
                    )
                    val until = when {
                        res.outcome is ru.danil.medtracker.calc.SupplyOutcome.Unlimited -> "запас достаточен"
                        res.coveredUntil() != null -> "обеспечен до ${Dates.formatRu(res.coveredUntil()!!)}"
                        else -> ""
                    }
                    b.para(if (until.isBlank()) line else "$line — $until")
                }
            }
            if (sortedIssues.isEmpty()) b.para("выдач не было", italic = false)

            // Текущий статус
            if (!v.noCalc) {
                when {
                    v.isUnlimited -> b.para(runsStatus("Запас достаточен (более 5 лет)"), bold = true)
                    v.coveredUntil != null -> {
                        b.para(
                            DocxBuilder.Run("Обеспечен до ", true),
                            DocxBuilder.Run(Dates.formatRu(v.coveredUntil!!), true),
                        )
                    }
                    v.issues.isNotEmpty() -> b.para("Выдач не было", bold = false)
                    else -> {}
                }
            }
            b.empty()
        }

        // План наблюдения
        if (patient.notes.isNotBlank()) {
            b.para("ПЛАН НАБЛЮДЕНИЯ:", bold = true, after = 120)
            patient.notes.split("\n").forEach { b.para(it.ifBlank { " " }) }
        }
        b.para("Документ сформирован ${Dates.formatRu(today)}.")
        return b.build()
    }

    private fun runsStatus(s: String) = DocxBuilder.Run(s, true)

    private fun DocxBuilder.para(r: DocxBuilder.Run, bold: Boolean = false) {
        this.para(listOf(r))
    }

    private fun DocxBuilder.para(vararg runs: DocxBuilder.Run) {
        this.para(runs.toList())
    }
}
