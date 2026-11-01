package ru.danil.medtracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
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

/** Коэффициент режима «крупный шрифт» (Ф11.1). */
const val LARGE_FONT_SCALE = 1.25f

/**
 * Тема приложения: светлая/тёмная по системе (п. 11.4).
 * [fontScale] — пользовательский масштаб шрифта (Ф11): применяется поверх системного
 * через Density.fontScale, т.е. увеличиваются только размеры в sp (dp-вёрстка не трогается).
 */
@Composable
fun MedTrackerTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val scaled = if (fontScale != 1f && fontScale > 0f) {
        androidx.compose.ui.unit.Density(density.density, density.fontScale * fontScale)
    } else null
    if (scaled != null) {
        CompositionLocalProvider(LocalDensity provides scaled) {
            MaterialTheme(
                colorScheme = if (dark) DarkColors else LightColors,
                content = content,
            )
        }
    } else {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}
