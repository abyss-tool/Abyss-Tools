package com.abyss.tools.feature.blueprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlueprintParser 纯逻辑单测（零 Android 依赖）：
 * 合法解析 / 默认值 / 未知字段忽略 / 坏 JSON / 缺字段点名 / 全部校验规则
 * （fail-closed：非法蓝图在解析闸就拒绝，人话错误带字段名）。
 */
class BlueprintParserTest {

    // ── 合法形态 ─────────────────────────────────────────────────────────

    @Test
    fun `合法蓝图完整解析`() {
        val raw = """
            {
              "id": "night-routine",
              "name": "睡前断网",
              "description": "关网并提示",
              "steps": [
                { "type": "SHELL", "content": "svc data disable && svc wifi disable", "timeoutSeconds": 20 },
                { "type": "TOAST", "content": "已断网，晚安" },
                { "type": "DELAY", "content": "1000" }
              ],
              "schedule": { "intervalMinutes": 30 }
            }
        """.trimIndent()

        val manifest = BlueprintParser.parse(raw).getOrThrow()

        assertEquals("night-routine", manifest.id)
        assertEquals("睡前断网", manifest.name)
        assertEquals(3, manifest.steps.size)

        val shell = manifest.steps[0]
        assertEquals(StepType.SHELL, shell.type)
        assertEquals("svc data disable && svc wifi disable", shell.content)
        assertEquals(20, shell.timeoutSeconds)
        assertNull(shell.successProbe)
        assertEquals(false, shell.continueOnFailure)

        assertEquals(StepType.TOAST, manifest.steps[1].type)
        assertEquals(StepType.DELAY, manifest.steps[2].type)
        assertEquals(30, manifest.schedule?.intervalMinutes)
    }

    @Test
    fun `最小合法蓝图使用默认值`() {
        val raw = """
            {
              "id": "minimal",
              "name": "最小蓝图",
              "steps": [ { "type": "SHELL", "content": "dumpsys battery reset" } ]
            }
        """.trimIndent()

        val manifest = BlueprintParser.parse(raw).getOrThrow()

        assertEquals("", manifest.description)
        assertNull(manifest.schedule)
        val step = manifest.steps.single()
        assertNull(step.successProbe)
        assertEquals(false, step.continueOnFailure)
        assertEquals(30, step.timeoutSeconds)
    }

    @Test
    fun `未知字段被忽略`() {
        val raw = """
            {
              "id": "extra-fields",
              "name": "带未知字段",
              "version": 2,
              "author": "abyss",
              "steps": [ { "type": "TOAST", "content": "hi", "remark": "x" } ]
            }
        """.trimIndent()

        val manifest = BlueprintParser.parse(raw).getOrThrow()
        assertEquals("带未知字段", manifest.name)
        assertEquals(1, manifest.steps.size)
    }

    @Test
    fun `successProbe 与 continueOnFailure 可解析`() {
        val raw = """
            {
              "id": "probe-bp",
              "name": "带探测",
              "steps": [
                {
                  "type": "SHELL",
                  "content": "svc wifi enable",
                  "successProbe": "enabled",
                  "continueOnFailure": true
                }
              ]
            }
        """.trimIndent()

        val step = BlueprintParser.parse(raw).getOrThrow().steps.single()
        assertEquals("enabled", step.successProbe)
        assertEquals(true, step.continueOnFailure)
    }

    // ── 坏 JSON / 缺字段 ────────────────────────────────────────────────

    @Test
    fun `坏 JSON 被拒绝并给出人话错误`() {
        listOf("", "   ", "{oops", "not-json", "[1, 2, 3]", "123").forEach { raw ->
            val outcome = BlueprintParser.parse(raw)
            assertTrue("应拒绝：$raw", outcome.isFailure)
            val message = outcome.exceptionOrNull()?.message.orEmpty()
            assertTrue("错误应为人话：$message", message.contains("JSON 解析失败") || message.contains("缺少必填字段"))
        }
    }

    @Test
    fun `缺必填字段 name 被点名`() {
        val raw = """{ "id": "no-name", "steps": [ { "type": "TOAST", "content": "hi" } ] }"""
        val message = BlueprintParser.parse(raw).exceptionOrNull()?.message.orEmpty()
        assertTrue("应点名 name 字段：$message", message.contains("name"))
    }

    @Test
    fun `缺必填字段 steps 被点名`() {
        val raw = """{ "id": "no-steps", "name": "没有步骤" }"""
        val message = BlueprintParser.parse(raw).exceptionOrNull()?.message.orEmpty()
        assertTrue("应点名 steps 字段：$message", message.contains("steps"))
    }

    @Test
    fun `未知步骤类型被拒绝`() {
        val raw = """
            {
              "id": "bad-type",
              "name": "坏类型",
              "steps": [ { "type": "REBOOT", "content": "now" } ]
            }
        """.trimIndent()

        assertTrue(BlueprintParser.parse(raw).isFailure)
    }

    // ── 校验规则（反序列化之后、放行之前）───────────────────────────────

    @Test
    fun `id 大写或非法字符被拒绝`() {
        idRejected("Battery-Reset", "大写")
        idRejected("电池复位", "中文")
        idRejected("battery reset", "空格")
        idRejected("battery_reset", "下划线")
        idRejected("battery.json", "路径字符")
        idRejected("../escape", "路径穿越")
        idRejected("", "空串")
    }

    @Test
    fun `id 合法边界`() {
        idAccepted("a")
        idAccepted("battery-reset")
        idAccepted("bp-007-x")
    }

    @Test
    fun `name 空白被拒绝`() {
        val raw = """
            { "id": "blank-name", "name": "   ", "steps": [ { "type": "TOAST", "content": "hi" } ] }
        """.trimIndent()
        val message = BlueprintParser.parse(raw).exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("name"))
    }

    @Test
    fun `steps 为空被拒绝`() {
        val raw = """{ "id": "empty-steps", "name": "空步骤", "steps": [] }"""
        val message = BlueprintParser.parse(raw).exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("steps"))
    }

    @Test
    fun `SHELL 空命令被拒绝`() {
        val raw = """
            {
              "id": "empty-shell",
              "name": "空命令",
              "steps": [ { "type": "SHELL", "content": "   " } ]
            }
        """.trimIndent()

        val message = BlueprintParser.parse(raw).exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("第 1 步"))
        assertTrue(message.contains("SHELL"))
    }

    @Test
    fun `DELAY 非整数被拒绝`() {
        val raw = """
            {
              "id": "bad-delay",
              "name": "坏延迟",
              "steps": [ { "type": "DELAY", "content": "abc" } ]
            }
        """.trimIndent()

        val message = BlueprintParser.parse(raw).exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("第 1 步"))
        assertTrue(message.contains("毫秒"))
    }

    @Test
    fun `DELAY 超出区间被拒绝`() {
        delayRejected("0")
        delayRejected("-100")
        delayRejected("600001")
        delayAccepted("1")
        delayAccepted("600000")
    }

    @Test
    fun `调度间隔小于 15 分钟被拒绝`() {
        val raw = """
            {
              "id": "too-fast",
              "name": "间隔过短",
              "steps": [ { "type": "TOAST", "content": "hi" } ],
              "schedule": { "intervalMinutes": 14 }
            }
        """.trimIndent()

        val message = BlueprintParser.parse(raw).exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("intervalMinutes"))
        assertTrue(message.contains("15"))
    }

    @Test
    fun `调度间隔 15 分钟合法`() {
        val raw = """
            {
              "id": "min-interval",
              "name": "最小间隔",
              "steps": [ { "type": "TOAST", "content": "hi" } ],
              "schedule": { "intervalMinutes": 15 }
            }
        """.trimIndent()

        assertEquals(15, BlueprintParser.parse(raw).getOrThrow().schedule?.intervalMinutes)
    }

    @Test
    fun `编辑器 JSON 剥离 schedule 且可回环解析`() {
        val original = BlueprintManifest(
            id = "round-trip",
            name = "回环",
            description = "调度应被剥离",
            steps = listOf(
                BlueprintStep(type = StepType.SHELL, content = "dumpsys battery reset"),
                BlueprintStep(type = StepType.DELAY, content = "500"),
            ),
            schedule = BlueprintSchedule(intervalMinutes = 30),
        )

        val editable = BlueprintParser.toEditableJson(original)
        val reparsed = BlueprintParser.parse(editable).getOrThrow()

        assertNull("编辑态不应携带 schedule", reparsed.schedule)
        assertEquals(original.copy(schedule = null), reparsed)
    }

    // ── 断言助手 ────────────────────────────────────────────────────────

    private fun idRejected(id: String, what: String) {
        val raw = """
            { "id": "$id", "name": "校验", "steps": [ { "type": "TOAST", "content": "hi" } ] }
        """.trimIndent()
        val outcome = BlueprintParser.parse(raw)
        assertTrue("id 应被拒绝（$what）：$id", outcome.isFailure)
    }

    private fun idAccepted(id: String) {
        val raw = """
            { "id": "$id", "name": "校验", "steps": [ { "type": "TOAST", "content": "hi" } ] }
        """.trimIndent()
        assertTrue("id 应放行：$id", BlueprintParser.parse(raw).isSuccess)
    }

    private fun delayRejected(content: String) {
        val raw = """
            { "id": "delay-check", "name": "延迟", "steps": [ { "type": "DELAY", "content": "$content" } ] }
        """.trimIndent()
        assertTrue("DELAY 应被拒绝：$content", BlueprintParser.parse(raw).isFailure)
    }

    private fun delayAccepted(content: String) {
        val raw = """
            { "id": "delay-check", "name": "延迟", "steps": [ { "type": "DELAY", "content": "$content" } ] }
        """.trimIndent()
        assertTrue("DELAY 应放行：$content", BlueprintParser.parse(raw).isSuccess)
    }
}
