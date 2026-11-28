package com.abyss.tools.privilege

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.abyss.tools.core.common.Capability
import com.abyss.tools.core.shell.ShizukuStatus

/**
 * 「去授权」落点（FEAT-038 FR-007）：能力 → 系统页 / 请求。
 *
 * 纪律：跳转一律 runCatching —— OEM 裁剪设置项时 ActivityNotFoundException
 * 只吞掉不崩；返回 false 让调用方知道没有可达路径（UI 可给一句话提示）。
 * 授权结果不在此缓存：返回 App 后由 onResume 重读真值。
 */
object CapabilityGuideIntents {

    fun open(
        context: Context,
        capability: Capability,
        shizukuStatus: ShizukuStatus,
        onRequestShizukuPermission: () -> Unit,
        onOpenShizuku: () -> Unit,
    ): Boolean = when (capability) {
        Capability.SHIZUKU_SHELL, Capability.SHIZUKU_ROOT -> {
            val running = shizukuStatus as? ShizukuStatus.Running
            if (running != null && !running.authorized) onRequestShizukuPermission() else onOpenShizuku()
            true
        }
        Capability.ACCESSIBILITY -> startActivity(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Capability.DEVICE_ADMIN_ACTIVE -> startActivity(context, addDeviceAdminIntent(context))
        Capability.DHIZUKU_ACTIVE -> openDhizuku(context)
        Capability.INSTALL_UNKNOWN -> startActivity(
            context,
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ),
        )
        Capability.OVERLAY -> startActivity(
            context,
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
        Capability.SELF_BRANDING, Capability.APP_OBSERVE -> false
    }

    /** 添加设备管理员（系统确认页；EXTRA_ADD_EXPLANATION 如实说明影响）。 */
    fun addDeviceAdminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(context, AbyssDeviceAdminReceiver::class.java),
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "激活后可解锁 abyss-ultra 档能力；当前版本尚无设备策略动作，可随时在设置中取消激活",
            )
        }

    /** Dhizuku：已装提供端 → 发授权请求；未装 → 退到设备管理员确认页（至少可达）。 */
    private fun openDhizuku(context: Context): Boolean {
        if (DhizukuBridge.providerInstalled(context) && DhizukuBridge.requestPermission(context)) return true
        return startActivity(context, addDeviceAdminIntent(context))
    }

    private fun startActivity(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
