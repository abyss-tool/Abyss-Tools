package com.abyss.tools.feature.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context

/**
 * 组件家族注册表（v0.9.0「引力场」）—— 四条 provider 家族的唯一清单。
 *
 * 为什么需要它：整分闹钟链（[BlackHoleTicker]）只有一条，一跳要喂饱
 * 全部家族 —— tick 广播只投递给 BlackHoleClockWidget（显式
 * PendingIntent），由它转手刷新全部；开机（BlackHoleBootReceiver）、
 * 工坊「立即刷新」（BlackHoleTicker.refreshNow）同样走这里。家族清单
 * 收敛在一处，新增组件只改本文件（登记 provider + pushUpdate）。
 *
 * 「家族」= 一个 provider 类 + 它专属的 pushUpdate（各自持有布局与绘
 * 制引擎）。时钟家族的宽版 / 方版是两个 provider 类共享一个
 * pushUpdate —— 都要登记（allIds 聚合才不漏方版实例）。
 */
internal object AbyssWidgetFamily {

    private class Family(
        val provider: Class<out AppWidgetProvider>,
        val push: (Context, AppWidgetManager, IntArray) -> Unit,
    )

    /** 全部家族（含时钟方版独立 provider）。顺序即刷新顺序 —— 无语义，仅保持时钟在前的可读性。 */
    private val families: List<Family> = listOf(
        Family(BlackHoleClockWidget::class.java) { ctx, mgr, ids ->
            BlackHoleClockWidget.pushUpdate(ctx, mgr, ids)
        },
        Family(BlackHoleClockWidgetSmall::class.java) { ctx, mgr, ids ->
            BlackHoleClockWidget.pushUpdate(ctx, mgr, ids)
        },
        Family(GravityBatteryWidget::class.java) { ctx, mgr, ids ->
            GravityBatteryWidget.pushUpdate(ctx, mgr, ids)
        },
        Family(StellarCalendarWidget::class.java) { ctx, mgr, ids ->
            StellarCalendarWidget.pushUpdate(ctx, mgr, ids)
        },
        Family(AbyssGaugeWidget::class.java) { ctx, mgr, ids ->
            AbyssGaugeWidget.pushUpdate(ctx, mgr, ids)
        },
    )

    /** 全部家族的全部实例 id（聚合去中心化 —— 谁也不许自己数漏别的家族）。 */
    fun allIds(context: Context, manager: AppWidgetManager): IntArray {
        val ids = ArrayList<Int>()
        families.forEach { family ->
            ids += manager.getAppWidgetIds(ComponentName(context, family.provider)).toList()
        }
        return ids.toIntArray()
    }

    /**
     * 重绘并推送全部家族（tick / 开机 / 「立即刷新」的统一出口）。
     * 每个家族的 pushUpdate 自带「空批次直通 + 逐实例 runCatching」——
     * 一家失败绝不拖垮其余三家。
     */
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        families.forEach { family ->
            val ids = manager.getAppWidgetIds(ComponentName(context, family.provider))
            if (ids.isNotEmpty()) family.push(context, manager, ids)
        }
    }

    /**
     * 四家族全部无实例才拆闹钟链（各 provider 的 onDisabled 调用）——
     * 删掉时钟后电池 / 日历 / 引力仪仍要整分重绘，链不能断。
     */
    fun cancelTickerIfIdle(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        if (allIds(context, manager).isEmpty()) {
            BlackHoleTicker.cancel(context)
        }
    }
}
