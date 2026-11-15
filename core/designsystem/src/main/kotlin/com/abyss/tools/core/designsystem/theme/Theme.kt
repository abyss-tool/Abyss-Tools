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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 深渊色板 —— 近黑蓝底 + 冷冽青蓝主色（「深渊之光」隐喻）。
 *
 * 全部界面（M3 组件 / 液态玻璃霜面）从该色板派生；玻璃组件的霜面色
 * 同样锚定 background/surface，保证玻璃降级档（Frosted）与 M3 实色
 * 容器在同一色彩语言里。
 *
 * v0.8.0 起对齐 Google Material 3 完整规范：
 *  - [AbyssTypography]：十五级字阶（display/ headline/ title/ body/ label）
 *    全量显式声明 —— Google Apps 的信息层级感来自字重与行高节奏，
 *    而非字号堆砌；
 *  - [AbyssShapes]：M3 形状阶梯（4/8/12/16/28）进主题 —— 芯片 / 卡片 /
 *    弹层的圆角从此全局一致，业务页不再手写 RoundedCornerShape 漂移；
 *  - 动态取色（Material You）：Android 12+ 用户可开关「跟随壁纸取色」，
 *    开启时经 dynamicColorScheme 从壁纸种子色派生整套色板 —— 学习
 *    Google 的个性化色彩语言，同时保留深渊默认色板作为关闭档。
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

/**
 * M3 字阶（对齐 Material 3 spec 的 15 级）—— 为什么显式全量声明：
 * 默认 Typography() 的行高 / 字距在中文长文案下密度失控（中文无升
 * 降部，行高节奏比拉丁字型更依赖显式值）。此处逐级钉死，全局信息
 * 层级（display 张力 / label 语义）从此单一事实源。
 */
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

/**
 * M3 形状阶梯 —— extraSmall(4) 输入内件 / small(8) 芯片 / medium(12)
 * 小卡 / large(16) 卡片 / extraLarge(28) 弹层与 Hero。业务侧形状从此
 * 引用 MaterialTheme.shapes.*，跨模块圆角语言一致（v0.8.0 起组件库
 * 与各 feature 逐步迁移）。
 */
private val AbyssShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 深渊主题（v0.8.0：Material 3 完整规范 + 动态取色档）。
 *
 * @param darkTheme 深浅色（默认跟随系统）
 * @param dynamicColor Android 12+ 且用户开启「跟随壁纸取色」时，整套
 *   色板从壁纸种子色派生（Material You）；老系统或关闭时静默回落深渊
 *   色板 —— 学习 Google 的个性化，但永不因能力缺失而闪退（fail-open
 *   只作用于装饰层）。
 */
@Composable
fun AbyssTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) AbyssDarkColors else AbyssLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AbyssTypography,
        shapes = AbyssShapes,
        content = content,
    )
}
