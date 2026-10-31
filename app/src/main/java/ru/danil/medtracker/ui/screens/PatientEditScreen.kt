package ru.danil.medtracker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.db.Benefit
import ru.danil.medtracker.data.db.Patient
import ru.danil.medtracker.ui.DatePickerField
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

class PatientEditViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repo

    val loaded = MutableStateFlow<Patient?>(null)
    val saved = MutableStateFlow(false)
    var loadedId: Long? = null

    fun load(id: Long) {
        if (loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            loaded.value = repo.patientDao.byId(id)
        }
    }

    fun save(draft: PatientDraft) {
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            val existing = loaded.value
            val patient = (existing ?: Patient(
                fio = "", birthDate = 0, address = "",
                createdAt = t, updatedAt = t,
            )).copy(
                fio = draft.fio.trim(),
                birthDate = draft.birthDate!!.toEpochDay(),
                address = draft.address.trim(),
                school = draft.school?.trim()?.ifEmpty { null },
                illnessStart = draft.illnessStart?.toEpochDay(),
                benefitsJson = Patient.benefitsToJson(draft.benefits),
                repName = draft.repName?.trim()?.ifEmpty { null },
                phone1 = draft.phone1?.trim()?.ifEmpty { null },
                phone2 = draft.phone2?.trim()?.ifEmpty { null },
                cardNo = draft.cardNo?.trim()?.ifEmpty { null },
                diagnosis = draft.diagnosis?.trim()?.ifEmpty { null },
                doctor = draft.doctor?.trim()?.ifEmpty { null },
                notes = draft.notes,
                archived = false,
                updatedAt = t,
            )
            if (existing == null) repo.patientDao.insert(patient)
            else repo.patientDao.update(patient)
            saved.value = true
        }
    }
}

data class PatientDraft(
    var fio: String = "",
    var birthDate: LocalDate? = null,
    var address: String = "",
    var school: String? = null,
    var illnessStart: LocalDate? = null,
    var benefits: List<Benefit> = emptyList(),
    var repName: String? = null,
    var phone1: String? = null,
    var phone2: String? = null,
    var cardNo: String? = null,
    var diagnosis: String? = null,
    var doctor: String? = null,
    var notes: String = "",
)

fun Patient.toDraft() = PatientDraft(
    fio = fio,
    birthDate = Dates.fromEpochDay(birthDate),
    address = address,
    school = school,
    illnessStart = illnessStart?.let { Dates.fromEpochDay(it) },
    benefits = benefits(),
    repName = repName,
    phone1 = phone1,
    phone2 = phone2,
    cardNo = cardNo,
    diagnosis = diagnosis,
    doctor = doctor,
    notes = notes,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientEditScreen(
    patientId: Long?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val vm = appViewModel { PatientEditViewModel(it) }
    if (patientId != null) {
        LaunchedEffect(patientId) { vm.load(patientId) }
    } else {
        LaunchedEffect(Unit) { vm.loaded.value = null; vm.loadedId = null }
    }
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) onSaved() }

    val draft = remember(loaded) { (loaded?.toDraft() ?: PatientDraft()) }
    var fio by remember(loaded) { mutableStateOf(draft.fio) }
    var birth by remember(loaded) { mutableStateOf(draft.birthDate) }
    var address by remember(loaded) { mutableStateOf(draft.address) }
    var school by remember(loaded) { mutableStateOf(draft.school ?: "") }
    var illnessStart by remember(loaded) { mutableStateOf(draft.illnessStart) }
    var benefits by remember(loaded) { mutableStateOf(draft.benefits) }
    var repName by remember(loaded) { mutableStateOf(draft.repName ?: "") }
    var phone1 by remember(loaded) { mutableStateOf(draft.phone1 ?: "") }
    var phone2 by remember(loaded) { mutableStateOf(draft.phone2 ?: "") }
    var cardNo by remember(loaded) { mutableStateOf(draft.cardNo ?: "") }
    var diagnosis by remember(loaded) { mutableStateOf(draft.diagnosis ?: "") }
    var doctor by remember(loaded) { mutableStateOf(draft.doctor ?: "") }
    var notes by remember(loaded) { mutableStateOf(draft.notes) }

    val today = Dates.today()
    val birthInFuture = birth != null && birth!!.isAfter(today)
    var editBenefitIdx by remember { mutableStateOf<Int?>(null) }
    // Дата рождения в будущем недопустима (замечание №6)
    val valid = fio.isNotBlank() && birth != null && !birthInFuture && address.isNotBlank()

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (patientId == null) "Новый пациент" else "Изменить пациента") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(fio, { fio = it }, label = { Text("ФИО *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DatePickerField("Дата рождения *", birth, { birth = it }, Modifier.fillMaxWidth().padding(top = 8.dp))
            if (birthInFuture) {
                Text(
                    "Дата рождения не может быть в будущем",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(address, { address = it }, label = { Text("Адрес *") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(school, { school = it }, label = { Text("Школа / класс / детсад") }, modifier = Modifier.fillMaxWidth())
            DatePickerField("Начало заболевания", illnessStart, { illnessStart = it }, Modifier.fillMaxWidth())

            Text("Льгота", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            // Дата действия льготы редактируется календарём (замечание №2)
            benefits.forEachIndexed { idx, b ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        (if (b.type == "federal") "Федеральная" else "Региональная") + " с ${Dates.formatRu(b.since)}",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        // быстрый сдвиг: тап по строке открывает календарь ниже
                        editBenefitIdx = if (editBenefitIdx == idx) null else idx
                    }) { Text("Изменить дату") }
                    TextButton(onClick = { benefits = benefits.filterIndexed { i, _ -> i != idx } }) { Text("Убрать") }
                }
                if (editBenefitIdx == idx) {
                    DatePickerField(
                        "Действует с",
                        Dates.fromEpochDay(b.since),
                        { d -> d?.let { nd -> benefits = benefits.toMutableList().also { list -> list[idx] = b.copy(since = Dates.toEpochDay(nd)) } } },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            Row {
                TextButton(onClick = {
                    benefits = benefits + Benefit("regional", Dates.toEpochDay(LocalDate.now()))
                }) { Text("+ Региональная") }
                TextButton(onClick = {
                    benefits = benefits + Benefit("federal", Dates.toEpochDay(LocalDate.now()))
                }) { Text("+ Федеральная") }
            }

            OutlinedTextField(repName, { repName = it }, label = { Text("Представитель (родитель)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone1, { phone1 = it }, label = { Text("Телефон 1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone2, { phone2 = it }, label = { Text("Телефон 2") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cardNo, { cardNo = it }, label = { Text("№ амбулаторной карты") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(diagnosis, { diagnosis = it }, label = { Text("Диагноз / код МКБ (например E10)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(doctor, { doctor = it }, label = { Text("Лечащий врач") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Заметки плана наблюдения (в план ДН)") }, modifier = Modifier.fillMaxWidth(), minLines = 3)

            Row(Modifier.padding(top = 16.dp)) {
                Button(
                    onClick = {
                        vm.save(
                            PatientDraft(
                                fio = fio, birthDate = birth, address = address,
                                school = school, illnessStart = illnessStart,
                                benefits = benefits, repName = repName,
                                phone1 = phone1, phone2 = phone2,
                                cardNo = cardNo, diagnosis = diagnosis,
                                doctor = doctor, notes = notes,
                            ),
                        )
                    },
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                ) { Text("Сохранить") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.padding(start = 8.dp)) { Text("Отмена") }
            }
            if (!valid) {
                Text(
                    when {
                        fio.isBlank() || address.isBlank() -> "Заполните ФИО, дату рождения и адрес"
                        birth == null -> "Укажите дату рождения"
                        else -> "Дата рождения не может быть в будущем"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
