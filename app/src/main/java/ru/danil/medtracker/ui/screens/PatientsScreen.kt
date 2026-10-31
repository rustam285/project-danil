package ru.danil.medtracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.calc.Level
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.PatientListItem
import ru.danil.medtracker.ui.EmptyState
import ru.danil.medtracker.ui.SupplyBadge
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

class PatientsViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.repo

    val threshold = combine(container.settings.settings) { it.drugThresholdDays }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7)

    /** Список с ближайшими датами; пересчитывается при любом изменении данных (п. 11.2). */
    val items = run {
        val dbFlow = combine(
            repo.patientDao.observeActive(),
            repo.prescriptionDao.observeAllActive(),
            repo.normDao.observeAll(),
        ) { p, pr, n -> Triple(p, pr, n) }
        val calcFlow = combine(
            repo.issueDao.observeAll(),
            repo.drugDao.observeAll(),
            container.settings.settings,
        ) { i, d, s -> Triple(i, d, s) }
        combine(dbFlow, calcFlow) { db, calc ->
            val (patients, prescriptions, norms) = db
            val (issues, drugs, settings) = calc
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                buildList(patients, prescriptions, norms, issues, drugs, settings, Dates.today())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private fun buildList(
        patients: List<ru.danil.medtracker.data.db.Patient>,
        prescriptions: List<ru.danil.medtracker.data.db.Prescription>,
        norms: List<ru.danil.medtracker.data.db.Norm>,
        issues: List<ru.danil.medtracker.data.db.Issue>,
        drugs: List<ru.danil.medtracker.data.db.Drug>,
        @Suppress("UNUSED_PARAMETER") settings: ru.danil.medtracker.data.AppSettings,
        today: LocalDate,
    ): List<PatientListItem> {
        val drugById = drugs.associateBy { it.id }
        val normsByP = norms.groupBy { it.prescriptionId }
        val issuesByP = issues.groupBy { it.prescriptionId }
        return patients.map { p ->
            val results = prescriptions.filter { it.patientId == p.id }.mapNotNull { pr ->
                val drug = drugById[pr.drugId] ?: return@mapNotNull null
                val ns = normsByP[pr.id].orEmpty()
                if (CalcService.isNoCalc(ns, today)) return@mapNotNull null
                val supply = CalcService.supplyFor(issuesByP[pr.id].orEmpty(), ns, drug)
                Pair(supply.coveredUntil(), supply.outcome is ru.danil.medtracker.calc.SupplyOutcome.Unlimited)
            }
            val dated = results.mapNotNull { it.first }
            PatientListItem(
                patient = p,
                nearestUntil = dated.minOrNull(),
                unlimitedOnly = dated.isEmpty() && results.any { it.second },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientsScreen(
    onOpenPatient: (Long) -> Unit,
    onAddPatient: () -> Unit,
) {
    val vm = appViewModel { PatientsViewModel(it) }
    val items by vm.items.collectAsStateWithLifecycle()
    val threshold by vm.threshold.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var alphabetical by remember { mutableStateOf(false) }
    var showSummaryDialog by remember { mutableStateOf(false) }
    var summaryFile by remember { mutableStateOf<java.io.File?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Пациенты") },
                actions = {
                    IconButton(onClick = { showSummaryDialog = true }) {
                        Icon(Icons.Filled.Description, contentDescription = "Сводная потребность")
                    }
                    IconButton(onClick = { alphabetical = !alphabetical }) {
                        Icon(Icons.Filled.SortByAlpha, contentDescription = "Сортировка")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddPatient) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить пациента")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Поиск по ФИО") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            val filtered = items.filter { it.patient.fio.contains(query, ignoreCase = true) }
            val sorted = if (alphabetical) {
                filtered.sortedBy { it.patient.fio.lowercase() }
            } else {
                // срочные сверху; у кого даты нет — в конец (п. 5.1)
                filtered.sortedWith(
                    compareBy({ it.nearestUntil == null }, { it.nearestUntil ?: LocalDate.MAX }),
                )
            }
            if (sorted.isEmpty()) {
                if (items.isEmpty()) {
                    EmptyState("Пациентов пока нет — добавьте первого", "Добавить пациента", onAddPatient)
                } else {
                    EmptyState("Ничего не найдено")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sorted, key = { it.patient.id }) { item ->
                        PatientRow(item, threshold, onClick = { onOpenPatient(item.patient.id) })
                    }
                }
            }
        }
    }

    if (showSummaryDialog) {
        SummaryDialog(
            onGenerated = { file ->
                showSummaryDialog = false
                ru.danil.medtracker.docx.DocxShare.saveViaSaf(context, file)
            },
            onDismiss = { showSummaryDialog = false },
        )
    }
}

@Composable
private fun PatientRow(item: PatientListItem, threshold: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.patient.fio, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    Dates.ageString(Dates.fromEpochDay(item.patient.birthDate)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val level = CalcService.level(item.nearestUntil, threshold)
            SupplyBadge(
                until = item.nearestUntil,
                level = if (item.unlimitedOnly) Level.GREEN else level,
            )
        }
    }
}

/** Диалог «Сводная потребность» (п. 8.3): выбор периода и генерация Word. */
@Composable
private fun SummaryDialog(
    onGenerated: (java.io.File) -> Unit,
    onDismiss: () -> Unit,
) {
    val vm = appViewModel { SummaryViewModel(it) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val months = (0..5).map { java.time.YearMonth.now().plusMonths(it.toLong()) }
    var selected by remember { mutableStateOf(months[1]) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сводная потребность") },
        text = {
            Column {
                Text("Период:")
                months.forEach { m ->
                    androidx.compose.material3.FilterChip(
                        selected = selected == m,
                        onClick = { selected = m },
                        label = {
                            val monthNames = listOf("январь","февраль","март","апрель","май","июнь","июль","август","сентябрь","октябрь","ноябрь","декабрь")
                            Text("${monthNames[m.monthValue - 1]} ${m.year}")
                        },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { vm.generate(context, selected) }
                            .onSuccess { onGenerated(it) }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
            ) { Text(if (busy) "…" else "Создать") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

class SummaryViewModel(private val container: ru.danil.medtracker.data.AppContainer) : androidx.lifecycle.ViewModel() {
    suspend fun generate(context: android.content.Context, period: java.time.YearMonth): java.io.File {
        val repo = container.repo
        val input = ru.danil.medtracker.docx.SummaryDoc.Input(
            patients = repo.patientDao.all(),
            prescriptions = repo.prescriptionDao.allActive(),
            drugs = repo.drugDao.all(),
            norms = repo.normDao.all(),
            issues = repo.issueDao.all(),
        )
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ru.danil.medtracker.docx.SummaryDoc.build(input, period)
        }
        val dir = java.io.File(context.cacheDir, "docs").apply { mkdirs() }
        val monthNames = listOf("01","02","03","04","05","06","07","08","09","10","11","12")
        val file = java.io.File(dir, "Сводная_потребность_${monthNames[period.monthValue - 1]}.${period.year % 100}.docx")
        file.writeBytes(bytes)
        return file
    }
}
