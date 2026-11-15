package com.abyss.tools.core.common

import java.io.File

/**
 * 渲染安全模式断路器（v0.7.3 → v2，v0.7.4）—— 「App 永远能打开」的最后防线。
 *
 * 背景：v0.7.1 启动秒闪退是 RenderThread 原生栈溢出 —— Java 层
 * CrashGuard 拦不住原生崩溃，用户面对的是无限闪退循环、连诊断页都
 * 进不去。本断路器从「进程死亡 → 快速重启」的外部行为特征识别闪退
 * 循环（不依赖崩溃类型，Java / 原生全适用），触发后强制下一启动进入
 * 渲染安全模式（glassEnabled = false）：禁用全部液态玻璃采样与
 * RenderEffect（Frosted 降级档），保证主壳可用、诊断可读。
 *
 * v2（bugreport PLE110 2026-11-15 教训）：v1 只认「间隔 < 20s 的快速
 * 冷启动」，而真实世界的慢性闪退循环**会话可存活数分钟** —— 渲染栈
 * 慢性增长类崩溃（液态玻璃层跨帧累积）要 5~8 分钟才把 RenderThread
 * 栈耗尽。会话一旦存活超过 [STABLE_AFTER_MS]，markStable 即清零计数
 * → 同一崩溃每天循环 N 次也永远不触发。v2 新增**连崩计数**：
 *  - 上一会话**未干净退出**（无 markCleanExit 标记 = 崩溃 / 被系统
 *    杀死）→ crashStreak + 1，**与间隔无关**；
 *  - 上一会话干净退出（用户主动离开，isFinishing 销毁）→ 归零；
 *  - crashStreak ≥ [TRIGGER_CRASH_STREAK] → 触发安全模式。
 * 代价（诚实声明）：滑卡清理 / 后台 LRU 回收也不留干净退出标记 ——
 * 连续 3 次「非主动退出」即误触发。后果仅是 Frosted 降级（可从横幅 /
 * 诊断页解释与重置），且正常使用一次（存活 ≥ 45s 后主动退出）即自愈
 * —— 保守方向正确：宁可偶发降级，不可无限闪退。
 *
 * 语义总表：
 *  - 冷启动间隔 < [RAPID_WINDOW_MS] → rapidRestarts +1，否则重新从 1 计；
 *  - rapidRestarts ≥ [TRIGGER_RAPID_RESTARTS]（快速循环）或
 *    crashStreak ≥ [TRIGGER_CRASH_STREAK]（慢性循环）或上次已 engaged
 *    → engage；
 *  - markStable（会话存活 ≥ [STABLE_AFTER_MS]）：只清 rapidRestarts +
 *    engaged —— **保留 crashStreak**（慢性循环不因「这次活得久一点」
 *    而遗忘），直到一次干净退出才归零；
 *  - 温重建（旋转 / 进程内 Activity 重建）不计数（由调用方进程门闩
 *    区分冷启动，本类不感知）。
 *
 * 设计纪律（对齐 v1）：
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

    /** 会话存活该时长后清零快速计数与 engaged（下次冷启动恢复正常档）。 */
    const val STABLE_AFTER_MS = 45_000L

    /**
     * 连续异常终止会话达到该次数 → 触发安全模式（v2：慢性闪退循环，
     * 会话存活数分钟才崩、快速窗口永远盖不住的那种）。
     */
    const val TRIGGER_CRASH_STREAK = 3

    /** 断路器状态（五行文本文件：lastLaunchAt / rapidRestarts / engaged / crashStreak / lastCleanExit）。 */
    data class State(
        val lastLaunchAt: Long = 0,
        val rapidRestarts: Int = 0,
        val engaged: Boolean = false,
        val crashStreak: Int = 0,
        val lastCleanExit: Boolean = true,
    )

    /**
     * 玻璃档位（v0.7.8 分级降级）—— 在「正常渲染 ↔ 全 Frosted」之间
     * 插入中间档，让断路器第一次异常终止就止血，而不是等满 3 连崩：
     *
     *  - [FULL]：vibrancy + blur + lens 全效果（默认档）；
     *  - [NO_LENS]：停 AGSL RuntimeShader（lens 折射/色散）—— 各 OEM
     *    GPU 驱动最脆弱的一环，保留 vibrancy + blur（代价最小的一档）；
     *  - [FROSTED]：全 Frosted 降级（零 RenderEffect / 零采样，原断路器
     *    engage 语义，阈值不变）。
     *
     * 映射（见 [deriveGlassMode]）：engage → FROSTED；连崩 1~2 →
     * NO_LENS；连崩 0 → FULL。诚实声明：连崩计数把「滑卡清理 / LRU
     * 回收」也计为异常终止（v2 已知取舍）—— 误伤代价 = lens 停一两个
     * 会话，一次干净退出自愈，方向保守正确。
     */
    enum class GlassMode { FULL, NO_LENS, FROSTED }

    /** 冷启动判定结果。[glassMode] 为本次会话应使用的玻璃档位。 */
    data class Decision(
        val engage: Boolean,
        val rapidRestarts: Int,
        val glassMode: GlassMode = if (engage) GlassMode.FROSTED else GlassMode.FULL,
    )

    /** 档位推导（纯函数，单测锚点）：engage 压倒一切，1~2 连崩落中间档。 */
    fun deriveGlassMode(engage: Boolean, crashStreak: Int): GlassMode = when {
        engage -> GlassMode.FROSTED
        crashStreak in 1..2 -> GlassMode.NO_LENS
        else -> GlassMode.FULL
    }

    /**
     * 冷启动调用（进程首个 Activity.onCreate）：计数 + 判定 + 落盘。
     * engaged 粘滞 —— 上次 engaged 则本次保持（直到 markStable / reset）。
     * 本会话先按「未干净退出」落盘：异常终止（默认）不留标记，正常
     * 退出由 markCleanExit 补写。
     */
    fun onColdStart(file: File, now: Long = System.currentTimeMillis()): Decision {
        val prev = readState(file)
        val rapid = if (now - prev.lastLaunchAt in 0 until RAPID_WINDOW_MS) {
            prev.rapidRestarts + 1
        } else {
            1
        }
        val streak = if (prev.lastCleanExit) 0 else prev.crashStreak + 1
        val engage = prev.engaged || rapid >= TRIGGER_RAPID_RESTARTS || streak >= TRIGGER_CRASH_STREAK
        write(
            file,
            State(
                lastLaunchAt = now,
                rapidRestarts = rapid,
                engaged = engage,
                crashStreak = streak,
                lastCleanExit = false,
            ),
        )
        return Decision(
            engage = engage,
            rapidRestarts = rapid,
            glassMode = deriveGlassMode(engage, streak),
        )
    }

    /**
     * 温重建调用（同进程内 Activity 重建）：只读判定，不计数。
     * 档位推导考虑干净退出（v0.7.8 修复）：cleanExit = true 即慢性循环
     * 已自愈 —— 温重建 / 诊断探针按 FULL 报告，不再拿旧 streak 报降级档
     * （streak 本体保留在状态里，下次冷启动语义不变）。
     */
    fun currentDecision(file: File): Decision {
        val s = readState(file)
        val effectiveStreak = if (s.lastCleanExit) 0 else s.crashStreak
        return Decision(
            engage = s.engaged,
            rapidRestarts = s.rapidRestarts,
            glassMode = deriveGlassMode(s.engaged, effectiveStreak),
        )
    }

    /**
     * 本会话存活达标：只清快速计数与 engaged —— 保留 crashStreak 与
     * 退出标记（慢性循环不遗忘；「活得久但没主动退出」仍是异常终止）。
     */
    fun markStable(file: File, now: Long = System.currentTimeMillis()) {
        val prev = readState(file)
        write(
            file,
            prev.copy(lastLaunchAt = now, rapidRestarts = 0, engaged = false),
        )
    }

    /**
     * 干净退出标记（v2）：用户主动离开（Activity isFinishing 销毁）时
     * 补写 —— 崩溃 / 被杀走不到这里，下次冷启动即按异常终止计连崩。
     */
    fun markCleanExit(file: File) {
        val prev = readState(file)
        write(file, prev.copy(lastCleanExit = true))
    }

    /** 设置页「重置渲染安全模式」：全零（下次启动生效）。 */
    fun reset(file: File) = write(file, State())

    /** 读状态（无文件 / 损坏 → 全零默认，fail-open）。 */
    fun readState(file: File): State = runCatching {
        if (!file.exists()) return State()
        val lines = file.readLines()
        State(
            lastLaunchAt = lines.getOrNull(0)?.toLongOrNull() ?: 0,
            rapidRestarts = lines.getOrNull(1)?.toIntOrNull() ?: 0,
            engaged = lines.getOrNull(2) == "1",
            // 旧版（v0.7.3 三行格式）无第 4/5 行：fail-open 中性处理 ——
            // 连崩从 0 起算、视为已干净退出，不惩罚升级用户的第一印象。
            crashStreak = lines.getOrNull(3)?.toIntOrNull() ?: 0,
            lastCleanExit = if (lines.size > 4) lines[4] == "1" else true,
        )
    }.getOrDefault(State())

    private fun write(file: File, state: State) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                listOf(
                    state.lastLaunchAt.toString(),
                    state.rapidRestarts.toString(),
                    if (state.engaged) "1" else "0",
                    state.crashStreak.toString(),
                    if (state.lastCleanExit) "1" else "0",
                ).joinToString(separator = "\n", postfix = "\n"),
            )
        }
    }
}
