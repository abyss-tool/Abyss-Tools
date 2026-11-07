package com.abyss.tools.feature.blueprint

import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShellResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlueprintEngine 语义单测（runTest 虚拟时钟 + 可编程 FakeGateway）：
 * 全成功链 / 失败中断 / continueOnFailure / successProbe 判定 / DELAY 语义 /
 * 超时 / 变量替换 / 通道失败 / 进度回调。零 Android 依赖。
 */
class BlueprintEngineTest {

    /** 可编程假通道：按命令回调结果，并记录实际收到的命令序列。 */
    private class FakeGateway(
        private val handler: suspend (String) -> Result<ShellResult>,
    ) : ShellGateway {
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String): Result<ShellResult> {
            commands += command
            return handler(command)
        }
    }

    private fun okGateway(stdout: String = "") = FakeGateway {
        Result.success(ShellResult(exitCode = 0, stdout = stdout, stderr = ""))
    }

    private fun manifestOf(vararg steps: BlueprintStep, id: String = "test-bp") =
        BlueprintManifest(id = id, name = "测试蓝图", steps = steps.toList())

    // ── 全成功链 ─────────────────────────────────────────────────────────

    @Test
    fun `全成功链`() = runTest {
        val gateway = FakeGateway { command ->
            Result.success(ShellResult(exitCode = 0, stdout = "out[$command]", stderr = ""))
        }
        val run = BlueprintEngine.execute(
            manifestOf(
                BlueprintStep(type = StepType.SHELL, content = "cmd-one"),
                BlueprintStep(type = StepType.TOAST, content = "完成啦"),
                BlueprintStep(type = StepType.DELAY, content = "600000"),
            ),
            gateway,
        )

        assertTrue(run.success)
        assertEquals("test-bp", run.blueprintId)
        assertEquals(3, run.results.size)
        assertTrue(run.results.all { it.ok })
        assertEquals(listOf("cmd-one"), gateway.commands)
        assertEquals("out[cmd-one]", run.results[0].stdout)
        assertEquals(0, run.results[0].exitCode)
        assertEquals("完成啦", run.results[1].message)
        assertEquals(StepType.DELAY, run.results[2].type)
    }

    // ── 失败中断 ─────────────────────────────────────────────────────────

    @Test
    fun `失败中断后续步骤`() = runTest {
        val gateway = FakeGateway {
            Result.success(ShellResult(exitCode = 1, stdout = "", stderr = "boom"))
        }
        val run = BlueprintEngine.execute(
            manifestOf(
                BlueprintStep(type = StepType.SHELL, content = "first"),
                BlueprintStep(type = StepType.SHELL, content = "second"),
            ),
            gateway,
        )

        assertFalse(run.success)
        assertEquals("失败即中断：只留第一步结果", 1, run.results.size)
        assertEquals("第二步不应被送进通道", 1, gateway.commands.size)
        val failed = run.results[0]
        assertFalse(failed.ok)
        assertEquals(1, failed.exitCode)
        assertEquals("boom", failed.stderr)
        assertNotNull(failed.message)
    }

    // ── continueOnFailure ───────────────────────────────────────────────

    @Test
    fun `continueOnFailure 继续执行`() = runTest {
        var call = 0
        val gateway = FakeGateway {
            call += 1
            if (call == 1) {
                Result.success(ShellResult(exitCode = 2, stdout = "", stderr = "err"))
            } else {
                Result.success(ShellResult(exitCode = 0, stdout = "recovered", stderr = ""))
            }
        }
        val run = BlueprintEngine.execute(
            manifestOf(
                BlueprintStep(type = StepType.SHELL, content = "flaky", continueOnFailure = true),
                BlueprintStep(type = StepType.SHELL, content = "next"),
            ),
            gateway,
        )

        assertFalse("整体仍算失败", run.success)
        assertEquals(2, run.results.size)
        assertFalse(run.results[0].ok)
        assertTrue(run.results[1].ok)
        assertEquals(2, gateway.commands.size)
    }

    // ── successProbe 判定 ───────────────────────────────────────────────

    @Test
    fun `exit 0 但探测失败仍算失败`() = runTest {
        val gateway = okGateway(stdout = "Wi-Fi is enabled")
        val run = BlueprintEngine.execute(
            manifestOf(
                BlueprintStep(type = StepType.SHELL, content = "svc wifi enable", successProbe = "disabled"),
            ),
            gateway,
        )

        assertFalse(run.success)
        val step = run.results[0]
        assertFalse(step.ok)
        assertEquals(0, step.exitCode)
        assertTrue(step.message.orEmpty().contains("successProbe"))
    }

    @Test
    fun `exit 0 且探测命中算成功`() = runTest {
        val gateway = okGateway(stdout = "Wi-Fi is enabled")
        val run = BlueprintEngine.execute(
            manifestOf(
                BlueprintStep(type = StepType.SHELL, content = "svc wifi enable", successProbe = "enabled"),
            ),
            gateway,
        )

        assertTrue(run.success)
        assertTrue(run.results[0].ok)
        assertNull(run.results[0].message)
    }

    // ── DELAY 语义 ──────────────────────────────────────────────────────

    @Test
    fun `DELAY 步骤恒成功且不触碰通道`() = runTest {
        val gateway = FakeGateway { Result.failure(IllegalStateException("DELAY 不应调用通道")) }
        val run = BlueprintEngine.execute(
            manifestOf(BlueprintStep(type = StepType.DELAY, content = "600000")),
            gateway,
        )

        assertTrue(run.success)
        assertTrue(run.results[0].ok)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test
    fun `程序化构造的非法 DELAY 被引擎兜底拒绝`() = runTest {
        val gateway = FakeGateway { Result.failure(IllegalStateException("不应调用通道")) }
        val run = BlueprintEngine.execute(
            manifestOf(BlueprintStep(type = StepType.DELAY, content = "not-a-number")),
            gateway,
        )

        assertFalse(run.success)
        val step = run.results[0]
        assertFalse(step.ok)
        assertTrue(step.message.orEmpty().contains("毫秒"))
    }

    // ── 超时语义 ────────────────────────────────────────────────────────

    @Test
    fun `SHELL 超时记为失败并中断`() = runTest {
        val gateway = FakeGateway {
            delay(60_000) // 真实 IO 线程上的挂起；withTimeout(1s) 按墙钟计
            Result.success(ShellResult(exitCode = 0, stdout = "late", stderr = ""))
        }
        val run = BlueprintEngine.execute(
            manifestOf(
                BlueprintStep(type = StepType.SHELL, content = "slow", timeoutSeconds = 1),
                BlueprintStep(type = StepType.SHELL, content = "never"),
            ),
            gateway,
        )

        assertFalse(run.success)
        assertEquals("超时后中断，后续步骤不执行", 1, run.results.size)
        val step = run.results[0]
        assertFalse(step.ok)
        assertNull("超时拿不到退出码", step.exitCode)
        assertTrue(step.message.orEmpty().contains("超时"))
        assertEquals(1, gateway.commands.size)
    }

    // ── 变量替换 ────────────────────────────────────────────────────────

    @Test
    fun `prev_output 替换为上一步 stdout`() = runTest {
        val gateway = FakeGateway { command ->
            if (command == "settings get global airplane_mode_on") {
                Result.success(ShellResult(exitCode = 0, stdout = "1", stderr = ""))
            } else {
                Result.success(ShellResult(exitCode = 0, stdout = "echoed", stderr = ""))
            }
        }
        val run = BlueprintEngine.execute(
            manifestOf(
                BlueprintStep(type = StepType.SHELL, content = "settings get global airplane_mode_on"),
                BlueprintStep(type = StepType.DELAY, content = "100"),
                BlueprintStep(type = StepType.SHELL, content = "echo {prev_output}"),
                BlueprintStep(type = StepType.TOAST, content = "当前值 {prev_output}"),
            ),
            gateway,
        )

        assertTrue(run.success)
        // 第 2 步的占位符取的是「最近一次 SHELL」（第 0 步）的 stdout，
        // 且中间穿过了 DELAY（第 1 步）不断链
        // commands 只记录 SHELL 执行（0/1 两项 —— DELAY/TOAST 不触碰通道）
        assertEquals("echo 1", gateway.commands[1])
        // 第 3 步（TOAST）取最近一次 SHELL（第 2 步）的 stdout "echoed"
        assertEquals("当前值 echoed", run.results[3].message)
    }

    @Test
    fun `无前置 SHELL 输出时占位符原样保留`() = runTest {
        val gateway = okGateway()
        BlueprintEngine.execute(
            manifestOf(BlueprintStep(type = StepType.SHELL, content = "echo {prev_output}")),
            gateway,
        )

        assertEquals("echo {prev_output}", gateway.commands.single())
    }

    // ── 通道失败 ────────────────────────────────────────────────────────

    @Test
    fun `通道异常记为失败并带人话原因`() = runTest {
        val gateway = FakeGateway { Result.failure(IllegalStateException("Shizuku 未连接")) }
        val run = BlueprintEngine.execute(
            manifestOf(BlueprintStep(type = StepType.SHELL, content = "cmd")),
            gateway,
        )

        assertFalse(run.success)
        val step = run.results[0]
        assertFalse(step.ok)
        assertNull(step.exitCode)
        assertEquals("", step.stdout)
        assertTrue(step.message.orEmpty().contains("Shizuku 未连接"))
    }

    // ── 进度回调 ────────────────────────────────────────────────────────

    @Test
    fun `回调按序触发且携带结果`() = runTest {
        val starts = mutableListOf<Int>()
        val dones = mutableListOf<StepResult>()
        val gateway = FakeGateway { command ->
            Result.success(ShellResult(exitCode = if (command == "a") 0 else 3, stdout = "", stderr = ""))
        }
        BlueprintEngine.execute(
            manifestOf(
                BlueprintStep(type = StepType.SHELL, content = "a"),
                BlueprintStep(type = StepType.SHELL, content = "b", continueOnFailure = true),
            ),
            gateway,
            onStepStart = { starts += it },
            onStepDone = { dones += it },
        )

        assertEquals(listOf(0, 1), starts)
        assertEquals(listOf(0, 1), dones.map { it.index })
        assertEquals(listOf(true, false), dones.map { it.ok })
    }

    @Test
    fun `中断链的回调不包含未执行步骤`() = runTest {
        val starts = mutableListOf<Int>()
        val dones = mutableListOf<StepResult>()
        val gateway = FakeGateway {
            Result.success(ShellResult(exitCode = 1, stdout = "", stderr = ""))
        }
        BlueprintEngine.execute(
            manifestOf(
                BlueprintStep(type = StepType.SHELL, content = "fail"),
                BlueprintStep(type = StepType.SHELL, content = "next"),
            ),
            gateway,
            onStepStart = { starts += it },
            onStepDone = { dones += it },
        )

        assertEquals(listOf(0), starts)
        assertEquals(listOf(0), dones.map { it.index })
    }

    // ── 运行元数据 ──────────────────────────────────────────────────────

    @Test
    fun `运行元数据完整`() = runTest {
        val before = System.currentTimeMillis()
        val run = BlueprintEngine.execute(
            manifestOf(BlueprintStep(type = StepType.TOAST, content = "hi")),
            okGateway(),
        )
        val after = System.currentTimeMillis()

        assertEquals("test-bp", run.blueprintId)
        assertTrue("startedAt 应落在执行窗口内", run.startedAt in before..after)
        assertTrue(run.totalMs >= 0)
        assertTrue(run.success)
    }
}
