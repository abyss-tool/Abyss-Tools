package com.abyss.tools.privilege

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import com.abyss.tools.core.common.Capability
import com.abyss.tools.core.common.PrivilegeSnapshot
import com.abyss.tools.core.common.ShizukuFacet
import com.abyss.tools.core.shell.ShizukuMonitor
import com.abyss.tools.core.shell.ShizukuStatus
import com.abyss.tools.core.shell.ShizukuVariants

/**
 * 能力探测接口（ARC-011 FR-007）。接口放 app 侧、实现隔离 Android 依赖 ——
 * 与 `PackageLauncherIconGateway` / `PackageInstallerApkGateway` 同构的
 * 手动 DI 纪律；[PrivilegeRepository] 只认接口，便于替换与单测桩。
 */
interface PrivilegeProbe {
    fun snapshot(): PrivilegeSnapshot
}

/**
 * 四类能力探测的 Android 实现。
 *
 * 纪律：
 *  - 任一探测异常一律 runCatching 吞为 false 并写 `reasons`（人话），绝不抛到 UI；
 *  - 无障碍双路交叉（Secure 设置登记 ∧ AccessibilityManager 已绑定），不一致保守判 false；
 *  - Dhizuku 三方交叉（init ∧ isPermissionGranted ∧ 提供端 isDeviceOwnerApp），
 *    **不**用跨包组件态 / 包名硬判（PR #30 首版的误判路径）；
 *  - Shizuku 身份用 `Shizuku.getUid()`，**不**用本进程 `id -u`（那是 App 自己的 uid，
 *    与 Shizuku 服务端身份无关）。
 */
class AndroidPrivilegeProbe(
    context: Context,
    private val accessibilityComponent: ComponentName = ComponentName(
        context,
        AbyssAccessibilityService::class.java,
    ),
    private val deviceAdminComponent: ComponentName = ComponentName(
        context,
        AbyssDeviceAdminReceiver::class.java,
    ),
    private val deviceOwnerProviders: List<String> = ShizukuVariants.DEVICE_OWNER_PROVIDERS,
) : PrivilegeProbe {

    private val appContext: Context = context.applicationContext

    override fun snapshot(): PrivilegeSnapshot {
        val capabilities = linkedSetOf<Capability>()
        val reasons = linkedMapOf<Capability, String>()
        val notes = mutableListOf<String>()

        // ── 零授权能力：自身组件恒可用；应用观察随 Manifest 的 QUERY_ALL_PACKAGES ──
        capabilities += Capability.SELF_BRANDING
        if (hasPermission(android.Manifest.permission.QUERY_ALL_PACKAGES)) {
            capabilities += Capability.APP_OBSERVE
        } else {
            reasons[Capability.APP_OBSERVE] = "QUERY_ALL_PACKAGES 未授予"
        }

        // ── Shizuku（core:shell 单一状态源）────────────────────────────
        var facet = ShizukuFacet()
        when (val status = runCatching { ShizukuMonitor.current() }.getOrDefault(ShizukuStatus.NotRunning)) {
            ShizukuStatus.NotRunning -> {
                reasons[Capability.SHIZUKU_SHELL] = "Shizuku 未运行"
                reasons[Capability.SHIZUKU_ROOT] = "Shizuku 未运行"
            }
            is ShizukuStatus.Running -> {
                facet = ShizukuFacet(
                    version = status.version,
                    serverUid = status.serverUid,
                    suiAvailable = status.suiAvailable,
                    installedVariants = status.installedVariants.map { it.displayName },
                    variantHint = status.variantHint,
                )
                if (status.authorized) {
                    capabilities += Capability.SHIZUKU_SHELL
                    if (status.serverUid == 0) {
                        capabilities += Capability.SHIZUKU_ROOT
                    } else {
                        reasons[Capability.SHIZUKU_ROOT] =
                            if (status.serverUid == null) "服务端身份未知" else "服务端以 shell 身份运行"
                    }
                } else {
                    reasons[Capability.SHIZUKU_SHELL] = "Shizuku 运行中但未授权本应用"
                    reasons[Capability.SHIZUKU_ROOT] = "Shizuku 未授权"
                }
            }
        }

        // ── 无障碍（双路交叉）──────────────────────────────────────────
        val (accEnabled, accReason) = accessibilityEnabled()
        if (accEnabled) capabilities += Capability.ACCESSIBILITY else reasons[Capability.ACCESSIBILITY] = accReason

        // ── 设备管理员（本 App receiver）──────────────────────────────
        val (adminActive, adminReason) = deviceAdminActive()
        if (adminActive) {
            capabilities += Capability.DEVICE_ADMIN_ACTIVE
        } else {
            reasons[Capability.DEVICE_ADMIN_ACTIVE] = adminReason
        }

        // ── Dhizuku（三方交叉）────────────────────────────────────────
        val (dhizukuActive, dhizukuReason) = dhizukuActive()
        if (dhizukuActive) capabilities += Capability.DHIZUKU_ACTIVE else reasons[Capability.DHIZUKU_ACTIVE] = dhizukuReason

        // ── 特殊授权（不升档，只登记）──────────────────────────────────
        val installGranted = runCatching { appContext.packageManager.canRequestPackageInstalls() }
            .getOrDefault(false)
        if (installGranted) {
            capabilities += Capability.INSTALL_UNKNOWN
        } else {
            reasons[Capability.INSTALL_UNKNOWN] = "未允许安装未知应用"
        }

        val overlayGranted = runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)
        if (overlayGranted) {
            capabilities += Capability.OVERLAY
        } else {
            reasons[Capability.OVERLAY] = "未允许显示在其他应用上层"
        }

        // ── 环境提示（只作说明，不卡流程）──────────────────────────────
        notes += environmentNotes(accessibilityEnabled = accEnabled)

        return PrivilegeSnapshot(
            capabilities = capabilities,
            shizuku = facet,
            reasons = reasons,
            environmentNotes = notes,
            probedAtEpochMs = System.currentTimeMillis(),
        )
    }

    /** 无障碍：主路 Settings.Secure，辅路 AccessibilityManager 已绑定列表；不一致判 false。 */
    fun accessibilityEnabled(): Pair<Boolean, String> {
        val am = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false to "系统无障碍服务不可用"
        val inSecure = runCatching {
            val flat = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            flat?.split(':')?.any { ComponentName.unflattenFromString(it) == accessibilityComponent } == true
        }.getOrDefault(false)
        val bound = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info -> ComponentName.unflattenFromString(info.id) == accessibilityComponent }
        }.getOrDefault(false)
        return when {
            inSecure && bound -> true to ""
            inSecure && !bound -> false to "无障碍已登记但服务未连接"
            else -> false to "无障碍服务未开启"
        }
    }

    /** 设备管理员：本 App receiver 的 isAdminActive（Device Admin，非 Device Owner）。 */
    fun deviceAdminActive(): Pair<Boolean, String> {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false to "设备策略服务不可用"
        val active = runCatching { dpm.isAdminActive(deviceAdminComponent) }.getOrDefault(false)
        return if (active) true to "" else false to "设备管理员未激活"
    }

    /** Dhizuku：公开 API + DPM 三方交叉；反射只进 debug 面板（本处不用反射）。 */
    fun dhizukuActive(): Pair<Boolean, String> {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false to "设备策略服务不可用"
        val providerIsDo = runCatching {
            deviceOwnerProviders.any { dpm.isDeviceOwnerApp(it) }
        }.getOrDefault(false)
        if (!providerIsDo) return false to "Dhizuku 提供端不是设备所有者"
        val failure: String? = runCatching {
            val initOk = DhizukuBridge.init(appContext)
            if (initOk && DhizukuBridge.isPermissionGranted()) null else "Dhizuku 未授权本应用"
        }.getOrElse { "Dhizuku 未安装或不可用" }
        return if (failure == null) true to "" else false to failure
    }

    /** 设备环境限制的人话提示（Android 15 受限设置 / 16 高级保护）。 */
    private fun environmentNotes(accessibilityEnabled: Boolean): List<String> {
        val notes = mutableListOf<String>()
        // API 35 = Android 15（侧载受限设置）/ API 36 = Android 16（高级保护）
        if (!accessibilityEnabled && Build.VERSION.SDK_INT >= 35) {
            notes += "Android 15+ 侧载应用需先在系统设置「允许受限设置」才能开启无障碍"
        }
        if (!accessibilityEnabled && Build.VERSION.SDK_INT >= 36) {
            notes += "Android 16 开启高级保护时无障碍不可授予，本机最高档为高级用户"
        }
        return notes
    }

    private fun hasPermission(permission: String): Boolean = runCatching {
        appContext.packageManager.checkPermission(permission, appContext.packageName) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
