package ru.danil.medtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.danil.medtracker.calc.Level
import ru.danil.medtracker.util.Dates
import java.time.LocalDate

/** Цвет подсветки (п. 7.4). */
@Composable
fun Level.color(): Color = when (this) {
    Level.RED -> MaterialTheme.colorScheme.error
    Level.YELLOW -> Color(0xFFF9A825)
    Level.GREEN -> Color(0xFF2E7D32)
}

/** Цветной бейдж «обеспечен до ДД.ММ.ГГГГ» — единый во всех списках (п. 7.4). */
@Composable
fun SupplyBadge(until: LocalDate?, level: Level, modifier: Modifier = Modifier) {
    val text = until?.let { "до ${Dates.formatRu(it)}" } ?: "запас > 5 лет"
    Box(
        modifier = modifier
            .background(level.color().copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            color = level.color(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Пустое состояние с подсказкой и кнопкой (п. 5.8). */
@Composable
fun EmptyState(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "Удалить",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Поле даты ДД.ММ.ГГГГ с гибким парсингом. */
@Composable
fun DateField(
    label: String,
    value: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by androidx.compose.runtime.remember(value) { androidx.compose.runtime.mutableStateOf(value?.let { Dates.formatRu(it) } ?: "") }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            val parsed = if (s.isBlank()) null else Dates.parseFlexible(s)
            val looksComplete = s.count { it == '.' } >= 1 && s.length >= 5
            error = looksComplete && parsed == null
            if (!error && (parsed != null || s.isBlank())) onChange(parsed)
        },
        label = { Text(label) },
        isError = error,
        supportingText = if (error) {
            { Text("Формат ДД.ММ.ГГГГ", color = MaterialTheme.colorScheme.error) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

/** Числовое поле с целым значением ≥ 0. */
@Composable
fun IntField(
    label: String,
    value: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by androidx.compose.runtime.remember(value) {
        androidx.compose.runtime.mutableStateOf(value?.toString() ?: "")
    }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            val filtered = s.filter { it.isDigit() }.take(6)
            text = filtered
            onChange(filtered.toIntOrNull())
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

/** Строка «метка: значение» для шапок. */
@Composable
fun LabeledRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
