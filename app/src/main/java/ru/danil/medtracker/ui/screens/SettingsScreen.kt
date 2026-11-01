package ru.danil.medtracker.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.danil.medtracker.App
import ru.danil.medtracker.backup.BackupManager
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.AppSettings
import ru.danil.medtracker.notify.AlarmScheduler
import ru.danil.medtracker.ui.IntField
import ru.danil.medtracker.ui.TimePickerField
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings = container.settings.settings
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val backups = MutableStateFlow<List<BackupManager.BackupEntry>>(emptyList())
    val restorePreview = MutableStateFlow<BackupManager.RestorePreview?>(null)
    private var pendingRestore: Pair<android.net.Uri, List<android.net.Uri>>? = null

    fun manager(context: android.content.Context) = BackupManager(container, context.applicationContext)

    fun setTree(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            manager(context).setTree(uri)
            message.value = "Каталог копий выбран"
            refreshBackups(context)
        }
    }

    fun refreshBackups(context: android.content.Context) {
        viewModelScope.launch {
            runCatching { backups.value = manager(context).list() }
        }
    }

    fun createFull(context: android.content.Context) {
        viewModelScope.launch {
            busy.value = true
            message.value = null
            runCatching { withContext(Dispatchers.Default) { manager(context).createFull() } }
                .onSuccess { message.value = "Полная копия создана: $it"; refreshBackups(context) }
                .onFailure { message.value = "Ошибка: ${it.message}" }
            busy.value = false
        }
    }

    fun createDelta(context: android.content.Context) {
        viewModelScope.launch {
            busy.value = true
            message.value = null
            runCatching { withContext(Dispatchers.Default) { manager(context).createDelta() } }
                .onSuccess { message.value = "Быстрая копия создана: $it"; refreshBackups(context) }
                .onFailure { message.value = "Ошибка: ${it.message}" }
            busy.value = false
        }
    }

    fun prepareRestore(context: android.content.Context, full: android.net.Uri, deltas: List<android.net.Uri>) {
        viewModelScope.launch {
            busy.value = true
            message.value = null
            runCatching { withContext(Dispatchers.Default) { manager(context).prepareRestore(full, deltas) } }
                .onSuccess {
                    pendingRestore = full to deltas
                    restorePreview.value = it
                }
                .onFailure { message.value = "Ошибка: ${it.message}" }
            busy.value = false
        }
    }

    fun confirmRestore(context: android.content.Context) {
        val pending = pendingRestore ?: return
        viewModelScope.launch {
            busy.value = true
            message.value = null
            runCatching { withContext(Dispatchers.Default) { manager(context).restore(pending.first, pending.second) } }
                .onSuccess { message.value = "Данные восстановлены" }
                .onFailure { message.value = "Ошибка: ${it.message}" }
            restorePreview.value = null
            pendingRestore = null
            busy.value = false
        }
    }

    fun deleteBackup(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            manager(context).delete(uri)
            refreshBackups(context)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch {
        container.settings.setNotificationsEnabled(enabled)
    }

    fun setCheckTime(minutes: Int) = viewModelScope.launch {
        container.settings.setCheckTime(minutes)
    }

    fun setAnalysisTime(minutes: Int) = viewModelScope.launch {
        container.settings.setAnalysisTime(minutes)
    }

    fun setDrugThreshold(days: Int) = viewModelScope.launch {
        container.settings.setDrugThreshold(days)
    }

    fun setRxThreshold(days: Int) = viewModelScope.launch {
        container.settings.setRxThreshold(days)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val backups by vm.backups.collectAsStateWithLifecycle()
    val restorePreview by vm.restorePreview.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshBackups(context) }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.setTree(context, it) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            // первая выбранная — полная, остальные — быстрые
            vm.prepareRestore(context, uris.first(), uris.drop(1))
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Уведомления (п. 5.7, Ф5.1) ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Уведомления", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ежедневная проверка", Modifier.weight(1f))
                        Switch(
                            checked = settings.notificationsEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= 33) {
                                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                vm.setNotificationsEnabled(enabled)
                                AlarmScheduler.scheduleAll(context, settings.copy(notificationsEnabled = enabled))
                            },
                        )
                    }
                    // Время сводки — полноценный TimePicker (Ф5.1), не чипы
                    TimePickerField(
                        "Время ежедневной сводки",
                        settings.checkTimeMinutes,
                        { minutes ->
                            vm.setCheckTime(minutes)
                            AlarmScheduler.scheduleNext(context, settings.copy(checkTimeMinutes = minutes))
                        },
                        Modifier.fillMaxWidth(),
                    )
                    // Время напоминаний об анализах — отдельный будильник (Ф3.7, Ф5.1)
                    TimePickerField(
                        "Время напоминаний об анализах",
                        settings.analysisTimeMinutes,
                        { minutes ->
                            vm.setAnalysisTime(minutes)
                            AlarmScheduler.scheduleNextAnalysis(context, settings.copy(analysisTimeMinutes = minutes))
                        },
                        Modifier.fillMaxWidth(),
                    )
                    Text("Порог для лекарств, дней", style = MaterialTheme.typography.titleSmall)
                    IntField("Порог лекарств (дней)", settings.drugThresholdDays, { v ->
                        if (v != null && v in 1..60) vm.setDrugThreshold(v)
                    }, Modifier.fillMaxWidth())
                    Text("Порог для рецептов, дней", style = MaterialTheme.typography.titleSmall)
                    IntField("Порог рецептов (дней)", settings.rxThresholdDays, { v ->
                        if (v != null && v in 1..90) vm.setRxThreshold(v)
                    }, Modifier.fillMaxWidth())
                }
            }

            // ── Резервные копии (раздел 9) ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Резервные копии", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (settings.backupTreeUri.isBlank()) "Каталог не выбран"
                        else "Каталог выбран" + (if (settings.lastFullAt > 0) " · последняя копия: ${Dates.formatRu(java.time.LocalDate.ofEpochDay(settings.lastBackupAt / 86400000))}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // FlowRow (Ф5.2): кнопки переносятся строками, а не сжимаются
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { treeLauncher.launch(null) }) { Text("Выбрать каталог") }
                        Button(onClick = { vm.createFull(context) }, enabled = !busy && settings.backupTreeUri.isNotBlank()) { Text("Полная копия") }
                        OutlinedButton(onClick = { vm.createDelta(context) }, enabled = !busy && settings.lastFullAt > 0) { Text("Быстрая") }
                        OutlinedButton(onClick = {
                            restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        }) { Text("Восстановить…") }
                    }
                    if (busy) CircularProgressIndicator(Modifier.height(20.dp))

                    if (backups.isNotEmpty()) {
                        Text("Копии в каталоге:", style = MaterialTheme.typography.titleSmall)
                        backups.take(12).forEach { b ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Text(
                                        (if (b.type == "FULL") "полная" else "быстрая") + " · " + Dates.formatRu(java.time.LocalDate.ofEpochDay(b.createdAt / 86400000)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { vm.deleteBackup(context, b.uri) }) { Text("Удалить") }
                            }
                        }
                    }
                    Text(
                        "Полная копия — база и фото одним файлом. Быстрая — только изменения. Храните копии в надёжном месте: они не шифруются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── HyperOS (п. 7.5) ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Чтобы уведомления приходили надёжно (Xiaomi/HyperOS)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. Настройки → Приложения → Med Tracker → Автозапуск: ВКЛ.\n" +
                            "2. Там же → Экономия заряда: «Без ограничений».\n" +
                            "3. Не закрывайте приложение свайпом из недавних, если хотите получать напоминания в 08:00.",
                    )
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            })
                        }
                    }) { Text("Открыть настройки приложения") }
                }
            }

            // ── О приложении ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("О приложении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Med Tracker — офлайн-учёт льготного лекарственного обеспечения. " +
                            "Версия ${ru.danil.medtracker.BuildConfig.VERSION_NAME}. Данные хранятся только на устройстве; " +
                            "копии не шифруются — храните их в надёжном месте.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            message?.let {
                Text(it, color = if (it.startsWith("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    restorePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { vm.restorePreview.value = null },
            title = { Text("Восстановление") },
            text = {
                Text(
                    "Полная копия от ${Dates.formatRu(java.time.LocalDate.ofEpochDay(preview.fullManifest.createdAt / 86400000))}" +
                        (if (preview.deltaManifests.isNotEmpty()) " + быстрых копий: ${preview.deltaManifests.size}" else "") +
                        ".\nПациентов: ${preview.patients}, выдач: ${preview.issues}, фото: ${preview.photos}.\n" +
                        "Текущие данные будут заменены; перед заменой автоматически создастся страховая копия.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmRestore(context) }) { Text("Восстановить") }
            },
            dismissButton = { TextButton(onClick = { vm.restorePreview.value = null }) { Text("Отмена") } },
        )
    }
}
