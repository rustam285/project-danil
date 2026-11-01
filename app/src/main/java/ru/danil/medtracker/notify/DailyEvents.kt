package ru.danil.medtracker.notify

import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.data.AppSettings
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/**
 * Чистая логика ежедневной сводки (п. 7.2–7.3) — без Android, покрыта юнит-тестами.
 * Резервные назначения (isReserve, Ф2.5) в сводку не попадают.
 */
object DailyEvents {

    data class Events(
        val lowStock: List<String>,
        val rxBelow: List<String>,
        /** Просроченные рецепты (Ф12.1): срок = сегодня — ещё действителен, вчера — просрочен. */
        val expiredRxCount: Int = 0,
    )

    fun compute(
        patients: List<Patient>,
        prescriptions: List<Prescription>,
        drugs: List<Drug>,
        norms: List<Norm>,
        issues: List<Issue>,
        rxs: List<Rx>,
        settings: AppSettings,
        today: LocalDate,
    ): Events {
        val patientById = patients.filter { !it.archived }.associateBy { it.id }
        val drugById = drugs.associateBy { it.id }
        val normsByP = norms.groupBy { it.prescriptionId }
        val issuesByP = issues.groupBy { it.prescriptionId }
        val low = mutableListOf<String>()
        val threshold = settings.drugThresholdDays
        for (p in prescriptions.filter { it.status == "ACTIVE" }) {
            if (p.isReserve) continue // резервные не уведомляются (Ф2.5)
            val patient = patientById[p.patientId] ?: continue
            val drug = drugById[p.drugId] ?: continue
            val ns = normsByP[p.id].orEmpty()
            if (CalcService.isNoCalc(ns, today)) continue
            val supply = CalcService.supplyFor(issuesByP[p.id].orEmpty(), ns, drug)
            val until = supply.coveredUntil() ?: continue
            if (!until.isAfter(today.plusDays(threshold.toLong()))) {
                low.add("${patient.fio} — ${drug.name}, обеспечен до ${Dates.formatRu(until)}")
            }
        }
        val rxThreshold = settings.rxThresholdDays
        val rxLow = rxs.filter { it.status == "ISSUED" }.mapNotNull { rx ->
            val patient = patientById[rx.patientId] ?: return@mapNotNull null
            val until = CalcService.rxValidUntil(rx.issueDate, rx.validDays, rx.validUntil)
                ?: return@mapNotNull null
            if (!until.isAfter(today.plusDays(rxThreshold.toLong()))) {
                "Пора выписать новый рецепт: ${patient.fio}"
            } else null
        }
        // Просроченные рецепты (Ф12.1): strictly before today; резервные назначения
        // в подсчёт не входят (Ф12.2)
        val prescriptionById = prescriptions.associateBy { it.id }
        val expiredRx = rxs.filter { it.status == "ISSUED" }.count { rx ->
            val until = CalcService.rxValidUntil(rx.issueDate, rx.validDays, rx.validUntil)
                ?: return@count false
            val isReserve = rx.prescriptionId?.let { prescriptionById[it]?.isReserve } == true
            until.isBefore(today) && !isReserve
        }
        return Events(lowStock = low, rxBelow = rxLow, expiredRxCount = expiredRx)
    }

    /**
     * Строка напоминания о давности резервной копии для сводки (Ф8.1):
     * копий не было вовсе — «ещё не создавалась»; последняя старше порога —
     * «последняя ДД.ММ.ГГГГ — пора сделать новую»; порог 0 = не напоминать.
     * Чистая функция, покрыта тестом.
     */
    fun backupReminderLine(lastBackupAt: Long, thresholdDays: Int, now: Long): String? {
        if (thresholdDays <= 0) return null
        if (lastBackupAt <= 0) return "Резервная копия: ещё не создавалась"
        val days = (now - lastBackupAt) / DAY_MILLIS
        if (days < thresholdDays) return null
        val date = java.time.Instant.ofEpochMilli(lastBackupAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return "Резервная копия: последняя ${Dates.formatRu(date)} — пора сделать новую"
    }

    private const val DAY_MILLIS = 24L * 3600 * 1000
}
