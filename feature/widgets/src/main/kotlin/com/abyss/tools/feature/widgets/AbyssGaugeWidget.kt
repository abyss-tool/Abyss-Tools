package com.abyss.tools.feature.widgets

import android.app.ActivityManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.widget.RemoteViews

/**
 * 深渊引力仪桌面组件（v0.9.0「引力场」，2×2）。
 *
 * 双环读数，均为渲染时无权限读取（不缓存 —— 与电池同款理由）：
 *  - 外环 内存：ActivityManager.MemoryInfo（totalMem - availMem / totalMem）；
 *  - 内环 存储：StatFs(data 分区)（1 - availableBytes / totalBytes）。
 * 任一读取失败只影响对应环（暗轨 + 文字行「读取失败」），另一环照常 ——
 * fail-closed 但不株连（内存读得到而存储读不到是真实存在的设备态）。
 *
 * 更新节奏双轨（与黑洞时钟同款）：30 分钟系统兜底 + 整分闹钟链。
 */
class AbyssGaugeWidget : AppWidgetProvider() {

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
        private const val TAG = "AbyssGauge"

        /**
         * 读内存 / 存储占用快照（无权限）。对应项失败 → 对应字段 null
         * （绘制层只画暗轨 + 诚实失败标注）。
         */
        fun readSnapshot(context: Context): AbyssGaugeSnapshot {
            return AbyssGaugeSnapshot(
                ramFraction = readRamFraction(context),
                storageFraction = readStorageFraction(context),
            )
        }

        private fun readRamFraction(context: Context): Float? = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            if (am != null) am.getMemoryInfo(mi)
            // am 取不到时 mi 全零 —— totalMem<=0 一并按失败处理（fail-closed）
            if (mi.totalMem > 0) (mi.totalMem - mi.availMem).toFloat() / mi.totalMem else null
        }.getOrNull()

        private fun readStorageFraction(context: Context): Float? = runCatching {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            if (stat.totalBytes > 0) 1f - stat.availableBytes.toFloat() / stat.totalBytes else null
        }.getOrNull()

        /** 渲染并推送位图到给定组件实例（读取主题偏好 → 逐实例尺寸 → 渲染 → RemoteViews）。 */
        fun pushUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
            if (appWidgetIds.isEmpty()) return
            val theme = WidgetStudioPrefs.theme(context)
            val density = context.resources.displayMetrics.density
            // 一批实例共享同一次读数（内存/存储是全局量 —— 各实例只在尺寸上不同）
            val snapshot = readSnapshot(context)
            appWidgetIds.forEach { id ->
                runCatching {
                    val opts = appWidgetManager.getAppWidgetOptions(id)
                    val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).coerceAtLeast(110)
                    val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).coerceAtLeast(110)
                    val wPx = (wDp * density).toInt().coerceAtLeast(160)
                    val hPx = (hDp * density).toInt().coerceAtLeast(160)
                    val bitmap = AbyssGaugeArt.render(wPx, hPx, System.currentTimeMillis(), theme, snapshot)
                    val views = RemoteViews(context.packageName, R.layout.abyss_gauge_widget).apply {
                        setImageViewBitmap(R.id.widget_canvas, bitmap)
                    }
                    appWidgetManager.updateAppWidget(id, views)
                }.onFailure { e ->
                    // 单实例失败不拖垮整批
                    Log.w(TAG, "深渊引力仪重绘失败 id=$id", e)
                }
            }
        }
    }
}
