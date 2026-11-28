package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.SemVer
import com.abyss.tools.plugin.api.PluginContract

/**
 * 宿主 PluginBroker（ARC-104 §4 —— 主壳自消费通道，纯 JVM 可单测）。
 *
 * 三个事实先摆清楚（为什么需要这个类）：
 *  1. 「宿主管理 / 绑定底座插件」天然成立 —— [DependencyResolver] / [CapabilityBinder] /
 *     [SessionGrantIssuer] 都在主壳进程里，宿主 → 提供方是唯一合法绑定方向；
 *  2. 「其他插件消费底座」也成立 —— `depends → getCapability 四重校验 → SessionGrant`；
 *  3. 「主壳**自己的功能模块**消费底座」此前**不成立**：[CapabilityBinder] 的
 *     `isConsumerPlugin()` 白名单只认索引里声明 depends 的消费方插件，会把宿主自己以
 *     `E_DIRECT_BIND_DENIED` 拒掉。本类补的就是这一条通道。
 *
 * 纪律（ARC-104 §4.2）：主壳既是裁判又是选手，不能让裁判拿选手的报名表拦自己，
 * 但也不能给自己发免检通行证 —— 跳过的只有「消费方白名单」一项；签名自校验、
 * 能力 ∈ 底座 provides、minHost / minApp、ChannelPolicy、底座已安装、审计、一次性
 * nonce、DeathRecipient 撤销全部照做。grant TTL 收紧到 ≤60s，底座未装即
 * `E_RUNTIME_UNAVAILABLE` + 一键引导，绝不谎报可用。
 */
class PluginBroker(
    private val resolver: DependencyResolver,
    private val grantIssuer: SessionGrantIssuer,
    /** 宿主自身包名（内部调用方身份；Android 侧由 ServiceRegistry 注入 packageName）。 */
    private val hostPackage: String,
    /** 宿主自身发布签名指纹（64 位 hex，冒号 / 空行归一后比对；fail-closed）。 */
    hostSignerSha256: String,
    /** Linux 底座插件 id（SRS-106：abyss-plugin-linux）。 */
    private val basePluginId: String = LINUX_BASE_PLUGIN_ID,
    /** 审计出口（Android 侧接 DiagnosticsLog；单测收集到列表）。 */
    private val onAudit: (AuditEvent) -> Unit = {},
) {

    /** 宿主签名指纹（归一化：去冒号 / 空白、大写）。 */
    private val hostSigner: String = normalizeSha256(hostSignerSha256)
        ?: throw IllegalArgumentException("hostSignerSha256 应为 64 位 hex（宿主签名指纹常量配置错误）")

    /** 内部会话请求（主壳功能模块 → 底座）。 */
    data class Request(
        /** 审计分账标识，形如 `internal:terminal`（[REQUESTER_ID_PATTERN]）。 */
        val requesterId: String,
        val capability: String = PluginContract.CAPABILITY_LINUX_RUNTIME_V1,
        /** 调用方自报身份 —— 必须与宿主自身包名 / 签名一致（自校验，防被当作通用后门）。 */
        val callerPackage: String,
        val callerSignerSha256: String,
    )

    /** 审计事件（与插件会话分账：requesterId 单独记录）。 */
    data class AuditEvent(
        val requesterId: String,
        val sessionId: String,
        val capability: String,
        val outcome: String,
        val baseVersion: SemVer? = null,
    )

    /** 能力不足时的引导动作。 */
    enum class Remedy { INSTALL_BASE_PLUGIN, RETRY }

    sealed interface Binding {
        /** 授权通过：受限 ILinuxSession 代理所需的内部令牌。 */
        data class Authorized(
            val requesterId: String,
            val capability: String,
            val grant: SessionGrant,
            val basePluginId: String,
            val baseVersion: SemVer,
        ) : Binding

        data class Denied(
            val requesterId: String,
            val code: String,
            val humanReason: String,
            val remedy: Remedy? = null,
        ) : Binding
    }

    /**
     * `getInternalSession()`（ARC-104 §4.2 / AC-002）：
     * 校验请求方 → 解析底座（索引 / minHost / minApp / ChannelPolicy）→ 底座已安装 →
     * 签发 INTERNAL grant（TTL ≤60s）→ 审计。
     *
     * @param index 插件中心索引快照
     * @param installed 本地安装账本（id → 当前版本）
     * @param sessionId 本次会话 id（每次 openSession 新建；grant 绑定该会话）
     */
    fun getInternalSession(
        request: Request,
        index: PluginIndex,
        installed: Map<String, SemVer>,
        sessionId: String,
    ): Binding {
        fun denied(code: String, human: String, remedy: Remedy? = null): Binding {
            val d = Binding.Denied(request.requesterId, code, human, remedy)
            onAudit(AuditEvent(request.requesterId, sessionId, request.capability, "DENIED:$code"))
            return d
        }

        // ⓪ requesterId 必须是 internal: 前缀的审计标识（与插件会话分账的前提）
        if (!REQUESTER_ID_PATTERN.matches(request.requesterId)) {
            return denied(
                "E_INTERNAL_REQUESTER_INVALID",
                "内部请求方标识非法（须形如 internal:terminal），已拒绝并记审计",
            )
        }
        // ⓪′ 会话标识预校验（NB-26）：issueInternal 的入参 require 非空 ——
        // 直接传入会让 IllegalArgumentException 穿透 Binder / UI 调用栈
        // （违反 FR-002「不抛异常穿透 UI」），在此预拦为人话错误码。
        if (sessionId.isBlank()) {
            return denied("E_GRANT_INVALID", "会话标识为空 —— 拒绝签发内部令牌（请重试并携带 sessionId）")
        }
        // ① 能力必须是注册能力（当前唯一：linux-runtime-v1）
        if (request.capability != PluginContract.CAPABILITY_LINUX_RUNTIME_V1) {
            return denied("E_CAPABILITY_MISMATCH", "请求的能力未注册：${request.capability}")
        }
        // ② 宿主自校验：包名 + 签名指纹必须就是宿主自己（不给自己发免检通行证）
        val callerSigner = normalizeSha256(request.callerSignerSha256)
        if (request.callerPackage != hostPackage || callerSigner == null || callerSigner != hostSigner) {
            return denied("E_SIGNATURE_MISMATCH", "宿主自校验失败：调用方身份与宿主签名不符 —— 拒绝内部会话")
        }
        // ③ 底座必须在索引且可解析（minHost / minApp / 渠道策略复用 DependencyResolver）
        val base = index.plugins.firstOrNull { it.id == basePluginId }
            ?: return denied(
                "E_RUNTIME_UNAVAILABLE",
                "Linux 运行环境（$basePluginId）未上架或已归档 —— 无法建立会话",
                Remedy.INSTALL_BASE_PLUGIN,
            )
        when (val resolution = resolver.resolve(basePluginId, index, installed)) {
            is DependencyResolver.Resolution.Rejection -> {
                return denied(resolution.code, resolution.humanReason, Remedy.RETRY)
            }
            is DependencyResolver.Resolution.InstallPlan -> {
                // 底座自身的依赖（当前为空）若缺失 → 同样不可用
                if (resolution.missingDeps.any { it.id != basePluginId }) {
                    return denied("E_RUNTIME_UNAVAILABLE", "底座插件的依赖未安装 —— 请先在插件中心一并安装", Remedy.INSTALL_BASE_PLUGIN)
                }
            }
        }
        // ④ 能力收敛：请求能力 ∈ 底座 provides（能力可收紧不可放宽）
        val provide = base.provides.firstOrNull { it.capability == request.capability }
            ?: return denied("E_CAPABILITY_MISMATCH", "$basePluginId 未提供 ${request.capability} 能力（能力版本不兼容）")
        ChannelPolicy.validateEntry(base)?.let { reason ->
            return denied("E_CAPABILITY_MISMATCH", "底座插件渠道不合规（$reason）—— 已拒绝解析", Remedy.RETRY)
        }
        // ⑤ 底座必须已安装（可选安装的独立 APK；未装即 fail-closed 引导，绝不显示半可用）
        val baseVersion = installed[basePluginId]
            ?: return denied(
                "E_RUNTIME_UNAVAILABLE",
                "请先安装 Linux 运行环境（$basePluginId）再使用此功能",
                Remedy.INSTALL_BASE_PLUGIN,
            )
        // ⑤′ since 门槛（NB-27）：能力自 provide.since 版本起才存在 —— 已装版本
        // 低于 since 时索引条目「看似提供」但本地底座实际没有，签发即谎报可用；
        // 在此比对并引导升级（假可用 → E_DEP_VERSION，绝不带病签发）。
        // since 非空但解析失败 = 索引数据损坏 → fail-closed 拒签（不猜、不放行）。
        val since: SemVer? = provide.since.takeIf { it.isNotBlank() }?.let { raw ->
            SemVer.parse(raw)
                ?: return denied("E_CAPABILITY_MISMATCH", "底座能力声明 since 非法（$raw）—— 拒绝解析", Remedy.RETRY)
        }
        if (since != null && baseVersion < since) {
            return denied(
                "E_DEP_VERSION",
                "Linux 运行环境需升级到 ${provide.since} 才提供 ${request.capability}（当前已装 $baseVersion）",
                Remedy.INSTALL_BASE_PLUGIN,
            )
        }
        // ⑥ 签发内部令牌（五元组 + TTL ≤60s + 一次性 nonce + requesterId 审计）
        val grant = grantIssuer.issueInternal(
            callerPackage = hostPackage,
            callerSignerSha256 = hostSigner,
            capability = request.capability,
            sessionId = sessionId,
            requesterId = request.requesterId,
        )
        onAudit(AuditEvent(request.requesterId, sessionId, request.capability, "AUTHORIZED", baseVersion))
        return Binding.Authorized(request.requesterId, request.capability, grant, basePluginId, baseVersion)
    }

    companion object {
        /** Linux 底座插件 id（与 ToolCatalog 的 linux-runtime 条目 pluginId 同源）。 */
        const val LINUX_BASE_PLUGIN_ID: String = "abyss-plugin-linux"

        /**
         * 依赖底座的消费方插件 id（宿主侧静态知识，与 plugins.json hubVersion 0.4.0 的 depends 对齐）：
         * 底座未装时这些入口一律显示「需先安装 Linux 运行环境」（ARC-104 AC-005）。
         * 索引快照可达的消费方（插件中心）以索引 depends 为准，本表只服务无索引的占位详情页。
         */
        val LINUX_DEPENDENT_PLUGIN_IDS: Set<String> = setOf(
            "abyss-plugin-app-builder",
            "abyss-plugin-phone-server",
        )

        /** 审计标识形态：internal:<小写字母数字与短横线，≤32>。 */
        val REQUESTER_ID_PATTERN: Regex = Regex("^internal:[a-z0-9][a-z0-9-]{0,31}$")

        private val SHA256_PATTERN: Regex = Regex("^[0-9A-F]{64}$")

        /** 冒号 / 空白分隔的指纹归一为 64 位大写 hex；非法形态返回 null（fail-closed）。 */
        fun normalizeSha256(raw: String?): String? {
            val compact = raw?.replace(":", "")?.replace(" ", "")?.trim()?.uppercase() ?: return null
            return if (SHA256_PATTERN.matches(compact)) compact else null
        }
    }
}
