package com.abyss.tools.core.plugin

import kotlinx.serialization.Serializable

/**
 * 插件权限透明化面板数据模型（N-EXP-006，M1）。
 *
 * 设计目标：把插件"要什么能力、默认开不开、传不传数据"用结构化字段
 * 描述，UI 层可以在安装前逐条人话展示，用户可独立开关每一项。
 *
 * 字段语义严格对齐 [plugin.json schema](PLG-002) 扩展字段 `permissions[]`：
 * ```json
 * "permissions": [
 *   { "id": "shizuku_shell", "required": false, "default": false,
 *     "label": "执行 Shizuku Shell 命令", "risk": "中等",
 *     "dataTransmitted": false }
 * ]
 * ```
 */
@Serializable
data class PluginPermission(
    /** 权限稳定 ID（shizuku_shell / internet / cloud_hash_lookup / overlay 等）。 */
    val id: String,
    /** 是否为插件运行必需权限（true = 关闭后插件完全不可用；false = 可降级）。 */
    val required: Boolean = false,
    /** 默认开关。涉及外传 / Shizuku / 安装监听的权限一律默认 false。 */
    val default: Boolean = false,
    /** 人话标签（中文，UI 直接展示）。 */
    val label: String,
    /** 人话风险等级说明（"无/低/中/高"）。 */
    val risk: String = "低",
    /** 是否会向第三方（含开发者自建服务器）传出用户数据。 */
    val dataTransmitted: Boolean = false,
    /** 外传数据类别（仅当 dataTransmitted=true 时有意义，如"文件哈希"）。 */
    val dataCategory: String? = null,
    /** 接收方说明（如 "VirusTotal 公共 API"）。 */
    val thirdParty: String? = null,
    /** 更长的人话说明（可选，对话框/详情页展示）。 */
    val description: String? = null,
) {
    /** 权限类别归类，供 UI 分组展示。 */
    val group: PermissionGroup get() = PermissionGroup.fromId(id)
}

@Serializable
enum class PermissionGroup {
    /** 执行 Shizuku/root 命令 */
    EXECUTION,
    /** 网络访问（仅含本组织更新通道 = INTERNAL，其它 = EXTERNAL） */
    NETWORK_INTERNAL,
    NETWORK_EXTERNAL,
    /** 悬浮窗 / 覆盖层 */
    OVERLAY,
    /** 监听/读取其它应用（安装事件/应用列表） */
    APP_OBSERVE,
    /** 读取 / 修改用户数据 */
    USER_DATA,
    OTHER;

    companion object {
        fun fromId(id: String): PermissionGroup = when {
            id.startsWith("shizuku") || id == "shell" || id == "root" -> EXECUTION
            id == "cloud_hash_lookup" || id == "external_api" -> NETWORK_EXTERNAL
            id == "internet" || id == "update_channel" -> NETWORK_INTERNAL
            id == "overlay" || id.startsWith("window_") -> OVERLAY
            id == "install_listener" || id == "package_query" || id == "app_list" -> APP_OBSERVE
            id.startsWith("read_") || id.startsWith("write_") || id == "storage" -> USER_DATA
            else -> OTHER
        }
    }
}

/**
 * 插件权限体检结论，供诊断中心/插件详情页展示。
 */
data class PermissionAudit(
    val pluginId: String,
    val permissions: List<PluginPermission>,
    /** 默认开启但带外传风险的权限数量（应警示）。 */
    val riskyDefaultOn: Int,
    /** 第三方外传项列表（必须在 UI 显著标注"会上传数据"）。 */
    val externalTransmissions: List<PluginPermission>,
) {
    companion object {
        fun audit(pluginId: String, permissions: List<PluginPermission>): PermissionAudit {
            val risky = permissions.count { it.default && (it.dataTransmitted || it.risk == "高") }
            val ext = permissions.filter { it.dataTransmitted }
            return PermissionAudit(pluginId, permissions, risky, ext)
        }
    }
}
