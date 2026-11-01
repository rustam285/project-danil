package ru.danil.medtracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import ru.danil.medtracker.data.AnalysisDue
import ru.danil.medtracker.data.AnalysisLogic
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.Diabetes
import ru.danil.medtracker.data.db.AnalysisRecord
import ru.danil.medtracker.data.db.AnalysisType
import ru.danil.medtracker.ui.DatePickerField
import ru.danil.medtracker.ui.EmptyState
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/** Вкладка «Анализы» (Ф3.4): пациенты-диабетики, статусы сроков, быстрая сдача. */
class AnalysesViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repo

    data class AnalysisRow(
        val type: AnalysisType,
        val due: AnalysisDue,
        val lastRecord: AnalysisRecord?,
    )

    data class PatientBlock(val patient: ru.danil.medtracker.data.db.Patient, val rows: List<AnalysisRow>) {
        val hasDue: Boolean get() = rows.any { it.due is AnalysisDue.Due || it.due is AnalysisDue.NoData }
    }

    data class State(
        val blocks: List<PatientBlock> = emptyList(),
        val types: List<AnalysisType> = emptyList(),
    )

    val state = combine(
        repo.patientDao.observeActive(),
        repo.analysisTypeDao.observeAll(),
        repo.analysisRecordDao.observeAll(),
    ) { patients, types, records ->
        withContext(Dispatchers.Default) {
            val today = Dates.today()
            val diabetics = patients.filter { Diabetes.isDiabetic(it) }
                .sortedBy { it.fio.lowercase() }
            val blocks = diabetics.map { p ->
                val mine = records.filter { it.patientId == p.id }
                PatientBlock(
                    patient = p,
                    rows = types.map { t ->
                        val of = mine.filter { it.typeId == t.id }
                        AnalysisRow(t, AnalysisLogic.statusFor(mine, t, today), of.maxByOrNull { it.date })
                    },
                )
            }
            State(blocks, types)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), State())

    fun saveRecord(existing: AnalysisRecord?, patientId: Long, typeId: Long, dateEpoch: Long, value: Double, note: String?) {
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            val rec = (existing ?: AnalysisRecord(patientId = patientId, typeId = typeId, date = dateEpoch, value = value))
                .copy(patientId = patientId, typeId = typeId, date = dateEpoch, value = value,
                    note = note?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = t)
            if (existing == null) repo.analysisRecordDao.insert(rec.copy(createdAt = t))
            else repo.analysisRecordDao.update(rec)
        }
    }

    fun deleteRecord(id: Long) {
        viewModelScope.launch { repo.analysisRecordDao.delete(id) }
    }

    fun saveType(existing: AnalysisType?, name: String, unit: String?, periodMonths: Int) {
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            val type = (existing ?: AnalysisType(name = name)).copy(
                name = name.trim(),
                unit = unit?.trim()?.takeIf { it.isNotEmpty() },
                periodMonths = periodMonths.coerceAtLeast(1),
                updatedAt = t,
            )
            if (existing == null) repo.analysisTypeDao.insert(type.copy(createdAt = t))
            else repo.analysisTypeDao.update(type)
        }
    }

    /** Удаление вида: встроенный нельзя; записи каскадом после подтверждения (Ф3.3). */
    fun deleteType(type: AnalysisType, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.coreDao.deleteAnalysisTypeCascade(repo.analysisTypeDao, repo.analysisRecordDao, type.id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysesScreen(onOpenPatient: (Long) -> Unit) {
    val vm = appViewModel { AnalysesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    var onlyDue by remember { mutableStateOf(false) }
    var showTypesManager by remember { mutableStateOf(false) }
    /** [patientId] + предвыбранный вид для диалога сдачи */
    var recordTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var editRecord by remember { mutableStateOf<AnalysisRecord?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Анализы") },
                actions = {
                    TextButton(onClick = { showTypesManager = true }) { Text("Виды") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = !onlyDue, onClick = { onlyDue = false }, label = { Text("Все") })
                FilterChip(selected = onlyDue, onClick = { onlyDue = true }, label = { Text("Пора сдавать") })
            }
            val blocks = if (onlyDue) state.blocks.filter { it.hasDue } else state.blocks
            if (blocks.isEmpty()) {
                if (onlyDue) {
                    EmptyState("Просроченных сдач нет — всё в срок")
                } else {
                    Column {
                        EmptyState("Пациентов с диабетом нет")
                        Text(
                            "Во вкладке попадают пациенты с МКБ E10/E11 или пометкой «Диабет» в карточке",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(blocks.size) { idx ->
                        val b = blocks[idx]
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        b.patient.fio,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onOpenPatient(b.patient.id) },
                                    )
                                    TextButton(onClick = { recordTarget = b.patient.id to (b.rows.firstOrNull { it.due !is AnalysisDue.InTime }?.type?.id ?: b.rows.firstOrNull()?.type?.id ?: -1L) }) {
                                        Text("Отметить сдачу")
                                    }
                                }
                                b.rows.forEach { row ->
                                    AnalysisStatusRow(row, onEdit = { rec -> editRecord = rec })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    recordTarget?.let { (patientId, typeId) ->
        AnalysisRecordDialog(
            vm = vm,
            patientId = patientId,
            initialTypeId = typeId.takeIf { it > 0 },
            existing = null,
            onDismiss = { recordTarget = null },
        )
    }
    editRecord?.let { rec ->
        AnalysisRecordDialog(
            vm = vm,
            patientId = rec.patientId,
            initialTypeId = rec.typeId,
            existing = rec,
            onDismiss = { editRecord = null },
        )
    }
    if (showTypesManager) {
        AnalysisTypesManagerDialog(vm = vm, onDismiss = { showTypesManager = false })
    }
}

@Composable
private fun AnalysisStatusRow(row: AnalysesViewModel.AnalysisRow, onEdit: (AnalysisRecord) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = row.lastRecord != null) { row.lastRecord?.let { onEdit(it) } }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.type.name, style = MaterialTheme.typography.bodyMedium)
            val last = row.lastRecord
            if (last != null) {
                Text(
                    "последний ${formatValue(last.value)}${row.type.unit?.let { " $it" } ?: ""} · ${Dates.formatRu(last.date)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val (text, color) = when (val d = row.due) {
            is AnalysisDue.NoData -> "нет данных" to MaterialTheme.colorScheme.onSurfaceVariant
            is AnalysisDue.Due ->
                if (d.overdueDays > 0) "пора сдавать · просрочено на ${d.overdueDays} ${Dates.plural(d.overdueDays.toInt(), "день", "дня", "дней")}" to MaterialTheme.colorScheme.error
                else "пора сдавать" to Color(0xFFF9A825)
            is AnalysisDue.InTime -> "в срок · до ${Dates.formatRu(d.dueDate)}" to Color(0xFF2E7D32)
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Форматирование значения без лишних нулей: 7.0 → «7», 7.4 → «7,4». */
internal fun formatValue(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v).replace('.', ',')

/**
 * Форма записи анализа (Ф3.3): вид, дата (будущая блокируется), значение, комментарий.
 * Используется со вкладки «Анализы» и из секции «Анализы» карточки пациента.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisRecordDialog(
    vm: AnalysesViewModel,
    patientId: Long,
    initialTypeId: Long?,
    existing: AnalysisRecord?,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val types = state.types
    val today = Dates.today()

    var typeId by remember(existing) { mutableStateOf(existing?.typeId ?: initialTypeId ?: types.firstOrNull()?.id) }
    var date by remember(existing) { mutableStateOf<LocalDate?>(existing?.let { Dates.fromEpochDay(it.date) } ?: today) }
    var value by remember(existing) { mutableStateOf(existing?.let { formatValue(it.value) } ?: "") }
    var note by remember(existing) { mutableStateOf(existing?.note ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    val parsedValue = value.trim().replace(',', '.').toDoubleOrNull()
    val future = date != null && date!!.isAfter(today)
    val valid = typeId != null && date != null && !future && parsedValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Новая запись анализа" else "Запись анализа") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Вид анализа", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t ->
                        FilterChip(
                            selected = typeId == t.id,
                            onClick = { typeId = t.id },
                            label = { Text(t.name) },
                        )
                    }
                }
                DatePickerField("Дата сдачи *", date, { date = it }, Modifier.fillMaxWidth().padding(top = 8.dp))
                if (future) {
                    Text(
                        "Дата не может быть в будущем",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val unit = types.firstOrNull { it.id == typeId }?.unit
                OutlinedTextField(
                    value,
                    { v -> value = v.filter { it.isDigit() || it == ',' || it == '.' }.take(8) },
                    label = { Text("Результат${unit?.let { ", $it" } ?: ""} *") },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    note, { note = it },
                    label = { Text("Комментарий") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val d = date
                    val t = typeId
                    if (d != null && t != null && parsedValue != null) {
                        vm.saveRecord(existing, patientId, t, Dates.toEpochDay(d), parsedValue, note)
                        onDismiss()
                    }
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить запись?") },
            text = { Text("Запись от ${existing.date.let { Dates.formatRu(it) }} будет удалена безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRecord(existing.id)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

/** Управление видами анализов (Ф3.2): период правится у всех, удаление — только для своих. */
@Composable
private fun AnalysisTypesManagerDialog(vm: AnalysesViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editType by remember { mutableStateOf<AnalysisType?>(null) }
    var createNew by remember { mutableStateOf(false) }
    var confirmDeleteType by remember { mutableStateOf<AnalysisType?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Виды анализов") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.types.forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "раз в ${t.periodMonths} ${Dates.plural(t.periodMonths, "месяц", "месяца", "месяцев")}" +
                                    (t.unit?.let { " · $it" } ?: "") +
                                    if (t.isBuiltIn) " · встроенный" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { editType = t }) { Icon(Icons.Filled.Edit, "Изменить период") }
                        if (!t.isBuiltIn) {
                            TextButton(onClick = { confirmDeleteType = t }) {
                                Text("Удалить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { createNew = true }) { Text("+ Свой вид") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )

    editType?.let { t ->
        AnalysisTypeEditDialog(
            initial = t,
            allowNameEdit = !t.isBuiltIn,
            onSave = { name, unit, period ->
                vm.saveType(t, name, unit, period)
                editType = null
            },
            onDismiss = { editType = null },
        )
    }
    if (createNew) {
        AnalysisTypeEditDialog(
            initial = null,
            allowNameEdit = true,
            onSave = { name, unit, period ->
                vm.saveType(null, name, unit, period)
                createNew = false
            },
            onDismiss = { createNew = false },
        )
    }
    confirmDeleteType?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDeleteType = null },
            title = { Text("Удалить вид?") },
            text = { Text("«${t.name}» и все его записи будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteType(t) { }
                    confirmDeleteType = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteType = null }) { Text("Отмена") } },
        )
    }
}

/** Создание/правка вида: название (кроме встроенного), единица, период ≥ 1 месяц. */
@Composable
private fun AnalysisTypeEditDialog(
    initial: AnalysisType?,
    allowNameEdit: Boolean,
    onSave: (name: String, unit: String?, periodMonths: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var unit by remember { mutableStateOf(initial?.unit ?: "") }
    var period by remember { mutableStateOf((initial?.periodMonths ?: 1).toString()) }
    val periodNum = period.toIntOrNull()
    val valid = name.isNotBlank() && periodNum != null && periodNum >= 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новый вид анализа" else "Вид анализа") },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("Название *") }, singleLine = true,
                    enabled = allowNameEdit,
                )
                OutlinedTextField(
                    unit, { unit = it },
                    label = { Text("Единица измерения (необязательно)") }, singleLine = true,
                )
                OutlinedTextField(
                    period, { p -> period = p.filter { it.isDigit() }.take(3) },
                    label = { Text("Периодичность, месяцев *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(name, unit, periodNum!!) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
