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
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.ocr.OcrEngine
import ru.danil.medtracker.ocr.PhotoStore
import ru.danil.medtracker.ocr.TextMatch
import ru.danil.medtracker.ui.DatePickerField
import ru.danil.medtracker.ui.EmptyState
import ru.danil.medtracker.ui.appViewModel
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
            onCreated(drug.copy(id = id, updatedAt = t))
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
fun RxScanScreen(onIssue: (prescriptionId: Long, rxDateEpoch: Long?) -> Unit) {
    val vm = appViewModel { RxScanViewModel(it) }
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val patients by vm.patients.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var viewPhoto by remember { mutableStateOf(false) }
    var showCreatePosition by remember { mutableStateOf(false) }
    var showCreatePatient by remember { mutableStateOf(false) }
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
                } else if (patient != null && state.prescriptions.isEmpty() && !state.draft?.drugLines.isNullOrEmpty()) {
                    OutlinedButton(onClick = { showCreatePosition = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("+ Создать позицию в справочнике")
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
                    // подтверждение пользователем в самой форме, автосохранения нет
                    TextButton(onClick = {
                        val rxDate = state.issueDate?.let { Dates.toEpochDay(it) }
                        vm.reset()
                        onIssue(pid, rxDate)
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

    // Быстрое создание позиции (Ф1.3): вид «препарат/устройство» — как у пациента, на месте
    if (showCreatePosition) {
        DrugCreateDialog(
            duplicateWarning = null,
            onCheckDuplicate = { },
            onCreate = { draft ->
                vm.createDrug(draft) { showCreatePosition = false }
            },
            onDismiss = { showCreatePosition = false },
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
