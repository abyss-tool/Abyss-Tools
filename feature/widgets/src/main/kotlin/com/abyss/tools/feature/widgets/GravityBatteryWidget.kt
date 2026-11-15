package com.abyss.tools.feature.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews

/**
 * 引力电池桌面组件（v0.9.0「引力场」，2×2）。
 *
 * 数据为渲染时读取：ACTION_BATTERY_CHANGED 粘性广播（registerReceiver
 * (null) 只读不注册，零权限零开销），不缓存 —— 系统每 30 分钟兜底 +
 * [BlackHoleTicker] 整分链都会触发重绘，读取即最新。读取失败渲染诚实
 * 占位（fail-closed：宁可空环也不编造电量）。
 *
 * 更新节奏双轨（与黑洞时钟同款）：
 *  - 兜底轨：updatePeriodMillis = 30 分钟（系统下限，防闹钟链断裂停摆）；
 *  - 主轨：整分精确闹钟链 —— 由时钟家族统一驱动，见 [AbyssWidgetFamily]。
 */
class GravityBatteryWidget : AppWidgetProvider() {

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
        newOptions: Bundle,
    ) {
        // 拖拽改尺寸 → 立即按新尺寸重绘（位图尺寸随 widgets 选项走）
        pushUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    companion object {
        private const val TAG = "GravityBattery"

        /**
         * 读电池粘性广播（只读快照，无注册无权限）。
         * levelPct = null 表示读取失败（广播缺失 / level·scale 不健康）。
         */
        fun readSnapshot(context: Context): GravityBatterySnapshot {
            val intent = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull() ?: return GravityBatterySnapshot(levelPct = null, charging = false, full = false)

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val full = status == BatteryManager.BATTERY_STATUS_FULL
            // plugged 参与判定：部分机型插着电 status 停在 NOT_CHARGING —— 观感上仍是「充电中」
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || (plugged != 0 && !full)

            // fail-closed：scale / level 任一不健康就放弃（防除零与越界百分比）
            val pct = if (scale > 0 && level in 0..100) level * 100 / scale else -1
            return GravityBatterySnapshot(
                levelPct = if (pct in 0..100) pct else null,
                charging = charging,
                full = full,
            )
        }

        /** 渲染并推送位图到给定组件实例（读取主题偏好 → 逐实例尺寸 → 渲染 → RemoteViews）。 */
        fun pushUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
            if (appWidgetIds.isEmpty()) return
            val theme = WidgetStudioPrefs.theme(context)
            val density = context.resources.displayMetrics.density
            // 一批实例共享同一次电池快照（粘性广播读一次即够 —— 各实例只在尺寸上不同）
            val snapshot = readSnapshot(context)
            appWidgetIds.forEach { id ->
                runCatching {
                    val opts = appWidgetManager.getAppWidgetOptions(id)
                    // 选项给的是 dp（minWidth = 竖屏宽度）：换算像素并设下限
                    val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).coerceAtLeast(110)
                    val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).coerceAtLeast(110)
                    val wPx = (wDp * density).toInt().coerceAtLeast(160)
                    val hPx = (hDp * density).toInt().coerceAtLeast(160)
                    val bitmap = GravityBatteryArt.render(wPx, hPx, System.currentTimeMillis(), theme, snapshot)
                    val views = RemoteViews(context.packageName, R.layout.gravity_battery_widget).apply {
                        setImageViewBitmap(R.id.widget_canvas, bitmap)
                    }
                    appWidgetManager.updateAppWidget(id, views)
                }.onFailure { e ->
                    // 单实例失败不拖垮整批（如厂商启动器对位图尺寸有上限）
                    Log.w(TAG, "引力电池重绘失败 id=$id", e)
                }
            }
        }
    }
}
