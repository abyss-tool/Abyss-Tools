package com.abyss.tools.feature.blueprint

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.abyss.tools.core.shell.ShizukuShellGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 调度到期接收器：AlarmManager 精确闹钟 → 执行蓝图 → 重排下一次。
 *
 * 生命周期（goAsync + 协程收尾自查）：
 *  - onReceive 里 goAsync() 拿到 PendingResult，随后在独立
 *    CoroutineScope(SupervisorJob() + Dispatchers.IO) 上执行；
 *  - 协程 try/finally 保证 pendingResult.finish() 恒被调用（执行成功、
 *    抛异常、任何分支都收尾，不会卡住 BroadcastReceiver 的 10 秒窗口）；
 *  - 作用域随本次执行创建并自然结束，不持有 Compose 状态、不泄漏。
 *
 * 诚实边界：goAsync 窗口约 10 秒 —— 常规命令链（每步默认 30s 超时但绝大多数
 * 系统命令毫秒级返回）足够；含长 DELAY / 多步慢命令的蓝图若超出窗口，
 * 进程可能被系统回收（该轮执行记录不落盘）。定时任务建议保持短链。
 * 本模块不做前台服务（宿主 targetSdk 28 后台执行限制宽松，通知保活
 * 属过度设计 —— 报告已说明）。
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val blueprintId = intent.getStringExtra(EXTRA_BLUEPRINT_ID)
        if (blueprintId == null) {
            // 无目标 id 的残留闹钟：直接忽略（不重排），闹钟链自然熄灭
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runDueBlueprint(context, blueprintId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun runDueBlueprint(context: Context, blueprintId: String) {
        val store = BlueprintStore.create(context)
        val manifest = store.loadAll().blueprints.firstOrNull { it.id == blueprintId }
        if (manifest == null) {
            // 蓝图已被删除：清掉残留闹钟，不再重排
            cancel(context, blueprintId)
            return
        }

        // 执行：Shizuku 未运行 / 未授权 → 步骤级失败，人话原因写入执行历史
        //（fail-closed：绝不因通道不可用而静默跳过或假装成功）
        val run = try {
            BlueprintEngine.execute(manifest, ShizukuShellGateway())
        } catch (e: Exception) {
            channelFailureRun(manifest, e.message ?: e.javaClass.simpleName)
        }
        store.appendRun(run)

        // 重排下一次：schedule 已被关闭 / 蓝图已删 → 不再排，闹钟链自然结束
        manifest.schedule?.let { schedule ->
            scheduleNext(context, blueprintId, schedule.intervalMinutes)
        }
    }

    /** 通道直接建立失败（连步骤都没跑起来）时的整链失败记录。 */
    private fun channelFailureRun(manifest: BlueprintManifest, reason: String): BlueprintRun =
        BlueprintRun(
            blueprintId = manifest.id,
            startedAt = System.currentTimeMillis(),
            totalMs = 0,
            success = false,
            results = manifest.steps.mapIndexed { index, step ->
                StepResult(
                    index = index,
                    type = step.type,
                    ok = false,
                    message = "命令通道建立失败：$reason",
                )
            },
        )

    companion object {

        private const val EXTRA_BLUEPRINT_ID = "blueprint_id"
        private const val EXTRA_INTERVAL_MINUTES = "interval_minutes"

        /**
         * 排下一次执行：setExactAndAllowWhileIdle（RTC_WAKEUP，doze 也能触发），
         * now + interval；SecurityException（精确闹钟权限被收回，targetSdk 28
         * 场景极罕见）→ 降级 setAndAllowWhileIdle。
         */
        fun scheduleNext(context: Context, blueprintId: String, intervalMinutes: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = pendingIntent(context, blueprintId, intervalMinutes)
            val triggerAtMillis = System.currentTimeMillis() + intervalMinutes * 60_000L
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } catch (e: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }

        /** 撤销调度（保存无 schedule / 删除蓝图 / 「取消调度」按钮）。 */
        fun cancel(context: Context, blueprintId: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = PendingIntent.getBroadcast(
                context,
                blueprintId.hashCode(),
                intent(context, blueprintId),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
            alarmManager.cancel(pending)
            pending.cancel()
        }

        private fun intent(context: Context, blueprintId: String): Intent =
            Intent(context, ScheduleAlarmReceiver::class.java)
                .putExtra(EXTRA_BLUEPRINT_ID, blueprintId)

        private fun pendingIntent(context: Context, blueprintId: String, intervalMinutes: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                blueprintId.hashCode(),
                intent(context, blueprintId).putExtra(EXTRA_INTERVAL_MINUTES, intervalMinutes),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
