package ru.danil.medtracker.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.calc.IssuePreview
import ru.danil.medtracker.calc.SupplyOutcome
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.RxDupCheck
import ru.danil.medtracker.data.RxIssuePlan
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.DrugKind
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.ocr.OcrEngine
import ru.danil.medtracker.ocr.PhotoStore
import ru.danil.medtracker.ocr.TextMatch
import ru.danil.medtracker.ui.DatePickerField
import ru.danil.medtracker.ui.EmptyState
import ru.danil.medtracker.ui.IntField
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.ui.color
import ru.danil.medtracker.util.Dates
import java.io.File
import java.time.LocalDate

/**
 * «Загрузить рецепт» (вкладка): быстрый скан бланка → проверка полей → готовый рецепт.
 * Поля предзаполняются OCR и помечаются к проверке (п. 6.4), итог подтверждает человек.
 */
class RxScanViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repo

    data class ScanState(
        val photoPath: String? = null,
        val ocrRunning: Boolean = false,
        val draft: OcrEngine.Draft? = null,
        val series: String = "",
        val number: String = "",
        val issueDate: LocalDate? = null,
        val validDays: Int? = null,
        val patient: Patient? = null,
        val prescriptions: List<Prescription> = emptyList(),
        val drugsById: Map<Long, Drug> = emptyMap(),
        /** балл совпадения назначения с распознанным препаратом */
        val prescriptionScores: Map<Long, Int> = emptyMap(),
        val prescriptionId: Long? = null,
        val report: String? = null,
        val error: String? = null,
        val saved: Boolean = false,
        val savedPrescriptionId: Long? = null,
        val savedRxId: Long? = null,
        // ── Ф13.7 «Сохранить и выдать» ──
        /** «Количество единиц» из бланка (правится вручную) */
        val units: Int? = null,
        /** «№ в упаковке» из строки Dtd (правится вручную) */
        val packSize: Int? = null,
        /** отсроченное обеспечение: штамп OCR + ручной тумблер */
        val deferred: Boolean = false,
        /** диалог сверки перед выдачей (непустой = показан) */
        val confirm: ConfirmData? = null,
        /** финальный диалог после выдачи (непустой = показан) */
        val issued: IssuedData? = null,
    )

    /** Данные диалога сверки (Ф13.7 §8): план, предпросмотр, предупреждения дубль-проверки. */
    data class ConfirmData(
        val plan: RxIssuePlan.Plan,
        val previewUntil: LocalDate?,
        val previewUnlimited: Boolean,
        val thresholdDays: Int,
        /** предупреждения вне плана: пустые серия/номер, дубль у другого пациента */
        val extraWarnings: List<String>,
        /** рецепт, готовый к issueFromRx (id != 0 — переиспользуемый дубль) */
        val rx: Rx,
        val prescriptionId: Long,
        val piecesPerPack: Int,
    )

    /** Данные финального диалога (Ф13.7 §8). */
    data class IssuedData(
        val issueId: Long,
        val rxId: Long,
        val prescriptionId: Long,
        val until: LocalDate?,
        val unlimited: Boolean,
        val noCalc: Boolean,
    )

    val state = MutableStateFlow(ScanState())

    val patients = repo.patientDao.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Снимок сделан: сохранить фото и сразу распознать. */
    fun takePhoto(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val old = state.value.photoPath
                val rel = PhotoStore.saveCompressed(context, uri, "scan_${System.currentTimeMillis()}")
                old?.let { PhotoStore.delete(context, it) }
                state.value = ScanState(photoPath = rel)
                recognize(context, rel)
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.message ?: "Не удалось сохранить фото")
            }
        }
    }

    fun retake(context: Context) {
        state.value.photoPath?.let { PhotoStore.delete(context, it) }
        state.value = ScanState()
    }

    fun reRecognize(context: Context) {
        val rel = state.value.photoPath ?: return
        recognize(context, rel)
    }

    private fun recognize(context: Context, rel: String) {
        viewModelScope.launch {
            state.value = state.value.copy(ocrRunning = true, error = null)
            try {
                val draft = withContext(Dispatchers.Default) {
                    try {
                        OcrEngine.recognize(context, PhotoStore.file(context, rel))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        //нативные сбои — Error, а не Exception: без перехвата приложение крашится
                        android.util.Log.e("RxScan", "OCR failed", e)
                        throw IllegalStateException(OcrEngine.friendlyError(e))
                    }
                }
                applyDraft(draft)
            } catch (e: Exception) {
                state.value = state.value.copy(
                    ocrRunning = false,
                    error = "Распознавание не удалось: ${e.message}",
                )
            }
        }
    }

    private suspend fun applyDraft(draft: OcrEngine.Draft) {
        val patients = repo.patientDao.all().filter { !it.archived }
        val drugs = repo.drugDao.all().filter { !it.archived }.associateBy { it.id }
        val matchedPatient = draft.fio?.let { f ->
            TextMatch.bestMatches(listOf(f), patients, nameOf = { it.fio })
                .firstOrNull { it.second >= TextMatch.AUTO_SELECT_MIN }?.first
        }
        var st = state.value.copy(
            ocrRunning = false,
            draft = draft,
            series = draft.series ?: "",
            number = draft.number ?: "",
            issueDate = draft.issueDate?.let { Dates.parseFlexible(it) },
            validDays = draft.validDays,
            patient = matchedPatient,
            // Ф13.7 §8: поля «Сохранить и выдать» предзаполнены OCR, правятся вручную
            units = draft.unitsCount,
            packSize = draft.packSize,
            deferred = draft.deferred,
            report = if (draft.rawText.length < 20) {
                "Текст не распознан. Переснимите бланк ближе, при хорошем свете и без наклона — или заполните поля вручную."
            } else {
                buildString {
                    append("Распознано ${draft.rawText.length} символов. Найдено:")
                    append(if (draft.series != null) " серия ${draft.series}" else " серия —")
                    append(if (draft.number != null) ", № ${draft.number}" else ", № —")
                    append(if (draft.issueDate != null) ", дата ${draft.issueDate}" else ", дата —")
                    append(if (draft.validDays != null) ", срок ${draft.validDays} дн" else ", срок —")
                    if (draft.fio != null) append(", ФИО «${draft.fio}»")
                    if (draft.birthDate != null) append(", дата рождения ${draft.birthDate}")
                    if (draft.diagnosis != null) append(", МКБ ${draft.diagnosis}")
                    if (draft.drugLines.isNotEmpty()) append(", позиция: ${draft.drugLines.first().take(60)}")
                    append(". Сверьте поля с фото и сохраните.")
                }
            },
        )
        if (matchedPatient != null) st = loadPrescriptions(st, matchedPatient.id, draft, drugs)
        state.value = st
    }

    /** Активные назначения пациента + автоподбор по совпадению препарата с OCR. */
    private suspend fun loadPrescriptions(
        st: ScanState,
        patientId: Long,
        draft: OcrEngine.Draft?,
        drugs: Map<Long, Drug>,
    ): ScanState {
        val ps = repo.prescriptionDao.allActive().filter { it.patientId == patientId }
        val scores = ps.mapNotNull { p ->
            val name = drugs[p.drugId]?.name ?: return@mapNotNull null
            p.id to prescriptionScore(draft?.drugLines ?: emptyList(), draft?.rawText, name)
        }.toMap()
        return st.copy(
            prescriptions = ps,
            drugsById = drugs,
            prescriptionScores = scores,
            prescriptionId = autoSelectPrescription(scores),
        )
    }

    /** Пациента нет в базе — создаём на месте, остальные поля карточки заполнят позже. */
    fun createPatient(fio: String, birthEpochDay: Long, diagnosis: String?) {
        viewModelScope.launch {
            try {
                val t = System.currentTimeMillis()
                val p = Patient(
                    fio = fio.trim(),
                    birthDate = birthEpochDay,
                    address = "",
                    diagnosis = diagnosis?.trim()?.takeIf { it.isNotBlank() },
                    createdAt = t,
                    updatedAt = t,
                )
                val id = repo.patientDao.insert(p)
                selectPatient(p.copy(id = id))
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.message ?: "Не удалось создать пациента")
            }
        }
    }

    /** Позиции нет в справочнике — быстрое создание на месте (Ф1.3), вид «препарат/устройство». */
    fun createDrug(drug: Drug, onCreated: (Drug) -> Unit) {
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            val id = repo.drugDao.insert(drug.copy(updatedAt = t))
            val created = drug.copy(id = id, updatedAt = t)
            // новая позиция сразу видна в чипах назначений и в подборе (Ф13.7)
            state.value = state.value.copy(drugsById = state.value.drugsById + (id to created))
            onCreated(created)
        }
    }

    /** Дубликат названия препарата (п. 5.9) — предупреждение в диалоге создания. */
    val duplicateWarning = MutableStateFlow<String?>(null)

    fun checkDuplicate(name: String) {
        viewModelScope.launch {
            val dup = repo.findDuplicateDrug(name)
            duplicateWarning.value = dup?.let { "Похожий препарат уже есть: «${it.name}». Сохранить всё равно?" }
        }
    }

    /** Создание назначения на месте (Ф13.7 §8): препарат + обязательная норма; новое назначение выбирается. */
    fun createPrescription(patientId: Long, drug: Drug, normDraft: NormDraft) {
        viewModelScope.launch {
            try {
                val t = System.currentTimeMillis()
                val pid = repo.prescriptionDao.insert(
                    Prescription(patientId = patientId, drugId = drug.id, createdAt = t, updatedAt = t),
                )
                repo.normDao.insert(normDraft.toNorm(pid, t))
                val drugs = state.value.drugsById.ifEmpty {
                    repo.drugDao.all().filter { !it.archived }.associateBy { it.id }
                }
                val newSt = loadPrescriptions(state.value, patientId, state.value.draft, drugs)
                state.value = newSt.copy(prescriptionId = pid)
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.message ?: "Не удалось создать назначение")
            }
        }
    }

    fun setSeries(v: String) {
        state.value = state.value.copy(series = v)
    }

    fun setNumber(v: String) {
        state.value = state.value.copy(number = v)
    }

    fun setIssueDate(d: LocalDate?) {
        state.value = state.value.copy(issueDate = d)
    }

    fun setValidDays(d: Int?) {
        state.value = state.value.copy(validDays = d)
    }

    fun setUnits(v: Int?) {
        state.value = state.value.copy(units = v)
    }

    fun setPackSize(v: Int?) {
        state.value = state.value.copy(packSize = v)
    }

    fun setDeferred(v: Boolean) {
        state.value = state.value.copy(deferred = v)
    }

    fun selectPatient(p: Patient) {
        viewModelScope.launch {
            val drugs = state.value.drugsById.ifEmpty {
                repo.drugDao.all().filter { !it.archived }.associateBy { it.id }
            }
            state.value = loadPrescriptions(state.value.copy(patient = p), p.id, state.value.draft, drugs)
        }
    }

    fun setPrescription(id: Long?) {
        state.value = state.value.copy(prescriptionId = id)
    }

    fun clearPatient() {
        state.value = state.value.copy(
            patient = null,
            prescriptions = emptyList(),
            prescriptionScores = emptyMap(),
            prescriptionId = null,
        )
    }

    fun save() {
        val st = state.value
        val patient = st.patient
        if (patient == null) {
            state.value = st.copy(error = "Выберите пациента")
            return
        }
        // Ф1.5: будущая дата выдачи рецепта блокируется с объяснением
        if (st.issueDate != null && st.issueDate!!.isAfter(Dates.today())) {
            state.value = st.copy(error = "Дата выдачи рецепта не может быть в будущем")
            return
        }
        viewModelScope.launch {
            try {
                val t = System.currentTimeMillis()
                val rx = Rx(
                    patientId = patient.id,
                    prescriptionId = st.prescriptionId,
                    series = st.series.trim(),
                    number = st.number.trim(),
                    issueDate = st.issueDate?.let { Dates.toEpochDay(it) },
                    validDays = st.validDays,
                    status = "ISSUED",
                    ocrText = st.draft?.rawText?.takeIf { it.isNotBlank() },
                    photoPaths = Rx.photosToJson(listOfNotNull(st.photoPath)),
                    createdAt = t,
                    updatedAt = t,
                )
                val id = repo.rxDao.insert(rx)
                state.value = st.copy(saved = true, savedPrescriptionId = st.prescriptionId, savedRxId = id)
            } catch (e: Exception) {
                state.value = st.copy(error = e.message ?: "Не удалось сохранить")
            }
        }
    }

    /** После «Готово» — к новому скану. */
    fun reset() {
        state.value = ScanState()
    }

    // ── Ф13 «Сохранить и выдать» (ТЗ 1.4.0 §7–8) ──────────────────────────────

    /**
     * Кнопка «Сохранить и выдать»: собрать план (RxIssuePlan), дубль-проверку
     * (RxDupCheck) и предпросмотр «обеспечен до» на чтение (IssuePreview) —
     * в диалог сверки. Автовыдачи нет: выдаёт только confirmIssue по кнопке.
     */
    fun prepareIssue() {
        val st = state.value
        val patient = st.patient ?: return
        val prescriptionId = st.prescriptionId ?: return
        viewModelScope.launch {
            try {
                val prescription = st.prescriptions.firstOrNull { it.id == prescriptionId }
                val drug = prescription?.let { st.drugsById[it.drugId] }
                if (prescription == null || drug == null) {
                    state.value = state.value.copy(error = "Назначение не найдено")
                    return@launch
                }
                val today = Dates.today()

                // дубль-проверка по серии/номеру (Ф13.5): нормализация и guard — в RxDupCheck
                val dups = if (RxDupCheck.normSeries(st.series).isBlank() || RxDupCheck.normNumber(st.number).isBlank()) {
                    emptyList()
                } else {
                    repo.rxDao.bySeriesNumber(RxDupCheck.normSeries(st.series), RxDupCheck.normNumber(st.number))
                        .map { rx ->
                            val issues = repo.issueDao.forRx(rx.id)
                            RxDupCheck.Dup(
                                rx = rx,
                                patientFio = repo.patientDao.byId(rx.patientId)?.fio ?: "—",
                                hasIssues = issues.isNotEmpty(),
                                firstIssueDate = issues.minOfOrNull { Dates.fromEpochDay(it.date) },
                            )
                        }
                }
                val dupResult = RxDupCheck.check(patient.id, st.series, st.number, dups)
                val extraWarnings = mutableListOf<String>()
                var reuse: Rx? = null
                when (dupResult) {
                    is RxDupCheck.Result.Skip -> extraWarnings += dupResult.warning
                    is RxDupCheck.Result.Found -> {
                        reuse = dupResult.reuse
                        dupResult.otherPatientWarning?.let { extraWarnings += it }
                    }
                    RxDupCheck.Result.None -> Unit
                }

                val issues = repo.issueDao.forPrescription(prescriptionId)
                val norms = repo.normDao.forPrescription(prescriptionId)
                val laterIssueDate = st.issueDate?.let { rd ->
                    issues.map { Dates.fromEpochDay(it.date) }.filter { it.isAfter(rd) }.minOrNull()
                }
                // в ScanState точной даты «действителен до» нет — вычисляем (§5 ТЗ 1.4.0)
                val validUntil = CalcService.rxValidUntil(
                    st.issueDate?.let { Dates.toEpochDay(it) }, st.validDays, null,
                )
                val plan = RxIssuePlan.build(
                    RxIssuePlan.Input(
                        units = st.units, packSize = st.packSize, drugPiecesPerPack = drug.piecesPerPack,
                        rxDate = st.issueDate, today = today, validUntil = validUntil,
                        deferred = st.deferred,
                        existingIssueDate = (dupResult as? RxDupCheck.Result.Found)?.existingIssueDate,
                        laterIssueDate = laterIssueDate,
                    ),
                )

                // предпросмотр на чтение (Ф10), только когда план имеет смысл
                var previewUntil: LocalDate? = null
                var previewUnlimited = false
                if (plan.total > 0 && st.issueDate != null && !CalcService.isNoCalc(norms, today)) {
                    val res = IssuePreview.afterIssue(issues, norms, drug, null, st.issueDate!!, plan.total)
                    previewUntil = res.coveredUntil()
                    previewUnlimited = res.outcome is SupplyOutcome.Unlimited
                }

                val t = System.currentTimeMillis()
                val rx = Rx(
                    id = reuse?.id ?: 0L,
                    patientId = patient.id,
                    prescriptionId = prescriptionId,
                    series = st.series.trim(),
                    number = st.number.trim(),
                    issueDate = st.issueDate?.let { Dates.toEpochDay(it) },
                    validDays = st.validDays,
                    status = "ISSUED",
                    ocrText = st.draft?.rawText?.takeIf { it.isNotBlank() },
                    photoPaths = Rx.photosToJson(listOfNotNull(st.photoPath)),
                    createdAt = reuse?.createdAt ?: t,
                    updatedAt = t,
                )
                state.value = state.value.copy(
                    confirm = ConfirmData(
                        plan = plan,
                        previewUntil = previewUntil,
                        previewUnlimited = previewUnlimited,
                        thresholdDays = container.settings.current().drugThresholdDays,
                        extraWarnings = extraWarnings,
                        rx = rx,
                        prescriptionId = prescriptionId,
                        piecesPerPack = drug.piecesPerPack,
                    ),
                )
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.message ?: "Не удалось подготовить выдачу")
            }
        }
    }

    /** Подтверждение в диалоге сверки: транзакция issueFromRx (REDEEMED) + фактический «обеспечен до». */
    fun confirmIssue() {
        val c = state.value.confirm ?: return
        viewModelScope.launch {
            try {
                val issue = repo.issueFromRx(c.rx, c.prescriptionId, c.plan, c.piecesPerPack)
                val prescription = repo.prescriptionDao.byId(c.prescriptionId)
                val drug = prescription?.let { repo.drugDao.byId(it.drugId) }
                val issues = repo.issueDao.forPrescription(c.prescriptionId)
                val norms = repo.normDao.forPrescription(c.prescriptionId)
                val res = CalcService.supplyFor(issues, norms, drug)
                state.value = state.value.copy(
                    confirm = null,
                    issued = IssuedData(
                        issueId = issue.id,
                        rxId = issue.rxId ?: c.rx.id,
                        prescriptionId = c.prescriptionId,
                        until = res.coveredUntil(),
                        unlimited = res.outcome is SupplyOutcome.Unlimited,
                        noCalc = CalcService.isNoCalc(norms),
                    ),
                )
            } catch (e: Exception) {
                state.value = state.value.copy(confirm = null, error = e.message ?: "Не удалось сохранить выдачу")
            }
        }
    }

    /**
     * «Отменить выдачу» (§7): удалить выдачу (+tombstone), вернуть «выписан», если
     * других выдач по рецепту нет. Форма скана остаётся — поля можно поправить и повторить.
     */
    fun undoIssue() {
        val d = state.value.issued ?: return
        viewModelScope.launch {
            try {
                repo.undoIssueFromRx(d.issueId, d.rxId)
                state.value = state.value.copy(issued = null)
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.message ?: "Не удалось отменить выдачу")
            }
        }
    }

    fun dismissConfirm() {
        state.value = state.value.copy(confirm = null)
    }

    fun dismissReport() {
        state.value = state.value.copy(report = null)
    }

    fun clearError() {
        state.value = state.value.copy(error = null)
    }

    companion object {
        /** Назначение помечается «совпадает с рецептом», начиная с этого балла. */
        const val DRUG_MATCH_MIN = 60

        /**
         * Балл назначения по OCR (Ф13.3): максимум из сходства с распознанными
         * строками препарата и доли токенов названия во всём тексте рецепта
         * (coverage; учитывается только при пороге [DRUG_MATCH_MIN] и выше).
         * Чистая функция — покрыта тестами.
         */
        fun prescriptionScore(drugLines: List<String>, rawText: String?, drugName: String): Int {
            val byLines = drugLines.maxOfOrNull { TextMatch.similarity(it, drugName) } ?: 0
            val cov = rawText?.let { TextMatch.coverage(it, drugName) } ?: 0
            return maxOf(byLines, if (cov >= DRUG_MATCH_MIN) cov else 0)
        }

        /**
         * Название для предзаполнения диалога позиции (Ф13.7 §8): строка после
         * «Выписано Rp./Вр.», при пустой — первая распознанная строка препарата.
         */
        fun ocrPositionName(draft: OcrEngine.Draft?): String =
            draft?.rpName?.takeIf { it.isNotBlank() } ?: draft?.drugLines?.firstOrNull() ?: ""

        /**
         * Эвристика вида «устройство» (Ф13.7 §8): слова-маркеры в форме выпуска
         * или названии. Чистая функция — покрыта тестами.
         */
        fun deviceKindHint(formName: String?, name: String): Boolean {
            val hay = "${formName ?: ""} $name".lowercase()
            return listOf("полоск", "набор", "штрих", "шприц", "помп", "сенсор").any { hay.contains(it) }
        }

        /**
         * Автоподбор назначения (Ф1.3): единственный явный лидер — выбирается;
         * несколько с равным максимальным баллом (неоднозначность) — без автовыбора,
         * пользователь выбирает из чипов вручную. Чистая функция, покрыта тестами.
         */
        fun autoSelectPrescription(scores: Map<Long, Int>, min: Int = DRUG_MATCH_MIN): Long? {
            val sorted = scores.entries.filter { it.value >= min }.sortedByDescending { it.value }
            return when {
                sorted.isEmpty() -> null
                sorted.size == 1 -> sorted.first().key
                sorted[0].value > sorted[1].value -> sorted.first().key
                else -> null // неоднозначно — ручной выбор
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RxScanScreen(
    onIssue: (prescriptionId: Long, rxDateEpoch: Long?, rxId: Long?) -> Unit,
    onOpenPrescription: (Long) -> Unit,
) {
    val vm = appViewModel { RxScanViewModel(it) }
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val patients by vm.patients.collectAsStateWithLifecycle()
    val duplicateWarning by vm.duplicateWarning.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var viewPhoto by remember { mutableStateOf(false) }
    var showCreatePosition by remember { mutableStateOf(false) }
    var showCreatePatient by remember { mutableStateOf(false) }
    var showNewPrescription by remember { mutableStateOf(false) }
    var showCameraRationale by remember { mutableStateOf(false) }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }

    // PickVisualMedia (системный фото-пикер) надёжнее OEM-реализаций GetContent на Xiaomi/HyperOS
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.takePhoto(context, it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraTarget?.let { vm.takePhoto(context, it) }
        cameraTarget = null
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanCamera({ cameraTarget = it }, cameraLauncher::launch, context)
        else showCameraRationale = true
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Загрузить рецепт") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ── Снимок ──
            if (state.photoPath == null) {
                Spacer(Modifier.height(24.dp))
                EmptyState("Сфотографируйте рецепт — поля заполнятся автоматически")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            launchScanCamera({ cameraTarget = it }, cameraLauncher::launch, context)
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }) { Text("Камера") }
                    OutlinedButton(onClick = {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Галерея") }
                }
            } else {
                // Тап по снимку — полноэкранный просмотр с зумом и повторным OCR (Ф1.1, Ф1.2)
                AsyncImage(
                    model = PhotoStore.file(context, state.photoPath!!),
                    contentDescription = "Фото рецепта",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewPhoto = true },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    TextButton(onClick = { vm.retake(context) }) { Text("Переснять") }
                    if (!state.ocrRunning && state.draft == null) {
                        TextButton(onClick = { vm.reRecognize(context) }) { Text("Распознать повторно") }
                    }
                }
                if (state.ocrRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("Распознаём…")
                    }
                }
            }

            // ── Поля рецепта (после снимка; правятся вручную при сбое OCR) ──
            if (state.photoPath != null && !state.ocrRunning) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    state.series, { vm.setSeries(it) },
                    label = { Text("Серия (например 75464)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    state.number, { vm.setNumber(it) },
                    label = { Text("Номер (например 25 222 011 880)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                DatePickerField(
                    "Дата выдачи рецепта",
                    state.issueDate,
                    { vm.setIssueDate(it) },
                    Modifier.fillMaxWidth(),
                )
                Text("Срок действия", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 60, 90).forEach { d ->
                        FilterChip(
                            selected = state.validDays == d,
                            onClick = { vm.setValidDays(if (state.validDays == d) null else d) },
                            label = { Text("$d дн") },
                        )
                    }
                }

                // ── Выдача по рецепту (Ф13.7 §8): сверяется человеком, поля правятся ──
                Text(
                    "Выдача по рецепту",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Количество единиц", state.units, { vm.setUnits(it) }, Modifier.weight(1f))
                    IntField("№ в упаковке", state.packSize, { vm.setPackSize(it) }, Modifier.weight(1f))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Switch(checked = state.deferred, onCheckedChange = { vm.setDeferred(it) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("Отсроченное обеспечение")
                        Text(
                            "Выдача запишется датой рецепта",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ── Пациент ──
                Text(
                    "Пациент",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                val patient = state.patient
                if (patient != null) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(patient.fio, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "родился ${Dates.formatRu(patient.birthDate)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.clearPatient() }) { Text("Сменить") }
                        }
                    }
                } else {
                    OutlinedTextField(
                        query, { query = it },
                        label = { Text("Поиск по ФИО") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    val filtered = if (query.isBlank()) patients else patients.filter {
                        it.fio.contains(query.trim(), ignoreCase = true)
                    }
                    filtered.take(6).forEach { p ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { vm.selectPatient(p) },
                        ) {
                            Text(p.fio, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            "Ничего не найдено",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { showCreatePatient = true },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("+ Создать нового пациента") }
                }

                // ── Назначение (необязательно) ──
                if (patient != null && state.prescriptions.isNotEmpty()) {
                    Text(
                        "Назначение (необязательно)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = state.prescriptionId == null,
                            onClick = { vm.setPrescription(null) },
                            label = { Text("Без назначения") },
                        )
                        state.prescriptions.forEach { p ->
                            val matched = (state.prescriptionScores[p.id] ?: 0) >= RxScanViewModel.DRUG_MATCH_MIN
                            val drug = state.drugsById[p.drugId]
                            FilterChip(
                                selected = state.prescriptionId == p.id,
                                onClick = { vm.setPrescription(if (state.prescriptionId == p.id) null else p.id) },
                                label = {
                                    Text(
                                        (drug?.name ?: "—") +
                                            (if (drug?.isDevice == true) " (устройство)" else "") +
                                            if (matched) " • совпадает с рецептом" else "",
                                    )
                                },
                            )
                        }
                    }
                    val bestScore = state.prescriptionScores.values.maxOrNull() ?: 0
                    if (bestScore < RxScanViewModel.DRUG_MATCH_MIN) {
                        // позиция не сопоставилась ни с одним назначением — быстрое создание (Ф1.3)
                        Text(
                            "Распознанная позиция не совпала с назначениями пациента",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { showCreatePosition = true }) {
                            Text("+ Создать позицию в справочнике")
                        }
                    }
                } else if (patient != null && state.prescriptions.isEmpty()) {
                    Text(
                        "Активных назначений нет — для выдачи по рецепту создайте назначение",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // Ф13.7 §8: назначение на месте — препарат из справочника (или новый) + обязательная норма
                if (patient != null) {
                    OutlinedButton(onClick = { showNewPrescription = true }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("+ Новое назначение")
                    }
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                Button(
                    onClick = { vm.save() },
                    enabled = state.patient != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text("Сохранить рецепт") }
                // Ф13.7 §8: вторая кнопка — сохраняет рецепт и выдаёт после сверки в диалоге
                Button(
                    onClick = { vm.prepareIssue() },
                    enabled = state.patient != null && state.prescriptionId != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Сохранить и выдать") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // Отчёт распознавания (п. 6.4 — итог подтверждает человек)
    state.report?.let { report ->
        AlertDialog(
            onDismissRequest = { vm.dismissReport() },
            title = { Text("Распознавание") },
            text = { Text(report) },
            confirmButton = {
                TextButton(onClick = { vm.dismissReport() }) { Text("Понятно") }
            },
        )
    }

    // Создание пациента на месте
    if (showCreatePatient) {
        var fio by remember { mutableStateOf(state.draft?.fio ?: "") }
        var birth by remember { mutableStateOf(state.draft?.birthDate?.let { Dates.parseFlexible(it) }) }
        var diag by remember { mutableStateOf(state.draft?.diagnosis ?: "") }
        AlertDialog(
            onDismissRequest = { showCreatePatient = false },
            title = { Text("Новый пациент") },
            text = {
                Column {
                    OutlinedTextField(
                        fio, { fio = it },
                        label = { Text("Ф.И.О.") }, singleLine = true,
                    )
                    DatePickerField("Дата рождения", birth, { birth = it }, Modifier.fillMaxWidth())
                    OutlinedTextField(
                        diag, { diag = it },
                        label = { Text("Диагноз (код МКБ, необязательно)") }, singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = fio.isNotBlank() && birth != null,
                    onClick = {
                        val b = birth
                        if (b != null) {
                            vm.createPatient(fio, Dates.toEpochDay(b), diag)
                            showCreatePatient = false
                        }
                    },
                ) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePatient = false }) { Text("Отмена") }
            },
        )
    }

    // Сохранено → предложить выдачу по рецепту
    if (state.saved) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Рецепт сохранён") },
            text = {
                Text(
                    (state.patient?.fio ?: "") +
                        (state.series.takeIf { it.isNotBlank() }?.let { "\nСерия $it" } ?: "") +
                        (state.number.takeIf { it.isNotBlank() }?.let { " № $it" } ?: ""),
                )
            },
            confirmButton = {
                val pid = state.savedPrescriptionId
                if (pid != null) {
                    // Ф1.4: «Выдать» (вместо «Выдать сейчас») — форма выдачи с датой рецепта,
                    // подтверждение пользователем в самой форме, автосохранения нет.
                    // Ф13.5 §6.3: передаём и rxId — чип рецепта предвыбран, выдача не
                    // останется без связи, дубль-проверка её видит
                    TextButton(onClick = {
                        val rxDate = state.issueDate?.let { Dates.toEpochDay(it) }
                        val savedRx = state.savedRxId
                        vm.reset()
                        onIssue(pid, rxDate, savedRx)
                    }) { Text("Выдать") }
                } else null
            },
            dismissButton = {
                TextButton(onClick = { vm.reset() }) { Text("Готово") }
            },
        )
    }

    if (showCameraRationale) {
        AlertDialog(
            onDismissRequest = { showCameraRationale = false },
            title = { Text("Нужна камера") },
            text = { Text("Разрешите доступ к камере в настройках, чтобы фотографировать бланки, или выберите снимок из галереи.") },
            confirmButton = {
                TextButton(onClick = { showCameraRationale = false }) { Text("Понятно") }
            },
        )
    }

    // Диалог сверки перед выдачей (Ф13.7 §8): обязателен — OCR ошибается, числа
    // проходят ручную сверку; блокировки гасят кнопку «Выдать», предупреждения — нет
    state.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.dismissConfirm() },
            title = { Text("Выдать по рецепту?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${state.units ?: "—"} × №${state.packSize ?: "—"} = ${c.plan.total} шт, " +
                            "дата ${state.issueDate?.let { Dates.formatRu(it) } ?: "—"}",
                        fontWeight = FontWeight.Bold,
                    )
                    if (c.previewUnlimited) {
                        Text(
                            "После этой выдачи: запас более 5 лет",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else c.previewUntil?.let { until ->
                        Text(
                            "После этой выдачи: обеспечен до ${Dates.formatRu(until)}",
                            fontWeight = FontWeight.SemiBold,
                            color = CalcService.level(until, c.thresholdDays).color(),
                        )
                    }
                    if (c.plan.blocks.isNotEmpty()) {
                        Text(
                            "Выдача невозможна:",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        c.plan.blocks.forEach {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    (c.extraWarnings + c.plan.warnings).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = c.plan.blocks.isEmpty(), onClick = { vm.confirmIssue() }) { Text("Выдать") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissConfirm() }) { Text("Отмена") }
            },
        )
    }

    // Финальный диалог (Ф13.7 §8): результат + «Готово» / «К назначению» / «Отменить выдачу»
    state.issued?.let { d ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Выдано") },
            text = {
                Text(
                    when {
                        d.noCalc -> "Записано в журнал. Назначение без расчёта срока."
                        d.unlimited -> "Обеспечен до: запас более 5 лет"
                        d.until != null -> "Обеспечен до ${Dates.formatRu(d.until)}"
                        else -> "Записано в журнал."
                    },
                    fontWeight = FontWeight.Bold,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.reset() }) { Text("Готово") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val pid = d.prescriptionId
                        vm.reset()
                        onOpenPrescription(pid)
                    }) { Text("К назначению") }
                    TextButton(onClick = { vm.undoIssue() }) {
                        Text("Отменить выдачу", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    // Создание назначения на месте (Ф13.7 §8): позиция + обязательная норма,
    // validFrom = дата рецепта (редактируемая), Signa — подсказкой над формой
    if (showNewPrescription && state.patient != null) {
        NewPrescriptionDialog(
            drugs = state.drugsById.values.sortedBy { it.name },
            initialValidFrom = state.issueDate,
            signa = state.draft?.signa,
            initialName = RxScanViewModel.ocrPositionName(state.draft),
            initialPackSize = state.packSize,
            initialFormName = state.draft?.formName,
            duplicateWarning = duplicateWarning,
            onCheckDuplicate = { vm.checkDuplicate(it) },
            onCreateDrug = { draft, onCreated -> vm.createDrug(draft, onCreated) },
            onSave = { drug, normDraft ->
                val patientId = state.patient?.id ?: return@NewPrescriptionDialog
                showNewPrescription = false
                vm.createPrescription(patientId, drug, normDraft)
            },
            onDismiss = { showNewPrescription = false },
        )
    }

    // Полноэкранный просмотр снимка с повторным распознаванием (Ф1.1, Ф1.2)
    if (viewPhoto && state.photoPath != null) {
        ru.danil.medtracker.ui.PhotoViewer(
            file = PhotoStore.file(context, state.photoPath!!),
            onReRecognize = {
                vm.reRecognize(context)
            },
            onDismiss = { viewPhoto = false },
        )
    }

    // Быстрое создание позиции (Ф1.3): вид «препарат/устройство» — как у пациента, на месте.
    // Ф13.7 §8: предзаполнено названием из OCR, «№» упаковки и видом по эвристике
    if (showCreatePosition) {
        val ocrName = RxScanViewModel.ocrPositionName(state.draft)
        DrugCreateDialog(
            duplicateWarning = duplicateWarning,
            onCheckDuplicate = { vm.checkDuplicate(it) },
            initialName = ocrName,
            initialPiecesPerPack = state.packSize,
            initialKind = if (RxScanViewModel.deviceKindHint(state.draft?.formName, ocrName)) {
                DrugKind.DEVICE
            } else DrugKind.DRUG,
            onCreate = { draft ->
                vm.createDrug(draft) { showCreatePosition = false }
            },
            onDismiss = { showCreatePosition = false },
        )
    }
}

/**
 * Создание назначения на месте (Ф13.7 §8): позиция из справочника или новая
 * (предзаполненная из OCR), затем обязательная форма нормы с validFrom = дата
 * рецепта (редактируемая); Signa из рецепта — подсказкой над формой. Выдача
 * возможна только после сохранённой нормы — «Сохранить» проверяет норму.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewPrescriptionDialog(
    drugs: List<Drug>,
    initialValidFrom: LocalDate?,
    signa: String?,
    initialName: String,
    initialPackSize: Int?,
    initialFormName: String?,
    duplicateWarning: String?,
    onCheckDuplicate: (String) -> Unit,
    onCreateDrug: (Drug, onCreated: (Drug) -> Unit) -> Unit,
    onSave: (Drug, NormDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedDrug by remember { mutableStateOf<Drug?>(null) }
    var showDrugSelect by remember { mutableStateOf(false) }
    var showDrugCreate by remember { mutableStateOf(false) }
    var norm by remember { mutableStateOf(NormDraft(validFrom = initialValidFrom ?: Dates.today())) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новое назначение") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                signa?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Signa из рецепта: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = selectedDrug?.let {
                        "${it.name}${if (it.isDevice) " (устройство)" else ""} — ${CalcService.drugForm(it)}"
                    } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (selectedDrug == null) "Позиция (выберите или создайте)" else "Позиция") },
                    trailingIcon = { TextButton(onClick = { showDrugSelect = true }) { Text("Выбрать") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Норма выдачи (обязательна)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                DatePickerField("Действует с", norm.validFrom, { norm = norm.copy(validFrom = it ?: Dates.today()) }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = norm.kind == NormDraft.NormKind.PER_DAY, onClick = { norm = norm.copy(kind = NormDraft.NormKind.PER_DAY) }, label = { Text("шт/день") })
                    FilterChip(selected = norm.kind == NormDraft.NormKind.PER_MONTH, onClick = { norm = norm.copy(kind = NormDraft.NormKind.PER_MONTH) }, label = { Text("шт/мес") })
                    FilterChip(selected = norm.kind == NormDraft.NormKind.PER_YEAR, onClick = { norm = norm.copy(kind = NormDraft.NormKind.PER_YEAR) }, label = { Text("шт/год") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = norm.kind == NormDraft.NormKind.EVERY_X_DAYS, onClick = { norm = norm.copy(kind = NormDraft.NormKind.EVERY_X_DAYS) }, label = { Text("раз в N дней") })
                    FilterChip(selected = norm.kind == NormDraft.NormKind.ME, onClick = { norm = norm.copy(kind = NormDraft.NormKind.ME) }, label = { Text("МЕ/сутки") })
                    FilterChip(selected = norm.kind == NormDraft.NormKind.NO_CALC, onClick = { norm = norm.copy(kind = NormDraft.NormKind.NO_CALC) }, label = { Text("без срока") })
                }
                when (norm.kind) {
                    NormDraft.NormKind.EVERY_X_DAYS -> Row {
                        IntField("Кол-во, шт", norm.amount, { norm = norm.copy(amount = it) }, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        IntField("Период, дней", norm.everyXDays, { norm = norm.copy(everyXDays = it) }, Modifier.weight(1f))
                    }
                    NormDraft.NormKind.ME -> {
                        IntField("МЕ (ЕД) в сутки", norm.meAmount, { norm = norm.copy(meAmount = it) }, Modifier.fillMaxWidth())
                        if (selectedDrug?.mePerPiece() == null) {
                            Text(
                                "Для МЕ-нормы задайте позиции концентрацию МЕ/мл и объём",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    NormDraft.NormKind.NO_CALC -> Text(
                        "Резерв / разовые выдачи: ведётся только журнал выдач, срок не считается",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> IntField("Количество, шт", norm.amount, { norm = norm.copy(amount = it) }, Modifier.fillMaxWidth())
                }
                localError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedDrug != null,
                onClick = {
                    localError = when {
                        selectedDrug == null -> "Выберите позицию"
                        !norm.isValid -> "Заполните норму"
                        norm.kind == NormDraft.NormKind.ME && selectedDrug?.mePerPiece() == null ->
                            "Позиции не заданы концентрация и объём для МЕ-нормы"
                        else -> null
                    }
                    if (localError == null) onSave(selectedDrug!!, norm)
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (showDrugSelect) {
        AlertDialog(
            onDismissRequest = { showDrugSelect = false },
            title = { Text("Позиция") },
            text = {
                if (drugs.isEmpty()) {
                    Text("Справочник пуст — создайте первую позицию")
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                        items(drugs.size) { idx ->
                            val drug = drugs[idx]
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedDrug = drug; showDrugSelect = false }
                                    .padding(10.dp),
                            ) {
                                Text(drug.name)
                                if (drug.isDevice) {
                                    Text(
                                        "устройство",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDrugSelect = false; showDrugCreate = true }) { Text("+ Создать новую") }
            },
            dismissButton = { TextButton(onClick = { showDrugSelect = false }) { Text("Отмена") } },
        )
    }

    if (showDrugCreate) {
        DrugCreateDialog(
            duplicateWarning = duplicateWarning,
            onCheckDuplicate = onCheckDuplicate,
            initialName = initialName,
            initialPiecesPerPack = initialPackSize,
            initialKind = if (RxScanViewModel.deviceKindHint(initialFormName, initialName)) {
                DrugKind.DEVICE
            } else DrugKind.DRUG,
            onCreate = { draft ->
                onCreateDrug(draft) { created ->
                    selectedDrug = created
                    showDrugCreate = false
                }
            },
            onDismiss = { showDrugCreate = false },
        )
    }
}

private fun launchScanCamera(
    setTarget: (Uri) -> Unit,
    launch: (Uri) -> Unit,
    context: Context,
) {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    setTarget(uri)
    launch(uri)
}
