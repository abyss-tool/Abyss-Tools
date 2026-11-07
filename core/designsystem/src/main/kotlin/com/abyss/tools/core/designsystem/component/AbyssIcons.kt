package com.abyss.tools.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 图标键 → Material 图标的单一映射。
 *
 * core:common 的 ToolDef 只带字符串 iconKey（保持纯 Kotlin 无 Compose
 * 依赖）；本对象把键落到 material-icons-extended 的具体向量。
 * 未知键一律回退 Extension（插件图腾），防手写漂移炸 UI。
 */
object AbyssIcons {
    fun icon(key: String): ImageVector = when (key) {
        "extension" -> Icons.Rounded.Extension
        "construction" -> Icons.Rounded.Construction
        "apps" -> Icons.Rounded.Apps
        "security" -> Icons.Rounded.Security
        "bolt" -> Icons.Rounded.Bolt
        "schedule" -> Icons.Rounded.Schedule
        "terminal" -> Icons.Rounded.Terminal
        "code" -> Icons.Rounded.Code
        "edit" -> Icons.Rounded.Edit
        "account_tree" -> Icons.Rounded.AccountTree
        "layers" -> Icons.Rounded.Layers
        "copy" -> Icons.Rounded.ContentCopy
        "theater" -> Icons.Rounded.TheaterComedy
        "battery" -> Icons.Rounded.BatteryFull
        "location" -> Icons.Rounded.LocationOn
        "signal" -> Icons.Rounded.SignalCellularAlt
        "smartphone" -> Icons.Rounded.Smartphone
        "visibility_off" -> Icons.Rounded.VisibilityOff
        "movie" -> Icons.Rounded.Movie
        "qr" -> Icons.Rounded.QrCode2
        "widgets" -> Icons.Rounded.Widgets
        "wallpaper" -> Icons.Rounded.Wallpaper
        "lan" -> Icons.Rounded.Lan
        "backup" -> Icons.Rounded.Backup
        "dashboard" -> Icons.Rounded.Dashboard
        "explore" -> Icons.Rounded.Explore
        "settings" -> Icons.Rounded.Settings
        "update" -> Icons.Rounded.Update
        "download" -> Icons.Rounded.Download
        "check" -> Icons.Rounded.CheckCircle
        "history" -> Icons.Rounded.History
        "info" -> Icons.Rounded.Info
        "open" -> Icons.Rounded.OpenInNew
        "warning" -> Icons.Rounded.Warning
        "search" -> Icons.Rounded.Search
        else -> Icons.Rounded.Extension
    }
}
