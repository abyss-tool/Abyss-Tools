package com.abyss.tools.feature.blueprint

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BlueprintStore 纯 JVM 单测（TemporaryFolder 模拟 filesDir/blueprints/）：
 * 内置目录 / 用户覆盖 / 删除回归 / 坏文件跳过 / 原子写与保留字 /
 * 执行历史 20 条裁剪。零 Android 依赖（构造函数只吃 java.io.File）。
 */
class BlueprintStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): BlueprintStore = BlueprintStore(tmp.root)

    private fun userManifest(
        id: String,
        name: String = "用户蓝图",
        schedule: BlueprintSchedule? = null,
    ) = BlueprintManifest(
        id = id,
        name = name,
        steps = listOf(BlueprintStep(type = StepType.SHELL, content = "dumpsys battery reset")),
        schedule = schedule,
    )

    // ── 内置目录 ─────────────────────────────────────────────────────────

    @Test
    fun `空目录返回五个内置蓝图`() {
        val snapshot = store().loadAll()

        assertEquals(
            listOf("battery-reset", "airplane-on", "airplane-off", "network-off", "network-on"),
            snapshot.blueprints.map { it.id },
        )
        assertTrue(snapshot.warnings.isEmpty())
        assertTrue(snapshot.userIds.isEmpty())
    }

    @Test
    fun `内置命令与 adb 手敲形态一致`() {
        val byId = store().loadAll().blueprints.associateBy { it.id }

        assertEquals("dumpsys battery reset", byId.getValue("battery-reset").steps.single().content)
        assertEquals(
            "cmd connectivity airplane-mode enable",
            byId.getValue("airplane-on").steps.single().content,
        )
        assertEquals(
            "cmd connectivity airplane-mode disable",
            byId.getValue("airplane-off").steps.single().content,
        )
        assertEquals(
            "svc data disable && svc wifi disable",
            byId.getValue("network-off").steps.single().content,
        )
        assertEquals(
            "svc wifi enable && svc data enable",
            byId.getValue("network-on").steps.single().content,
        )
    }

    // ── 用户目录合并语义 ────────────────────────────────────────────────

    @Test
    fun `用户同 id 覆盖内置`() {
        val store = store()
        store.saveUser(userManifest(id = "battery-reset", name = "我的复位")).getOrThrow()

        val snapshot = store.loadAll()

        assertEquals("总数不变（覆盖而非新增）", 5, snapshot.blueprints.size)
        assertEquals("我的复位", snapshot.blueprints.first { it.id == "battery-reset" }.name)
        assertTrue(snapshot.userIds.contains("battery-reset"))
        assertEquals("内置次序稳定", "battery-reset", snapshot.blueprints.first().id)
    }

    @Test
    fun `用户新 id 追加进目录`() {
        val store = store()
        store.saveUser(userManifest(id = "night-routine", name = "睡前断网")).getOrThrow()

        val snapshot = store.loadAll()

        assertEquals(6, snapshot.blueprints.size)
        assertTrue(snapshot.userIds == setOf("night-routine"))
    }

    @Test
    fun `删除用户蓝图后内置回归`() {
        val store = store()
        store.saveUser(userManifest(id = "battery-reset", name = "我的复位")).getOrThrow()
        store.deleteUser("battery-reset").getOrThrow()

        val snapshot = store.loadAll()

        assertEquals(5, snapshot.blueprints.size)
        assertEquals("电池状态复位", snapshot.blueprints.first { it.id == "battery-reset" }.name)
        assertTrue(snapshot.userIds.isEmpty())
    }

    @Test
    fun `坏用户文件被跳过并给出人话警告`() {
        File(tmp.root, "broken.json").writeText("{ 这不是合法 JSON ")

        val snapshot = store().loadAll()

        assertEquals("坏文件不进目录", 5, snapshot.blueprints.size)
        assertEquals(1, snapshot.warnings.size)
        assertTrue(snapshot.warnings.single().contains("broken.json"))
        // fail-closed：坏文件原样保留，绝不静默删除
        assertTrue(File(tmp.root, "broken.json").exists())
    }

    @Test
    fun `runs json 不会被当成蓝图加载`() {
        val store = store()
        store.appendRun(
            BlueprintRun(
                blueprintId = "battery-reset",
                startedAt = 1L,
                totalMs = 10L,
                success = true,
                results = emptyList(),
            ),
        ).getOrThrow()

        val snapshot = store.loadAll()

        assertEquals(5, snapshot.blueprints.size)
        assertTrue(snapshot.warnings.isEmpty())
    }

    // ── 保存校验闸（id 即文件名）────────────────────────────────────────

    @Test
    fun `非法 id 在落盘前被拒绝`() {
        val store = store()

        listOf("Bad-Id", "battery reset", "../escape", "").forEach { id ->
            val outcome = store.saveUser(userManifest(id = id))
            assertTrue("id 应被拒绝：$id", outcome.isFailure)
        }
        assertTrue(tmp.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `保留字 id runs 被拒绝`() {
        val outcome = store().saveUser(userManifest(id = "runs", name = "与历史文件冲突"))

        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("保留字"))
    }

    @Test
    fun `非法清单在落盘前被校验拒绝`() {
        val outcome = store().saveUser(
            userManifest(id = "too-fast", schedule = BlueprintSchedule(intervalMinutes = 5)),
        )

        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("intervalMinutes"))
    }

    // ── 执行历史 ────────────────────────────────────────────────────────

    @Test
    fun `无历史文件时返回空列表`() {
        assertTrue(store().loadRuns().getOrThrow().isEmpty())
    }

    @Test
    fun `appendRun 头插并裁剪到最近二十条`() {
        val store = store()

        repeat(25) { i ->
            store.appendRun(
                BlueprintRun(
                    blueprintId = "bp-$i",
                    startedAt = i.toLong(),
                    totalMs = 0,
                    success = true,
                    results = emptyList(),
                ),
            ).getOrThrow()
        }

        val runs = store.loadRuns().getOrThrow()
        assertEquals(20, runs.size)
        assertEquals("最新在前", "bp-24", runs.first().blueprintId)
        assertEquals("最老保留到第 6 条", "bp-5", runs.last().blueprintId)
    }

    @Test
    fun `坏历史文件返回 failure 而非空列表`() {
        File(tmp.root, "runs.json").writeText("[{ broken")

        val outcome = store().loadRuns()

        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("损坏"))
    }

    @Test
    fun `超长步骤输出在落盘时被截断`() {
        val store = store()
        val longOutput = "x".repeat(50_000)
        store.appendRun(
            BlueprintRun(
                blueprintId = "big-output",
                startedAt = 0L,
                totalMs = 1L,
                success = false,
                results = listOf(
                    StepResult(
                        index = 0,
                        type = StepType.SHELL,
                        ok = false,
                        exitCode = 1,
                        stdout = longOutput,
                        stderr = null,
                        message = null,
                        elapsedMs = 5,
                    ),
                ),
            ),
        ).getOrThrow()

        val persisted = store.loadRuns().getOrThrow().single().results.single()

        assertTrue("应被截断", persisted.stdout.length < longOutput.length)
        assertTrue(persisted.stdout.contains("已截断"))
    }

    @Test
    fun `调度随清单一起落盘并可回读`() {
        val store = store()
        store.saveUser(
            userManifest(id = "night-routine", schedule = BlueprintSchedule(intervalMinutes = 30)),
        ).getOrThrow()

        val reloaded = store.loadAll().blueprints.first { it.id == "night-routine" }

        assertEquals(30, reloaded.schedule?.intervalMinutes)
    }
}
