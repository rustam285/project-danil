package ru.danil.medtracker.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.PrescriptionView
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.docx.PlanDnDoc
import ru.danil.medtracker.ui.ConfirmDialog
import ru.danil.medtracker.ui.EmptyState
import ru.danil.medtracker.ui.LabeledRow
import ru.danil.medtracker.ui.SupplyBadge
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

class PatientCardViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repo

    data class State(
        val patient: Patient? = null,
        val views: List<PrescriptionView> = emptyList(),
        val rxs: List<Rx> = emptyList(),
        val threshold: Int = 7,
        /** Секция «Анализы» (Ф3.5): доступна любому пациенту */
        val analysisTypes: List<ru.danil.medtracker.data.db.AnalysisType> = emptyList(),
        val analysisRecords: List<ru.danil.medtracker.data.db.AnalysisRecord> = emptyList(),
    )

    fun state(patientId: Long) = combine(
        combine(
            repo.patientDao.observeAll(),
            repo.prescriptionDao.observeActiveForPatient(patientId),
            repo.normDao.observeAll(),
            repo.analysisTypeDao.observeAll(),
        ) { p, pr, n, at -> Quad(p, pr, n, at) },
        combine(
            repo.issueDao.observeAll(),
            repo.drugDao.observeAll(),
            repo.rxDao.observeForPatient(patientId),
            repo.analysisRecordDao.observeForPatient(patientId),
        ) { i, d, r, ar -> Quad(i, d, r, ar) },
        container.settings.settings,
    ) { db, calc, settings ->
        val (patients, prescriptions, norms, analysisTypes) = db
        val (issues, drugs, rxs, analysisRecords) = calc
        val today = Dates.today()
        withContext(Dispatchers.Default) {
            val drugById = drugs.associateBy { it.id }
            val normsByP = norms.groupBy { it.prescriptionId }
            val issuesByP = issues.groupBy { it.prescriptionId }
            val views = prescriptions.mapNotNull { pr ->
                val drug = drugById[pr.drugId] ?: return@mapNotNull null
                val ns = normsByP[pr.id].orEmpty().sortedBy { it.validFrom }
                val issuesList = issuesByP[pr.id].orEmpty().sortedBy { it.date }
                val noCalc = CalcService.isNoCalc(ns, today)
                val supply = if (noCalc) ru.danil.medtracker.calc.SupplyResult(ru.danil.medtracker.calc.SupplyOutcome.NoEvents)
                else CalcService.supplyFor(issuesList, ns, drug)
                val remainder = if (noCalc) ru.danil.medtracker.calc.Rational.ZERO
                else ru.danil.medtracker.calc.SupplyEngine.remainderAt(today, CalcService.stockEvents(issuesList), CalcService.normRecs(ns), drug.mePerPiece())
                PrescriptionView(pr, drug, ns, issuesList, supply, noCalc, remainder)
            }.sortedBy { it.drug.name.lowercase() }
            State(
                patient = patients.firstOrNull { it.id == patientId },
                views = views,
                rxs = rxs,
                threshold = settings.drugThresholdDays,
                analysisTypes = analysisTypes,
                analysisRecords = analysisRecords,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), State())

    /** combine() больше 5 Flow нельзя (грабли §7) — четвёрки через data class (componentN gratis). */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    fun archive(id: Long) {
        viewModelScope.launch {
            val p = repo.patientDao.byId(id) ?: return@launch
            repo.patientDao.update(p.copy(archived = true, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteForever(id: Long) {
        viewModelScope.launch {
            repo.coreDao.deletePatientCascade(
                repo.patientDao, repo.prescriptionDao, repo.normDao, repo.issueDao, repo.rxDao,
                repo.analysisRecordDao, id,
            )
        }
    }

    /** «В ожидании поступления» для резервного препарата в карточке (Ф2.6). */
    fun setAwaiting(id: Long, awaiting: Boolean) {
        viewModelScope.launch { repo.setAwaitingRestock(id, awaiting) }
    }

    /** План ДН: генерация .docx во временный файл (п. 8.2). */
    suspend fun generatePlanDn(context: android.content.Context, patientId: Long): java.io.File {
        val patient = repo.patientDao.byId(patientId) ?: error("Пациент не найден")
        val prescriptions = repo.prescriptionDao.allActive().filter { it.patientId == patientId }
        val drugById = repo.drugDao.all().associateBy { it.id }
        val normsByP = repo.normDao.forPrescriptions(prescriptions.map { it.id }).groupBy { it.prescriptionId }
        val issuesByP = repo.issueDao.forPrescriptions(prescriptions.map { it.id }).groupBy { it.prescriptionId }
        val views = prescriptions.mapNotNull { pr ->
            val drug = drugById[pr.drugId] ?: return@mapNotNull null
            val ns = normsByP[pr.id].orEmpty().sortedBy { it.validFrom }
            val issuesList = issuesByP[pr.id].orEmpty().sortedBy { it.date }
            val noCalc = CalcService.isNoCalc(ns)
            val supply = if (noCalc) ru.danil.medtracker.calc.SupplyResult(ru.danil.medtracker.calc.SupplyOutcome.NoEvents)
            else CalcService.supplyFor(issuesList, ns, drug)
            PrescriptionView(pr, drug, ns, issuesList, supply, noCalc, ru.danil.medtracker.calc.Rational.ZERO)
        }
        val bytes = withContext(Dispatchers.Default) { PlanDnDoc.build(patient, views) }
        val dir = java.io.File(context.cacheDir, "docs").apply { mkdirs() }
        val surnameInitial = patient.fio.split(" ").getOrNull(0)?.replace(Regex("[^А-Яа-яЁёA-Za-z-]"), "") ?: "Пациент"
        val nameInitial = patient.fio.split(" ").getOrNull(1)?.take(1)?.uppercase() ?: ""
        val file = java.io.File(dir, "План_ДН_${surnameInitial}_$nameInitial.docx")
        file.writeBytes(bytes)
        return file
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientCardScreen(
    patientId: Long,
    onEdit: (Long) -> Unit,
    onAddPrescription: (Long) -> Unit,
    onOpenPrescription: (Long) -> Unit,
    onAddIssue: (Long) -> Unit,
    onAddRx: (Long, Long?) -> Unit,
    onCopyPrescriptions: (Long) -> Unit,
    onOpenRx: (Long, Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm = appViewModel { PatientCardViewModel(it) }
    val stateFlow = remember(patientId) { vm.state(patientId) }
    val state by stateFlow.collectAsStateWithLifecycle()
    // отдельный VM анализов для формы записи (Ф3.5), скоуп этого экрана
    val analysesVm = appViewModel { AnalysesViewModel(it) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var showDocDialog by remember { mutableStateOf<java.io.File?>(null) }
    var showAnalysisRecord by remember { mutableStateOf(false) }
    var addRecordType by remember { mutableStateOf<Long?>(null) }
    var editRecord by remember { mutableStateOf<ru.danil.medtracker.data.db.AnalysisRecord?>(null) }
    var viewerFile by remember { mutableStateOf<java.io.File?>(null) }
    var recognizedText by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var ocrBusy by remember { mutableStateOf(false) }

    val patient = state.patient

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(patient?.fio ?: "Пациент") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = { patient?.let { onEdit(it.id) } }) { Icon(Icons.Filled.Edit, "Изменить") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Archive, "В архив / удалить") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddPrescription(patientId) }) {
                Icon(Icons.Filled.Add, "Добавить назначение")
            }
        },
    ) { padding ->
        if (patient == null) {
            Column(Modifier.padding(padding)) { EmptyState("Загрузка…") }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            // Шапка (п. 3.1)
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(patient.fio, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        LabeledRow("Возраст", Dates.ageString(Dates.fromEpochDay(patient.birthDate)) + " (${Dates.formatRu(patient.birthDate)})")
                        LabeledRow("Адрес", patient.address)
                        patient.school?.let { LabeledRow("Учреждение", it) }
                        patient.illnessStart?.let { LabeledRow("Начало заболевания", Dates.formatRu(it)) }
                        val benefits = patient.benefits()
                        if (benefits.isNotEmpty()) {
                            LabeledRow(
                                "Льгота",
                                benefits.joinToString(", ") {
                                    (if (it.type == "federal") "федеральная" else "региональная") + " с ${Dates.formatRu(it.since)}"
                                },
                            )
                        }
                        patient.repName?.let { rep ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Представитель: $rep", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                        listOfNotNull(patient.phone1, patient.phone2).forEach { phone ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(phone, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    // Звонок через системный набор номера, без разрешения CALL_PHONE (п. 3.1)
                                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                                }) { Icon(Icons.Filled.Call, "Позвонить") }
                            }
                        }
                        patient.cardNo?.let { LabeledRow("Карта", it) }
                        patient.diagnosis?.let { LabeledRow("Диагноз", it) }
                        patient.doctor?.let { LabeledRow("Лечащий врач", it) }
                    }
                }
            }

            // Кнопки действий
            item {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row {
                        OutlinedButton(onClick = { onCopyPrescriptions(patientId) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.padding(end = 6.dp))
                            Text("Скопировать назначения", maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { vm.generatePlanDn(context, patientId) }
                                        .onSuccess { showDocDialog = it }
                                        .onFailure { android.widget.Toast.makeText(context, "Не удалось создать документ: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Description, null, Modifier.padding(end = 6.dp))
                            Text("План ДН (Word)")
                        }
                    }
                }
            }

            // Назначения (п. 5.3); резервные схемы — отдельной секцией ниже (Ф2.4)
            item {
                Text("Назначения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Тап по строке — детали; кнопка «+» — быстрая выдача",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val normalViews = state.views.filter { !it.prescription.isReserve }
            val reserveViews = state.views.filter { it.prescription.isReserve }
            if (normalViews.isEmpty() && reserveViews.isEmpty()) {
                item { EmptyState("Назначений пока нет — добавьте первое", "Добавить назначение") { onAddPrescription(patientId) } }
            } else {
                items(normalViews.size) { idx ->
                    PrescriptionRow(
                        v = normalViews[idx],
                        threshold = state.threshold,
                        onOpenPrescription = onOpenPrescription,
                        onAddIssue = onAddIssue,
                        showReserveChip = false,
                    )
                }
            }

            // Резервные препараты (Ф2.4): расчёт как у обычных, из «Срочного» скрыты
            if (reserveViews.isNotEmpty()) {
                item {
                    Text(
                        "Резервные препараты",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    Text(
                        "Резервные схемы: в «Срочное» не попадают, уведомлений нет",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(reserveViews.size) { idx ->
                    PrescriptionRow(
                        v = reserveViews[idx],
                        threshold = state.threshold,
                        onOpenPrescription = onOpenPrescription,
                        onAddIssue = onAddIssue,
                        showReserveChip = true,
                        onToggleWaiting = { id, awaiting -> vm.setAwaiting(id, awaiting) },
                    )
                }
            }

            // Анализы (Ф3.5): записи всех видов, доступно любому пациенту
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                    Text("Анализы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { addRecordType = null; showAnalysisRecord = true }) { Text("+ запись") }
                }
            }
            if (state.analysisTypes.isEmpty()) {
                item {
                    Text(
                        "Виды анализов создаётся во вкладке «Анализы»",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.analysisTypes.size) { idx ->
                    val t = state.analysisTypes[idx]
                    val last = state.analysisRecords.firstOrNull { it.typeId == t.id }
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                editRecord = last
                                addRecordType = t.id
                                showAnalysisRecord = true
                            },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                if (last != null) {
                                    Text(
                                        formatValue(last.value) + (t.unit?.let { " $it" } ?: "") +
                                            " · ${Dates.formatRu(last.date)}" +
                                            (last.note?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        "нет записей",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            val due = ru.danil.medtracker.data.AnalysisLogic.statusFor(state.analysisRecords, t, java.time.LocalDate.now())
                            val (label, color) = when (due) {
                                is ru.danil.medtracker.data.AnalysisDue.NoData -> "нет данных" to MaterialTheme.colorScheme.onSurfaceVariant
                                is ru.danil.medtracker.data.AnalysisDue.Due ->
                                    if (due.overdueDays > 0) "пора сдавать" to androidx.compose.ui.graphics.Color(0xFFC62828)
                                    else "пора сдавать" to androidx.compose.ui.graphics.Color(0xFFF9A825)
                                is ru.danil.medtracker.data.AnalysisDue.InTime -> "в срок" to androidx.compose.ui.graphics.Color(0xFF2E7D32)
                            }
                            Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Рецепты
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Рецепты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAddRx(patientId, null) }) { Text("+ рецепт") }
                }
            }
            if (state.rxs.isEmpty()) {
                item { EmptyState("Рецептов пока нет") }
            } else {
                items(state.rxs.size) { idx ->
                    val rx = state.rxs[idx]
                    val until = CalcService.rxValidUntil(rx.issueDate, rx.validDays, rx.validUntil)
                    val expired = rx.status == "ISSUED" && until?.isBefore(LocalDate.now()) == true
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onOpenRx(patientId, rx.id) },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Рецепт ${rx.series} № ${rx.number}".trim(),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    (rx.issueDate?.let { "от ${Dates.formatRu(it)}" } ?: "") +
                                        " · " + when {
                                            rx.status == "REDEEMED" -> "отоварен"
                                            expired -> "истёк"
                                            else -> "выписан"
                                        } +
                                        (rx.doctor?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // миниатюры: тап — полноэкранный просмотр (Ф1.1, после сохранения)
                                val photoFiles = rx.photos().map { ru.danil.medtracker.ocr.PhotoStore.file(context, it) }
                                if (photoFiles.isNotEmpty()) {
                                    ru.danil.medtracker.ui.PhotoThumbRow(
                                        photos = photoFiles,
                                        onOpen = { viewerFile = it },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // План наблюдения
            if (patient.notes.isNotBlank()) {
                item {
                    Text("План наблюдения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Text(patient.notes, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Пациент") },
            text = { Text("«В архив» скрывает пациента из списков (история сохраняется). «Удалить навсегда» стирает назначения, выдачи, рецепты и фото безвозвратно.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteForever(patientId) }) {
                    Text("Удалить навсегда", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { confirmDelete = false; vm.archive(patientId) }) { Text("В архив") }
                    TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
                }
            },
        )
    }

    showDocDialog?.let { file ->
        AlertDialog(
            onDismissRequest = { showDocDialog = null },
            title = { Text("Документ готов") },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    ru.danil.medtracker.docx.DocxShare.share(context, file)
                    showDocDialog = null
                }) { Text("Поделиться") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ru.danil.medtracker.docx.DocxShare.saveViaSaf(context, file)
                    showDocDialog = null
                }) { Text("Сохранить") }
            },
        )
    }

    if (showAnalysisRecord) {
        AnalysisRecordDialog(
            vm = analysesVm,
            patientId = patientId,
            initialTypeId = addRecordType,
            existing = editRecord,
            onDismiss = {
                showAnalysisRecord = false
                editRecord = null
                addRecordType = null
            },
        )
    }

    // Полноэкранный просмотр фото сохранённого рецепта (Ф1.1); повторный OCR (Ф1.2) —
    // текст диалогом с копированием, в поля переносится вручную
    viewerFile?.let { f ->
        ru.danil.medtracker.ui.PhotoViewer(
            file = f,
            onReRecognize = {
                ocrBusy = true
                ru.danil.medtracker.ocr.OcrRunner.recognize(scope, context, f) { res ->
                    ocrBusy = false
                    viewerFile = null
                    res.onSuccess { d ->
                        recognizedText = d.rawText.ifBlank { " " } to buildString {
                            append("Серия ${d.series ?: "—"}, № ${d.number ?: "—"}")
                            d.issueDate?.let { append(", дата $it") }
                        }
                    }.onFailure { e ->
                        android.widget.Toast.makeText(context, e.message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { viewerFile = null },
        )
    }
    if (ocrBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Распознавание") },
            text = { Text("Распознаём…") },
            confirmButton = {},
        )
    }
    recognizedText?.let { (text, summary) ->
        ru.danil.medtracker.ui.RecognizedTextDialog(text, summary) { recognizedText = null }
    }
}

/** Строка назначения в карточке пациента; в резервной секции — чип «Резерв» и
 *  кнопка «В ожидании поступления» у закончившегося препарата (Ф2.4, Ф2.6). */
@Composable
private fun PrescriptionRow(
    v: PrescriptionView,
    threshold: Int,
    onOpenPrescription: (Long) -> Unit,
    onAddIssue: (Long) -> Unit,
    showReserveChip: Boolean,
    onToggleWaiting: ((Long, Boolean) -> Unit)? = null,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onOpenPrescription(v.prescription.id) },
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.drug.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (v.noCalc) "без расчёта срока" else CalcService.normShort(CalcService.currentNorm(v.norms)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!v.noCalc) {
                        val rem = v.remainder.toDisplay()
                        val ppp = v.drug.piecesPerPack
                        val packs = v.remainder.div(ru.danil.medtracker.calc.Rational.of(ppp.toLong())).toDisplay()
                        Text(
                            if (ppp > 1) "остаток $rem шт ($packs уп)" else "остаток $rem шт",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (showReserveChip) {
                        Text(
                            "Резерв",
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color(0xFF00796B),
                        )
                    }
                    if (v.noCalc) {
                        Text("журнал", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (v.issues.isEmpty()) {
                        Text("выдач нет", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (v.isUnlimited) {
                        Text("запас > 5 лет", color = androidx.compose.ui.graphics.Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
                    } else {
                        SupplyBadge(v.coveredUntil, CalcService.level(v.coveredUntil, threshold))
                    }
                }
                // Контекстный быстрый ввод выдачи (п. 5.3)
                IconButton(onClick = { onAddIssue(v.prescription.id) }) {
                    Icon(Icons.Filled.Add, "Выдать")
                }
            }
            if (v.prescription.awaitingRestock) {
                Text(
                    "ожидается поставка",
                    style = MaterialTheme.typography.labelMedium,
                    color = androidx.compose.ui.graphics.Color(0xFF00796B),
                )
                if (onToggleWaiting != null) {
                    TextButton(onClick = { onToggleWaiting(v.prescription.id, false) }) { Text("Снять") }
                }
            } else if (onToggleWaiting != null && v.issues.isNotEmpty() &&
                v.coveredUntil?.isBefore(java.time.LocalDate.now()) == true
            ) {
                // закончившийся резервный препарат можно пометить «нет на складе» (Ф2.6)
                TextButton(onClick = { onToggleWaiting(v.prescription.id, true) }) { Text("В ожидании поступления") }
            }
        }
    }
}
