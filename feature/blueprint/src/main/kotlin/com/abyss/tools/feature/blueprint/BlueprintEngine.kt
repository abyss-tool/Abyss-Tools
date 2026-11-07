package com.abyss.tools.feature.blueprint

import com.abyss.tools.core.shell.ShellGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/**
 * 单步执行结果（实时回调与 runs.json 历史共用的落盘形态）。
 */
@Serializable
data class StepResult(
    val index: Int,
    val type: StepType,
    val ok: Boolean,
    /** 仅 SHELL：命令退出码；null = 通道失败 / 超时（未拿到退出码）。 */
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String? = null,
    /** 人话备注：TOAST 文案 / DELAY 说明 / 失败原因。 */
    val message: String? = null,
    val elapsedMs: Long = 0,
)

/** 一次完整执行（含中断链：results 只含已执行的步骤）。 */
@Serializable
data class BlueprintRun(
    val blueprintId: String,
    val startedAt: Long,
    val totalMs: Long,
    val success: Boolean,
    val results: List<StepResult> = emptyList(),
)

/**
 * 蓝图执行引擎（纯 suspend 编排，零 Android 依赖 —— 可单测）。
 *
 * 步骤语义：
 *  - SHELL → gateway.exec(content)（Shizuku adb shell 上下文）。成功判定：
 *    exitCode == 0 且（successProbe 为空，或 stdout 包含 successProbe）。
 *    执行包裹 withContext(Dispatchers.IO) + withTimeout(timeoutSeconds)：
 *    通道异常 / 超时均记为失败（人话原因），continueOnFailure 决定是否中断整链；
 *  - TOAST → 恒成功（message = 文案，UI 层负责真正的 Toast 展示）；
 *  - DELAY → content 毫秒数 delay 后恒成功（非法毫秒 → 该步失败，fail-closed）。
 *
 * 变量替换：后续步骤的 content 中 `{prev_output}` 会被替换为「最近一次 SHELL
 * 步骤的 stdout」（仅 SHELL 步骤产生该值；TOAST/DELAY 之间的传递不断链）。
 * 若此前没有任何 SHELL 步骤，占位符原样保留（可观察、不静默猜值）。
 *
 * 回调：onStepStart(index) 在每步开始前、onStepDone(result) 在每步结束后
 * 调用（均在调用方协程上下文，UI 层可安全直接更新 Compose 状态）。
 */
object BlueprintEngine {

    /** 引擎唯一支持的变量占位符。 */
    const val PREV_OUTPUT_VAR = "{prev_output}"

    suspend fun execute(
        manifest: BlueprintManifest,
        gateway: ShellGateway,
        onStepStart: (Int) -> Unit = {},
        onStepDone: (StepResult) -> Unit = {},
    ): BlueprintRun {
        val startedAt = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        val results = mutableListOf<StepResult>()
        var prevShellOutput: String? = null

        for ((index, step) in manifest.steps.withIndex()) {
            onStepStart(index)
            val result = when (step.type) {
                StepType.SHELL ->
                    runShellStep(index, step, gateway, prevShellOutput)
                        .also { prevShellOutput = it.stdout }

                StepType.TOAST -> toastStep(index, step, prevShellOutput)

                StepType.DELAY -> delayStep(index, step)
            }
            results += result
            onStepDone(result)
            if (!result.ok && !step.continueOnFailure) break
        }

        return BlueprintRun(
            blueprintId = manifest.id,
            startedAt = startedAt,
            totalMs = elapsedMs(startedNanos),
            success = results.all { it.ok },
            // 命名参数漏传会静默吃掉整份结果列表（默认 emptyList）—— 单测
            // 以 results[x] 断言逐条守护，缺一不可
            results = results,
        )
    }

    // ── SHELL ─────────────────────────────────────────────────────────────

    private suspend fun runShellStep(
        index: Int,
        step: BlueprintStep,
        gateway: ShellGateway,
        prevShellOutput: String?,
    ): StepResult {
        val command = applyVariables(step.content, prevShellOutput)
        val startedNanos = System.nanoTime()
        return try {
            // IO 在外、withTimeout 在内：超时计时与命令执行同在 IO 线程，
            // 真实墙钟语义（调度器里无虚拟时钟，单测里也稳定可测）。
            val outcome = withContext(Dispatchers.IO) {
                withTimeout(step.timeoutSeconds.coerceAtLeast(1) * 1000L) {
                    gateway.exec(command)
                }
            }
            outcome.fold(
                onSuccess = { shell ->
                    val exitOk = shell.exitCode == 0
                    val probeOk = step.successProbe == null || shell.stdout.contains(step.successProbe)
                    StepResult(
                        index = index,
                        type = StepType.SHELL,
                        ok = exitOk && probeOk,
                        exitCode = shell.exitCode,
                        stdout = shell.stdout,
                        stderr = shell.stderr,
                        message = if (exitOk && probeOk) null else shellFailureMessage(exitOk, probeOk),
                        elapsedMs = elapsedMs(startedNanos),
                    )
                },
                onFailure = { e ->
                    // Shizuku 未运行 / 未授权 / 通道断开 → 人话失败，fail-closed
                    StepResult(
                        index = index,
                        type = StepType.SHELL,
                        ok = false,
                        exitCode = null,
                        stdout = "",
                        stderr = null,
                        message = "命令通道失败：${e.message ?: e.javaClass.simpleName}",
                        elapsedMs = elapsedMs(startedNanos),
                    )
                },
            )
        } catch (e: TimeoutCancellationException) {
            // 只吞本步超时（精确捕获 TimeoutCancellationException）；
            // 外层协程取消（结构化并发）原样向外传播
            StepResult(
                index = index,
                type = StepType.SHELL,
                ok = false,
                exitCode = null,
                stdout = "",
                stderr = null,
                message = "执行超时（超过 ${step.timeoutSeconds} 秒），本步已中止",
                elapsedMs = elapsedMs(startedNanos),
            )
        }
    }

    private fun shellFailureMessage(exitOk: Boolean, probeOk: Boolean): String = buildString {
        if (!exitOk) append("命令退出码非 0（失败）")
        if (!probeOk) {
            if (isNotEmpty()) append("；")
            append("输出中未探测到成功标记（successProbe）")
        }
    }

    // ── TOAST / DELAY ─────────────────────────────────────────────────────

    private fun toastStep(index: Int, step: BlueprintStep, prevShellOutput: String?): StepResult =
        StepResult(
            index = index,
            type = StepType.TOAST,
            ok = true,
            message = applyVariables(step.content, prevShellOutput),
            elapsedMs = 0,
        )

    private suspend fun delayStep(index: Int, step: BlueprintStep): StepResult {
        val startedNanos = System.nanoTime()
        val ms = step.content.trim().toIntOrNull()
        return if (ms == null || ms !in BlueprintParser.DELAY_MIN_MS..BlueprintParser.DELAY_MAX_MS) {
            // parse 阶段已拦截；程序化构造的越值清单在此兜底拒绝（fail-closed）
            StepResult(
                index = index,
                type = StepType.DELAY,
                ok = false,
                message = "DELAY 毫秒数「${step.content}」无效" +
                    "（必须在 ${BlueprintParser.DELAY_MIN_MS}..${BlueprintParser.DELAY_MAX_MS}）",
                elapsedMs = elapsedMs(startedNanos),
            )
        } else {
            delay(ms.toLong())
            StepResult(
                index = index,
                type = StepType.DELAY,
                ok = true,
                message = "等待 $ms 毫秒",
                elapsedMs = elapsedMs(startedNanos),
            )
        }
    }

    // ── 变量替换 ──────────────────────────────────────────────────────────

    private fun applyVariables(content: String, prevShellOutput: String?): String =
        if (prevShellOutput == null || !content.contains(PREV_OUTPUT_VAR)) {
            content
        } else {
            content.replace(PREV_OUTPUT_VAR, prevShellOutput)
        }

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000
}
