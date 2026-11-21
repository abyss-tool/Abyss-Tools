package com.abyss.tools.core.plugin

import com.abyss.tools.core.update.FetchHostPolicy
import com.abyss.tools.core.update.HttpTransport
import com.abyss.tools.core.update.SignedHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 插件中心仓库客户端：拉取 plugins.json 索引与各插件补丁通道。
 *
 * 拉到 ≠ 可用：每条索引 / 补丁条目都经 [ChannelPolicy] 运行时校验
 * （fail-closed）—— 不合规条目剔除进 `rejected` 列表（UI 可展示拒收
 * 原因），绝不进入可安装 / 可更新集合。
 *
 * v0.10（N-SEC-001）：[signedTransport] 非空时走 `.minisig` detached 签名
 * 校验，清单被篡改则直接 fail-closed，抛出 SignatureVerificationException
 * 折叠为 Result.failure。
 *
 * 全部返回 kotlin.Result —— UI 层折叠为 UiState，错误展示统一
 * 「网络 / 解析 / 签名」三类话术，不带堆栈。
 */
class PluginRepository(
    private val transport: HttpTransport,
    private val indexUrl: String,
    private val signedTransport: SignedHttpTransport? = null,
) {

    constructor(
        signed: SignedHttpTransport,
        indexUrl: String,
    ) : this(
        transport = signed.delegateTransport(),
        indexUrl = indexUrl,
        signedTransport = signed,
    )

    suspend fun fetchIndex(): Result<PluginIndex> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = if (signedTransport != null) {
                signedTransport.getSigned(indexUrl)
            } else {
                FetchHostPolicy.check(indexUrl)
                transport.get(indexUrl)
            }
            val parsed = PluginParsers.parseIndex(raw)
            val valid = mutableListOf<PluginIndexEntry>()
            val rejected = mutableListOf<RejectedPluginEntry>()
            for (entry in parsed.plugins) {
                val reason = ChannelPolicy.validateEntry(entry)
                if (reason == null) {
                    valid += entry
                } else {
                    rejected += RejectedPluginEntry(id = entry.id, reason = reason)
                }
            }
            parsed.copy(plugins = valid, rejected = rejected)
        }
    }
}

/** 插件补丁通道客户端（每插件一通道，URL 来自索引或契约推导）。 */
class PluginChannelClient(
    private val transport: HttpTransport,
    private val signedTransport: SignedHttpTransport? = null,
) {

    constructor(signed: SignedHttpTransport) : this(
        transport = signed.delegateTransport(),
        signedTransport = signed,
    )

    suspend fun fetchPatchChannel(url: String): Result<PluginPatchChannel> =
        withContext(Dispatchers.IO) {
            runCatching {
                FetchHostPolicy.check(url)
                val raw = if (signedTransport != null) {
                    signedTransport.getSigned(url)
                } else {
                    transport.get(url)
                }
                val parsed = PluginParsers.parsePatchChannel(raw)
                val valid = parsed.patches.filter { patch ->
                    ChannelPolicy.validatePatch(patch) == null
                }
                val rejected = parsed.patches.mapNotNull { patch ->
                    ChannelPolicy.validatePatch(patch)?.let { reason ->
                        RejectedPluginEntry(id = "${patch.from}→${patch.to}", reason = reason)
                    }
                }
                parsed.copy(patches = valid, rejectedPatches = rejected)
            }
        }
}
