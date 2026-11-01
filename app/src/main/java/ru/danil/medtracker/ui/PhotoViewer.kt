package ru.danil.medtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

/**
 * Полноэкранный просмотр фото рецепта (Ф1.1): pinch-zoom, перетаскивание,
 * двойной тап — сброс масштаба. Если файла нет — вежливое сообщение без краша.
 * [onReRecognize] — кнопка «Распознать заново» (Ф1.2), null — без кнопки.
 */
@Composable
fun PhotoViewer(
    file: File,
    onReRecognize: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var missing by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (!file.exists()) {
            missing = true
        }
        if (missing) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Фото недоступно") },
                text = { Text("Файл перемещён или удалён с устройства.") },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
            )
        } else {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale > 1f) {
                                val maxX = (newScale - 1f) * size.width / 2
                                val maxY = (newScale - 1f) * size.height / 2
                                Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            } else Offset.Zero
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                    },
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = "Фото рецепта",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
                ) {
                    if (onReRecognize != null) {
                        TextButton(onClick = onReRecognize) { Text("Распознать заново", color = Color.White) }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White) }
                }
                Text(
                    "щипок — масштаб, двойной тап — сброс",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                )
            }
        }
    }
}

/**
 * Строка миниатюр фото рецепта (Ф1.1): до [max] штук + счётчик; тап — просмотр.
 * [onOpen] получает файл выбранного фото.
 */
@Composable
fun PhotoThumbRow(
    photos: List<File>,
    onOpen: (File) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 4,
) {
    if (photos.isEmpty()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Row {
                photos.take(max).forEach { f ->
                    AsyncImage(
                        model = f,
                        contentDescription = "Фото рецепта",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpen(f) },
                    )
                }
            }
        }
        if (photos.size > max) {
            Text(
                "+${photos.size - max}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Распознанный текст с возможностью копирования (Ф1.2: перенос в поля вручную). */
@Composable
fun RecognizedTextDialog(text: String, summary: String?, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Распознанный текст") },
        text = {
            Column {
                summary?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("", style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text.take(4000), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
            }) { Text("Скопировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
