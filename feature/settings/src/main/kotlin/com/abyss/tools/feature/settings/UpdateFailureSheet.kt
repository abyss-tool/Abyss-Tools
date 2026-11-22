package com.abyss.tools.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abyss.tools.core.update.UpdateFailure
import com.abyss.tools.core.update.UpdateFailureReason
import com.abyss.tools.core.update.UpdateSessionSummary

/**
 * N-UPD-003 · 更新失败详情底部抽屉
 *
 * 展示：
 * - 原因分类（人话标题）
 * - 具体说明与建议
 * - 本次会话统计（镜像、耗时、重试次数、下载字节）
 * - 复制诊断ID按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateFailureSheet(
    summary: UpdateSessionSummary,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCopyDiagnostic: (String) -> Unit
) {
    val failure = summary.error ?: return
    val cs = MaterialTheme.colorScheme
    val categoryIcon = when (failure.reason.category) {
        "security" -> "🔒"
        "network" -> "🌐"
        "storage" -> "💾"
        "install" -> "📦"
        "parse" -> "⚠"
        else -> "ℹ"
    }
    val categoryColor = when (failure.reason.category) {
        "security", "parse" -> cs.error
        "network", "install" -> cs.tertiary
        "storage" -> cs.primary
        else -> cs.secondary
    }

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 拖拽条
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp).height(4.dp)
                        .background(cs.outlineVariant, RoundedCornerShape(2.dp))
                )
            }

            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).background(categoryColor.copy(alpha = 0.15f),
                    RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Text(categoryIcon, fontSize = 14.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(failure.formatUserMessage(),
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = cs.onSurface)
                    Text("诊断ID：${summary.sessionId}",
                        fontSize = 11.sp, color = cs.secondary)
                }
            }

            // 建议卡片
            Surface(color = cs.surfaceContainerLowest, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (failure.reason.recoverable) "✅ 这个问题通常可以自行恢复"
                        else "🛑 这个问题需要检查网络/联系开发者",
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = if (failure.reason.recoverable) cs.primary else cs.error
                    )
                    Text(suggestionFor(failure.reason), fontSize = 12.sp, color = cs.onSurfaceVariant, lineHeight = 18.sp)
                }
            }

            // 本次会话统计
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatRow("镜像源", summary.mirrorId ?: "—", cs)
                StatRow("重试次数", "${summary.retries} 次", cs)
                StatRow("下载大小", formatBytes(summary.totalBytes), cs)
                StatRow("总耗时", "${summary.totalLatencyMs / 1000} 秒", cs)
                StatRow("事件数", "${summary.eventCount}", cs)
            }

            // 操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onCopyDiagnostic(summary.sessionId) },
                    modifier = Modifier.weight(1f)) {
                    Text("复制诊断ID")
                }
                Button(onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    enabled = failure.reason.recoverable) {
                    Text(if (failure.reason.recoverable) "重试更新" else "知道了")
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, cs: ColorScheme) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = cs.secondary)
        Text(value, fontSize = 12.sp, color = cs.onSurfaceVariant, fontWeight = FontWeight.Medium)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun suggestionFor(r: UpdateFailureReason): String = when (r) {
    UpdateFailureReason.NETWORK_UNREACHABLE,
    UpdateFailureReason.CONNECTION_TIMEOUT,
    UpdateFailureReason.DNS_LOOKUP_FAILED ->
        "建议切换 Wi-Fi/移动数据后重试；如仍失败请尝试开启 VPN 或切换 DNS。"
    UpdateFailureReason.MIRROR_ALL_UNREACHABLE,
    UpdateFailureReason.HTTP_5XX_SERVER ->
        "当前所有镜像均不可用，可能是服务器临时故障，建议 5–10 分钟后再试。"
    UpdateFailureReason.SSL_HANDSHAKE_FAILED,
    UpdateFailureReason.HTTP_REDIRECT_TO_UNTRUSTED_HOST ->
        "可能是网络被劫持、公司代理或证书异常，请先尝试切换到可信网络。如问题持续，勿继续安装。"
    UpdateFailureReason.SIGNATURE_VERIFICATION_FAILED,
    UpdateFailureReason.SIGNATURE_MISSING,
    UpdateFailureReason.PUBLISHER_KEY_MISMATCH ->
        "文件签名校验失败，可能是第三方篡改或网络劫持。请不要继续安装并将诊断ID发给开发者。"
    UpdateFailureReason.CHECKSUM_MISMATCH,
    UpdateFailureReason.PART_FILE_CORRUPTED ->
        "下载过程文件损坏，点击重试会自动重新下载。如多次失败请清理应用缓存后再试。"
    UpdateFailureReason.STORAGE_INSUFFICIENT,
    UpdateFailureReason.INSTALL_INSUFFICIENT_STORAGE ->
        "请清理系统存储空间，建议至少保留安装包大小 2 倍以上的空闲空间。"
    UpdateFailureReason.INSTALL_SIGNATURE_MISMATCH ->
        "设备上已安装签名不一致的版本，请先卸载旧版后再安装。"
    UpdateFailureReason.INSTALL_VERSION_DOWNGRADE ->
        "当前已安装更高版本，无需回退。如确需降级请先卸载。"
    UpdateFailureReason.INSTALL_USER_REJECTED ->
        "您取消了安装流程，可以随时点击「重试更新」再次触发安装。"
    UpdateFailureReason.INSTALL_BLOCKED ->
        "请在系统设置中允许 Abyss 安装未知来源应用，然后重试。"
    UpdateFailureReason.INDEX_PARSE_FAILED,
    UpdateFailureReason.MANIFEST_DRIFT ->
        "更新清单格式异常，可能是您当前版本过旧，请升级到最新版客户端。"
    UpdateFailureReason.PATCH_APPLY_FAILED,
    UpdateFailureReason.PATCH_PARSE_FAILED,
    UpdateFailureReason.PATCH_VERIFY_FAILED ->
        "增量补丁合成失败，下次重试会自动改为下载完整包。"
    UpdateFailureReason.WRITE_PERMISSION_DENIED ->
        "应用无法写入下载目录，请在系统设置中授予存储权限。"
    UpdateFailureReason.INSTALL_PARSE_APK_BAD,
    UpdateFailureReason.INSTALL_ABORTED ->
        "安装包格式异常或被系统中止，请重试。如多次失败请反馈。"
    UpdateFailureReason.HTTP_4XX_CLIENT ->
        "请求被服务器拒绝，可能是当前版本过旧或已被回滚。"
    UpdateFailureReason.UNKNOWN ->
        "未知错误，请将诊断ID发给开发者排查。"
}
