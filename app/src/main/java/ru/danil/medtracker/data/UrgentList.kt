package ru.danil.medtracker.data

import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import java.time.LocalDate

/**
 * Построение вкладки «Срочное» (п. 5.2) — чистая функция над снимком БД.
 * Резервные назначения (isReserve, Ф2.3) исключаются полностью:
 * из списка закончившихся, из «В ожидании поступления» и из «осталось мало».
 */
data class UrgentState(
    val ended: List<UrgentItem> = emptyList(),
    val waiting: List<UrgentItem> = emptyList(),
    val low: List<UrgentItem> = emptyList(),
    val rx: List<UrgentItem> = emptyList(),
)

object UrgentList {

    fun build(
        patients: List<Patient>,
        prescriptions: List<Prescription>,
        norms: List<Norm>,
        issues: List<Issue>,
        drugs: List<Drug>,
        rxs: List<Rx>,
        settings: AppSettings,
        today: LocalDate,
    ): UrgentState {
        val patientById = patients.associateBy { it.id }
        val drugById = drugs.associateBy { it.id }
        val prescriptionById = prescriptions.associateBy { it.id }
        val normsByP = norms.groupBy { it.prescriptionId }
        val issuesByP = issues.groupBy { it.prescriptionId }
        val drugThreshold = settings.drugThresholdDays
        val rxThreshold = settings.rxThresholdDays

        val ended = mutableListOf<UrgentItem>()
        val waiting = mutableListOf<UrgentItem>()
        val low = mutableListOf<UrgentItem>()

        for (p in prescriptions) {
            // резервные схемы скрыты из «Срочного» полностью (Ф2.3)
            if (p.isReserve) continue
            val patient = patientById[p.patientId] ?: continue // архивные пациенты не участвуют
            val drug = drugById[p.drugId] ?: continue
            val ns = normsByP[p.id].orEmpty()
            // «без расчёта срока» не попадают (п. 5.2)
            if (CalcService.isNoCalc(ns, today)) continue
            val supply = CalcService.supplyFor(issuesByP[p.id].orEmpty(), ns, drug)
            val until = supply.coveredUntil() ?: continue // «запас > 5 лет» не попадает
            when {
                until.isBefore(today) -> {
                    // закончилось и врач пометил «нет на складе» — своя секция;
                    // если запас восстановился, флаг игнорируем (снимется при выдаче)
                    val item = UrgentItem(patient, drug, p, null, until, UrgentKind.ENDED)
                    if (p.awaitingRestock) waiting.add(item.copy(kind = UrgentKind.WAITING)) else ended.add(item)
                }
                !until.isAfter(today.plusDays(drugThreshold.toLong())) ->
                    low.add(UrgentItem(patient, drug, p, null, until, UrgentKind.LOW))
                else -> {}
            }
        }

        val rxItems = rxs.mapNotNull { rx ->
            val patient = patientById[rx.patientId] ?: return@mapNotNull null
            val until = CalcService.rxValidUntil(rx.issueDate, rx.validDays, rx.validUntil)
                ?: return@mapNotNull null
            if (!until.isAfter(today.plusDays(rxThreshold.toLong()))) {
                val drug = rx.prescriptionId
                    ?.let { prescriptionById[it] }
                    ?.let { drugById[it.drugId] }
                UrgentItem(patient, drug, null, rx, until, UrgentKind.RX)
            } else null
        }.sortedBy { it.date }

        return UrgentState(
            ended = ended.sortedBy { it.date },
            waiting = waiting.sortedBy { it.date },
            low = low.sortedBy { it.date },
            rx = rxItems,
        )
    }
}
