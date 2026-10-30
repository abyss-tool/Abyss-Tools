package com.abyss.tools.core.plugin

import com.abyss.tools.core.update.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 插件中心仓库客户端：拉取 plugins.json 索引与各插件补丁通道。
 *
 * 全部返回 kotlin.Result —— UI 层折叠为 UiState，错误展示统一
 * 「网络 / 解析」两类话术，不带堆栈。
 */
class PluginRepository(
    private val transport: HttpTransport,
    private val indexUrl: String,
) {

    suspend fun fetchIndex(): Result<PluginIndex> = withContext(Dispatchers.IO) {
        runCatching { PluginParsers.parseIndex(transport.get(indexUrl)) }
    }
}

/** 插件补丁通道客户端（每插件一通道，URL 来自索引或契约推导）。 */
class PluginChannelClient(private val transport: HttpTransport) {

    suspend fun fetchPatchChannel(url: String): Result<PluginPatchChannel> =
        withContext(Dispatchers.IO) {
            runCatching { PluginParsers.parsePatchChannel(transport.get(url)) }
        }
}
