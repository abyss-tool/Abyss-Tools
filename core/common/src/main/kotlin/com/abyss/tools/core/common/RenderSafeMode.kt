package com.abyss.tools.core.common

import java.io.File

/**
 * 渲染安全模式断路器（v0.7.3，P0）—— 「App 永远能打开」的最后防线。
 *
 * 背景：v0.7.1 启动秒闪退是 RenderThread 原生栈溢出 —— Java 层
 * CrashGuard 拦不住原生崩溃，用户面对的是无限闪退循环、连诊断页都
 * 进不去。本断路器从「进程死亡 → 快速重启」的外部行为特征识别闪退
 * 循环（不依赖崩溃类型，Java / 原生全适用），连续 TRIGGER 次快速
 * 冷启动后强制下一启动进入渲染安全模式（glassEnabled = false）：
 * 禁用全部液态玻璃采样与 RenderEffect（Frosted 降级档），保证主壳
 * 可用、诊断可读。
 *
 * 语义：
 *  - 冷启动间隔 < [RAPID_WINDOW_MS] → rapidRestarts +1，否则重新从 1 计；
 *  - rapidRestarts ≥ [TRIGGER_RAPID_RESTARTS] 或上次已 engaged → engage；
 *  - engaged 粘滞：直到本会话存活 ≥ [STABLE_AFTER_MS]（markStable 清零）
 *    或设置页手动 reset —— 期间每次启动都是安全模式；
 *  - 温重建（旋转 / 进程内 Activity 重建）不计数（由调用方进程门闩
 *    区分冷启动，本类不感知）。
 *
 * 设计纪律（对齐 CrashGuard）：
 *  - 纯 Kotlin（java.io），core:common 保持纯 JVM 可单测；
 *  - 一切 IO runCatching 静默兜底 —— 断路器自身【绝不抛】、绝不成为
 *    新的崩溃源（fail-open：读不到状态 = 未启用）。
 */
object RenderSafeMode {

    const val FILE_NAME = "render-safemode.txt"

    /** 连续快速冷启动达到该次数 → 触发安全模式。 */
    const val TRIGGER_RAPID_RESTARTS = 3

    /** 「快速」窗口：两次冷启动间隔小于该值视为同一次闪退循环。 */
    const val RAPID_WINDOW_MS = 20_000L

    /** 会话存活该时长后清零（下次冷启动恢复正常渲染档）。 */
    const val STABLE_AFTER_MS = 45_000L

    /** 断路器状态（三行文本文件：lastLaunchAt / rapidRestarts / engaged）。 */
    data class State(
        val lastLaunchAt: Long = 0,
        val rapidRestarts: Int = 0,
        val engaged: Boolean = false,
    )

    /** 冷启动判定结果。 */
    data class Decision(val engage: Boolean, val rapidRestarts: Int)

    /**
     * 冷启动调用（进程首个 Activity.onCreate）：计数 + 判定 + 落盘。
     * engaged 粘滞 —— 上次 engaged 则本次保持（直到 markStable / reset）。
     */
    fun onColdStart(file: File, now: Long = System.currentTimeMillis()): Decision {
        val prev = readState(file)
        val rapid = if (now - prev.lastLaunchAt in 0 until RAPID_WINDOW_MS) {
            prev.rapidRestarts + 1
        } else {
            1
        }
        val engage = prev.engaged || rapid >= TRIGGER_RAPID_RESTARTS
        write(file, State(lastLaunchAt = now, rapidRestarts = rapid, engaged = engage))
        return Decision(engage = engage, rapidRestarts = rapid)
    }

    /** 温重建调用（同进程内 Activity 重建）：只读判定，不计数。 */
    fun currentDecision(file: File): Decision {
        val s = readState(file)
        return Decision(engage = s.engaged, rapidRestarts = s.rapidRestarts)
    }

    /** 本会话存活达标（或设置页手动重置）：清零，下次冷启动恢复正常档。 */
    fun markStable(file: File, now: Long = System.currentTimeMillis()) {
        write(file, State(lastLaunchAt = now, rapidRestarts = 0, engaged = false))
    }

    /** 设置页「重置渲染安全模式」：与 markStable 等价（下次启动生效）。 */
    fun reset(file: File) = markStable(file)

    /** 读状态（无文件 / 损坏 → 全零默认，fail-open）。 */
    fun readState(file: File): State = runCatching {
        if (!file.exists()) return State()
        val lines = file.readLines()
        State(
            lastLaunchAt = lines.getOrNull(0)?.toLongOrNull() ?: 0,
            rapidRestarts = lines.getOrNull(1)?.toIntOrNull() ?: 0,
            engaged = lines.getOrNull(2) == "1",
        )
    }.getOrDefault(State())

    private fun write(file: File, state: State) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                "${state.lastLaunchAt}\n${state.rapidRestarts}\n${if (state.engaged) 1 else 0}\n",
            )
        }
    }
}
