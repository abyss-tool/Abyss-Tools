package com.abyss.tools.core.designsystem.component

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 全局动效系统（v0.7.5）：液态玻璃手感的三个通用件，业务页零直接
 * 动画 API（与玻璃纪律同构 —— 底层动效替换时只改本文件）。
 *
 *  - [rememberReducedMotion]：无障碍硬要求 —— 系统「移除动画」/ 开发者
 *    选项动画时长缩放 = 0 时，全部动效直切（转场 / 入场 / 按压全关）。
 *  - [Modifier.entranceStagger]：卡片错峰入场（alpha 0→1 + 上滑 16dp），
 *    屏内序号决定延迟步进，每张只播一次（LazyColumn 滚动复用不重播）。
 *  - [Modifier.pressScale]：按压缩放反馈（1f → 0.97，spring 回弹）——
 *    玻璃件的「果冻」手感，配合 ListRowCard / 入口卡统一使用。
 */

/** 屏幕转场时长（ms）：返回栈推入 / 弹出 / tab 切换共用的时间基准。 */
const val NAV_TRANSITION_MS = 260

/** 卡片错峰入场：单张时长 / 步进间隔 / 最大步数。 */
const val ENTRANCE_DURATION_MS = 320
const val ENTRANCE_STAGGER_STEP_MS = 40
const val ENTRANCE_MAX_STAGGER_STEPS = 8

/** 按压缩放比例（1f - PRESS_SCALE = 视觉下陷量，玻璃件统一手感）。 */
const val PRESS_SCALE = 0.97f

/**
 * 系统动画已被移除（「移除动画」无障碍开关 / 开发者选项动画时长缩放
 * = 0）。读取失败 fail-open 为 false（保留动效 —— 与断路器纪律同构，
 * 动效检测自身绝不能成为新的故障源）。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            val resolver = context.contentResolver
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
                Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * 卡片错峰入场：alpha 0→1 + 上滑 16dp。`index` 为卡片在屏内的序号 ——
 * 前 [ENTRANCE_MAX_STAGGER_STEPS] 张依次延迟步进形成波浪，更深的卡片
 * 立即入场（LazyColumn 滚动复用场景不让人等）。每张只播一次：
 * rememberSaveable 记「已播」，滚动出屏再回来不重播。
 *
 * 用法：`item { Box(Modifier.fillMaxWidth().entranceStagger(i)) { Card() } }`
 * —— 包裹 Box 而非侵入卡片签名，业务卡零改动。
 */
@Composable
fun Modifier.entranceStagger(index: Int): Modifier {
    if (rememberReducedMotion()) return this
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = ENTRANCE_DURATION_MS,
                    delayMillis = if (index < ENTRANCE_MAX_STAGGER_STEPS) {
                        index * ENTRANCE_STAGGER_STEP_MS
                    } else {
                        0
                    },
                    easing = FastOutSlowInEasing,
                ),
            )
            played = true
        }
    }
    return graphicsLayer {
        // draw 阶段读值：动画全程不重组（对齐玻璃 lambda 记忆化纪律）
        alpha = progress.value
        translationY = (1f - progress.value) * 16.dp.toPx()
    }
}

/**
 * 按压缩放反馈：按下 1f → [PRESS_SCALE]，spring 回弹 —— 玻璃件被指尖
 * 「按进液体」的手感。`interactionSource` 由调用方持有并同步喂给
 * clickable（ListRowCard 内建此接线，业务页通常无需直接使用本修饰符）。
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    if (rememberReducedMotion()) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "press_scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
