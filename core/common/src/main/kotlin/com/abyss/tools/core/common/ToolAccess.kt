package com.abyss.tools.core.common

/**
 * 功能级门禁与多模式（FEAT-039，v0.10.2）。
 *
 * 每条工具声明「打开所需的最小能力集合」+「同一工具的降级 / 增强路径」。
 * 门禁判定唯一公式：`snapshot.allows(access.required)`；档位标签不参与。
 * `modes` 让 UI 如实展示「只读 / 执行 / 深度」各需要什么，并支撑
 * 「以受限模式进入」（任一模式门槛已满足时）。
 */
data class ToolAccess(
    /** 打开该工具所需的最小能力集合；空集 = 普通用户即可。 */
    val required: Set<Capability> = emptySet(),
    /** 多模式：同一工具的降级 / 增强路径，UI 逐条展示。 */
    val modes: List<ToolMode> = emptyList(),
    /** 能力不足时的引导落点（默认回新手引导档位中心）。 */
    val guide: ToolGuide = ToolGuide.ONBOARDING,
) {
    companion object {
        val NONE = ToolAccess()
    }
}

/** 单一模式：自己的门槛 + 人话说明（能做 / 不能做什么）。 */
data class ToolMode(
    val name: String,
    val required: Set<Capability>,
    val note: String,
)

/** 门禁说明页「去授权」的落点提示（app 壳据此决定跳引导 / 设置 / 插件中心）。 */
enum class ToolGuide {
    ONBOARDING,
    SHIZUKU,
    ACCESSIBILITY,
    DEVICE_ADMIN,
    INSTALL_SOURCES,
    OVERLAY,
    PLUGINS,
}

/** 锁定卡的短文案：「需 Shizuku、无障碍」；无缺失返回空串。 */
fun lockedLabel(missing: Set<Capability>): String =
    if (missing.isEmpty()) "" else "需 " + missing.sortedBy { it.priority }.joinToString("、") { it.label }
