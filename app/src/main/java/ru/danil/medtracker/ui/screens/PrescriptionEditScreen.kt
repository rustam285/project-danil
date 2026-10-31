package ru.danil.medtracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.danil.medtracker.calc.Rational
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.db.Drug
import ru.danil.medtracker.data.db.Norm
import ru.danil.medtracker.data.db.Prescription
import ru.danil.medtracker.ui.DateField
import ru.danil.medtracker.ui.IntField
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

class PrescriptionEditViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repo

    val drugs = repo.drugDao.observeActive()
    val duplicateWarning = MutableStateFlow<String?>(null)
    val saved = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val existingPrescription = MutableStateFlow<Prescription?>(null)

    /** Режим «новая норма к существующему назначению» (п. 5.4: кнопка «+ норма»). */
    fun loadPrescription(prescriptionId: Long) {
        viewModelScope.launch {
            val p = repo.prescriptionDao.byId(prescriptionId) ?: return@launch
            existingPrescription.value = p
        }
    }

    fun checkDuplicate(name: String, excludeId: Long? = null) {
        viewModelScope.launch {
            val dup = repo.findDuplicateDrug(name, excludeId)
            duplicateWarning.value = dup?.let { "Похожий препарат уже есть: «${it.name}». Сохранить всё равно?" }
        }
    }

    suspend fun createDrug(drug: Drug): Drug {
        val t = System.currentTimeMillis()
        val id = repo.drugDao.insert(drug.copy(updatedAt = t))
        return drug.copy(id = id, updatedAt = t)
    }

    fun save(
        existingPrescription: Prescription?,
        patientId: Long,
        drug: Drug,
        normDraft: NormDraft,
        initialBalance: InitialBalanceDraft?,
    ) {
        viewModelScope.launch {
            try {
                val t = System.currentTimeMillis()
                val prescription = existingPrescription
                    ?: Prescription(patientId = patientId, drugId = drug.id, createdAt = t, updatedAt = t)
                val pid = if (existingPrescription == null) {
                    repo.prescriptionDao.insert(prescription)
                } else {
                    repo.prescriptionDao.update(prescription.copy(drugId = drug.id, updatedAt = t))
                    existingPrescription.id
                }
                if (normDraft.isValid) {
                    repo.normDao.insert(normDraft.toNorm(pid, t))
                }
                if (initialBalance != null && initialBalance.hasData) {
                    val norms = repo.normDao.forPrescription(pid)
                    repo.saveInitialBalance(
                        prescriptionId = pid,
                        date = initialBalance.dateEpoch ?: Dates.toEpochDay(Dates.today()),
                        shownUntil = initialBalance.shownUntilEpoch,
                        remainderPieces = initialBalance.remainderPieces,
                        norms = norms,
                        drug = drug,
                    )
                }
                saved.value = true
            } catch (e: Exception) {
                error.value = e.message ?: "Не удалось сохранить"
            }
        }
    }
}

data class NormDraft(
    var validFrom: LocalDate = LocalDate.now(),
    var kind: NormKind = NormKind.PER_DAY,
    var amount: Int? = null,
    var everyXDays: Int? = null,
    var meAmount: Int? = null,
    var noCalc: Boolean = false,
) {
    enum class NormKind { PER_DAY, PER_MONTH, PER_YEAR, EVERY_X_DAYS, ME, NO_CALC }

    val isValid: Boolean
        get() = when (kind) {
            NormKind.NO_CALC -> true
            NormKind.ME -> (meAmount ?: 0) > 0
            NormKind.EVERY_X_DAYS -> (amount ?: 0) > 0 && (everyXDays ?: 0) > 0
            else -> (amount ?: 0) > 0
        }

    fun toNorm(prescriptionId: Long, t: Long): Norm = Norm(
        prescriptionId = prescriptionId,
        validFrom = Dates.toEpochDay(validFrom),
        pieceKind = when (kind) {
            NormKind.PER_DAY -> "PER_DAY"
            NormKind.PER_MONTH -> "PER_MONTH"
            NormKind.PER_YEAR -> "PER_YEAR"
            NormKind.EVERY_X_DAYS -> "EVERY_X_DAYS"
            else -> null
        },
        pieceAmount = when (kind) {
            NormKind.ME, NormKind.NO_CALC -> null
            else -> amount
        },
        everyXDays = if (kind == NormKind.EVERY_X_DAYS) everyXDays else null,
        meAmount = if (kind == NormKind.ME) meAmount else null,
        noCalc = kind == NormKind.NO_CALC,
        createdAt = t,
        updatedAt = t,
    )
}

data class InitialBalanceDraft(
    var dateEpoch: Long? = null,
    var shownUntilEpoch: Long? = null,
    var remainderPieces: Int? = null,
) {
    val hasData: Boolean get() = shownUntilEpoch != null || (remainderPieces ?: 0) > 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrescriptionEditScreen(
    patientId: Long,
    prescriptionId: Long?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val vm = appViewModel { PrescriptionEditViewModel(it) }
    val drugs by vm.drugs.collectAsStateWithLifecycle()
    val duplicateWarning by vm.duplicateWarning.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val existingPrescription by vm.existingPrescription.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) onSaved() }
    LaunchedEffect(prescriptionId) { prescriptionId?.let { vm.loadPrescription(it) } }

    var selectedDrug by remember(existingPrescription) {
        mutableStateOf<Drug?>(null)
    }
    // предзаполняем препарат существующего назначения
    LaunchedEffect(existingPrescription, drugs) {
        val p = existingPrescription
        if (p != null && selectedDrug == null) {
            selectedDrug = drugs.firstOrNull { it.id == p.drugId }
        }
    }
    var showDrugDialog by remember { mutableStateOf(false) }
    var showDrugCreate by remember { mutableStateOf(false) }
    var norm by remember { mutableStateOf(NormDraft()) }
    var initialBalance by remember { mutableStateOf(InitialBalanceDraft()) }
    var showInitialBalance by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val mePreview = selectedDrug?.mePerPiece()?.let { mePerPiece ->
        if (norm.kind == NormDraft.NormKind.ME && (norm.meAmount ?: 0) > 0) {
            val rate = Rational.of(norm.meAmount!!.toLong(), mePerPiece.toLong())
            val days = Rational.ONE.div(rate).truncate().toLong()
            "≈ ${norm.meAmount} ЕД/сутки → 1 шт на $days дн"
        } else null
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (prescriptionId == null) "Новое назначение" else "Новая норма") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Препарат", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = selectedDrug?.let { "${it.name} — ${ru.danil.medtracker.calc.CalcService.drugForm(it)}" } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(if (selectedDrug == null) "Выберите или создайте" else "Препарат") },
                trailingIcon = { TextButton(onClick = { showDrugDialog = true }) { Text("Выбрать") } },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Норма выдачи", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            DateField("Действует с", norm.validFrom, { norm = norm.copy(validFrom = it ?: LocalDate.now()) }, Modifier.fillMaxWidth())

            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                NormDraft.NormKind.EVERY_X_DAYS -> Row(Modifier.padding(top = 8.dp)) {
                    IntField("Кол-во, шт", norm.amount, { norm = norm.copy(amount = it) }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    IntField("Период, дней", norm.everyXDays, { norm = norm.copy(everyXDays = it) }, Modifier.weight(1f))
                }
                NormDraft.NormKind.ME -> {
                    IntField("МЕ (ЕД) в сутки", norm.meAmount, { norm = norm.copy(meAmount = it) }, Modifier.fillMaxWidth())
                    if (selectedDrug?.mePerPiece() == null) {
                        Text(
                            "Для МЕ-нормы задайте препарату концентрацию МЕ/мл и объём",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                NormDraft.NormKind.NO_CALC -> Text("Резерв / разовые выдачи: ведётся только журнал выдач, срок не считается", style = MaterialTheme.typography.bodySmall)
                else -> IntField("Количество, шт", norm.amount, { norm = norm.copy(amount = it) }, Modifier.fillMaxWidth())
            }
            mePreview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

            if (prescriptionId == null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(checked = showInitialBalance, onCheckedChange = { showInitialBalance = it })
                    Text("Уже обеспечен (начальный остаток)")
                }
                if (showInitialBalance) {
                    DateField("Остаток на дату", initialBalance.dateEpoch?.let { Dates.fromEpochDay(it) }, {
                        initialBalance = initialBalance.copy(dateEpoch = it?.let { d -> Dates.toEpochDay(d) })
                    }, Modifier.fillMaxWidth())
                    DateField("Обеспечен до", initialBalance.shownUntilEpoch?.let { Dates.fromEpochDay(it) }, {
                        initialBalance = initialBalance.copy(shownUntilEpoch = it?.let { d -> Dates.toEpochDay(d) })
                    }, Modifier.fillMaxWidth())
                    IntField("Остаток, шт (если известен)", initialBalance.remainderPieces, {
                        initialBalance = initialBalance.copy(remainderPieces = it)
                    }, Modifier.fillMaxWidth())
                    Text(
                        "Если заполнить только «обеспечен до», количество посчитается автоматически",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            (localError ?: error.value)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            Button(
                onClick = {
                    val drug = selectedDrug
                    localError = when {
                        drug == null -> "Выберите препарат"
                        !norm.isValid -> "Заполните норму"
                        norm.kind == NormDraft.NormKind.ME && drug.mePerPiece() == null ->
                            "Препарату не заданы концентрация и объём для МЕ-нормы"
                        else -> null
                    }
                    if (localError == null) {
                        vm.save(existingPrescription, patientId, drug!!, norm, if (showInitialBalance) initialBalance else null)
                    }
                },
                enabled = selectedDrug != null,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Сохранить") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Отмена") }
        }
    }

    if (showDrugDialog) {
        AlertDialog(
            onDismissRequest = { showDrugDialog = false },
            title = { Text("Препарат") },
            text = {
                if (drugs.isEmpty()) {
                    Text("Справочник пуст — создайте первый препарат")
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                        items(drugs.size) { idx ->
                            val drug = drugs[idx]
                            Text(
                                drug.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDrug = drug
                                        showDrugDialog = false
                                    }
                                    .padding(10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDrugDialog = false; showDrugCreate = true }) { Text("+ Создать новый") }
            },
            dismissButton = { TextButton(onClick = { showDrugDialog = false }) { Text("Отмена") } },
        )
    }

    if (showDrugCreate) {
        DrugCreateDialog(
            duplicateWarning = duplicateWarning,
            onCheckDuplicate = { vm.checkDuplicate(it) },
            onCreate = { draft ->
                vm.viewModelScope.launch {
                    val drug = vm.createDrug(draft)
                    selectedDrug = drug
                    showDrugCreate = false
                }
            },
            onDismiss = { showDrugCreate = false },
        )
    }
}

/** Диалог создания препарата (п. 3.2, 5.9). */
@Composable
fun DrugCreateDialog(
    duplicateWarning: String?,
    onCheckDuplicate: (String) -> Unit,
    onCreate: (Drug) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Лекарство") }
    var unit by remember { mutableStateOf("упаковка") }
    var piecesPerPack by remember { mutableStateOf("1") }
    var concMe by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf("") }
    var concPct by remember { mutableStateOf("") }
    var pieceNouns by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый препарат") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    name, { name = it; onCheckDuplicate(it) },
                    label = { Text("Название *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                duplicateWarning?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("Лекарство", "Расходник", "Питание").forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf("упаковка", "флакон", "банка", "штука").forEach { u ->
                        FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    IntField("Штук в уп. *", piecesPerPack.toIntOrNull() ?: 1, { piecesPerPack = (it ?: 1).toString() }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    IntField("Конц., МЕ/мл", concMe.toIntOrNull(), { concMe = it?.toString() ?: "" }, Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 8.dp)) {
                    IntField("Объём, мл", volume.toIntOrNull(), { volume = it?.toString() ?: "" }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    IntField("Конц., %", concPct.toIntOrNull(), { concPct = it?.toString() ?: "" }, Modifier.weight(1f))
                }
                OutlinedTextField(
                    pieceNouns, { pieceNouns = it },
                    label = { Text("Название штуки: картридж/картриджа/картриджей") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "МЕ в штуке = концентрация × объём" +
                        ((concMe.toIntOrNull()?.times(volume.toIntOrNull() ?: 0))?.takeIf { it > 0 }?.let { " = $it МЕ" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        Drug(
                            name = name.trim(),
                            category = category,
                            unit = unit,
                            piecesPerPack = (piecesPerPack.toIntOrNull() ?: 1).coerceAtLeast(1),
                            concMePerMl = concMe.toIntOrNull(),
                            volumeMl = volume.toIntOrNull(),
                            concPercent = concPct.toIntOrNull(),
                            pieceNounForms = pieceNouns.trim().ifEmpty { null },
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
