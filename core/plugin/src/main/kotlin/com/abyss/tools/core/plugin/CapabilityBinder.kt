package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.SemVer
import com.abyss.tools.plugin.api.PluginContract

/**
 * 能力绑定器（ARC-102 §4 —— `IAbyssPluginHost.getCapability` 的四重校验，
 * 纯 JVM 可单测；Android 侧 Binder/PackageManager 由宿主 app 注入）。
 *
 * 四重校验（FR-001，缺一不可）：
 *  1. 调用方包名 ∈ 白名单（[CallerTrust.isConsumerPlugin]）；
 *  2. 调用方签名集 == 发布者指纹（[CallerTrust.publisherFingerprint]）；
 *  3. 索引中存在匹配 depends 声明（[DependencyResolver] 解析通过）；
 *  4. minHost 满足（resolver 的宿主门槛，含 v1 宿主 fail-closed）。
 *
 * 任一校验失败返回人话错误码（FR-002），不抛异常穿透 UI，不返回半可用
 * Binder —— 「无中介直连」在此即被拒（E_DIRECT_BIND_DENIED 语义由
 * 非白名单包名触发：插件间永不直连）。
 */
class CapabilityBinder(
    private val resolver: DependencyResolver,
    private val grantIssuer: SessionGrantIssuer,
    /** 调用方信任锚（Android 侧注入：包名白名单 + 发布者指纹比对）。 */
    private val trust: CallerTrust,
) {

    /** 调用方身份（Binder.getCallingUid → 包名 + 签名集摘要）。 */
    data class CallerIdentity(
        val packageName: String,
        val signerSha256: String?,
    )

    /**
     * 调用方信任锚（宿主 app 注入；纯 JVM 测试用 fake 实现）：
     * 白名单语义 = 「在索引中声明 depends 的消费方插件」。
     */
    interface CallerTrust {
        /** 调用方包名是否为已知的消费方插件（索引白名单）。 */
        fun isConsumerPlugin(packageName: String): Boolean

        /** 该包的发布者签名指纹（64 位 hex；未知包返回 null）。 */
        fun publisherFingerprint(packageName: String): String?

        /** 包名 → 插件 id 映射（宿主安装账本 / 索引约定）。 */
        fun pluginIdOf(packageName: String): String?
    }

    sealed interface Binding {
        /** 授权通过：下发受限代理所需的令牌（能力绑定完成）。 */
        data class Authorized(
            val callerPackage: String,
            val capability: String,
            val grant: SessionGrant,
        ) : Binding

        data class Denied(val code: String, val humanReason: String) : Binding
    }

    /**
     * `getCapability(capability)`（ARC-102 §4 时序 ②~④）：
     * 校验调用方 → 解析 depends → 签发 SessionGrant。
     *
     * @param capability 消费方请求的能力 id（必须为 linux-runtime-v1）
     * @param index 插件中心索引快照
     * @param installed 本地安装账本（id → 当前版本）
     * @param sessionId 本次会话 id（宿主侧分配；grant 绑定该会话）
     */
    fun getCapability(
        caller: CallerIdentity,
        capability: String,
        index: PluginIndex,
        installed: Map<String, SemVer>,
        sessionId: String,
    ): Binding {
        // ⓪ 能力必须是注册能力
        if (capability != PluginContract.CAPABILITY_LINUX_RUNTIME_V1) {
            return denied("E_CAPABILITY_MISMATCH", "请求的能力未注册：$capability")
        }
        // ⓪′ 会话标识预校验（NB-26）：issue 的入参 require 非空 —— 直接传入
        // 会让 IllegalArgumentException 穿透 Binder / UI 调用栈（违反 FR-002）。
        if (sessionId.isBlank()) {
            return denied("E_GRANT_INVALID", "会话标识为空 —— 拒绝签发令牌（请重试并携带 sessionId）")
        }
        // ① 包名白名单（插件间永不直连 —— 非白名单包即「无中介直连」）
        if (!trust.isConsumerPlugin(caller.packageName)) {
            return denied("E_DIRECT_BIND_DENIED", "请通过插件中心启动（能力绑定仅对注册消费方开放）")
        }
        // ② 签名集 == 发布者指纹
        val fingerprint = trust.publisherFingerprint(caller.packageName)
        val callerSigner = caller.signerSha256
        if (fingerprint == null || callerSigner == null || !fingerprint.equals(callerSigner, ignoreCase = true)) {
            return denied("E_SIGNATURE_MISMATCH", "插件来源不可信（签名集/发布者指纹不符）—— 拒绝能力绑定")
        }
        // ③④ 依赖解析（depends 声明匹配 + minHost + 版本区间 + 通道策略）
        val pluginId = trust.pluginIdOf(caller.packageName)
            ?: return denied("E_DIRECT_BIND_DENIED", "调用方不在插件账本（包名无法映射插件 id）—— 请通过插件中心启动")
        // ③′ 能力声明硬校验（NB-28）：白名单只证明「是消费方」，不等于
        // 「可拿任意能力」—— 消费方必须在索引里 depends 声明所请求的能力，
        // 否则拒发（HOST_API v3 引入第二能力后，这条是防越权的唯一契约锚）。
        val consumerEntry = index.plugins.firstOrNull { it.id == pluginId }
        if (consumerEntry == null || consumerEntry.depends.none { it.capability == capability }) {
            return denied("E_DIRECT_BIND_DENIED", "消费方未声明依赖该能力（$capability）—— 拒绝签发")
        }
        when (val resolution = resolver.resolve(pluginId, index, installed)) {
            is DependencyResolver.Resolution.Rejection -> {
                return denied(resolution.code, resolution.humanReason)
            }
            is DependencyResolver.Resolution.InstallPlan -> {
                // 目标插件（调用方自身）已装运行中；仅看依赖方是否满足
                val unsatisfied = resolution.missingDeps.filter { it.id != pluginId }
                if (unsatisfied.isNotEmpty()) {
                    return denied(
                        "E_DEP_MISSING",
                        "请先安装依赖插件（${unsatisfied.joinToString("、") { it.name }}），再重新启动",
                    )
                }
            }
        }
        // ④ 签发令牌（五元组 + TTL ≤300s + 一次性 nonce）
        val grant = grantIssuer.issue(
            callerPackage = caller.packageName,
            callerSignerSha256 = callerSigner,
            capability = capability,
            sessionId = sessionId,
        )
        return Binding.Authorized(caller.packageName, capability, grant)
    }

    private fun denied(code: String, human: String): Binding = Binding.Denied(code, human)
}
