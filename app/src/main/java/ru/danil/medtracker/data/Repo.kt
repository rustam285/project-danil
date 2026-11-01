package ru.danil.medtracker.data

import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.calc.SupplyOutcome
import ru.danil.medtracker.calc.SupplyResult
import ru.danil.medtracker.data.db.AppDatabase
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Issue
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/** UI-модель строки назначения (карточка пациента, список, срочное). */
data class PrescriptionView(
    val prescription: Prescription,
    val drug: Drug,
    val norms: List<Norm>,
    val issues: List<Issue>,
    val supply: SupplyResult,
    val noCalc: Boolean,
    /** Остаток на сегодня в штуках (точная дробь, п. 4.2.7) */
    val remainder: ru.danil.medtracker.calc.Rational,
) {
    val coveredUntil: LocalDate? get() = supply.coveredUntil()
    val isUnlimited: Boolean get() = supply.outcome is SupplyOutcome.Unlimited
}

/** UI-модель пациента с агрегатами для списка (п. 5.1). */
data class PatientListItem(
    val patient: Patient,
    /** Ближайшая дата «обеспечен до» по активным назначениям с расчётом срока */
    val nearestUntil: LocalDate?,
    val unlimitedOnly: Boolean,
    /** активных назначений нет вообще */
    val noPrescriptions: Boolean = false,
    /** назначения есть, выдач ещё не было (замечание №5) */
    val noIssues: Boolean = false,
)

/** Строка вкладки «Срочное» (п. 5.2). */
enum class UrgentKind { ENDED, WAITING, LOW, RX }

data class UrgentItem(
    val patient: Patient,
    val drug: Drug?,
    val prescription: Prescription?,
    val rx: Rx?,
    val date: LocalDate?,
    val kind: UrgentKind,
)

class Repo(
    private val db: AppDatabase,
    val settings: SettingsStore,
) {
    val patientDao = db.patientDao()
    val drugDao = db.drugDao()
    val prescriptionDao = db.prescriptionDao()
    val normDao = db.normDao()
    val issueDao = db.issueDao()
    val rxDao = db.rxDao()
    val tombstoneDao = db.tombstoneDao()
    val coreDao = db.coreDao()
    val analysisTypeDao = db.analysisTypeDao()
    val analysisRecordDao = db.analysisRecordDao()

    fun now() = System.currentTimeMillis()

    // ── Выдачи ───────────────────────────────────────────────────────────────

    suspend fun saveIssue(
        existing: Issue?,
        prescriptionId: Long,
        date: Long,
        packs: Int,
        pieces: Int,
        note: String?,
        rxId: Long?,
        piecesPerPack: Int,
    ): Issue {
        val total = packs * piecesPerPack + pieces
        require(total > 0) { "Количество должно быть больше нуля" }
        val t = now()
        val issue = (existing ?: Issue(
            prescriptionId = prescriptionId,
            date = date,
            piecesPerPackAtIssue = piecesPerPack,
            totalPieces = total,
        )).copy(
            prescriptionId = prescriptionId,
            date = date,
            enteredPacks = packs,
            enteredPieces = pieces,
            piecesPerPackAtIssue = piecesPerPack,
            totalPieces = total,
            note = note?.takeIf { it.isNotBlank() },
            rxId = rxId,
            updatedAt = t,
        )
        return if (existing == null) {
            issue.copy(createdAt = t).let { saved -> saved.copy(id = issueDao.insert(saved)) }
        } else {
            issueDao.update(issue)
            issue
        }.also {
            // выдача состоялась — «В ожидании поступления» больше не актуально
            clearAwaitingRestock(prescriptionId, t)
        }
    }

    suspend fun saveInitialBalance(
        prescriptionId: Long,
        date: Long,
        shownUntil: Long?,
        remainderPieces: Int?,
        norms: List<Norm>,
        drug: Drug?,
    ): Issue {
        val t = now()
        val total = remainderPieces ?: run {
            requireNotNull(shownUntil) { "Укажите дату «обеспечен до» или остаток" }
            val need = ru.danil.medtracker.calc.SupplyEngine.piecesNeeded(
                Dates.fromEpochDay(date).plusDays(1),
                Dates.fromEpochDay(shownUntil),
                CalcService.normRecs(norms),
                drug?.mePerPiece(),
            )
            need.toInt()
        }
        require(total >= 0) { "Остаток не может быть отрицательным" }
        val issue = Issue(
            prescriptionId = prescriptionId,
            date = date,
            enteredPacks = 0,
            enteredPieces = total,
            piecesPerPackAtIssue = drug?.piecesPerPack ?: 1,
            totalPieces = total,
            isInitialBalance = true,
            shownUntil = shownUntil,
            createdAt = t,
            updatedAt = t,
        )
        return issue.copy(id = issueDao.insert(issue)).also {
            clearAwaitingRestock(prescriptionId, t)
        }
    }

    /** «В ожидании поступления»: ручное включение/снятие статуса назначения. */
    suspend fun setAwaitingRestock(prescriptionId: Long, awaiting: Boolean) {
        val p = prescriptionDao.byId(prescriptionId) ?: return
        if (p.awaitingRestock != awaiting) {
            prescriptionDao.update(p.copy(awaitingRestock = awaiting, updatedAt = now()))
        }
    }

    private suspend fun clearAwaitingRestock(prescriptionId: Long, t: Long) {
        val p = prescriptionDao.byId(prescriptionId) ?: return
        if (p.awaitingRestock) {
            prescriptionDao.update(p.copy(awaitingRestock = false, updatedAt = t))
        }
    }

    // ── Препараты (п. 5.9) ───────────────────────────────────────────────────

    /** Дубликат названия после нормализации (регистр, пробелы)? */
    suspend fun findDuplicateDrug(name: String, excludeId: Long? = null): Drug? {
        val norm = { s: String -> s.lowercase().replace(Regex("\\s+"), " ").trim() }
        return drugDao.all().firstOrNull {
            norm(it.name) == norm(name) && it.id != (excludeId ?: -1L)
        }
    }

    suspend fun deleteOrArchiveDrug(id: Long) {
        val hasPrescriptions = prescriptionDao.countForDrug(id) > 0
        val drug = drugDao.byId(id) ?: return
        if (hasPrescriptions) {
            drugDao.update(drug.copy(archived = true, updatedAt = now()))
        } else {
            drugDao.delete(id)
            tombstoneDao.insert(ru.danil.medtracker.data.db.Tombstone(entityType = "drug", entityId = id, deletedAt = now()))
        }
    }

    // ── Копирование назначений (п. 5.9) ──────────────────────────────────────

    /** Копирует назначения пациента-источника: препараты общие, validFrom = сегодня, выдачи не копируются. */
    suspend fun copyPrescriptions(sourcePatientId: Long, targetPatientId: Long) {
        val t = now()
        val today = Dates.toEpochDay(Dates.today())
        val sources = prescriptionDao.all().filter { it.patientId == sourcePatientId && it.status == "ACTIVE" }
        for (src in sources) {
            val newP = Prescription(
                patientId = targetPatientId,
                drugId = src.drugId,
                isReserve = src.isReserve,
                createdAt = t,
                updatedAt = t,
            )
            val newId = prescriptionDao.insert(newP)
            val norms = normDao.forPrescription(src.id)
            val copied = norms.map { n ->
                if (norms.size == 1) {
                    // единственную норму стартуем сегодня; историю версий не тянем
                    n.copy(id = 0, prescriptionId = newId, validFrom = today, createdAt = t, updatedAt = t)
                } else {
                    n.copy(id = 0, prescriptionId = newId, createdAt = t, updatedAt = t)
                }
            }
            if (copied.isEmpty()) {
                // назначение без норм не копируем — норма обязательна
                prescriptionDao.delete(newId)
            } else {
                normDao.insertAll(copied)
            }
        }
    }

    // ── Агрегаты ─────────────────────────────────────────────────────────────

    /** Все активные назначения с расчётными данными (для списков/срочного/документов). */
    suspend fun allPrescriptionViews(): List<PrescriptionView> {
        val prescriptions = prescriptionDao.allActive()
        if (prescriptions.isEmpty()) return emptyList()
        val drugs = drugDao.all().associateBy { it.id }
        val norms = normDao.forPrescriptions(prescriptions.map { it.id }).groupBy { it.prescriptionId }
        val issues = issueDao.forPrescriptions(prescriptions.map { it.id }).groupBy { it.prescriptionId }
        val today = Dates.today()
        return prescriptions.mapNotNull { p ->
            val drug = drugs[p.drugId] ?: return@mapNotNull null
            val ns = norms[p.id].orEmpty().sortedBy { it.validFrom }
            val issuesList = issues[p.id].orEmpty()
            val noCalc = CalcService.isNoCalc(ns, today)
            val supply = if (noCalc) {
                SupplyResult(SupplyOutcome.NoEvents)
            } else {
                CalcService.supplyFor(issuesList, ns, drug)
            }
            val remainder = if (noCalc) ru.danil.medtracker.calc.Rational.ZERO else
                ru.danil.medtracker.calc.SupplyEngine.remainderAt(
                    today, CalcService.stockEvents(issuesList), CalcService.normRecs(ns), drug.mePerPiece(),
                )
            PrescriptionView(p, drug, ns, issuesList, supply, noCalc, remainder)
        }
    }
}
