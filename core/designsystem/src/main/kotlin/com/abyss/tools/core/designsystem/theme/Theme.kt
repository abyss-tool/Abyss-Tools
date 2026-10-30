package com.abyss.tools.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 深渊色板 —— 近黑蓝底 + 冷冽青蓝主色（「深渊之光」隐喻）。
 *
 * 全部界面（M3 组件 / 液态玻璃霜面）从该色板派生；玻璃组件的霜面色
 * 同样锚定 background/surface，保证玻璃降级档（Frosted）与 M3 实色
 * 容器在同一色彩语言里。
 */

private val AbyssDarkColors = darkColorScheme(
    primary = Color(0xFF7EC8FF),
    onPrimary = Color(0xFF00293D),
    primaryContainer = Color(0xFF0E425F),
    onPrimaryContainer = Color(0xFFCBE5FF),
    secondary = Color(0xFF4FD8EB),
    onSecondary = Color(0xFF00323A),
    secondaryContainer = Color(0xFF0F454F),
    onSecondaryContainer = Color(0xFFB4EBF7),
    tertiary = Color(0xFFB9A6FF),
    onTertiary = Color(0xFF221040),
    tertiaryContainer = Color(0xFF392968),
    onTertiaryContainer = Color(0xFFDCC7FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0E13),
    onBackground = Color(0xFFDCE3EA),
    surface = Color(0xFF0F141B),
    onSurface = Color(0xFFDCE3EA),
    surfaceVariant = Color(0xFF1A222D),
    onSurfaceVariant = Color(0xFFB3C0CF),
    outline = Color(0xFF5A6B7D),
    outlineVariant = Color(0xFF2C3743),
    inverseSurface = Color(0xFFDCE3EA),
    inverseOnSurface = Color(0xFF12161C),
    inversePrimary = Color(0xFF1E5F86),
)

private val AbyssLightColors = lightColorScheme(
    primary = Color(0xFF1E5F86),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCBE5FF),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF0F6B7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB4EBF7),
    onSecondaryContainer = Color(0xFF002027),
    tertiary = Color(0xFF5A43A5),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCC7FF),
    onTertiaryContainer = Color(0xFF17005E),
    error = Color(0xFF93000A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FAFE),
    onBackground = Color(0xFF101418),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFDEE8F1),
    onSurfaceVariant = Color(0xFF41484F),
    outline = Color(0xFF717881),
    outlineVariant = Color(0xFFC1C7CF),
)

@Composable
fun AbyssTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AbyssDarkColors else AbyssLightColors,
        typography = Typography(),
        content = content,
    )
}
