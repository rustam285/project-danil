package ru.danil.medtracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1B6B93),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBE4F5),
    secondary = Color(0xFF4E6355),
    tertiary = Color(0xFF6B5778),
    error = Color(0xFFBA1A1A),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF94CCF3),
    onPrimary = Color(0xFF003350),
    primaryContainer = Color(0xFF004B72),
    secondary = Color(0xFFB5CCB8),
    tertiary = Color(0xFFD7BEE4),
    error = Color(0xFFFFB4AB),
)

/** Цвета подсветки дат (п. 7.4). */
object StatusColors {
    val Red = Color(0xFFD32F2F)
    val Yellow = Color(0xFFF9A825)
    val Green = Color(0xFF2E7D32)

    @Composable
    fun red() = MaterialTheme.colorScheme.error
}
