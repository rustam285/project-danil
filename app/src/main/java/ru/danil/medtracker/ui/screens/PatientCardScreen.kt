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
    )

    fun state(patientId: Long) = combine(
        combine(
            repo.patientDao.observeAll(),
            repo.prescriptionDao.observeActiveForPatient(patientId),
            repo.normDao.observeAll(),
        ) { p, pr, n -> Triple(p, pr, n) },
        combine(
            repo.issueDao.observeAll(),
            repo.drugDao.observeAll(),
            repo.rxDao.observeForPatient(patientId),
        ) { i, d, r -> Triple(i, d, r) },
        container.settings.settings,
    ) { db, calc, settings ->
        val (patients, prescriptions, norms) = db
        val (issues, drugs, rxs) = calc
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
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), State())

    fun archive(id: Long) {
        viewModelScope.launch {
            val p = repo.patientDao.byId(id) ?: return@launch
            repo.patientDao.update(p.copy(archived = true, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteForever(id: Long) {
        viewModelScope.launch {
            repo.coreDao.deletePatientCascade(
                repo.patientDao, repo.prescriptionDao, repo.normDao, repo.issueDao, repo.rxDao, id,
            )
        }
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
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var showDocDialog by remember { mutableStateOf<java.io.File?>(null) }

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

            // Назначения (п. 5.3)
            item {
                Text("Назначения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Тап по строке — детали; кнопка «+» — быстрая выдача",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.views.isEmpty()) {
                item { EmptyState("Назначений пока нет — добавьте первое", "Добавить назначение") { onAddPrescription(patientId) } }
            } else {
                items(state.views.size) { idx ->
                    val v = state.views[idx]
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onOpenPrescription(v.prescription.id) },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            if (v.noCalc) {
                                Text("журнал", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (v.issues.isEmpty()) {
                                Text("выдач нет", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (v.isUnlimited) {
                                Text("запас > 5 лет", color = androidx.compose.ui.graphics.Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
                            } else {
                                SupplyBadge(v.coveredUntil, CalcService.level(v.coveredUntil, state.threshold))
                            }
                            // Контекстный быстрый ввод выдачи (п. 5.3)
                            IconButton(onClick = { onAddIssue(v.prescription.id) }) {
                                Icon(Icons.Filled.Add, "Выдать")
                            }
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
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("${rx.photos().size} фото", style = MaterialTheme.typography.labelMedium)
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
}
