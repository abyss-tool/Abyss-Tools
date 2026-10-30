package com.abyss.tools.core.common

/**
 * UI 通用状态封装：Loading / Error(可重试) / Data 三态。
 *
 * 框架期约定：所有远端数据（更新清单、插件索引、插件补丁通道）一律
 * 经由该状态暴露给界面，禁止「加载中显示假数据」。
 */
sealed interface UiState<out T> {

    data object Loading : UiState<Nothing>

    data class Error(
        val message: String,
        val retryable: Boolean = true,
    ) : UiState<Nothing>

    data class Data<T>(val value: T) : UiState<T>
}

/** 把 kotlin.Result 折叠成 UiState（网络层的标准出口）。 */
fun <T> Result<T>.toUiState(): UiState<T> = fold(
    onSuccess = { UiState.Data(it) },
    onFailure = { UiState.Error(it.message ?: it.javaClass.simpleName) },
)
