package ru.danil.medtracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.ui.EmptyState
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates

class CopyPrescriptionsViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repo

    val sources = repo.patientDao.observeActive()
    val done = MutableStateFlow(false)

    fun copy(fromId: Long, toId: Long) {
        viewModelScope.launch {
            repo.copyPrescriptions(fromId, toId)
            done.value = true
        }
    }
}

/** «Скопировать назначения у другого пациента» (п. 5.9): препараты и нормы копируются, выдачи — нет. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyPrescriptionsScreen(
    patientId: Long,
    onDone: () -> Unit,
) {
    val vm = appViewModel { CopyPrescriptionsViewModel(it) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(done) { if (done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Скопировать назначения") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        val list = sources.filter { it.id != patientId }
        if (list.isEmpty()) {
            Column(Modifier.padding(padding)) { EmptyState("Нет других пациентов для копирования") }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                item {
                    Text(
                        "Выберите, у кого скопировать назначения. Препараты и нормы скопируются (норма действует с сегодняшнего дня), история выдач — нет.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                items(list.size) { idx ->
                    val p = list[idx]
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { vm.copy(p.id, patientId) },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(p.fio, fontWeight = FontWeight.SemiBold)
                            Text(
                                Dates.ageString(Dates.fromEpochDay(p.birthDate)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
