package com.abyss.tools.feature.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.abyss.tools.feature.widgets.WidgetStudioPrefs.minuteTickEnabled

/**
 * 黑洞动态时钟桌面组件（4×2 宽版入口；2×2 见 [BlackHoleClockWidgetSmall]）。
 *
 * 更新节奏双轨：
 *  - 兜底轨：updatePeriodMillis = 30 分钟（系统下限，防闹钟链断裂后
 *    彻底停摆）；
 *  - 主轨：[BlackHoleTicker] 的「精确到分」自续期闹钟链 —— 每个整分
 *    边界重绘位图（吸积盘旋转角由时间驱动，见 [BlackHoleMath]），
 *    开机经 [BlackHoleBootReceiver] 重排。targetSdk 28 < 31，无需
 *    SCHEDULE_EXACT_ALARM 用户授权。
 *
 * v0.9.0「引力场」起整分链服务全部四条家族（时钟宽/方 + 引力电池 +
 * 星轨日历 + 深渊引力仪）—— tick 广播仍投递给本类（显式
 * PendingIntent），由 [AbyssWidgetFamily.refreshAll] 转手刷新全家，
 * 家族清单收敛在 [AbyssWidgetFamily]。
 */
open class BlackHoleClockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        pushUpdate(context, appWidgetManager, appWidgetIds)
        BlackHoleTicker.scheduleNext(context)
    }

    override fun onEnabled(context: Context) {
        BlackHoleTicker.scheduleNext(context)
    }

    override fun onDisabled(context: Context) {
        // 四家族全部无实例才拆链 —— 时钟删了，电池 / 日历 / 引力仪还要整分重绘
        AbyssWidgetFamily.cancelTickerIfIdle(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        // 拖拽改尺寸 → 立即按新尺寸重绘（位图尺寸随 widgets 选项走）
        pushUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BlackHoleTicker.ACTION_TICK) {
            // 一跳喂全家：tick 只打到时钟，转手刷新四条家族（含本家族）
            AbyssWidgetFamily.refreshAll(context)
            BlackHoleTicker.scheduleNext(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    companion object {
        /** 渲染并推送位图到给定组件实例（读取主题 / 分钟重绘偏好）。 */
        fun pushUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
            if (appWidgetIds.isEmpty()) return
            val theme = WidgetStudioPrefs.theme(context)
            val density = context.resources.displayMetrics.density
            appWidgetIds.forEach { id ->
                runCatching {
                    val opts = appWidgetManager.getAppWidgetOptions(id)
                    // 选项给的是 dp（minWidth = 竖屏宽度）：换算像素并设下限
                    val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).coerceAtLeast(110)
                    val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).coerceAtLeast(110)
                    val wPx = (wDp * density).toInt().coerceAtLeast(160)
                    val hPx = (hDp * density).toInt().coerceAtLeast(160)
                    val bitmap = BlackHoleArt.render(wPx, hPx, System.currentTimeMillis(), theme)
                    val views = android.widget.RemoteViews(context.packageName, R.layout.black_hole_widget).apply {
                        setImageViewBitmap(R.id.widget_canvas, bitmap)
                    }
                    appWidgetManager.updateAppWidget(id, views)
                }.onFailure { e ->
                    // 单实例失败不拖垮整批（如厂商启动器对位图尺寸有上限）
                    Log.w(TAG, "黑洞时钟重绘失败 id=$id", e)
                }
            }
        }

        private const val TAG = "BlackHoleClock"
    }
}

/** 2×2 方版入口 —— 与宽版同一引擎，仅为桌面选择器提供第二个尺寸选项。 */
class BlackHoleClockWidgetSmall : BlackHoleClockWidget()

/** 开机重排分钟级重绘链（闹钟不随重启存活 —— 与 blueprint 同款纪律），并刷新全部四家族。 */
class BlackHoleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AbyssWidgetFamily.refreshAll(context)
        BlackHoleTicker.scheduleNext(context)
    }
}

/**
 * 「精确到分」自续期闹钟链：每个整分边界触发 ACTION_TICK → 重绘全部
 * 四条家族实例（时钟宽/方 + 电池 + 日历 + 引力仪，见
 * [AbyssWidgetFamily.refreshAll]）→ 重排下一次。用户可在组件工坊关闭
 * （省电模式语义：全家回到 30 分钟系统兜底轨 —— 一个开关管四组件）。
 *
 * 公共面（ACTION_TICK / scheduleNext / cancel / refreshNow）自 v0.8.0
 * 起保持不变 —— 工坊页的偏好流与「立即刷新」按钮零改动。
 */
object BlackHoleTicker {

    const val ACTION_TICK = "com.abyss.tools.action.BLACK_HOLE_TICK"
    private const val REQUEST_CODE = 0x0B11
    private const val TAG = "BlackHoleTicker"

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BlackHoleClockWidget::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun scheduleNext(context: Context) {
        if (!context.minuteTickEnabled()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = BlackHoleMath.nextMinuteBoundary(System.currentTimeMillis())
        runCatching {
            // targetSdk 28：setExactAndAllowWhileIdle 无需 SCHEDULE_EXACT_ALARM
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        }.onFailure {
            // 双保险降级（对齐 blueprint ScheduleAlarmReceiver 的策略）
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context)) }
                .onFailure { e -> Log.w(TAG, "分钟重绘闹钟重排失败", e) }
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }

    /** 立即触发一次全家重绘 + 重排（组件工坊「立即刷新」按钮消费）。 */
    fun refreshNow(context: Context) {
        AbyssWidgetFamily.refreshAll(context)
        scheduleNext(context)
    }

    /** SDK 门闩仅为 KDoc 一致性（minSdk 26 恒真）—— 防 lint 误报未用。 */
    @Suppress("unused")
    private val sdkGate: Boolean = Build.VERSION.SDK_INT >= 26
}
