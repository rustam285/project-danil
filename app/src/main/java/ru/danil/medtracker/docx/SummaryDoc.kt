package ru.danil.medtracker.docx

import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.calc.Rational
import ru.danil.medtracker.calc.SupplyEngine
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.util.Dates
import java.time.LocalDate
import java.time.YearMonth

/**
 * «Сводная потребность» по всем пациентам за период (п. 8.3).
 * Требуется/Осталось/Докупить — точные дроби, округление вверх после суммирования по пациентам.
 */
object SummaryDoc {

    data class Input(
        val patients: List<Patient>,
        val prescriptions: List<Prescription>,
        val drugs: List<Drug>,
        val norms: List<Norm>,
        val issues: List<Issue>,
    )

    fun build(input: Input, period: YearMonth): ByteArray {
        val b = DocxBuilder()
        val periodStart = period.atDay(1)
        val periodEnd = period.atEndOfMonth()
        val days = period.lengthOfMonth()

        b.para("СВОДНАЯ ПОТРЕБНОСТЬ", bold = true, align = "center", size = 28, after = 40)
        b.para(
            "Период: ${Dates.formatRu(periodStart)} — ${Dates.formatRu(periodEnd)}",
            align = "center", after = 200,
        )

        val activePatients = input.patients.filter { !it.archived }.associateBy { it.id }
        val drugById = input.drugs.associateBy { it.id }
        val normsByP = input.norms.groupBy { it.prescriptionId }
        val issuesByP = input.issues.groupBy { it.prescriptionId }

        data class Row(
            val drug: Drug,
            var required: Rational = Rational.ZERO,
            var remainder: Rational = Rational.ZERO,
            val endings: MutableList<Pair<String, LocalDate>> = mutableListOf(),
        )

        val rows = sortedMapOf<String, Row>(String.CASE_INSENSITIVE_ORDER)

        // резервные схемы в «Сводной потребности» не участвуют: документ планирует закупку
        // по основным схемам (Ф2.7 ТЗ 1.2.0)
        for (p in input.prescriptions.filter { it.status == "ACTIVE" && !it.isReserve }) {
            val patient = activePatients[p.patientId] ?: continue
            val drug = drugById[p.drugId] ?: continue
            val norms = normsByP[p.id].orEmpty()
            if (CalcService.isNoCalc(norms)) continue // п. 4.4: не участвует
            // Норма, действующая на начало периода
            val norm = norms.filter { it.validFrom <= Dates.toEpochDay(periodStart) }
                .maxByOrNull { it.validFrom } ?: continue
            val row = rows.getOrPut(drug.name) { Row(drug) }

            // Требуется на период (п. 8.3)
            val recs = CalcService.normRecs(listOf(norm))
            val normRec = recs.first()
            row.required += if (normRec.meAmount != null && !normRec.hasPiecePart() && drug.mePerPiece() != null) {
                SupplyEngine.meRequirementForPeriod(normRec.meAmount!!, drug.mePerPiece()!!, days)
            } else {
                SupplyEngine.requirementForPeriod(normRec, days)
            }

            // Осталось на начало периода (п. 4.2.7)
            val issuesList = issuesByP[p.id].orEmpty()
            row.remainder += SupplyEngine.remainderAt(
                periodStart, CalcService.stockEvents(issuesList), CalcService.normRecs(norms), drug.mePerPiece(),
            )

            // У кого заканчивается и когда: до конца периода
            val supply = CalcService.supplyFor(issuesList, norms, drug)
            val until = supply.coveredUntil()
            if (supply.outcome is ru.danil.medtracker.calc.SupplyOutcome.Unlimited || until == null) continue
            if (!until.isAfter(periodEnd)) {
                val parts = patient.fio.split(" ")
                val short = parts.getOrNull(0)?.let { sn ->
                    sn + (parts.getOrNull(1)?.take(1)?.let { " ${it}." } ?: "")
                } ?: patient.fio
                row.endings.add(short to until)
            }
        }

        if (rows.isEmpty()) {
            b.para("Активных назначений нет.")
            return b.build()
        }

        val header = listOf(
            listOf(DocxBuilder.Run("№", true)),
            listOf(DocxBuilder.Run("Препарат и форма выпуска", true)),
            listOf(DocxBuilder.Run("Требуется на период", true)),
            listOf(DocxBuilder.Run("Осталось на начало периода", true)),
            listOf(DocxBuilder.Run("Докупить", true)),
            listOf(DocxBuilder.Run("У кого заканчивается и когда", true)),
        )
        val tableRows = mutableListOf(header)
        rows.values.forEachIndexed { idx, row ->
            val drug = row.drug
            val pack = drug.piecesPerPack
            fun fmtPieces(r: Rational): String {
                val whole = r.truncate().toLong()
                return if (pack > 1) "$whole шт (${r.div(Rational.of(pack.toLong())).truncate()} уп)" else "$whole шт"
            }
            val requiredCeil = ceilR(row.required)
            val remainderFloor = row.remainder.truncate().toLong()
            val buy = maxOf(0L, requiredCeil - remainderFloor)
            fun fmtBuy(): String {
                val v = buy
                return if (pack > 1) "$v шт (${v / pack} уп)" else "$v шт"
            }
            tableRows.add(
                listOf(
                    listOf(DocxBuilder.Run("${idx + 1}")),
                    listOf(DocxBuilder.Run(drug.name + ", " + CalcService.drugForm(drug))),
                    listOf(DocxBuilder.Run(fmtPieces(row.required))),
                    listOf(DocxBuilder.Run("$remainderFloor шт")),
                    listOf(DocxBuilder.Run(fmtBuy())),
                    listOf(DocxBuilder.Run(
                        row.endings.sortedBy { it.second }
                            .joinToString("; ") { "${it.first} — ${Dates.formatRu(it.second)}" },
                    )),
                ),
            )
        }
        b.table(DocxBuilder.Table(tableRows, header = true))
        b.para("Документ сформирован ${Dates.formatRu(LocalDate.now())}.")
        return b.build()
    }

    /** Округление точной дроби вверх до целой штуки. */
    private fun ceilR(r: Rational): Long {
        val whole = r.truncate()
        return if (!r.minus(Rational.of(whole, java.math.BigInteger.ONE)).isZero()) whole.toLong() + 1 else whole.toLong()
    }
}
