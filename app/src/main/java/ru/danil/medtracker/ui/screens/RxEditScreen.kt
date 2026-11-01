package ru.danil.medtracker.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.danil.medtracker.data.AppContainer
import ru.danil.medtracker.data.db.Rx
import ru.danil.medtracker.ocr.OcrEngine
import ru.danil.medtracker.ocr.PhotoStore
import ru.danil.medtracker.ui.DatePickerField
import ru.danil.medtracker.ui.appViewModel
import ru.danil.medtracker.util.Dates
import java.io.File
import java.time.LocalDate

class RxEditViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repo

    val existing = MutableStateFlow<Rx?>(null)
    val prescriptions = MutableStateFlow<List<ru.danil.medtracker.data.db.Prescription>>(emptyList())
    val drugs = MutableStateFlow<Map<Long, ru.danil.medtracker.data.db.Drug>>(emptyMap())
    /** Ранее введённые врачи — подсказки для поля «Врач» (Ф4.3): из рецептов и карточек пациентов. */
    val knownDoctors = MutableStateFlow<List<String>>(emptyList())
    val saved = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val ocrRunning = MutableStateFlow(false)

    fun load(patientId: Long, rxId: Long?) {
        viewModelScope.launch {
            existing.value = rxId?.let { repo.rxDao.byId(it) }
            val ps = repo.prescriptionDao.allActive().filter { it.patientId == patientId }
            prescriptions.value = ps
            drugs.value = repo.drugDao.all().associateBy { d -> d.id }
            knownDoctors.value = (
                repo.rxDao.distinctDoctors() +
                    repo.patientDao.all().mapNotNull { p -> p.doctor?.trim()?.takeIf { it.isNotEmpty() } }
                ).distinct().sorted()
        }
    }

    fun save(base: Rx) {
        viewModelScope.launch {
            try {
                val t = System.currentTimeMillis()
                val cur = existing.value
                val rx = if (cur == null) base.copy(createdAt = t, updatedAt = t)
                else base.copy(id = cur.id, createdAt = cur.createdAt, updatedAt = t)
                if (cur == null) repo.rxDao.insert(rx) else repo.rxDao.update(rx)
                saved.value = true
            } catch (e: Exception) {
                error.value = e.message ?: "Не удалось сохранить"
            }
        }
    }

    fun delete(context: android.content.Context, rxId: Long) {
        viewModelScope.launch {
            val rx = repo.rxDao.byId(rxId) ?: return@launch
            // п. 5.9: привязки выдач обнуляются, фото удаляются
            repo.issueDao.all().filter { it.rxId == rxId }.forEach { issue ->
                repo.issueDao.update(issue.copy(rxId = null, updatedAt = System.currentTimeMillis()))
            }
            rx.photos().forEach { PhotoStore.delete(context, it) }
            repo.rxDao.delete(rxId)
            repo.tombstoneDao.insert(
                ru.danil.medtracker.data.db.Tombstone(entityType = "rx", entityId = rxId, deletedAt = System.currentTimeMillis()),
            )
            saved.value = true
        }
    }

    /** «Распознать» (п. 5.6): OCR первого фото, результат — черновик для проверки. */
    fun runOcr(context: android.content.Context, photos: List<String>, onDraft: (OcrEngine.Draft) -> Unit) {
        if (photos.isEmpty()) {
            error.value = "Сначала добавьте фото"
            return
        }
        viewModelScope.launch {
            ocrRunning.value = true
            error.value = null
            try {
                val draft = withContext(Dispatchers.Default) {
                    try {
                        OcrEngine.recognize(context, PhotoStore.file(context, photos.first()))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        //нативные сбои — Error, а не Exception: без перехвата приложение крашится
                        android.util.Log.e("RxEdit", "OCR failed", e)
                        throw IllegalStateException(OcrEngine.friendlyError(e))
                    }
                }
                onDraft(draft)
            } catch (e: Exception) {
                error.value = "Распознавание не удалось: ${e.message}"
            } finally {
                ocrRunning.value = false
            }
        }
    }

    fun deletePhoto(context: android.content.Context, rx: Rx, relPath: String): Rx {
        PhotoStore.delete(context, relPath)
        val updated = rx.copy(photoPaths = Rx.photosToJson(rx.photos() - relPath))
        viewModelScope.launch {
            if (existing.value != null) repo.rxDao.update(updated.copy(updatedAt = System.currentTimeMillis()))
            existing.value = updated
        }
        return updated
    }

    fun addPhoto(context: android.content.Context, rx: Rx, uri: Uri): Rx {
        val t = System.currentTimeMillis()
        val rel = PhotoStore.saveCompressed(context, uri, "rx_${rx.id}_$t")
        val updated = rx.copy(photoPaths = Rx.photosToJson(rx.photos() + rel))
        viewModelScope.launch {
            if (existing.value != null) repo.rxDao.update(updated.copy(updatedAt = System.currentTimeMillis()))
            existing.value = updated
        }
        return updated
    }
}

/** Экран рецепта (п. 5.6, разд. 6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxEditScreen(
    patientId: Long,
    rxId: Long?,
    prescriptionId: Long?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val vm = appViewModel { RxEditViewModel(it) }
    val context = LocalContext.current

    val existing by vm.existing.collectAsStateWithLifecycle()
    val prescriptions by vm.prescriptions.collectAsStateWithLifecycle()
    val drugs by vm.drugs.collectAsStateWithLifecycle()
    val knownDoctors by vm.knownDoctors.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val ocrRunning by vm.ocrRunning.collectAsStateWithLifecycle()
    LaunchedEffect(patientId, rxId) { vm.load(patientId, rxId) }
    LaunchedEffect(saved) { if (saved) onSaved() }

    var rx by remember(existing) {
        mutableStateOf(
            existing ?: Rx(patientId = patientId, prescriptionId = prescriptionId),
        )
    }
    var showCameraPermissionRationale by remember { mutableStateOf(false) }
    var ocrReport by remember { mutableStateOf<String?>(null) }
    var viewPhotoPath by remember { mutableStateOf<String?>(null) }

    // PickVisualMedia (системный фото-пикер) надёжнее OEM-реализаций GetContent на Xiaomi/HyperOS
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { runCatching { rx = vm.addPhoto(context, rx, it) }.onFailure { vm.error.value = it.message } }
    }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = cameraTarget
        if (ok && target != null) {
            runCatching { rx = vm.addPhoto(context, rx, target) }.onFailure { vm.error.value = it.message }
        }
        cameraTarget = null
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera({ cameraTarget = it }, cameraLauncher::launch, context)
        else showCameraPermissionRationale = true
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (rxId == null) "Новый рецепт" else "Рецепт") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Фото
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchCamera({ cameraTarget = it }, cameraLauncher::launch, context)
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }) { Text("Камера") }
                OutlinedButton(onClick = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Галерея") }
            }
            if (rx.photos().isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    items(rx.photos().size) { idx ->
                        val rel = rx.photos()[idx]
                        Column {
                            // Тап по миниатюре — полноэкранный просмотр с зумом и повторным OCR (Ф1.1, Ф1.2)
                            AsyncImage(
                                model = PhotoStore.file(context, rel),
                                contentDescription = "Фото рецепта",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewPhotoPath = rel },
                            )
                            TextButton(onClick = { rx = vm.deletePhoto(context, rx, rel) }) { Text("Удалить", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                // «Распознать» (разд. 6)
                OutlinedButton(
                    onClick = {
                        vm.runOcr(context, rx.photos()) { draft ->
                            rx = rx.copy(
                                series = draft.series ?: rx.series,
                                number = draft.number ?: rx.number,
                                ocrText = draft.rawText,
                            )
                            draft.issueDate?.let { parsed ->
                                Dates.parseFlexible(parsed)?.let { rx = rx.copy(issueDate = Dates.toEpochDay(it)) }
                            }
                            draft.validDays?.let { rx = rx.copy(validDays = it) }
                            // Отчёт: что нашлось, что нет (п. 6.4 — итог подтверждает человек)
                            ocrReport = if (draft.rawText.length < 20) {
                                "Текст не распознан. Переснимите бланк ближе, при хорошем свете и без наклона."
                            } else {
                                buildString {
                                    append("Распознано ${draft.rawText.length} символов. Найдено:")
                                    append(if (draft.series != null) " серия ${draft.series}" else " серия —")
                                    append(if (draft.number != null) ", № ${draft.number}" else ", № —")
                                    append(if (draft.issueDate != null) ", дата ${draft.issueDate}" else ", дата —")
                                    append(if (draft.validDays != null) ", срок ${draft.validDays} дн" else ", срок —")
                                    append(". Сверьте поля с фото и сохраните.")
                                }
                            }
                        }
                    },
                    enabled = !ocrRunning,
                ) {
                    if (ocrRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Распознать")
                }
                Text(
                    "Поля после распознавания помечены к проверке: сверьте с фото и сохраньте (п. 6.4)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                rx.series, { rx = rx.copy(series = it) },
                label = { Text("Серия (например 75464)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                rx.number, { rx = rx.copy(number = it) },
                label = { Text("Номер (например 25 222 011 880)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            // Врач (Ф4.3): только вручную — ввод нового или выбор из ранее введённых
            OutlinedTextField(
                value = rx.doctor ?: "",
                onValueChange = { rx = rx.copy(doctor = it.trim().takeIf { s -> s.isNotEmpty() }) },
                label = { Text("Врач (выписал рецепт)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val doctorInput = rx.doctor?.trim().orEmpty()
            if (doctorInput.length >= 2) {
                val hints = knownDoctors
                    .filter { it.contains(doctorInput, ignoreCase = true) && !it.equals(doctorInput, ignoreCase = true) }
                    .take(4)
                if (hints.isNotEmpty()) {
                    Column(Modifier.padding(top = 2.dp)) {
                        hints.forEach { doc ->
                            Text(
                                doc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { rx = rx.copy(doctor = doc) }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            DatePickerField(
                "Дата выдачи рецепта",
                rx.issueDate?.let { Dates.fromEpochDay(it) },
                { rx = rx.copy(issueDate = it?.let { d -> Dates.toEpochDay(d) }) },
                Modifier.fillMaxWidth(),
            )
            Text("Срок действия", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 60, 90).forEach { d ->
                    FilterChip(
                        selected = rx.validDays == d,
                        onClick = { rx = rx.copy(validDays = d, validUntil = null) },
                        label = { Text("$d дн") },
                    )
                }
                FilterChip(
                    selected = rx.validUntil != null,
                    onClick = {
                        rx = if (rx.validUntil == null) {
                            rx.copy(validUntil = Dates.toEpochDay(LocalDate.now().plusDays(30)), validDays = null)
                        } else {
                            rx.copy(validUntil = null)
                        }
                    },
                    label = { Text("точно до ${rx.validUntil?.let { Dates.formatShort(it) } ?: "…"}") },
                )
            }
            if (rx.validUntil != null) {
                DatePickerField(
                    "Действителен до",
                    Dates.fromEpochDay(rx.validUntil!!),
                    { rx = rx.copy(validUntil = it?.let { d -> Dates.toEpochDay(d) }, validDays = null) },
                    Modifier.fillMaxWidth(),
                )
            }
            Text("Статус", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = rx.status == "ISSUED", onClick = { rx = rx.copy(status = "ISSUED") }, label = { Text("выписан") })
                FilterChip(selected = rx.status == "REDEEMED", onClick = { rx = rx.copy(status = "REDEEMED") }, label = { Text("отоварен") })
            }
            val until = ru.danil.medtracker.calc.CalcService.rxValidUntil(rx.issueDate, rx.validDays, rx.validUntil)
            val expired = rx.status == "ISSUED" && until?.isBefore(LocalDate.now()) == true
            Text(
                when {
                    rx.status == "REDEEMED" -> "Статус: отоварен"
                    expired -> "Статус: истёк"
                    until != null -> "Действителен до ${Dates.formatRu(until)}"
                    else -> ""
                },
                color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )

            Text("Назначение (необязательно)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            prescriptions.forEach { p ->
                FilterChip(
                    selected = rx.prescriptionId == p.id,
                    onClick = { rx = rx.copy(prescriptionId = if (rx.prescriptionId == p.id) null else p.id) },
                    label = { Text(drugs[p.drugId]?.name ?: "—") },
                )
            }

            rx.ocrText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Распознанный текст (сохранён для поиска):",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(it.take(1200), style = MaterialTheme.typography.bodySmall)
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            // Ф1.5: будущая дата выдачи рецепта блокируется с объяснением
            val issueInFuture = rx.issueDate?.let { Dates.fromEpochDay(it).isAfter(LocalDate.now()) } == true
            if (issueInFuture) {
                Text(
                    "Дата выдачи рецепта не может быть в будущем",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { vm.save(rx) },
                enabled = !issueInFuture,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Сохранить") }
            if (rxId != null) {
                OutlinedButton(
                    onClick = { vm.delete(context, rxId) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Удалить рецепт", color = MaterialTheme.colorScheme.error) }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Отмена") }
            Spacer(Modifier.height(32.dp))
        }
    }

    // Отчёт распознавания (п. 6.4)
    ocrReport?.let { report ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ocrReport = null },
            title = { Text("Распознавание") },
            text = { Text(report) },
            confirmButton = {
                TextButton(onClick = { ocrReport = null }) { Text("Понятно") }
            },
        )
    }

    // Полноэкранный просмотр фото (Ф1.1): повторное распознавание заполняет поля формы (Ф1.2)
    viewPhotoPath?.let { rel ->
        ru.danil.medtracker.ui.PhotoViewer(
            file = PhotoStore.file(context, rel),
            onReRecognize = {
                vm.runOcr(context, listOf(rel)) { draft ->
                    rx = rx.copy(
                        series = draft.series ?: rx.series,
                        number = draft.number ?: rx.number,
                        ocrText = draft.rawText,
                    )
                    draft.issueDate?.let { parsed ->
                        Dates.parseFlexible(parsed)?.let { rx = rx.copy(issueDate = Dates.toEpochDay(it)) }
                    }
                    draft.validDays?.let { rx = rx.copy(validDays = it) }
                    ocrReport = "Распознано ${draft.rawText.length} символов — поля обновлены, сверьте с фото."
                }
            },
            onDismiss = { viewPhotoPath = null },
        )
    }
}

private fun launchCamera(
    setTarget: (Uri) -> Unit,
    launch: (Uri) -> Unit,
    context: android.content.Context,
) {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "rx_photo_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    setTarget(uri)
    launch(uri)
}
