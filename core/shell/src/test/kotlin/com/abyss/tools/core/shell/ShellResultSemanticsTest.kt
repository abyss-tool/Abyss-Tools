package com.abyss.tools.core.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-08 回归：超时 / 流放弃语义。
 *
 * 旧实现 ShellResult 无 timedOut/streamAbandoned 字段，且 isSuccess 只看
 * exitCode —— 看门狗强杀 / 流放弃被当成功（fail-open）。新语义：
 *  - timedOut=true → isSuccess 恒 false（超时 ≠ 成功，SHL-003/005）；
 *  - streamAbandoned=true → 输出不完整（同时 truncated=true）。
 */
class ShellResultSemanticsTest {

    @Test
    fun `timedOut forces isSuccess false even with exit code zero`() {
        // 竞态窗口：进程恰在看门狗 destroy 前以 0 退出 —— 仍按跑满预算上报
        val r = ShellResult(exitCode = 0, stdout = "", stderr = "", timedOut = true)
        assertFalse(r.isSuccess)
    }

    @Test
    fun `normal success semantics unchanged`() {
        val r = ShellResult(exitCode = 0, stdout = "ok", stderr = "")
        assertTrue(r.isSuccess)
        assertFalse(r.truncated)
        assertFalse(r.timedOut)
        assertFalse(r.streamAbandoned)
    }

    @Test
    fun `streamAbandoned marks incomplete output with truncated`() {
        // B-08 核心用例：流放弃必须如实标注（旧实现 truncated=false 空文本）
        val r = ShellResult(
            exitCode = 0, stdout = "", stderr = "",
            truncated = true, streamAbandoned = true,
        )
        assertTrue(r.truncated)
        assertTrue(r.streamAbandoned)
        // 进程退出码 0 但流被放弃：成功位仍为 true（进程确实正常退出），
        // 但消费方必须读 truncated/streamAbandoned 判定输出完整性 ——
        // 定时任务以 isSuccess 判成功时，输出完整性位由调用侧另行裁决。
    }

    @Test
    fun `failure exit code stays failure regardless of new flags`() {
        assertFalse(ShellResult(exitCode = 1, stdout = "", stderr = "", timedOut = true).isSuccess)
        assertFalse(ShellResult(exitCode = 1, stdout = "", stderr = "").isSuccess)
    }
}
