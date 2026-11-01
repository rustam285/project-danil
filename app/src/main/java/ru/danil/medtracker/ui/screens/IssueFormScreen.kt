package ru.danil.medtracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.calc.IssuePreview
import ru.danil.medtracker.calc.SupplyEngine
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.ui.DatePickerField
import ru.danil.medtracker.ui.IntField
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

class IssueFormViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repo

    data class State(
        val prescription: ru.danil.medtracker.data.db.Prescription? = null,
        val drug: ru.danil.medtracker.data.db.Drug? = null,
        val existing: ru.danil.medtracker.data.db.Issue? = null,
        val rxs: List<ru.danil.medtracker.data.db.Rx> = emptyList(),
        /** Нормы и история выдач — для предпросмотра «обеспечен до» (Ф10) */
        val norms: List<ru.danil.medtracker.data.db.Norm> = emptyList(),
        val issues: List<ru.danil.medtracker.data.db.Issue> = emptyList(),
        /** Порог жёлтой зоны — цвет предпросмотра при дефиците (Ф10.2) */
        val thresholdDays: Int = 7,
    )

    val state = MutableStateFlow(State())
    val savedWithDate = MutableStateFlow<LocalDate?>(null)
    val deleted = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun load(prescriptionId: Long, issueId: Long?) {
        viewModelScope.launch {
            val p = repo.prescriptionDao.byId(prescriptionId)
            val drug = p?.let { repo.drugDao.byId(it.drugId) }
            val existing = issueId?.let { repo.issueDao.byId(it) }
            val norms = p?.let { repo.normDao.forPrescription(it.id) } ?: emptyList()
            val issues = p?.let { repo.issueDao.forPrescription(it.id) } ?: emptyList()
            val threshold = container.settings.current().drugThresholdDays
            state.value = State(
                p, drug, existing,
                p?.let { repo.rxDao.all().filter { r -> r.patientId == it.patientId } } ?: emptyList(),
                norms, issues, threshold,
            )
        }
    }

    /** Сохранение выдачи + пересчитанная дата «обеспечен до» (п. 5.5). */
    fun save(
        prescriptionId: Long,
        date: LocalDate,
        packs: Int,
        pieces: Int,
        note: String?,
        rxId: Long?,
    ) {
        viewModelScope.launch {
            try {
                val p = repo.prescriptionDao.byId(prescriptionId) ?: error("Назначение не найдено")
                val drug = repo.drugDao.byId(p.drugId) ?: error("Препарат не найден")
                val saved = repo.saveIssue(
                    existing = state.value.existing,
                    prescriptionId = prescriptionId,
                    date = Dates.toEpochDay(date),
                    packs = packs,
                    pieces = pieces,
                    note = note,
                    rxId = rxId,
                    piecesPerPack = drug.piecesPerPack,
                )
                // Перевод рецепта в «отоварен» при привязке (п. 3.5)
                if (rxId != null && state.value.existing?.rxId != rxId) {
                    repo.rxDao.byId(rxId)?.let { rx ->
                        if (rx.status == "ISSUED") {
                            repo.rxDao.update(rx.copy(status = "REDEEMED", updatedAt = System.currentTimeMillis()))
                        }
                    }
                }
                // Пересчёт даты для подтверждения
                val issues = repo.issueDao.forPrescription(prescriptionId)
                val norms = repo.normDao.forPrescription(prescriptionId)
                val res = withContext(Dispatchers.Default) {
                    CalcService.supplyFor(issues, norms, drug)
                }
                savedWithDate.value = res.coveredUntil()
            } catch (e: Exception) {
                error.value = e.message ?: "Не удалось сохранить"
            }
        }
    }

    fun delete(issueId: Long) {
        viewModelScope.launch {
            repo.issueDao.delete(issueId)
            repo.tombstoneDao.insert(
                ru.danil.medtracker.data.db.Tombstone(entityType = "issue", entityId = issueId, deletedAt = System.currentTimeMillis()),
            )
            deleted.value = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueFormScreen(
    prescriptionId: Long,
    issueId: Long?,
    defaultDate: Long? = null,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val vm = appViewModel { IssueFormViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val savedDate by vm.savedWithDate.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    LaunchedEffect(prescriptionId, issueId) { vm.load(prescriptionId, issueId) }
    LaunchedEffect(deleted) { if (deleted) onSaved() }

    val existing = state.existing
    val drug = state.drug

    // Дата по умолчанию: из сценария «Выдать» из скан-потока — дата рецепта (Ф1.4);
    // будущая дата не допускается (Ф1.5, по образцу даты рождения)
    val initialDate = existing?.let { Dates.fromEpochDay(it.date) }
        ?: defaultDate?.let { Dates.fromEpochDay(it) }?.takeIf { !it.isAfter(Dates.today()) }
        ?: Dates.today()
    var date by remember(existing) { mutableStateOf(initialDate) }
    var packs by remember(existing) { mutableStateOf(existing?.enteredPacks?.takeIf { it > 0 }?.toString() ?: "") }
    var pieces by remember(existing) { mutableStateOf(existing?.enteredPieces?.takeIf { it > 0 }?.toString() ?: "") }
    var note by remember(existing) { mutableStateOf(existing?.note ?: "") }
    var rxId by remember(existing) { mutableStateOf<Long?>(existing?.rxId) }

    val today = Dates.today()
    val dateInFuture = date.isAfter(today)

    val packSize = drug?.piecesPerPack ?: 1
    val totalPieces = (packs.toIntOrNull() ?: 0) * packSize + (pieces.toIntOrNull() ?: 0)
    val mePerPiece = drug?.mePerPiece()
    val confirmDialog = savedDate != null

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (existing == null) "Выдача" else "Изменить выдачу") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            drug?.let {
                Text(it.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(CalcService.drugForm(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DatePickerField("Дата выдачи", date, { date = it ?: Dates.today() }, Modifier.fillMaxWidth().padding(top = 8.dp))
            if (dateInFuture) {
                Text(
                    "Дата выдачи не может быть в будущем",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("Упаковки", packs.toIntOrNull(), { packs = it?.toString() ?: "" }, Modifier.weight(1f))
                IntField("Штуки", pieces.toIntOrNull(), { pieces = it?.toString() ?: "" }, Modifier.weight(1f))
            }

            // Живой пересчёт (п. 3.4): «2 уп × 5 + 3 = 13 шт», для инсулина «13 карт × 300 МЕ = 3900 МЕ»
            if (totalPieces > 0) {
                Text(
                    buildString {
                        append("= $totalPieces шт")
                        if (packSize > 1 && (packs.toIntOrNull() ?: 0) > 0) {
                            append(" (${packs.toIntOrNull()} уп × $packSize")
                            if ((pieces.toIntOrNull() ?: 0) > 0) append(" + ${pieces.toIntOrNull()}")
                            append(")")
                        }
                        mePerPiece?.let { me ->
                            append(" · $totalPieces шт × $me МЕ = ${totalPieces * me} МЕ")
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // Предпросмотр «обеспечен до» (Ф10): что покажет движок после сохранения
            // этой выдачи; при дефиците — цвет дефицита (Ф10.2)
            if (totalPieces > 0 && !dateInFuture && !CalcService.isNoCalc(state.norms, today)) {
                val preview = remember(date, totalPieces, state.issues, state.norms, state.drug, existing?.id) {
                    IssuePreview.afterIssue(
                        issues = state.issues,
                        norms = state.norms,
                        drug = drug,
                        replaceIssueId = existing?.id,
                        newDate = date,
                        newPieces = totalPieces,
                    )
                }
                val previewUntil = preview.coveredUntil()
                val previewText = if (preview.outcome is ru.danil.medtracker.calc.SupplyOutcome.Unlimited) {
                    "После этой выдачи: запас более 5 лет"
                } else {
                    previewUntil?.let { "После этой выдачи: обеспечен до ${Dates.formatRu(it)}" }
                }
                if (previewText != null) {
                    val level = CalcService.level(previewUntil, state.thresholdDays, today)
                    Text(
                        previewText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = when (level) {
                            ru.danil.medtracker.calc.Level.RED -> MaterialTheme.colorScheme.error
                            ru.danil.medtracker.calc.Level.YELLOW -> androidx.compose.ui.graphics.Color(0xFFF9A825)
                            ru.danil.medtracker.calc.Level.GREEN -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            OutlinedTextField(note, { note = it }, label = { Text("Примечание (необязательно)") }, modifier = Modifier.fillMaxWidth())

            if (state.rxs.isNotEmpty()) {
                Text("Рецепт (необязательно)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                state.rxs.take(6).forEach { rx ->
                    androidx.compose.material3.FilterChip(
                        selected = rxId == rx.id,
                        onClick = { rxId = if (rxId == rx.id) null else rx.id },
                        label = { Text("№ ${rx.number.ifEmpty { "—" }} (${if (rx.status == "REDEEMED") "отоварен" else "выписан"})") },
                    )
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            Button(
                onClick = {
                    vm.save(prescriptionId, date, packs.toIntOrNull() ?: 0, pieces.toIntOrNull() ?: 0, note.ifBlank { null }, rxId)
                },
                enabled = totalPieces > 0 && !dateInFuture,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Сохранить") }
            if (existing != null) {
                OutlinedButton(
                    onClick = { vm.delete(existing.id) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Удалить выдачу", color = MaterialTheme.colorScheme.error) }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Отмена") }
            Spacer(Modifier.height(32.dp))
        }
    }

    // Подтверждение с новой датой после сохранения (п. 5.5)
    if (confirmDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Выдача сохранена") },
            text = {
                Text(
                    savedDate?.let { "Обеспечен до ${Dates.formatRu(it)}" } ?: "Запас не ограничен",
                    fontWeight = FontWeight.Bold,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onSaved) { Text("Понятно") }
            },
        )
    }
}
