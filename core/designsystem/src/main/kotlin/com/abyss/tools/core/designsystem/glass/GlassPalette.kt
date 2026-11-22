package com.abyss.tools.core.designsystem.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * ═══════════════════════════════════════════════════════════════════
 *  液态玻璃明暗双档色板（v0.9.6）
 * ═══════════════════════════════════════════════════════════════════
 *
 * 单一事实源：所有玻璃栏/液滴的色值从本对象派生，由 Theme 注入，
 * 禁止文件级常量写死深色值（修 v0.9.5 白天模式硬编码近黑 Bug）。
 *
 * 设计目标：
 *  - 浅色档：iOS 26 风格白色液态玻璃，135°对角渐变白膜（38%→8%），
 *    冷灰厚度阴影；
 *  - 深色档：保留原有近黑深渊美学（30%→5% 黑膜），整体观感不变。
 */
data class AbyssGlassPalette(
    /** 整栏渐变起点（亮角） */
    val barTintStart: Color,
    /** 整栏渐变终点（暗角） */
    val barTintEnd: Color,
    /** 高光描边（rim） */
    val rimColor: Color,
    /** 内阴影/厚度阴影色 */
    val innerShadowColor: Color,
    /** 外投影颜色（冷灰，不再纯黑） */
    val ambientShadowColor: Color,
    val spotShadowColor: Color,
    /** Highlight 高光的 BlendMode：浅色用 SrcOver 避免 Plus 过曝发灰 */
    val highlightBlendMode: androidx.compose.ui.graphics.BlendMode,
    /** 液滴膜 tint（深色近透明，浅色稍显白） */
    val dropletTint: Color,
    /** 选中色薄膜 alpha：浅色模式提至 0.20 保证对比 */
    val dropletIndicatorAlpha: Float,
    /** Clear 档液滴膜 tint（FEAT-033）：极薄白/黑纱（≤0.08 alpha），中心近乎纯透明 */
    val clearDropletTint: Color,
    /** Clear 档选中色薄膜 alpha（FEAT-033）：显著低于 Frosted 档，保证背景文字/图标清晰可读 */
    val clearIndicatorAlpha: Float,
) {
    companion object {
        /** 浅色档：白色液态玻璃 */
        val Light = AbyssGlassPalette(
            barTintStart = Color.White.copy(alpha = 0.38f),
            barTintEnd = Color.White.copy(alpha = 0.08f),
            rimColor = Color.White.copy(alpha = 0.55f),
            innerShadowColor = Color(0xFF0F172A).copy(alpha = 0.10f),
            ambientShadowColor = Color(0xFF0F172A).copy(alpha = 0.12f),
            spotShadowColor = Color(0xFF0F172A).copy(alpha = 0.12f),
            highlightBlendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
            dropletTint = Color.White.copy(alpha = 0.32f),
            dropletIndicatorAlpha = 0.20f,
            clearDropletTint = Color.White.copy(alpha = 0.06f),
            clearIndicatorAlpha = 0.08f,
        )

        /** 深色档：深渊近黑（保留原有美学） */
        val Dark = AbyssGlassPalette(
            barTintStart = Color(0xFF0A0E13).copy(alpha = 0.30f),
            barTintEnd = Color(0xFF0A0E13).copy(alpha = 0.05f),
            rimColor = Color.White.copy(alpha = 0.42f),
            innerShadowColor = Color.Black.copy(alpha = 0.35f),
            ambientShadowColor = Color.Black.copy(alpha = 0.50f),
            spotShadowColor = Color.Black.copy(alpha = 0.50f),
            highlightBlendMode = androidx.compose.ui.graphics.BlendMode.Plus,
            dropletTint = Color(0x260A0E13),
            dropletIndicatorAlpha = 0.14f,
            // 0x0A0E13 @ alpha 0.04（0x0A/255 ≈ 0.039）
            clearDropletTint = Color(0x0A0A0E13),
            clearIndicatorAlpha = 0.06f,
        )
    }
}

/** CompositionLocal：由 AbyssTheme 注入，业务侧只读不写。 */
val LocalAbyssGlassPalette = compositionLocalOf<AbyssGlassPalette> {
    error("LocalAbyssGlassPalette not provided — wrap in AbyssTheme")
}

/** 便捷访问：当前主题下的玻璃色板。 */
object AbyssGlass {
    val palette: AbyssGlassPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalAbyssGlassPalette.current
}

/**
 * 根据 M3 主色派生玻璃色板（动态取色档使用）。
 * 取色板基础上做白膜/黑膜覆盖，保持玻璃质感又不丢失主题主色。
 */
@Composable
fun derivedGlassPalette(darkTheme: Boolean): AbyssGlassPalette {
    val scheme = MaterialTheme.colorScheme
    return if (darkTheme) {
        // 动态深色：基于 surface 做黑膜
        AbyssGlassPalette.Dark.copy(
            barTintStart = scheme.surface.copy(alpha = 0.30f),
            barTintEnd = scheme.surface.copy(alpha = 0.05f),
        )
    } else {
        // 动态浅色：基于 surface 做白膜
        AbyssGlassPalette.Light.copy(
            barTintStart = Color.White.copy(alpha = 0.42f)
                .compositeOver(scheme.surface.copy(alpha = 0.10f)),
            barTintEnd = Color.White.copy(alpha = 0.06f),
        )
    }
}
