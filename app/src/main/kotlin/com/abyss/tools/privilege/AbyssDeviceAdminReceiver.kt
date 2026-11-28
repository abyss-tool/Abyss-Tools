package com.abyss.tools.privilege

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.abyss.tools.ServiceRegistry

/**
 * 深渊工具设备管理员接收器（ARC-011 FR-007 / N-DO-001 的探测底座）。
 *
 * 本阶段**不**落地任何设备策略动作（N-DO-001 仍为规划中）：只声明一个
 * 零策略的 receiver（res/xml/device_admin.xml），让 `isAdminActive` 有真实
 * 查询对象，并在激活 / 取消激活时刷新权限快照。
 *
 * 诚实边界（N-DO-001 FR-002 / SEC-010）：激活前 UI 必须显著告知「占用
 * 设备管理员槽位、取消激活路径、与工作资料互斥」；未激活时相关能力一律
 * 显示不可用，不假装生效。
 */
class AbyssDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        refresh()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        refresh()
    }

    private fun refresh() {
        runCatching { ServiceRegistry.privilegeRepository.refresh() }
    }
}
