package com.abyss.tools.privilege

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.abyss.tools.core.common.privilege.Capability
import com.abyss.tools.core.common.privilege.PrivilegeSnapshot
import com.abyss.tools.core.shell.ShizukuShell
import com.abyss.tools.core.shell.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 权限探针：负责探测系统中各能力的实际启用状态
 */
object PrivilegeProbe {

    /**
     * Abyss无障碍服务组件名
     */
    private const val ACCESSIBILITY_SERVICE_CLASS = "com.abyss.tools.privilege.AbyssAccessibilityService"

    /**
     * Dhizuku相关包名与动作
     */
    private const val DHIZUKU_PACKAGE = "moe.hamon.dhizuku"
    private const val SHEVERY_PACKAGE = "io.github.vova7865.shevery"
    private const val SHIZUKU_PLUS_PACKAGE = "io.github.vova7865.shizukuplus"

    /**
     * 异步探测当前所有权限能力，生成快照
     */
    suspend fun snapshot(context: Context): PrivilegeSnapshot = withContext(Dispatchers.IO) {
        val capabilities = mutableSetOf<Capability>()
        var shizukuVersion: Int? = null
        var shizukuVariant: String? = null

        // 1. 探测Shizuku状态
        val shizukuStatus = ShizukuShell.current()
        if (shizukuStatus is ShizukuStatus.Running) {
            if (shizukuStatus.authorized) {
                capabilities.add(Capability.SHIZUKU_SHELL)
                shizukuVersion = shizukuStatus.version
                // 探测Shizuku是否为root运行
                try {
                    val process = Runtime.getRuntime().exec("id -u")
                    val uid = process.inputStream.bufferedReader().readLine().trim()
                    if (uid == "0") {
                        capabilities.add(Capability.SHIZUKU_ROOT)
                    }
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        // 探测Shizuku变体（Sui/Shevery/Shizuku+）
        shizukuVariant = detectShizukuVariant(context)
        if (shizukuVariant != null && shizukuVariant != "official") {
            // Shevery/Shizuku+支持Dhizuku，进一步探测Dhizuku是否激活
            if (isDhizukuActive(context)) {
                capabilities.add(Capability.DHIZUKU_ACTIVE)
            }
        }

        // 2. 探测无障碍服务状态（修复id比对bug：用ComponentName相等判断）
        if (isAccessibilityEnabled(context)) {
            capabilities.add(Capability.ACCESSIBILITY)
        }

        // 3. 探测设备管理员状态
        if (isDeviceAdminActive(context)) {
            capabilities.add(Capability.DEVICE_ADMIN_ACTIVE)
        }

        // 4. 探测安装未知应用权限
        if (context.packageManager.canRequestPackageInstalls()) {
            capabilities.add(Capability.INSTALL_UNKNOWN_APPS)
        }

        // 5. 探测通知监听权限
        if (isNotificationListenerEnabled(context)) {
            capabilities.add(Capability.NOTIFICATION_LISTENER)
        }

        // 6. 探测写入安全设置权限
        if (Settings.System.canWrite(context)) {
            capabilities.add(Capability.WRITE_SECURE_SETTINGS)
        }

        PrivilegeSnapshot(
            capabilities = capabilities,
            shizukuVersion = shizukuVersion,
            shizukuVariant = shizukuVariant
        )
    }

    /**
     * 探测Shizuku运行变体
     */
    private fun detectShizukuVariant(context: Context): String? {
        val pm = context.packageManager
        return when {
            isPackageInstalled(pm, "moe.shizuku.privileged.api") -> "official"
            isPackageInstalled(pm, "com.android.sui") -> "sui"
            isPackageInstalled(pm, SHEVERY_PACKAGE) -> "shevery"
            isPackageInstalled(pm, SHIZUKU_PLUS_PACKAGE) -> "shizukuplus"
            else -> null
        }
    }

    /**
     * 检查包是否已安装
     */
    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 探测Dhizuku是否已激活（通过Dhizuku公开API判定）
     */
    private fun isDhizukuActive(context: Context): Boolean {
        return try {
            // Dhizuku激活后会提供授权，尝试通过反射检测Dhizuku服务是否运行
            val dhizukuComponent = ComponentName(
                DHIZUKU_PACKAGE,
                "$DHIZUKU_PACKAGE.server.DhizukuServerService"
            )
            val state = context.packageManager.getComponentEnabledSetting(dhizukuComponent)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 探测无障碍服务是否已启用
     * 修复bug：使用ComponentName.unflattenFromString比较，而非字符串直接相等
     */
    private fun isAccessibilityEnabled(context: Context): Boolean {
        val myComponent = ComponentName(context.packageName, ACCESSIBILITY_SERVICE_CLASS)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val inSecure = try {
            val enabledServicesStr = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServicesStr.split(':').any { serviceStr ->
                val cn = ComponentName.unflattenFromString(serviceStr)
                cn == myComponent
            }
        } catch (_: Exception) {
            false
        }
        val bound = enabledServices.any { serviceInfo ->
            ComponentName.unflattenFromString(serviceInfo.id) == myComponent
        }
        return inSecure && bound
    }

    /**
     * 探测设备管理员是否已激活
     */
    private fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val adminComponent = ComponentName(
            context.packageName,
            "com.abyss.tools.privilege.AbyssDeviceAdminReceiver"
        )
        return try {
            dpm.isAdminActive(adminComponent)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 探测通知监听权限是否已启用
     */
    private fun isNotificationListenerEnabled(context: Context): Boolean {
        return try {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            val myComponent = ComponentName(
                context.packageName,
                "com.abyss.tools.privilege.AbyssNotificationListener"
            )
            enabledListeners.split(':').any {
                ComponentName.unflattenFromString(it) == myComponent
            }
        } catch (_: Exception) {
            false
        }
    }
}
