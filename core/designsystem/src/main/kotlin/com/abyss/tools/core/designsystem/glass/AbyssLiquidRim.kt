package com.abyss.tools.core.designsystem.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.sign

/**
 * 液滴边缘渐变流光（soap-bubble rim / specular sheen）——
 * 「滑动时光带跟着流动」的实现层。
 *
 * 光学模型（AGSL，API 33+）：
 *  1. `sdCapsule` SDF 求液滴有向距离（CircleShape 胶囊 = r = min(size)/2，
 *     满足 vendored `Lens.kt` 对 CornerBasedShape 的同款约束）；
 *  2. 数值差分 SDF 梯度 → 伪 3D 法线：边缘朝外抬升、中心近乎朝上；
 *  3. `rim = exp(-dist/w)` 边缘一圈衰减 + `pow(|dot(n,L)|, 9)` 左上光源
 *     镜面 + `pow(1-N.z, 3)` Fresnel；
 *  4. 沿 x 的高斯光带：中心 `sheen = 0.5 - sign(velocity)*stretch*0.35`
 *     —— 光带被甩向运动**反方向**，拉伸越大偏移与展宽越大；
 *  5. 返回**预乘 alpha**（Compose 合成器要求）。
 *
 * 双模式（FEAT-033）：[AbyssRimMode.FLOW] 为现状（速度驱动流光，参数
 * 逐字冻结）；[AbyssRimMode.CLEAR] 为清透玻璃档 —— 光带冻结中心、rim
 * 增益 1.45、四角镜面点光、相对侧（右下）轻暗边，**静止与运动下高光
 * 均静态可见、不随速度位移**（FR-005）。
 *
 * 零耗电纪律（硬条件，改动前必读）：
 *  - 不用 `infiniteTransition` / `withFrameMillis` 常驻循环 —— 常驻底栏的
 *    无限缓动高光会让采样背景每帧重录（实机 ~45fps 全屏重采样、~400mA）；
 *  - 运动状态只在拖动 / 切 tab 时变值；静止时 State 不写 → draw 不失效
 *    → **零 invalidate**；
 *  - [RuntimeShader] 只在 `drawWithCache` 里构建一次，每帧只
 *    `setFloatUniform`；motion 状态在 draw 阶段读取（不进重组）。
 *
 * 降级：API 31–32 走横向渐变档（FLOW 档光带中心/展宽同样随 motion 绘制
 * 阶段响应，无 SDF 边缘精度；CLEAR 档为静态三段式：上缘亮线 + 下缘暗边 +
 * 左右水平渐隐，不读 motion）；API < 31 / 安全模式：液滴本身已是 M3 卡面
 * 档（零 RenderEffect），流光只是普通 paint 绘制 —— 不触发任何采样/录制，
 * 与 GlassRecordingGuard / RenderSafeMode 全兼容。
 */

/** 液滴边缘光模式（FEAT-033）：FLOW = 速度驱动流光（现状冻结）；CLEAR = 静态玻璃高光。 */
enum class AbyssRimMode { FLOW, CLEAR }

fun Modifier.abyssLiquidRim(
    motion: AbyssLiquidMotion,
    mode: AbyssRimMode = AbyssRimMode.FLOW,
    rimColor: Color = Color.White,
): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rimShader(motion, mode)
    } else {
        rimGradient(motion, mode, rimColor)
    }

/** AGSL 档：RuntimeShader 一次构建，每帧仅 uniform 更新（含 uEdgeMode）。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.rimShader(motion: AbyssLiquidMotion, mode: AbyssRimMode): Modifier =
    drawWithCache {
        val shader = RuntimeShader(RIM_AGSL)
        onDrawBehind {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uStretch", motion.stretch.value)
            shader.setFloatUniform("uVelocity", motion.velocity.value)
            shader.setFloatUniform("uEdgeMode", if (mode == AbyssRimMode.CLEAR) 1f else 0f)
            drawRect(brush = ShaderBrush(shader))
        }
    }

/** 渐变档（API 31–32）：横向高斯光带近似，父容器 CircleShape 已裁剪。 */
private fun Modifier.rimGradient(
    motion: AbyssLiquidMotion,
    mode: AbyssRimMode,
    rimColor: Color,
): Modifier =
    drawWithCache {
        val h = size.height
        val w = size.width
        if (mode == AbyssRimMode.CLEAR) {
            // CLEAR（FEAT-033 §4.3）：静态三段式 —— 上缘 1dp 亮线（rimColor）
            // + 下缘暗边 + 左右水平渐隐；不读 motion（静止零 invalidate，
            // 亦不随速度位移）。
            onDrawBehind {
                val fade = Brush.horizontalGradient(
                    0f to rimColor.copy(alpha = 0f),
                    0.18f to rimColor.copy(alpha = 1f),
                    0.82f to rimColor.copy(alpha = 1f),
                    1f to rimColor.copy(alpha = 0f),
                )
                // 上缘 1dp 亮线（左右渐隐由同一渐变罩承担）
                drawRect(brush = fade, topLeft = Offset.Zero, size = Size(w, 1.dp.toPx()))
                // 下缘暗边（相对侧轻暗）
                val darkFade = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0f),
                    0.18f to Color.Black.copy(alpha = 0.10f),
                    0.82f to Color.Black.copy(alpha = 0.10f),
                    1f to Color.Black.copy(alpha = 0f),
                )
                drawRect(
                    brush = darkFade,
                    topLeft = Offset(0f, h - 2.dp.toPx()),
                    size = Size(w, 2.dp.toPx()),
                )
            }
        } else {
            onDrawBehind {
                val stretch = motion.stretch.value
                val velocity = motion.velocity.value
                val bandCenter = 0.5f - sign(velocity) * stretch * 0.35f
                val bandWidth = w * (0.62f + stretch * 0.38f)
                val brush = Brush.horizontalGradient(
                    0f to rimColor.copy(alpha = 0f),
                    0.5f to rimColor.copy(alpha = 0.16f + stretch * 0.12f),
                    1f to rimColor.copy(alpha = 0f),
                    startX = 0f,
                    endX = bandWidth,
                )
                translate(left = bandCenter * w - bandWidth / 2f) {
                    drawRect(brush = brush, topLeft = Offset.Zero, size = Size(bandWidth, h))
                }
            }
        }
    }

/**
 * 边缘流光 shader。uSize = 液滴像素尺寸；uStretch ∈ [0,1] 横向拉伸；
 * uVelocity ∈ [-1,1] 归一化速度；uEdgeMode：0 = FLOW（速度甩光带），
 * 1 = CLEAR（光带冻结中心 + rim 增益 + 四角点光 + 相对侧暗边）。
 * 冷白高光、预乘 alpha 输出。
 */
private const val RIM_AGSL = """
uniform float2 uSize;
uniform float uStretch;
uniform float uVelocity;
uniform float uEdgeMode;

float sdCapsule(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fragCoord) {
    float2 halfSize = uSize * 0.5;
    float2 p = fragCoord - halfSize;
    float r = min(halfSize.x, halfSize.y);
    float d = sdCapsule(p, halfSize, r);
    if (d > 0.0) { return half4(0.0); }

    // 数值差分 SDF 梯度 → 伪 3D 法线（边缘朝外抬升、中心近乎朝上）
    float e = 1.5;
    float2 g = float2(
        sdCapsule(p + float2(e, 0.0), halfSize, r) - sdCapsule(p - float2(e, 0.0), halfSize, r),
        sdCapsule(p + float2(0.0, e), halfSize, r) - sdCapsule(p - float2(0.0, e), halfSize, r)
    ) / (2.0 * e);
    float inner = max(-d, 0.0);
    float depth = clamp(inner / max(uSize.y, 1.0), 0.0, 1.0);
    float3 n = normalize(float3(g, mix(0.42, 0.05, depth)));

    // 边缘一圈 + 左上光源镜面 + Fresnel
    float rim = exp(-inner / max(uSize.y * 0.18, 1.0));
    float3 L = normalize(float3(-0.48, -0.62, 0.62));
    float spec = pow(clamp(dot(n, L), 0.0, 1.0), 9.0);
    float fresnel = pow(1.0 - n.z, 3.0);

    // 高斯光带：FLOW 被速度甩向运动反方向；CLEAR 冻结中心（FR-005 不位移）
    float bx = fragCoord.x / max(uSize.x, 1.0);
    float bandCenter;
    float spread;
    if (uEdgeMode > 0.5) {
        bandCenter = 0.5;
        spread = 1.9;
    } else {
        bandCenter = 0.5 - sign(uVelocity) * uStretch * 0.35;
        spread = 1.9 + uStretch * 1.3;
    }
    float band = exp(-(bx - bandCenter) * (bx - bandCenter) * spread * spread);

    float sheen;
    if (uEdgeMode > 0.5) {
        // CLEAR（FEAT-033）：静态玻璃高光 —— rim 增益 1.45、四角镜面点光、
        // 相对侧（右下）轻暗边；全部静态几何光，零常驻 invalidate。
        float2 ex = float2(halfSize.x - r * 0.8, r * 0.42);
        float rr = r * r * 0.14;
        float corner =
            exp(-dot(p - float2(-ex.x, -ex.y), p - float2(-ex.x, -ex.y)) / rr) +
            exp(-dot(p - float2( ex.x, -ex.y), p - float2( ex.x, -ex.y)) / rr) +
            exp(-dot(p - float2(-ex.x,  ex.y), p - float2(-ex.x,  ex.y)) / rr) +
            exp(-dot(p - float2( ex.x,  ex.y), p - float2( ex.x,  ex.y)) / rr);
        // 相对侧轻暗边：光源左上 → 暗边权重在右下（q ∈ 归一化坐标）
        float2 q = p / max(halfSize, float2(1.0, 1.0));
        float opposite = clamp(dot(q, float2(0.48, 0.62)), 0.0, 1.0);
        float darkEdge = rim * opposite * 0.22;

        float base = rim * (0.30 + 0.70 * fresnel) * (0.55 + 0.45 * spec) * 1.45;
        base = base * band * 1.35 + rim * spec * 0.55 * 1.45;
        sheen = base + corner * 0.85 * (0.35 + 0.65 * rim) - darkEdge;
    } else {
        sheen = rim * (0.30 + 0.70 * fresnel) * (0.55 + 0.45 * spec);
        sheen = sheen * band * 1.35 + rim * spec * 0.55;
    }

    float a = clamp(sheen, 0.0, 1.0);
    return half4(a * 0.92, a * 0.96, a, a);
}
"""
