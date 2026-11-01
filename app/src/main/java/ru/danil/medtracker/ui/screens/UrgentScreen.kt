package ru.danil.medtracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.QuickIssue
import ru.danil.medtracker.data.UrgentItem
import ru.danil.medtracker.data.UrgentKind
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.ui.DatePickerField
import ru.danil.medtracker.ui.EmptyState
import ru.danil.medtracker.ui.IntField
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

class UrgentViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.repo

    val state = run {
        val dbFlow = combine(
            repo.patientDao.observeActive(),
            repo.prescriptionDao.observeAllActive(),
            repo.normDao.observeAll(),
        ) { p, pr, n -> Triple(p, pr, n) }
        val calcFlow = combine(
            repo.issueDao.observeAll(),
            repo.drugDao.observeAll(),
            repo.rxDao.observeAllIssued(),
        ) { i, d, r -> Triple(i, d, r) }
        combine(dbFlow, calcFlow, container.settings.settings) { db, calc, settings ->
            val (patients, prescriptions, norms) = db
            val (issues, drugs, rxs) = calc
            withContext(Dispatchers.Default) {
                ru.danil.medtracker.data.UrgentList.build(
                    patients, prescriptions, norms, issues, drugs, rxs, settings, Dates.today(),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ru.danil.medtracker.data.UrgentState())
    }

    /** «В ожидании поступления»: включить/снять статус вручную. */
    fun setAwaiting(prescriptionId: Long, awaiting: Boolean) {
        viewModelScope.launch { repo.setAwaitingRestock(prescriptionId, awaiting) }
    }

    /** Экспорт «Срочного» в Word (Ф7): тот же вид, что на экране. */
    fun buildDoc(
        context: android.content.Context,
        state: ru.danil.medtracker.data.UrgentState,
        onReady: (java.io.File) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val bytes = ru.danil.medtracker.docx.UrgentDoc.build(state, Dates.today())
                    val dir = java.io.File(context.cacheDir, "docs").apply { mkdirs() }
                    val file = java.io.File(dir, "Срочная_выдача_${Dates.formatIso(Dates.today())}.docx")
                    file.writeBytes(bytes)
                    file
                }
            }.onSuccess { onReady(it) }
                .onFailure { onError(it.message ?: "не удалось создать документ") }
        }
    }

    // ── Быстрая выдача (Ф6) ────────────────────────────────────────────────────

    /** Исходные данные упрощённого диалога: подсказка количества и размер упаковки. */
    data class QuickIssueData(
        val drug: Drug?,
        val defaultPieces: Int?,
        val packSize: Int,
    )

    fun prepareQuickIssue(prescriptionId: Long, onReady: (QuickIssueData) -> Unit) {
        viewModelScope.launch {
            val p = repo.prescriptionDao.byId(prescriptionId) ?: return@launch
            val drug = repo.drugDao.byId(p.drugId)
            val norms = repo.normDao.forPrescription(prescriptionId)
            val last = repo.issueDao.forPrescription(prescriptionId).maxByOrNull { it.date }
            val def = withContext(Dispatchers.Default) {
                QuickIssue.defaultPieces(norms, Dates.today(), last)
            }
            onReady(QuickIssueData(drug, def, drug?.piecesPerPack ?: 1))
        }
    }

    /**
     * Сохраняет выдачу из упрощённого диалога. [onDone] получает пару
     * («обеспечен до» или null при неограниченном запасе, признак успеха).
     */
    fun quickIssue(prescriptionId: Long, date: LocalDate, pieces: Int, onDone: (LocalDate?, Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val p = repo.prescriptionDao.byId(prescriptionId) ?: error("Назначение не найдено")
                val drug = repo.drugDao.byId(p.drugId) ?: error("Препарат не найден")
                val packSize = drug.piecesPerPack
                repo.saveIssue(
                    existing = null,
                    prescriptionId = prescriptionId,
                    date = Dates.toEpochDay(date),
                    packs = pieces / packSize,
                    pieces = pieces % packSize,
                    note = null,
                    rxId = null,
                    piecesPerPack = packSize,
                )
                val issues = repo.issueDao.forPrescription(prescriptionId)
                val norms = repo.normDao.forPrescription(prescriptionId)
                val res = withContext(Dispatchers.Default) {
                    ru.danil.medtracker.calc.CalcService.supplyFor(issues, norms, drug)
                }
                onDone(res.coveredUntil(), true)
            } catch (e: Exception) {
                onDone(null, false)
            }
        }
    }
}

private val WaitingColor = Color(0xFF00796B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrgentScreen(
    onOpenPrescription: (Long) -> Unit,
    onOpenRx: (Long, Long) -> Unit,
    /** Полная форма выдачи из упрощённого диалога (Ф6.2 «Подробнее») */
    onOpenIssueForm: (Long) -> Unit = {},
) {
    val vm = appViewModel { UrgentViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var quickIssueFor by remember { mutableStateOf<UrgentItem?>(null) }
    var docFile by remember { mutableStateOf<java.io.File?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Срочное") }) },
    ) { padding ->
        val isEmpty = state.ended.isEmpty() && state.waiting.isEmpty() &&
            state.low.isEmpty() && state.rx.isEmpty()
        if (isEmpty) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                EmptyState("Всё под контролем")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
            ) {
                // Экспорт «Срочного» в Word (Ф7.1)
                item {
                    OutlinedButton(
                        onClick = {
                            vm.buildDoc(
                                context, state,
                                onReady = { docFile = it },
                                onError = {
                                    android.widget.Toast.makeText(context, "Не удалось создать документ: $it", android.widget.Toast.LENGTH_LONG).show()
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Description, null,
                            Modifier.padding(end = 6.dp),
                        )
                        Text("Документ Word")
                    }
                }
                if (state.ended.isNotEmpty()) {
                    item { SectionHeader("Закончилось") }
                    items(state.ended.size) { idx ->
                        UrgentRow(state.ended[idx], onOpenPrescription, onOpenRx, { id, awaiting ->
                            vm.setAwaiting(id, awaiting)
                        }) { quickIssueFor = it }
                    }
                }
                if (state.waiting.isNotEmpty()) {
                    item { SectionHeader("В ожидании поступления") }
                    items(state.waiting.size) { idx ->
                        UrgentRow(state.waiting[idx], onOpenPrescription, onOpenRx, { id, awaiting ->
                            vm.setAwaiting(id, awaiting)
                        }) { quickIssueFor = it }
                    }
                }
                if (state.low.isNotEmpty()) {
                    item { SectionHeader("Осталось мало") }
                    items(state.low.size) { idx ->
                        UrgentRow(state.low[idx], onOpenPrescription, onOpenRx, { id, awaiting ->
                            vm.setAwaiting(id, awaiting)
                        }) { quickIssueFor = it }
                    }
                }
                if (state.rx.isNotEmpty()) {
                    item { SectionHeader("Рецепты") }
                    items(state.rx.size) { idx ->
                        UrgentRow(state.rx[idx], onOpenPrescription, onOpenRx, { id, awaiting ->
                            vm.setAwaiting(id, awaiting)
                        }) { quickIssueFor = it }
                    }
                }
            }
        }
    }

    quickIssueFor?.let { item ->
        QuickIssueDialog(
            vm = vm,
            item = item,
            onDismiss = { quickIssueFor = null },
            onOpenFull = { pid ->
                quickIssueFor = null
                onOpenIssueForm(pid)
            },
        )
    }

    // «Документ готов» → Сохранить/Поделиться (Ф7.2, по образцу «Плана ДН»)
    docFile?.let { file ->
        AlertDialog(
            onDismissRequest = { docFile = null },
            title = { Text("Документ готов") },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    ru.danil.medtracker.docx.DocxShare.share(context, file)
                    docFile = null
                }) { Text("Поделиться") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ru.danil.medtracker.docx.DocxShare.saveViaSaf(context, file)
                    docFile = null
                }) { Text("Сохранить") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun UrgentRow(
    item: UrgentItem,
    onOpenPrescription: (Long) -> Unit,
    onOpenRx: (Long, Long) -> Unit,
    onToggleWaiting: (Long, Boolean) -> Unit,
    onQuickIssue: (UrgentItem) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable {
                if (item.kind == UrgentKind.RX) {
                    item.rx?.let { onOpenRx(item.patient.id, it.id) }
                } else {
                    item.prescription?.let { onOpenPrescription(it.id) }
                }
            },
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.patient.fio, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when (item.kind) {
                            UrgentKind.RX -> item.drug?.name ?: "Рецепт ${item.rx?.series ?: ""} ${item.rx?.number ?: ""}"
                            else -> item.drug?.name ?: ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    item.date?.let { Dates.formatRu(it) } ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = when (item.kind) {
                        UrgentKind.ENDED -> MaterialTheme.colorScheme.error
                        UrgentKind.WAITING -> WaitingColor
                        else -> Color(0xFFF9A825)
                    },
                )
            }
            when (item.kind) {
                UrgentKind.ENDED -> item.prescription?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onQuickIssue(item) }) { Text("Выдать") }
                        TextButton(onClick = { onToggleWaiting(p.id, true) }) {
                            Text("В ожидании поступления")
                        }
                    }
                }
                UrgentKind.LOW -> item.prescription?.let { _ ->
                    Button(onClick = { onQuickIssue(item) }) { Text("Выдать") }
                }
                UrgentKind.WAITING -> item.prescription?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "ожидается поставка",
                            style = MaterialTheme.typography.labelMedium,
                            color = WaitingColor,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onToggleWaiting(p.id, false) }) { Text("Снять") }
                    }
                }
                else -> {}
            }
        }
    }
}

/**
 * Упрощённый диалог быстрой выдачи (Ф6.1): пациент/позиция уже известны,
 * дата = сегодня, количество по умолчанию — месячная норма (или последняя выдача).
 */
@Composable
private fun QuickIssueDialog(
    vm: UrgentViewModel,
    item: UrgentItem,
    onDismiss: () -> Unit,
    onOpenFull: (Long) -> Unit,
) {
    val prescriptionId = item.prescription?.id ?: return
    var data by remember { mutableStateOf<UrgentViewModel.QuickIssueData?>(null) }
    var date by remember { mutableStateOf(Dates.today()) }
    var pieces by remember { mutableStateOf<Int?>(null) }
    /** результат сохранения: null = ещё не сохраняли; пара (дата или null=не ограничен, успех) */
    var saveResult by remember { mutableStateOf<Pair<LocalDate?, Boolean>?>(null) }

    LaunchedEffect(prescriptionId) {
        vm.prepareQuickIssue(prescriptionId) { d ->
            data = d
            if (pieces == null) pieces = d.defaultPieces
        }
    }

    val today = Dates.today()
    val dateInFuture = date.isAfter(today)
    val packSize = data?.packSize ?: 1
    val qty = pieces ?: 0
    val done = saveResult != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Быстрая выдача") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.patient.fio, fontWeight = FontWeight.SemiBold)
                Text(item.drug?.name ?: "", style = MaterialTheme.typography.bodyMedium)
                if (done) {
                    // результат сохранения (Ф6.2): строка уходит из «Срочного» сама
                    val res = saveResult
                    Text(
                        when {
                            res?.second == false -> "Не удалось сохранить выдачу"
                            res?.first != null -> "Выдача сохранена. Обеспечен до ${Dates.formatRu(res.first!!)}"
                            else -> "Выдача сохранена. Запас не ограничен"
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = if (res?.second == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                } else {
                    DatePickerField("Дата выдачи", date, { date = it ?: today }, Modifier.fillMaxWidth())
                    if (dateInFuture) {
                        Text("Дата выдачи не может быть в будущем", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    IntField("Количество, шт", qty.takeIf { it > 0 }, { pieces = it }, Modifier.fillMaxWidth())
                    if (qty > 0 && packSize > 1) {
                        Text(
                            "= ${qty / packSize} уп × $packSize" + if (qty % packSize > 0) " + ${qty % packSize}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("Готово") }
            } else {
                Button(
                    onClick = {
                        vm.quickIssue(prescriptionId, date, qty) { until, ok ->
                            saveResult = until to ok
                        }
                    },
                    enabled = qty > 0 && !dateInFuture,
                ) { Text("Выдать") }
            }
        },
        dismissButton = {
            if (!done) {
                TextButton(onClick = { onOpenFull(prescriptionId) }) { Text("Подробнее") }
            }
        },
    )
}
