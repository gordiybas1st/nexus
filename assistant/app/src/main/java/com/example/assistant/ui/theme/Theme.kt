package com.example.assistant.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import com.example.assistant.domain.model.ThemePreset

private val DefaultLight = lightColorScheme(
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

private val DefaultDark = darkColorScheme(
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

private val MinimalLight = lightColorScheme(
    primary = Color(0xFF1F1F1F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color(0xFF1F1F1F),
    secondary = Color(0xFF5E5E5E),
    tertiary = Color(0xFF7A7A7A),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEEEEE),
)

private val MinimalDark = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = Color(0xFFBDBDBD),
    tertiary = Color(0xFF9E9E9E),
    background = Color(0xFF0E0E0E),
    surface = Color(0xFF0E0E0E),
    surfaceVariant = Color(0xFF2A2A2A),
)

private val CyberLight = lightColorScheme(
    primary = Color(0xFFD1006F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8E8),
    onPrimaryContainer = Color(0xFF3E0021),
    secondary = Color(0xFF00838F),
    tertiary = Color(0xFF6A1B9A),
    background = Color(0xFFFAF7FF),
    surface = Color(0xFFFAF7FF),
    surfaceVariant = Color(0xFFE8E0F0),
)

private val CyberDark = darkColorScheme(
    primary = Color(0xFFFF2E97),
    onPrimary = Color(0xFF2B0016),
    primaryContainer = Color(0xFF5C0034),
    onPrimaryContainer = Color(0xFFFFD8E8),
    secondary = Color(0xFF00F0FF),
    tertiary = Color(0xFFB388FF),
    background = Color(0xFF0B0716),
    surface = Color(0xFF0B0716),
    surfaceVariant = Color(0xFF2A1F3D),
)

private val PastelLight = lightColorScheme(
    primary = Color(0xFF7C6FD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF2B2266),
    secondary = Color(0xFF5FA88E),
    tertiary = Color(0xFFE38FA7),
    background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7),
    surfaceVariant = Color(0xFFF1E9F5),
)

private val PastelDark = darkColorScheme(
    primary = Color(0xFFC9C0FF),
    onPrimary = Color(0xFF2B2266),
    primaryContainer = Color(0xFF4A3F99),
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Color(0xFFA8DCC7),
    tertiary = Color(0xFFF5B8C9),
    background = Color(0xFF161419),
    surface = Color(0xFF161419),
    surfaceVariant = Color(0xFF3A3540),
)

private fun schemeFor(preset: ThemePreset, dark: Boolean): ColorScheme = when (preset) {
    ThemePreset.DEFAULT -> if (dark) DefaultDark else DefaultLight
    ThemePreset.MINIMAL -> if (dark) MinimalDark else MinimalLight
    ThemePreset.CYBERPUNK -> if (dark) CyberDark else CyberLight
    ThemePreset.PASTEL -> if (dark) PastelDark else PastelLight
}

/** Primary color of a preset, for the picker in Settings. */
fun ThemePreset.previewColor(dark: Boolean): Color = schemeFor(this, dark).primary

/** Colors used for priority indicators. */
object PriorityColors {
    val Low = Color(0xFF16A34A)
    val Medium = Color(0xFFF59E0B)
    val High = Color(0xFFDC2626)
}

/**
 * App theme. Material You dynamic colors on Android 12+ when [dynamicColor] is on,
 * otherwise the chosen [preset] palette.
 */
@Composable
fun AssistantTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    preset: ThemePreset = ThemePreset.DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        schemeFor(preset, dark)
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
