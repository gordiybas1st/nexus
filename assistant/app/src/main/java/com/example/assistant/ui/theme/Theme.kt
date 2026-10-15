package com.example.assistant.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.assistant.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF14105E),
    secondary = Color(0xFF5C5D72),
    tertiary = Color(0xFF78536B),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE4E1EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBFC2FF),
    onPrimary = Color(0xFF282A8F),
    primaryContainer = Color(0xFF3F41A6),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFFC5C4DD),
    tertiary = Color(0xFFE7B9D5),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceVariant = Color(0xFF46464F),
)

/** Colors used for priority indicators. */
object PriorityColors {
    val Low = Color(0xFF16A34A)
    val Medium = Color(0xFFF59E0B)
    val High = Color(0xFFDC2626)
}

/**
 * App theme. Uses Material You dynamic colors on Android 12+ when [dynamicColor]
 * is enabled, otherwise the static indigo palette.
 */
@Composable
fun AssistantTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
