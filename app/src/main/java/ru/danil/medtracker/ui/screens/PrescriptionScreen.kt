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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import ru.danil.medtracker.calc.SupplyEngine
import ru.danil.medtracker.calc.SupplyOutcome
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.ui.ConfirmDialog
import ru.danil.medtracker.ui.SupplyBadge
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

class PrescriptionViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repo

    data class JournalRow(
        val issue: ru.danil.medtracker.data.db.Issue,
        /** «обеспечен до» на момент этой выдачи (п. 4.6) */
        val asOf: LocalDate?,
        val asOfUnlimited: Boolean,
        /** перерыв, который закрыла эта выдача: «перерыв 12.11–20.11» */
        val gapClosed: Pair<Long, Long>?,
    )

    data class State(
        val prescription: ru.danil.medtracker.data.db.Prescription? = null,
        val drug: ru.danil.medtracker.data.db.Drug? = null,
        val norms: List<ru.danil.medtracker.data.db.Norm> = emptyList(),
        val issues: List<ru.danil.medtracker.data.db.Issue> = emptyList(),
        val journal: List<JournalRow> = emptyList(),
        val coveredUntil: LocalDate? = null,
        val unlimited: Boolean = false,
        val noCalc: Boolean = false,
        val remainder: String = "",
        val remainderPacks: String? = null,
        val threshold: Int = 7,
        val patientId: Long = 0,
        val gapsAll: List<Pair<Long, Long>> = emptyList(),
    )

    fun state(prescriptionId: Long) = combine(
        combine(
            repo.prescriptionDao.observeAllActive(),
            repo.normDao.observeAll(),
            repo.issueDao.observeAll(),
        ) { p, n, i -> Triple(p, n, i) },
        combine(
            repo.drugDao.observeAll(),
            container.settings.settings,
        ) { d, s -> Pair(d, s) },
    ) { db, aux ->
        val (prescriptions, norms, issues) = db
        val (drugs, settings) = aux
        withContext(Dispatchers.Default) {
            val p = prescriptions.firstOrNull { it.id == prescriptionId }
            val ns = norms.filter { it.prescriptionId == prescriptionId }.sortedBy { it.validFrom }
            val issuesList = issues.filter { it.prescriptionId == prescriptionId }.sortedWith(compareBy({ it.date }, { it.id }))
            val drug = p?.let { drugs.firstOrNull { d -> d.id == it.drugId } }
            val today = Dates.today()
            val noCalc = CalcService.isNoCalc(ns, today)
            var covered: LocalDate? = null
            var unlimited = false
            var remainder = ""
            var remainderPacks: String? = null
            if (!noCalc && drug != null) {
                val supply = CalcService.supplyFor(issuesList, ns, drug)
                covered = supply.coveredUntil()
                unlimited = supply.outcome is SupplyOutcome.Unlimited
                val rem = SupplyEngine.remainderAt(today, CalcService.stockEvents(issuesList), CalcService.normRecs(ns), drug.mePerPiece())
                remainder = rem.toDisplay()
                if (drug.piecesPerPack > 1) {
                    remainderPacks = rem.div(ru.danil.medtracker.calc.Rational.of(drug.piecesPerPack.toLong())).toDisplay()
                }
                // Журнал: дата на момент каждой выдачи (п. 4.6)
                val events = CalcService.stockEvents(issuesList)
                val normRecs = CalcService.normRecs(ns)
                val journal = issuesList.reversed().map { issue ->
                    val res = SupplyEngine.supplyEndAsOf(events.first { e -> e.id == issue.id }, events, normRecs, drug.mePerPiece())
                    val gap = res.gaps.lastOrNull()
                    JournalRow(
                        issue = issue,
                        asOf = res.coveredUntil(),
                        asOfUnlimited = res.outcome is SupplyOutcome.Unlimited,
                        gapClosed = gap?.let { Dates.toEpochDay(it.first) to Dates.toEpochDay(it.second) },
                    )
                }
                return@withContext State(
                    p, drug, ns, issuesList, journal, covered, unlimited, noCalc,
                    remainder, remainderPacks, settings.drugThresholdDays,
                    p?.patientId ?: 0, supply.gaps.map { Dates.toEpochDay(it.first) to Dates.toEpochDay(it.second) },
                )
            }
            val journal = issuesList.reversed().map { issue ->
                JournalRow(issue, null, false, null)
            }
            State(p, drug, ns, issuesList, journal, covered, unlimited, noCalc, remainder, remainderPacks, settings.drugThresholdDays, p?.patientId ?: 0, emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), State())

    fun deletePrescription(id: Long) {
        viewModelScope.launch {
            repo.coreDao.deletePrescriptionCascade(repo.prescriptionDao, repo.normDao, repo.issueDao, id)
        }
    }

    fun archivePrescription(id: Long) {
        viewModelScope.launch {
            val p = repo.prescriptionDao.byId(id) ?: return@launch
            repo.prescriptionDao.update(p.copy(status = "ARCHIVE", updatedAt = System.currentTimeMillis()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrescriptionScreen(
    prescriptionId: Long,
    onAddIssue: (Long) -> Unit,
    onEditIssue: (Long, Long) -> Unit,
    onAddRx: (Long, Long?) -> Unit,
    onEdit: (Long, Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm = appViewModel { PrescriptionViewModel(it) }
    val state by remember(prescriptionId) { vm.state(prescriptionId) }.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.drug?.name ?: "Назначение") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = { state.prescription?.let { onEdit(it.patientId, it.id) } }) {
                        Icon(Icons.Filled.Edit, "Новая норма / препарат")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Archive, "В архив / удалить")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddIssue(prescriptionId) }) {
                Icon(Icons.Filled.Add, "Выдать")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            // Текущая норма и «обеспечен до» — крупно (п. 5.4)
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val current = CalcService.currentNorm(state.norms)
                        Text(
                            if (state.noCalc) "Без расчёта срока (резерв)" else CalcService.normDoc(current, CalcService.pieceNounForms(state.drug ?: ru.danil.medtracker.data.db.Drug(name = ""))),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        state.drug?.let {
                            Text(CalcService.drugForm(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (state.noCalc) {
                            Text("Ведётся только журнал выдач", style = MaterialTheme.typography.bodyMedium)
                        } else if (state.unlimited) {
                            Text("запас > 5 лет", color = androidx.compose.ui.graphics.Color(0xFF2E7D32), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        } else {
                            SupplyBadge(state.coveredUntil, CalcService.level(state.coveredUntil, state.threshold), Modifier)
                            Text(
                                "Обеспечен до ${state.coveredUntil?.let { Dates.formatRu(it) } ?: "—"}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text("Остаток: ${state.remainder} шт${state.remainderPacks?.let { " ($it уп)" } ?: ""}")
                        }
                    }
                }
            }

            // История норм
            if (state.norms.isNotEmpty()) {
                item {
                    Text("История норм", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    state.norms.reversed().forEachIndexed { idx, n ->
                        val label = if (idx == 0) "сейчас" else "заменено"
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(
                                "с ${Dates.formatRu(n.validFrom)} — ${CalcService.normShort(n)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Журнал выдач (п. 5.4)
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Журнал выдач", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    state.prescription?.let { p ->
                        TextButton(onClick = { onAddRx(p.patientId, p.id) }) {
                            Icon(Icons.Filled.Receipt, null, Modifier.padding(end = 4.dp))
                            Text("+ рецепт")
                        }
                    }
                }
            }
            if (state.journal.isEmpty()) {
                item { ru.danil.medtracker.ui.EmptyState("Выдач пока нет", "Выдать") { onAddIssue(prescriptionId) } }
            } else {
                items(state.journal.size) { idx ->
                    val row = state.journal[idx]
                    val issue = row.issue
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onEditIssue(prescriptionId, issue.id) },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${if (issue.isInitialBalance) "Остаток" else "Выдано"} ${Dates.formatRu(issue.date)}",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${issue.totalPieces} шт" +
                                        (if (!issue.isInitialBalance && issue.enteredPacks > 0 && issue.piecesPerPackAtIssue > 1) {
                                            var q = " (${issue.enteredPacks} уп × ${issue.piecesPerPackAtIssue}"
                                            if (issue.enteredPieces > 0) q += " + ${issue.enteredPieces}"
                                            q + ")"
                                        } else ""),
                                )
                            }
                            row.gapClosed?.let { (from, to) ->
                                Text(
                                    "перерыв ${Dates.formatShort(from)}–${Dates.formatShort(to)}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row {
                                Column(Modifier.weight(1f)) {
                                    issue.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (issue.isInitialBalance) {
                                        issue.shownUntil?.let {
                                            Text("по ${Dates.formatRu(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Text(
                                    when {
                                        issue.isInitialBalance -> "начальный"
                                        row.asOfUnlimited -> "запас есть"
                                        row.asOf != null -> "обеспечен до ${Dates.formatRu(row.asOf)}"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Назначение") },
            text = { Text("«В архив» сохраняет историю и скрывает из списков. «Удалить» стирает назначение и все его выдачи.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deletePrescription(prescriptionId)
                    onBack()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Column {
                    TextButton(onClick = {
                        confirmDelete = false
                        vm.archivePrescription(prescriptionId)
                        onBack()
                    }) { Text("В архив") }
                    TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
                }
            },
        )
    }
}
