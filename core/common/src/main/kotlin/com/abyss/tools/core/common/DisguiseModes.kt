package com.abyss.tools.core.common

/**
 * 设备伪装五级模式（FEAT-040，v0.10.2）—— 纯 JVM、可单测。
 *
 * 每级绑定能力集合，并如实给出「生效 / 仅生成档案 / 不可用」真值：
 *  - LOW    自身组件（图标 / 别名 / 主题）：零授权（空集门槛），恒生效；
 *  - MEDIUM 无障碍：前台包名感知 + 手势原语；
 *  - HIGH   Shizuku：四件套系统态命令；
 *  - MAX    Shizuku + 无障碍：HIGH + MEDIUM 组合可执行；STANDARD / DEEP 档案
 *           仅导出（Hook 层 N-SBX-001 未落地 → note 如实标注「仅导出」）；
 *  - ULTRA  Shizuku + Dhizuku + 设备管理员：设备策略面板 N-DO-001 未落地，
 *           三项齐全也只到「仅档案」—— 「已生成档案 ≠ 已生效」。
 *
 * 生效模式以 [PrivilegeSnapshot] 实时计算为准，偏好只作 UI 缓存。
 */
enum class DisguiseMode(
    val label: String,
    val required: Set<Capability>,
    /** 诚实边界短句（UI-001：无长文本标点）。 */
    val boundary: String,
) {
    // LOW 的门槛为空集：自身组件能力（SELF_BRANDING）恒为真，不作为门槛 ——
    // 即使快照为空（旧调用 / 探测失败 fail-closed）low 也必须如实为「生效」。
    LOW("low", emptySet(), "图标 / 名称 / 主题，零授权"),
    MEDIUM("medium", setOf(Capability.ACCESSIBILITY), "Android 15+ 需允许受限设置"),
    HIGH("high", setOf(Capability.SHIZUKU_SHELL), "不改 ro.product，重启掉档"),
    MAX("max", setOf(Capability.SHIZUKU_SHELL, Capability.ACCESSIBILITY), "档案仅导出，待 Hook 层"),
    ULTRA(
        "ultra",
        setOf(Capability.SHIZUKU_SHELL, Capability.DHIZUKU_ACTIVE, Capability.DEVICE_ADMIN_ACTIVE),
        "设备策略面板待落地，仅档案",
    ),
}

/** 模式生效真值。 */
enum class ModeState {
    /** 该级宣称的动作当前真的可执行。 */
    EFFECTIVE,

    /** 能力齐全，但深度部分只有「档案导出」、无消费方。 */
    PROFILE_ONLY,

    /** 能力不足。 */
    UNAVAILABLE,
}

/** 单级模式的生效结果：状态 + 缺失能力 + 人话说明。 */
data class ModeEffect(
    val state: ModeState,
    val missing: Set<Capability> = emptySet(),
    val note: String = "",
)

object DisguiseModes {

    /** 纯函数：能力不足 → UNAVAILABLE；否则按各级诚实边界给真值。 */
    fun effect(mode: DisguiseMode, snapshot: PrivilegeSnapshot): ModeEffect {
        val missing = snapshot.missing(mode.required)
        if (missing.isNotEmpty()) {
            return ModeEffect(ModeState.UNAVAILABLE, missing, "缺 " + missing.sortedBy { it.priority }.joinToString("、") { it.label })
        }
        return when (mode) {
            DisguiseMode.LOW -> ModeEffect(ModeState.EFFECTIVE, note = mode.boundary)
            DisguiseMode.MEDIUM -> ModeEffect(ModeState.EFFECTIVE, note = mode.boundary)
            DisguiseMode.HIGH -> ModeEffect(ModeState.EFFECTIVE, note = mode.boundary)
            // HIGH + MEDIUM 组合本身可执行；档案层无消费方（FEAT-031 FR-003 / N-SBX-001）
            // 只在 note 里如实标注「仅导出」。
            DisguiseMode.MAX -> ModeEffect(ModeState.EFFECTIVE, note = mode.boundary)
            // 设备策略面板 N-DO-001 未落地：三项齐全也只到「仅档案」。
            DisguiseMode.ULTRA -> ModeEffect(ModeState.PROFILE_ONLY, note = mode.boundary)
        }
    }

    /** 按实时能力算出的最高 EFFECTIVE 模式（至少为 LOW）。 */
    fun highestEffective(snapshot: PrivilegeSnapshot): DisguiseMode =
        DisguiseMode.entries.lastOrNull { effect(it, snapshot).state == ModeState.EFFECTIVE }
            ?: DisguiseMode.LOW

    /** 偏好回退：坏值 fail-closed 回 HIGH（与 DisguiseSettings 既有纪律一致）。 */
    fun fromNameOrNull(name: String?): DisguiseMode? =
        DisguiseMode.entries.firstOrNull { it.name == name }
}
