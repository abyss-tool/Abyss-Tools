package com.abyss.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.abyss.tools.core.shell.ShizukuMonitor
import com.abyss.tools.core.shell.ShizukuStatus

/**
 * Shizuku 状态 → Compose 状态（唯一桥接点）。
 *
 * ShizukuMonitor 是纯监听器扇出（core:shell 不依赖 Compose —— 保持
 * 该模块可被非 UI 消费方复用）；Compose 侧的生命周期绑定统一收敛在
 * 这一个文件里。
 */
@Composable
fun rememberShizukuStatus(monitor: ShizukuMonitor): State<ShizukuStatus> {
    val state = remember { mutableStateOf<ShizukuStatus>(ShizukuStatus.NotRunning) }
    DisposableEffect(monitor) {
        val listener: (ShizukuStatus) -> Unit = { snapshot -> state.value = snapshot }
        monitor.addListener(listener)
        onDispose { monitor.removeListener(listener) }
    }
    return state
}
