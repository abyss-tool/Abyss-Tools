package com.abyss.tools.core.common.privilege

/**
 * 能力枚举：每个枚举项代表一种具体可检测的系统权限/能力
 */
enum class Capability {
    SHIZUKU_SHELL,        // Shizuku已授权可使用shell
    SHIZUKU_ROOT,         // Shizuku以root身份运行
    ACCESSIBILITY,        // 无障碍服务已启用
    DEVICE_ADMIN_ACTIVE,  // 设备管理员已激活
    DHIZUKU_ACTIVE,       // Dhizuku已激活
    INSTALL_UNKNOWN_APPS, // 允许安装未知来源应用
    NOTIFICATION_LISTENER,// 通知监听权限
    WRITE_SECURE_SETTINGS,// 写入安全设置权限
}

/**
 * 用户权限档位：按需求严格定义五档
 */
enum class PrivilegeTier(val label: String, val badgeColor: Long) {
    BASIC("普通用户", 0xFF9E9E9E),          // 灰色：无任何授予权限
    ADVANCED("进阶用户", 0xFF2196F3),       // 蓝色：开启无障碍
    SENIOR("高级用户", 0xFF4CAF50),         // 绿色：Shizuku已授权
    ABYSS_MAX("Abyss-Max", 0xFFFF9800),     // 橙色：Shizuku + 无障碍等必要权限
    ABYSS_ULTRA("Abyss-Ultra", 0xFF9C27B0)  // 紫色：Shizuku + Dhizuku + 设备管理员
}

/**
 * 权限快照：某一时刻系统中所有能力的启用状态
 */
data class PrivilegeSnapshot(
    val capabilities: Set<Capability>,
    val shizukuVersion: Int? = null,
    val shizukuVariant: String? = null
) {
    fun has(capability: Capability) = capability in capabilities
}

/**
 * 档位计算器：根据能力集合计算最终权限档位，严格遵循需求规则
 */
object TierCalculator {
    fun compute(snapshot: PrivilegeSnapshot): PrivilegeTier {
        val caps = snapshot.capabilities
        return when {
            // Abyss-Ultra：Shizuku + Dhizuku + 设备管理员（严格判定，不使用root顶替）
            caps.contains(Capability.SHIZUKU_SHELL) &&
            caps.contains(Capability.DHIZUKU_ACTIVE) &&
            caps.contains(Capability.DEVICE_ADMIN_ACTIVE) -> PrivilegeTier.ABYSS_ULTRA

            // Abyss-Max：Shizuku + 无障碍等必要权限
            caps.contains(Capability.SHIZUKU_SHELL) &&
            caps.contains(Capability.ACCESSIBILITY) -> PrivilegeTier.ABYSS_MAX

            // 高级用户：Shizuku已授权
            caps.contains(Capability.SHIZUKU_SHELL) -> PrivilegeTier.SENIOR

            // 进阶用户：开启无障碍
            caps.contains(Capability.ACCESSIBILITY) -> PrivilegeTier.ADVANCED

            // 普通用户：无任何授予权限
            else -> PrivilegeTier.BASIC
        }
    }

    /**
     * 判断给定工具的最低权限要求是否满足
     */
    fun satisfies(required: Set<Capability>, snapshot: PrivilegeSnapshot): Boolean {
        return snapshot.capabilities.containsAll(required)
    }
}

/**
 * 各档位说明文案
 */
object TierDescriptions {
    const val BASIC_HINT = "当前未授予任何特殊权限，只能启动极少功能。完成授权后可解锁更多高级能力。"
    const val ADVANCED_HINT = "已开启无障碍服务，可使用界面自动化、智能交互类进阶功能。"
    const val SENIOR_HINT = "Shizuku已授权，可执行系统级命令、应用管理等高级功能。"
    const val MAX_HINT = "Shizuku + 无障碍已开启，解锁全部常规功能，可使用应用构建器、系统深度控制等能力。"
    const val ULTRA_HINT = "已激活Abyss-Ultra最高权限：Shizuku + Dhizuku + 设备管理员，可使用默认桌面接管、深度画像等终极能力。"
}
