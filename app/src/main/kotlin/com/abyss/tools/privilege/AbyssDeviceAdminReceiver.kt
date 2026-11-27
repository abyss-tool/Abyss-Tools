package com.abyss.tools.privilege

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Abyss设备管理员接收器：激活后用于ultra档位的系统级操作
 */
class AbyssDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
