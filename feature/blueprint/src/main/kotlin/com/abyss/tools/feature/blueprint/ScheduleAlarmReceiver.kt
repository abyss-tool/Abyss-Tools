package com.abyss.tools.feature.blueprint

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        // （v0.9.0：双模式 —— 间隔 now+N / 每日按 SchedulePlanner 折算今天或明天）
        manifest.schedule?.let { schedule ->
            scheduleNext(context, blueprintId, schedule)
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

        /**
         * 排下一次执行（v0.9.0 双模式）：
         *  - 间隔模式：setExactAndAllowWhileIdle（RTC_WAKEUP，doze 也能触发），
         *    now + interval；
         *  - 每日模式：SchedulePlanner 折算今天 / 明天的固定时刻。
         *  SecurityException（精确闹钟权限被收回，targetSdk 28 场景极罕见）
         *  → 降级 setAndAllowWhileIdle。intent 不再携带间隔 extra —— 到期后
         *  由 store 重读蓝图，模式变更 / 删除都能在下一次重排时自然收敛。
         */

        /**
         * 蓝图调度 PendingIntent 的 data URI 字符串（B-13 身份锚）：
         * `abyss://blueprint/<id>` —— 前缀 + id 字面量构成单射映射，
         * 任意两个不同蓝图（含 hashCode 碰撞对，如 "Aa"/"BB"）的
         * Intent.filterEquals 必不相等 → PendingIntent 天然隔离。
         * 纯字符串函数（零 Android 依赖）供单测锁定契约。
         */
        fun blueprintDataUriString(blueprintId: String): String =
            "abyss://blueprint/$blueprintId"

        fun scheduleNext(context: Context, blueprintId: String, schedule: BlueprintSchedule) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = pendingIntent(context, blueprintId)
            val triggerAtMillis = when {
                schedule.intervalMinutes != null ->
                    System.currentTimeMillis() + schedule.intervalMinutes * 60_000L

                schedule.dailyAtMinute != null ->
                    SchedulePlanner.nextDailyAt(schedule.dailyAtMinute, System.currentTimeMillis())

                else -> return // 数据契约双保险（init 已闸互斥；理论上不可达）
            }
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
                // B-13: data URI 参与 Intent.filterEquals —— 旧形态仅靠
                // requestCode（blueprintId.hashCode）+ component 匹配（extras
                // 不参与），哈希碰撞的两个蓝图共用同一 PendingIntent，
                // FLAG_UPDATE_CURRENT 相互覆盖 → 其中一个永不触发。
                .setData(Uri.parse(blueprintDataUriString(blueprintId)))
                .putExtra(EXTRA_BLUEPRINT_ID, blueprintId)

        private fun pendingIntent(context: Context, blueprintId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                blueprintId.hashCode(),
                intent(context, blueprintId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

}
