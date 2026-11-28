package com.abyss.tools.core.common

/**
 * 权限能力与档位模型（ARC-011，v0.10.2）—— 纯 JVM、零 Android 依赖。
 *
 * 设计裁决（为什么不是「五档枚举比大小」）：
 *  - 底层能力彼此正交：无障碍不蕴含 Shizuku，Dhizuku 与无障碍无关，
 *    「安装未知应用」是特殊授权不该让用户一开就「升档」；
 *  - 因此 **能力集合是唯一事实源**（[PrivilegeSnapshot.capabilities]），
 *    [PrivilegeTier] 只是从能力集合派生出来的**展示标签**；
 *  - 功能放行一律走超集判定 `required ⊆ capabilities`，档位标签不参与。
 *
 * 歧义定稿（ARC-011 FR-003）：
 *  - ultra 不隐含无障碍：ULTRA = SHIZUKU ∧ DHIZUKU ∧ DEVICE_ADMIN；
 *  - max 不要求 INSTALL_UNKNOWN（缺失时 UI 提示「待补 1 项」）；
 *  - 「设备管理员」= 本 App DeviceAdminReceiver 已激活；Dhizuku 提供端
 *    是否为 DeviceOwner 由 DHIZUKU_ACTIVE 的探测交叉验证表达。
 */

/** 能力不足时的引导目标（app 壳据此决定跳哪个系统页 / 发哪个请求）。 */
enum class CapabilityGuide {
    /** 无需引导（零授权能力）。 */
    NONE,
    /** Shizuku：未运行 → 打开 Shizuku；运行未授权 → 请求授权。 */
    SHIZUKU,
    /** 系统设置 · 无障碍。 */
    ACCESSIBILITY,
    /** 系统设置 · 添加设备管理员（Dhizuku 同走此入口，附 Dhizuku 授权请求）。 */
    DEVICE_ADMIN,
    /** 系统设置 · 安装未知应用。 */
    INSTALL_SOURCES,
    /** 系统设置 · 显示在其他应用上层。 */
    OVERLAY,
}

/**
 * 能力维度（彼此正交；不是等级，不做 ordinal 比较）。
 *
 * [priority] 是「去授权」按钮的目标排序：Shizuku 解锁绝大多数工具，
 * 排最前；特殊授权与 DeviceOwner 通道靠后。
 */
enum class Capability(
    val label: String,
    val guide: CapabilityGuide,
    val priority: Int,
) {
    /** 操作本 App 自身组件：图标 / 别名 / 主题（零授权，恒为 true）。 */
    SELF_BRANDING("自身组件", CapabilityGuide.NONE, 99),

    /** 本 App 无障碍服务已启用且已绑定。 */
    ACCESSIBILITY("无障碍", CapabilityGuide.ACCESSIBILITY, 20),

    /** Shizuku binder 存活且已授权本应用（uid 2000 / 0 细分见 SHIZUKU_ROOT）。 */
    SHIZUKU_SHELL("Shizuku", CapabilityGuide.SHIZUKU, 10),

    /** Shizuku.getUid() == 0：仅用于高危动作二次门禁，不单独升档。 */
    SHIZUKU_ROOT("Shizuku root", CapabilityGuide.SHIZUKU, 60),

    /** canRequestPackageInstalls()。 */
    INSTALL_UNKNOWN("安装未知应用", CapabilityGuide.INSTALL_SOURCES, 30),

    /** canDrawOverlays() 或 appops SYSTEM_ALERT_WINDOW allow。 */
    OVERLAY("悬浮窗", CapabilityGuide.OVERLAY, 40),

    /** 安装事件 / 应用列表观察（QUERY_ALL_PACKAGES 已在 Manifest）。 */
    APP_OBSERVE("应用观察", CapabilityGuide.NONE, 98),

    /** Dhizuku.init ∧ isPermissionGranted ∧ 提供端 isDeviceOwnerApp。 */
    DHIZUKU_ACTIVE("Dhizuku", CapabilityGuide.DEVICE_ADMIN, 50),

    /** 本 App DeviceAdminReceiver isAdminActive。 */
    DEVICE_ADMIN_ACTIVE("设备管理员", CapabilityGuide.DEVICE_ADMIN, 51),
}

/** 展示档位：由能力集合派生，只用于 UI，不用于放行。 */
enum class PrivilegeTier(
    val label: String,
    /** 一句话达成条件（对话框五档表用）。 */
    val condition: String,
) {
    BASIC("普通用户", "未授权 Shizuku / 无障碍 / Dhizuku"),
    ADVANCED("进阶用户", "开启无障碍"),
    SENIOR("高级用户", "Shizuku 已授权"),
    ABYSS_MAX("abyss-max", "Shizuku + 无障碍"),
    ABYSS_ULTRA("abyss-ultra", "Shizuku + Dhizuku + 设备管理员"),
}

/** Shizuku 侧的展示性事实：官方 API 不提供「活跃变体」，一律只作 hint。 */
data class ShizukuFacet(
    val version: Int? = null,
    /** Shizuku.getUid()：0 = root / 2000 = shell；null = 不可知。 */
    val serverUid: Int? = null,
    val suiAvailable: Boolean = false,
    /** 本机已装变体的展示名（已装 ≠ 活跃）。 */
    val installedVariants: List<String> = emptyList(),
    /** 仅展示用，措辞允许「疑似 / 未确认」。 */
    val variantHint: String? = null,
) {
    /** serverUid 的人话（null = 未知）。 */
    val serverIdentity: String?
        get() = when (serverUid) {
            null -> null
            0 -> "root"
            else -> "shell"
        }
}

/**
 * 某一时刻的能力快照（单一事实源）。
 *
 * [reasons]：每项能力为什么没拿到（人话，探测层写入）；
 * [environmentNotes]：设备环境限制（Android 15 受限设置 / 16 高级保护…）；
 * 二者只作展示，不参与判定。
 */
data class PrivilegeSnapshot(
    val capabilities: Set<Capability> = emptySet(),
    val shizuku: ShizukuFacet = ShizukuFacet(),
    val reasons: Map<Capability, String> = emptyMap(),
    val environmentNotes: List<String> = emptyList(),
    val probedAtEpochMs: Long = 0L,
) {
    val tier: PrivilegeTier get() = TierCalculator.compute(capabilities)

    fun has(capability: Capability): Boolean = capability in capabilities

    /** 超集判定：功能放行的唯一公式。 */
    fun allows(required: Set<Capability>): Boolean = capabilities.containsAll(required)

    fun missing(required: Set<Capability>): Set<Capability> = required - capabilities

    /** 按引导优先级排序的缺失能力（「去授权」取首个）。 */
    fun missingOrdered(required: Set<Capability>): List<Capability> =
        missing(required).sortedBy { it.priority }

    /** 到下一档（按阶梯顺序）还差什么；已是最高档返回 null。 */
    val toNextTier: Pair<PrivilegeTier, Set<Capability>>?
        get() = TierCalculator.nextTier(tier)?.let { it to (TierCalculator.requirements.getValue(it) - capabilities) }
}

/** 档位计算器（纯函数，可单测）。 */
object TierCalculator {

    /** 阶梯顺序（展示与「下一档」语义；判定按优先级从高到低）。 */
    val ladder: List<PrivilegeTier> = listOf(
        PrivilegeTier.BASIC,
        PrivilegeTier.ADVANCED,
        PrivilegeTier.SENIOR,
        PrivilegeTier.ABYSS_MAX,
        PrivilegeTier.ABYSS_ULTRA,
    )

    private val table: Map<PrivilegeTier, Set<Capability>> = mapOf(
        PrivilegeTier.BASIC to emptySet(),
        PrivilegeTier.ADVANCED to setOf(Capability.ACCESSIBILITY),
        PrivilegeTier.SENIOR to setOf(Capability.SHIZUKU_SHELL),
        PrivilegeTier.ABYSS_MAX to setOf(Capability.SHIZUKU_SHELL, Capability.ACCESSIBILITY),
        PrivilegeTier.ABYSS_ULTRA to setOf(
            Capability.SHIZUKU_SHELL,
            Capability.DHIZUKU_ACTIVE,
            Capability.DEVICE_ADMIN_ACTIVE,
        ),
    )

    /** 每档的达成条件（不可变视图，对话框五档表与单测共用）。 */
    val requirements: Map<PrivilegeTier, Set<Capability>> get() = table

    /**
     * 优先级 ULTRA > MAX > SENIOR > ADVANCED > BASIC，只用于展示选一个标签。
     * INSTALL_UNKNOWN / OVERLAY / APP_OBSERVE / SELF_BRANDING / SHIZUKU_ROOT 不升档。
     */
    fun compute(capabilities: Set<Capability>): PrivilegeTier = when {
        capabilities.containsAll(table.getValue(PrivilegeTier.ABYSS_ULTRA)) -> PrivilegeTier.ABYSS_ULTRA
        capabilities.containsAll(table.getValue(PrivilegeTier.ABYSS_MAX)) -> PrivilegeTier.ABYSS_MAX
        Capability.SHIZUKU_SHELL in capabilities -> PrivilegeTier.SENIOR
        Capability.ACCESSIBILITY in capabilities -> PrivilegeTier.ADVANCED
        else -> PrivilegeTier.BASIC
    }

    /**
     * 「打开某工具所需的最小档位」= 把该工具的 required 当作能力集合算出的档位。
     * 只用于锁定卡的朗读与说明，不用于放行。
     */
    fun minimumTier(required: Set<Capability>): PrivilegeTier = compute(required)

    /** 阶梯上的下一档；已是最高档返回 null。 */
    fun nextTier(current: PrivilegeTier): PrivilegeTier? {
        val idx = ladder.indexOf(current)
        return if (idx < 0 || idx >= ladder.lastIndex) null else ladder[idx + 1]
    }
}
