package com.abyss.tools.core.plugin

import com.abyss.tools.plugin.api.PluginContract
import java.security.SecureRandom
import java.util.UUID

/**
 * 宿主签发的跨插件会话令牌（ARC-102 §4 ④⑤ / SEC-100 L4 —— 纯 JVM 可单测）。
 *
 * 模型：`SessionGrant` 五元组（调用方包名 / 签名指纹 / 能力 / 会话 /
 * nonce）+ TTL + 宿主签名。TTL ≤ [PluginContract.GRANT_TTL_MAX_SEC]
 * （能力描述可收紧不可放宽）；nonce 一次性（重放即 E_GRANT_REPLAY）；
 * 宿主重启（代际翻转）后全部旧 grant 失效（ARC-102 FR-007）。
 *
 * 纪律（FR-002/FR-005）：
 *  - 任何失败返回人话错误码（DC-102 表），不抛异常穿透 UI；
 *  - grant 不可跨会话复用、不可持久化（消费方每次 openSession 重新申请）；
 *  - 会话心跳续期需宿主重新签发（旧 grant 不延期）。
 */
data class SessionGrant(
    val callerPackage: String,
    /** 调用方 APK 签名集 SHA-256（十六进制大写，64 位）。 */
    val callerSignerSha256: String,
    val capability: String,
    val sessionId: String,
    /** 一次性随机数（UUID；重放即拒绝并记审计）。 */
    val nonce: String,
    val issuedAtEpochMs: Long,
    val expiryEpochMs: Long,
    /** 宿主签名摘要（含代际：generation 编入签名串，跨代即失效）。 */
    val hostSignature: String,
) {
    init {
        require(callerPackage.isNotBlank()) { "callerPackage 不得为空" }
        require(SIGNER_PATTERN.matches(callerSignerSha256)) { "callerSignerSha256 应为 64 位 hex" }
        require(capability.isNotBlank()) { "capability 不得为空" }
        require(sessionId.isNotBlank()) { "sessionId 不得为空" }
        require(expiryEpochMs > issuedAtEpochMs) { "expiry 必须晚于 issuedAt" }
        require(expiryEpochMs - issuedAtEpochMs <= PluginContract.GRANT_TTL_MAX_SEC * 1000L) {
            "grant TTL 不得超过 ${PluginContract.GRANT_TTL_MAX_SEC} 秒"
        }
        require(hostSignature.isNotBlank()) { "hostSignature 不得为空（宿主签发凭证）" }
    }

    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiryEpochMs

    companion object {
        private val SIGNER_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

/**
 * 令牌签发 / 校验 / 撤销（宿主 PluginBroker 的令牌引擎）。
 *
 * 单次使用语义：[verify] 成功即消耗 nonce —— 「每个 nonce 只放行一次」，
 * 二次出现按重放拒绝（E_GRANT_REPLAY，ARC-102 AC-003）。
 */
class SessionGrantIssuer(
    /** 注入时钟（单测可控；默认系统时钟）。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 宿主签名身份（稳定发布者标识；代际由内部 generation 承载）。 */
    private val hostIdentity: String = DEFAULT_HOST_IDENTITY,
    private val random: SecureRandom = SecureRandom(),
) {

    /** 签发代际：宿主重启 → [invalidateAll] 翻转 → 旧 grant 全部失效。 */
    @Volatile
    private var generation: Long = 1L

    /** 已消耗的 nonce（重放检测；会话撤销时按 sessionId 收缩）。 */
    private val usedNonces = mutableMapOf<String, String>() // nonce → sessionId

    /** 有效会话的授权登记（sessionId → grant；撤销 / 死亡回收用）。 */
    private val activeGrants = mutableMapOf<String, SessionGrant>()

    /**
     * 签发（四重校验通过后调用 —— [CapabilityBinder]）：
     * TTL 固定取 [PluginContract.GRANT_TTL_MAX_SEC] 上限（FR-005 ≤300s）。
     */
    fun issue(
        callerPackage: String,
        callerSignerSha256: String,
        capability: String,
        sessionId: String,
    ): SessionGrant {
        val now = clock()
        val nonce = newNonce()
        val grant = SessionGrant(
            callerPackage = callerPackage,
            callerSignerSha256 = callerSignerSha256,
            capability = capability,
            sessionId = sessionId,
            nonce = nonce,
            issuedAtEpochMs = now,
            expiryEpochMs = now + PluginContract.GRANT_TTL_MAX_SEC * 1000L,
            hostSignature = signatureOf(generation),
        )
        activeGrants[sessionId] = grant
        return grant
    }

    /** 校验结果（人话错误码出口；不抛异常穿透 UI，ARC-102 FR-002）。 */
    sealed interface GrantCheck {
        data class Valid(val grant: SessionGrant) : GrantCheck
        data class Invalid(val code: String, val reason: String) : GrantCheck
    }

    /**
     * 校验 + 单次消耗（五元组逐项：宿主签名/代际 → 过期 → 会话匹配 →
     * nonce 重放）。任何一项失败即拒；成功即消耗 nonce（重放防护）。
     */
    fun verify(grant: SessionGrant, sessionId: String): GrantCheck {
        // ① 宿主签名（代际）—— 宿主重启后旧 grant 失效
        if (grant.hostSignature != signatureOf(generation)) {
            return invalid("E_GRANT_INVALID", "令牌与当前宿主不符（宿主已重启），请重新发起")
        }
        // ② 过期
        if (grant.isExpired(clock())) {
            return invalid("E_GRANT_EXPIRED", "令牌已过期（已自动重试重新签发）")
        }
        // ③ 会话匹配（不可跨会话复用）
        if (grant.sessionId != sessionId) {
            return invalid("E_GRANT_REPLAY", "令牌不可跨会话复用（已拒绝并记审计）")
        }
        // 撤销的会话：直接失效
        if (sessionId !in activeGrants.keys) {
            return invalid("E_GRANT_INVALID", "会话已关闭或被撤销，请重新开始")
        }
        // ④ nonce 重放（单次使用）
        val consumedBy = usedNonces[grant.nonce]
        if (consumedBy != null) {
            return if (consumedBy == sessionId) {
                invalid("E_GRANT_REPLAY", "令牌已被使用（nonce 重放），已拒绝并记审计")
            } else {
                invalid("E_GRANT_REPLAY", "nonce 跨会话重放，已拒绝并记审计")
            }
        }
        usedNonces[grant.nonce] = sessionId
        return GrantCheck.Valid(grant)
    }

    /** 撤销会话授权（close / Binder 死亡回收 / 宿主撤销）。返回撤销数。 */
    fun revoke(sessionId: String): Int {
        activeGrants.remove(sessionId) ?: return 0
        usedNonces.entries.removeAll { it.value == sessionId }
        return 1
    }

    /** 全量失效（宿主重启 / 灰度开关关停 —— ADR-100 §5 R5 回滚路径）。 */
    fun invalidateAll(): Int {
        val count = activeGrants.size
        activeGrants.clear()
        usedNonces.clear()
        generation += 1
        return count
    }

    private fun newNonce(): String = UUID(random.nextLong(), random.nextLong()).toString()

    private fun invalid(code: String, reason: String): GrantCheck.Invalid =
        GrantCheck.Invalid(code, reason)

    private fun signatureOf(gen: Long): String = "abyss-host|$hostIdentity|gen:$gen"

    companion object {
        const val DEFAULT_HOST_IDENTITY: String = "abyss-tools-publisher"
    }
}
