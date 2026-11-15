package com.abyss.tools.feature.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews

/**
 * 星轨日历桌面组件（v0.9.0「引力场」，2×2）。
 *
 * 无系统数据读取 —— 日历即 System.currentTimeMillis()（[StellarCalendarMath]
 * 纯 JVM 折算轨道几何），读取不可能失败，无占位态。
 *
 * 更新节奏双轨（与黑洞时钟同款）：
 *  - 兜底轨：updatePeriodMillis = 30 分钟（系统下限）；
 *  - 主轨：整分精确闹钟链（一天 1440 跳 —— 行星的行进肉眼可感知），
 *    由时钟家族统一驱动，见 [AbyssWidgetFamily]。
 */
class StellarCalendarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        pushUpdate(context, appWidgetManager, appWidgetIds)
        BlackHoleTicker.scheduleNext(context)
    }

    override fun onEnabled(context: Context) {
        BlackHoleTicker.scheduleNext(context)
    }

    override fun onDisabled(context: Context) {
        // 四家族全部无实例才拆链 —— 其余家族还要整分重绘
        AbyssWidgetFamily.cancelTickerIfIdle(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        pushUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    companion object {
        private const val TAG = "StellarCalendar"

        /** 渲染并推送位图到给定组件实例（读取主题偏好 → 逐实例尺寸 → 渲染 → RemoteViews）。 */
        fun pushUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
            if (appWidgetIds.isEmpty()) return
            val theme = WidgetStudioPrefs.theme(context)
            val density = context.resources.displayMetrics.density
            appWidgetIds.forEach { id ->
                runCatching {
                    val opts = appWidgetManager.getAppWidgetOptions(id)
                    val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).coerceAtLeast(110)
                    val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).coerceAtLeast(110)
                    val wPx = (wDp * density).toInt().coerceAtLeast(160)
                    val hPx = (hDp * density).toInt().coerceAtLeast(160)
                    val bitmap = StellarCalendarArt.render(wPx, hPx, System.currentTimeMillis(), theme)
                    val views = RemoteViews(context.packageName, R.layout.stellar_calendar_widget).apply {
                        setImageViewBitmap(R.id.widget_canvas, bitmap)
                    }
                    appWidgetManager.updateAppWidget(id, views)
                }.onFailure { e ->
                    // 单实例失败不拖垮整批
                    Log.w(TAG, "星轨日历重绘失败 id=$id", e)
                }
            }
        }
    }
}
