package com.abyss.tools.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abyss.tools.core.designsystem.glass.AbyssGlassPalette
import com.abyss.tools.core.designsystem.glass.LocalAbyssGlassPalette
import com.abyss.tools.core.designsystem.glass.derivedGlassPalette

/**
 * 深渊色板 —— 近黑蓝底 + 冷冽青蓝主色（「深渊之光」隐喻）。
 *
 * v0.9.6：
 *  - 新增 `darkTheme: Boolean?` 参数（null = 跟随系统），支持外观三态；
 *  - 注入 [LocalAbyssGlassPalette]：浅色传 Light 白色液态玻璃色板、
 *    深色传 Dark 深渊色板，修 v0.9.5 白天模式底栏硬编码近黑 Bug。
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
    surfaceContainerLowest = Color(0xFF090D12),
    surfaceContainerLow = Color(0xFF12171E),
    surfaceContainer = Color(0xFF161C24),
    surfaceContainerHigh = Color(0xFF1C242E),
    surfaceContainerHighest = Color(0xFF27303C),
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
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5FA),
    surfaceContainer = Color(0xFFEAF0F6),
    surfaceContainerHigh = Color(0xFFE4EBF2),
    surfaceContainerHighest = Color(0xFFDEE6EE),
    surfaceVariant = Color(0xFFDEE8F1),
    onSurfaceVariant = Color(0xFF41484F),
    outline = Color(0xFF717881),
    outlineVariant = Color(0xFFC1C7CF),
)

private val AbyssTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

private val AbyssShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 深渊主题（v0.9.6：注入玻璃色板 + 支持外观三态）。
 *
 * @param darkTheme 深浅色：`true`=深色、`false`=浅色、`null`=跟随系统。
 * @param dynamicColor Android 12+ 且用户开启「跟随壁纸取色」时启用 Material You；
 *   玻璃色板同时按动态色派生，保持玻璃质感不丢失主题主色。
 */
@Composable
fun AbyssTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (isDark) AbyssDarkColors else AbyssLightColors
    }
    // 玻璃色板：固定明暗档色板；动态取色档用派生色板
    val glassPalette = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        derivedGlassPalette(isDark)
    } else {
        if (isDark) AbyssGlassPalette.Dark else AbyssGlassPalette.Light
    }
    CompositionLocalProvider(LocalAbyssGlassPalette provides glassPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AbyssTypography,
            shapes = AbyssShapes,
            content = content,
        )
    }
}
