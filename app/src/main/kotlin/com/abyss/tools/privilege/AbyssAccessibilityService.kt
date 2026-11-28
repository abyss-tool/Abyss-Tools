package com.abyss.tools.privilege

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureResultCallback
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.abyss.tools.ServiceRegistry

/**
 * 深渊工具无障碍服务（ARC-011 FR-007 / FEAT-040 MEDIUM 模式的能力载体）。
 *
 * 诚实声明：`isAccessibilityTool="false"`（见 res/xml/accessibility_service_config.xml）
 * —— 本服务不是无障碍工具，只提供两类**原语**，不读屏、不取窗口内容：
 *  1. 前台包名感知（typeWindowStateChanged → [lastForegroundPackage]）；
 *  2. 手势代点（[tap]，dispatchGesture）—— 自动化「代点」与场景化伪装的消费入口。
 *
 * 具体业务流程（代点确认 / 场景化切换图标）由后续规格落地；本类只做最小
 * 能力集，且随时可被用户在系统设置中关闭 —— 关闭即 [onUnbind] 刷新快照。
 */
class AbyssAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshPrivilege()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        // 自身与系统 UI 的窗口切换不算「前台应用」感知信号
        if (pkg == packageName || pkg in SYSTEM_UI_PACKAGES) return
        lastForegroundPackage = pkg
    }

    override fun onInterrupt() {
        // feedbackGeneric：无反馈通道，无需中断处理
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        val result = super.onUnbind(intent)
        refreshPrivilege()
        return result
    }

    override fun onDestroy() {
        instance = null
        refreshPrivilege()
        super.onDestroy()
    }

    companion object {
        /** 已绑定的服务实例（null = 未开启 / 已断开）。 */
        @Volatile
        var instance: AbyssAccessibilityService? = null
            private set

        /** 最近一次前台应用包名（进程内有效，不持久化）。 */
        @Volatile
        var lastForegroundPackage: String? = null
            private set

        private val SYSTEM_UI_PACKAGES = setOf("com.android.systemui", "com.android.launcher3")

        /**
         * 手势代点原语：在屏幕坐标 (x, y) 点击一次。
         * 返回 dispatch 是否被系统接受（不代表手势已完成）；服务未连接返回 false。
         */
        fun tap(x: Float, y: Float, durationMs: Long = 48L, onCompleted: ((Boolean) -> Unit)? = null): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = if (onCompleted != null) {
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) = onCompleted(true)
                    override fun onCancelled(gestureDescription: GestureDescription) = onCompleted(false)
                }
            } else {
                null
            }
            return runCatching { service.dispatchGesture(gesture, callback, Handler(Looper.getMainLooper())) }
                .getOrDefault(false)
        }

        /** 当前服务是否真的在运行（已连接）—— 比设置项登记更严格的真值。 */
        val bound: Boolean get() = instance != null

        private fun refreshPrivilege() {
            runCatching { ServiceRegistry.privilegeRepository.refresh() }
        }
    }
}
