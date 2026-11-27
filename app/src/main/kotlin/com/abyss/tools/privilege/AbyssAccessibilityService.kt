package com.abyss.tools.privilege

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Abyss无障碍服务：提供界面自动化能力，启用后用户进入进阶档位
 */
class AbyssAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 空实现，后续可扩展自动化逻辑
    }

    override fun onInterrupt() {
        // 服务中断时无特殊处理
    }
}
