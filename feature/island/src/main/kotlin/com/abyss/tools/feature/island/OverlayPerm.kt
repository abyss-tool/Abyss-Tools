package com.abyss.tools.feature.island

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 悬浮窗（SYSTEM_ALERT_WINDOW）授权层。
 *
 * 官方通道直达：Settings.canDrawOverlays 判定 + ACTION_MANAGE_OVERLAY_PERMISSION
 * 带 package Uri 跳到本应用一页的授权开关 —— 不碰任何反射 / 内部 API。
 */
internal object OverlayPerm {

    /** 宿主 applicationId（MainActivity 所在包）。 */
    private const val HOST_PACKAGE = "com.abyss.tools"

    /** 是否已获得「显示在其他应用上层」授权（官方 API，无副作用）。 */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 本应用的悬浮窗授权页 Intent（带 package Uri 直达单应用开关页）。
     *
     * 注：包名按本仓库既定 applicationId 直写（模块内无 BuildConfig，且该签名
     * 保持无参 —— 若未来出现不同 applicationId 的构建变体，需改为接收 Context
     * 的重载并以 context.packageName 构造 Uri）。
     */
    fun overlaySettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$HOST_PACKAGE"),
    )
}
