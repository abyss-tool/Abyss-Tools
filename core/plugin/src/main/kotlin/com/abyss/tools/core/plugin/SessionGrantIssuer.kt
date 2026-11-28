package com.abyss.tools.core.plugin

import com.abyss.tools.plugin.api.PluginContract
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

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
 *
 * NB-25（深挖第三轮）：issue / verify / revoke / invalidateAll 在 Binder 池线程 +
 * 主壳功能协程并发下的三处硬伤一并收口：
 *  - nonce 消耗由「读—判—写」改为 putIfAbsent 原子消耗（并发重放只放行一次）；
 *  - 代际翻转改 AtomicLong（volatile 读-改-写丢更新）；
 *  - usedNonces / activeGrants 按过期时限机会性清扫（长驻宿主不再无界增长），
 *   两表标 internal 供同模块单测直接断言（清扫 / 有界性）。
 */
/**
 * 令牌作用域（ARC-104 §4.2）：
 *  - [PLUGIN]：消费方插件经 `getCapability` 四重校验签发，TTL ≤ [PluginContract.GRANT_TTL_MAX_SEC]；
 *  - [INTERNAL]：主壳内部模块经 `PluginBroker.getInternalSession` 签发 —— 跳过消费方白名单
 *    但保留签名自校验 / 能力收敛 / 审计，TTL 收紧到 ≤ [SessionGrantIssuer.INTERNAL_GRANT_TTL_SEC]。
 */
enum class GrantScope {
    PLUGIN,
    INTERNAL,
}

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
    /** v2（ARC-104）：作用域，默认插件会话 —— 既有构造零改动。 */
    val scope: GrantScope = GrantScope.PLUGIN,
    /** v2（ARC-104）：内部会话的请求方标识（如 internal:terminal），与插件会话分账审计。 */
    val requesterId: String? = null,
) {
    init {
        require(callerPackage.isNotBlank()) { "callerPackage 不得为空" }
        require(SIGNER_PATTERN.matches(callerSignerSha256)) { "callerSignerSha256 应为 64 位 hex" }
        require(capability.isNotBlank()) { "capability 不得为空" }
        require(sessionId.isNotBlank()) { "sessionId 不得为空" }
        require(expiryEpochMs > issuedAtEpochMs) { "expiry 必须晚于 issuedAt" }
        val ttlMs = expiryEpochMs - issuedAtEpochMs
        require(ttlMs <= PluginContract.GRANT_TTL_MAX_SEC * 1000L) {
            "grant TTL 不得超过 ${PluginContract.GRANT_TTL_MAX_SEC} 秒"
        }
        if (scope == GrantScope.INTERNAL) {
            // 内部通道 TTL 只许更紧（能力可收紧不可放宽，ARC-104 §4.2）
            require(ttlMs <= SessionGrantIssuer.INTERNAL_GRANT_TTL_SEC * 1000L) {
                "内部会话 grant TTL 不得超过 ${SessionGrantIssuer.INTERNAL_GRANT_TTL_SEC} 秒"
            }
            require(!requesterId.isNullOrBlank()) { "内部会话必须携带 requesterId（审计分账）" }
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

    /** 签发代际：宿主重启 → [invalidateAll] 翻转 → 旧 grant 全部失效。
     * NB-25：AtomicLong —— 并发 invalidateAll（灰度关停 + 宿主回收同拍）
     * 下 volatile 读-改-写会丢一次翻转。 */
    private val generation = AtomicLong(1L)

    /** 已消耗的 nonce 登记（重放检测；会话撤销时按 sessionId 收缩）。
     * NB-12：issue/verify/revoke/invalidateAll 将跑在 Binder 池线程 +
     * DeathRecipient 线程（AIDL 桥落地后的真实并发面）—— 线性表在并发
     * 修改下会丢登记（重放检测穿透）/ CME / 表损坏；nonce 消费必须与
     * 重放判定原子，改用 ConcurrentHashMap（null 值本就不进表，语义不变）。
     * NB-25：internal 供同模块单测断言（并发消耗 / TTL 清扫 / 有界性）。 */
    internal val usedNonces = java.util.concurrent.ConcurrentHashMap<String, NonceUse>() // nonce → 登记项

    /** 有效会话的授权登记（sessionId → grant；撤销 / 死亡回收用）。
     * NB-25：internal 供同模块单测断言（过期清扫 / 有界性）。 */
    internal val activeGrants = java.util.concurrent.ConcurrentHashMap<String, SessionGrant>()

    /** nonce 登记项：归属会话 + 该 grant 的过期时刻（清扫依据）。 */
    internal data class NonceUse(
        val sessionId: String,
        val expiryEpochMs: Long,
    )

    /**
     * 机会性清扫（NB-25）：nonce 过期即无用（verify ② 先拒过期 grant，
     * 到不了 nonce 检查）；activeGrants 超过一个 TTL 未续签的会话按协议
     * 已死（心跳续期 = 重新签发）。每次签发 / 校验顺带扫一遍，长驻宿主
     * 不再无界增长（内部会话 60s 续签节奏下，表大小 ~活跃会话数）。
     */
    private fun sweep(nowEpochMs: Long) {
        usedNonces.entries.removeIf { it.value.expiryEpochMs <= nowEpochMs }
        activeGrants.entries.removeIf { it.value.expiryEpochMs + PluginContract.GRANT_TTL_MAX_SEC * 1000L <= nowEpochMs }
    }

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
            hostSignature = signatureOf(generation.get()),
        )
        sweep(now)
        activeGrants[sessionId] = grant
        return grant
    }

    /**
     * 签发内部会话令牌（ARC-104 §4.2 宿主自消费通道）：五元组同插件会话，
     * 但 scope = INTERNAL、TTL 固定 60s、强制 requesterId（审计分账）。
     */
    fun issueInternal(
        callerPackage: String,
        callerSignerSha256: String,
        capability: String,
        sessionId: String,
        requesterId: String,
    ): SessionGrant {
        val now = clock()
        val grant = SessionGrant(
            callerPackage = callerPackage,
            callerSignerSha256 = callerSignerSha256,
            capability = capability,
            sessionId = sessionId,
            nonce = newNonce(),
            issuedAtEpochMs = now,
            expiryEpochMs = now + INTERNAL_GRANT_TTL_SEC * 1000L,
            hostSignature = signatureOf(generation.get()),
            scope = GrantScope.INTERNAL,
            requesterId = requesterId,
        )
        sweep(now)
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
     * 撤销 → nonce 重放）。任何一项失败即拒；成功即消耗 nonce（重放防护）。
     *
     * NB-25：nonce 消耗改为 putIfAbsent 原子操作 —— 旧「读-判-写」在两线程
     * 同拍 verify 同一 grant 时会双双放行（单次使用承诺被穿透）；撤销检查
     * 放在消耗之后，若此刻会话已被撤销则回收刚登记的 nonce（不留孤儿）
     * 并拒绝 —— 在途调用不再穿透已撤销会话。
     */
    fun verify(grant: SessionGrant, sessionId: String): GrantCheck {
        // ① 宿主签名（代际）—— 宿主重启后旧 grant 失效
        if (grant.hostSignature != signatureOf(generation.get())) {
            return invalid("E_GRANT_INVALID", "令牌与当前宿主不符（宿主已重启），请重新发起")
        }
        // ② 过期
        val now = clock()
        if (grant.isExpired(now)) {
            return invalid("E_GRANT_EXPIRED", "令牌已过期（已自动重试重新签发）")
        }
        // ③ 会话匹配（不可跨会话复用）
        if (grant.sessionId != sessionId) {
            return invalid("E_GRANT_REPLAY", "令牌不可跨会话复用（已拒绝并记审计）")
        }
        // ④ nonce 重放（单次使用）—— putIfAbsent 原子消耗：并发双发只有一个成功
        val consumedBy = usedNonces.putIfAbsent(grant.nonce, NonceUse(sessionId, grant.expiryEpochMs))
        if (consumedBy != null) {
            return if (consumedBy.sessionId == sessionId) {
                invalid("E_GRANT_REPLAY", "令牌已被使用（nonce 重放），已拒绝并记审计")
            } else {
                invalid("E_GRANT_REPLAY", "nonce 跨会话重放，已拒绝并记审计")
            }
        }
        // ⑤ 撤销的会话：失效并回收刚登记的 nonce（revoke 的收缩可能发生在
        // putIfAbsent 之前 —— 此处原子移除自己的登记，不留孤儿条目）
        if (sessionId !in activeGrants.keys) {
            usedNonces.remove(grant.nonce, NonceUse(sessionId, grant.expiryEpochMs))
            return invalid("E_GRANT_INVALID", "会话已关闭或被撤销，请重新开始")
        }
        sweep(now)
        return GrantCheck.Valid(grant)
    }

    /** 撤销会话授权（close / Binder 死亡回收 / 宿主撤销）。返回撤销数。
     * NB-25：nonce 收缩无条件执行（旧实现 remove ?: return 0 会跳过 ——
     * 二次 revoke 同一会话时孤儿 nonce 永久残留）。 */
    fun revoke(sessionId: String): Int {
        val removed = activeGrants.remove(sessionId)
        usedNonces.entries.removeIf { it.value.sessionId == sessionId }
        return if (removed != null) 1 else 0
    }

    /** 全量失效（宿主重启 / 灰度开关关停 —— ADR-100 §5 R5 回滚路径）。 */
    fun invalidateAll(): Int {
        val count = activeGrants.size
        activeGrants.clear()
        usedNonces.clear()
        generation.incrementAndGet()
        return count
    }

    /** 当前代际（测试可观测；生产只读不写）。 */
    internal fun currentGeneration(): Long = generation.get()

    private fun newNonce(): String = UUID(random.nextLong(), random.nextLong()).toString()

    private fun invalid(code: String, reason: String): GrantCheck.Invalid =
        GrantCheck.Invalid(code, reason)

    private fun signatureOf(gen: Long): String = "abyss-host|$hostIdentity|gen:$gen"

    companion object {
        const val DEFAULT_HOST_IDENTITY: String = "abyss-tools-publisher"

        /** 内部会话 grant TTL 上限（秒）：比插件会话 300s 更短（ARC-104 §4.2）。 */
        const val INTERNAL_GRANT_TTL_SEC: Int = 60
    }
}
