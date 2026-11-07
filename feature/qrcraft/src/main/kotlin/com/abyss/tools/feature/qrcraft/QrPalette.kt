package com.abyss.tools.feature.qrcraft

import androidx.compose.ui.graphics.Color

/** 一组配色预设（前景 = 码点色，背景 = 静区/底色）。 */
data class QrColorPreset(
    val name: String,
    val foreground: Color,
    val background: Color,
)

/**
 * 二维码配色常量（唯一来源 —— 十六进制全部集中在此，禁止散落硬编码）。
 *
 * 全部「深色前景 × 浅色背景」：zxing 的 RGBLuminanceSource 按 RGB 加权算
 * 亮度，深前景浅背景是稳定可扫的必要条件；刻意避开纯蓝紫系（工程视觉纪律）。
 * 色系：深渊青（onSurface 同族）两档 + 墨黑纸白 + 暖橙 + 墨绿 + 绯红。
 */
object QrPalette {

    val presets: List<QrColorPreset> = listOf(
        QrColorPreset(name = "深渊青", foreground = Color(0xFF08363E), background = Color(0xFFE6F6F9)),
        QrColorPreset(name = "深海", foreground = Color(0xFF04222A), background = Color(0xFFBFE9F2)),
        QrColorPreset(name = "墨黑纸白", foreground = Color(0xFF141414), background = Color(0xFFFFFFFF)),
        QrColorPreset(name = "暖橙", foreground = Color(0xFF7A3B00), background = Color(0xFFFFF1E0)),
        QrColorPreset(name = "墨绿", foreground = Color(0xFF0C3D26), background = Color(0xFFE3F5EB)),
        QrColorPreset(name = "绯红", foreground = Color(0xFF77121F), background = Color(0xFFFBE9EC)),
    )
}
