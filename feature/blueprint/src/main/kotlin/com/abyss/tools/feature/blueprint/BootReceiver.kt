package com.abyss.tools.feature.blueprint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机重排接收器：RECEIVE_BOOT_COMPLETED → 把所有带 schedule 的蓝图
 * 重新排上闹钟（AlarmManager 闹钟不随重启存活，这里是调度不丢的兜底）。
 *
 * 纪律：纯 Context 操作（读 filesDir 蓝图目录 + 重排闹钟），不持有任何
 * Compose 状态；目录内只有少量小 JSON 文件，同步读取即可（无协程、无前台服务）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = BlueprintStore.create(context)
        store.loadAll().blueprints.forEach { manifest ->
            manifest.schedule?.let { schedule ->
                ScheduleAlarmReceiver.scheduleNext(context, manifest.id, schedule.intervalMinutes)
            }
        }
    }
}
