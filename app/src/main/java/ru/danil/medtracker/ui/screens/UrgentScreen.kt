package ru.danil.medtracker.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import ru.danil.medtracker.calc.CalcService
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.UrgentItem
import ru.danil.medtracker.data.UrgentKind
import ru.danil.medtracker.ui.EmptyState
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
                build(patients, prescriptions, norms, issues, drugs, rxs, settings, Dates.today())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UrgentState())
    }

    /** «В ожидании поступления»: включить/снять статус вручную. */
    fun setAwaiting(prescriptionId: Long, awaiting: Boolean) {
        viewModelScope.launch { repo.setAwaitingRestock(prescriptionId, awaiting) }
    }
}

data class UrgentState(
    val ended: List<UrgentItem> = emptyList(),
    val waiting: List<UrgentItem> = emptyList(),
    val low: List<UrgentItem> = emptyList(),
    val rx: List<UrgentItem> = emptyList(),
)

private val WaitingColor = Color(0xFF00796B)

private fun build(
    patients: List<ru.danil.medtracker.data.db.Patient>,
    prescriptions: List<ru.danil.medtracker.data.db.Prescription>,
    norms: List<ru.danil.medtracker.data.db.Norm>,
    issues: List<ru.danil.medtracker.data.db.Issue>,
    drugs: List<ru.danil.medtracker.data.db.Drug>,
    rxs: List<ru.danil.medtracker.data.db.Rx>,
    settings: ru.danil.medtracker.data.AppSettings,
    today: LocalDate,
): UrgentState {
    val patientById = patients.associateBy { it.id }
    val drugById = drugs.associateBy { it.id }
    val prescriptionById = prescriptions.associateBy { it.id }
    val normsByP = norms.groupBy { it.prescriptionId }
    val issuesByP = issues.groupBy { it.prescriptionId }
    val drugThreshold = settings.drugThresholdDays
    val rxThreshold = settings.rxThresholdDays

    val ended = mutableListOf<UrgentItem>()
    val waiting = mutableListOf<UrgentItem>()
    val low = mutableListOf<UrgentItem>()

    for (p in prescriptions) {
        val patient = patientById[p.patientId] ?: continue // архивные пациенты не участвуют
        val drug = drugById[p.drugId] ?: continue
        val ns = normsByP[p.id].orEmpty()
        // «без расчёта срока» не попадают (п. 5.2)
        if (CalcService.isNoCalc(ns, today)) continue
        val supply = CalcService.supplyFor(issuesByP[p.id].orEmpty(), ns, drug)
        val until = supply.coveredUntil() ?: continue // «запас > 5 лет» не попадает
        when {
            until.isBefore(today) -> {
                // закончилось и врач пометил «нет на складе» — своя секция;
                // если запас восстановился, флаг игнорируем (снимется при выдаче)
                val item = UrgentItem(patient, drug, p, null, until, UrgentKind.ENDED)
                if (p.awaitingRestock) waiting.add(item.copy(kind = UrgentKind.WAITING)) else ended.add(item)
            }
            !until.isAfter(today.plusDays(drugThreshold.toLong())) ->
                low.add(UrgentItem(patient, drug, p, null, until, UrgentKind.LOW))
            else -> {}
        }
    }

    val rxItems = rxs.mapNotNull { rx ->
        val patient = patientById[rx.patientId] ?: return@mapNotNull null
        val until = CalcService.rxValidUntil(rx.issueDate, rx.validDays, rx.validUntil)
            ?: return@mapNotNull null
        if (!until.isAfter(today.plusDays(rxThreshold.toLong()))) {
            val drug = rx.prescriptionId
                ?.let { prescriptionById[it] }
                ?.let { drugById[it.drugId] }
            UrgentItem(patient, drug, null, rx, until, UrgentKind.RX)
        } else null
    }.sortedBy { it.date }

    return UrgentState(
        ended = ended.sortedBy { it.date },
        waiting = waiting.sortedBy { it.date },
        low = low.sortedBy { it.date },
        rx = rxItems,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrgentScreen(
    onOpenPrescription: (Long) -> Unit,
    onOpenRx: (Long, Long) -> Unit,
) {
    val vm = appViewModel { UrgentViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

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
                if (state.ended.isNotEmpty()) {
                    item { SectionHeader("Закончилось") }
                    items(state.ended.size) { idx ->
                        UrgentRow(state.ended[idx], onOpenPrescription, onOpenRx) { id, awaiting ->
                            vm.setAwaiting(id, awaiting)
                        }
                    }
                }
                if (state.waiting.isNotEmpty()) {
                    item { SectionHeader("В ожидании поступления") }
                    items(state.waiting.size) { idx ->
                        UrgentRow(state.waiting[idx], onOpenPrescription, onOpenRx) { id, awaiting ->
                            vm.setAwaiting(id, awaiting)
                        }
                    }
                }
                if (state.low.isNotEmpty()) {
                    item { SectionHeader("Осталось мало") }
                    items(state.low.size) { idx ->
                        UrgentRow(state.low[idx], onOpenPrescription, onOpenRx) { id, awaiting ->
                            vm.setAwaiting(id, awaiting)
                        }
                    }
                }
                if (state.rx.isNotEmpty()) {
                    item { SectionHeader("Рецепты") }
                    items(state.rx.size) { idx ->
                        UrgentRow(state.rx[idx], onOpenPrescription, onOpenRx) { id, awaiting ->
                            vm.setAwaiting(id, awaiting)
                        }
                    }
                }
            }
        }
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
            // «В ожидании поступления» (п. 3.3): вручную или снимается после выдачи
            when (item.kind) {
                UrgentKind.ENDED -> item.prescription?.let { p ->
                    TextButton(onClick = { onToggleWaiting(p.id, true) }) {
                        Text("В ожидании поступления")
                    }
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
