package com.abyss.tools.core.shell

/**
 * Shell 执行网关（伪装四件套 / 内置工具共用的系统级指令通道）。
 *
 * 工程纪律（对齐 ApkInstallGateway 的隔离模式）：指令编排（哪个工具发
 * 哪条命令、怎么解析输出）全部落在纯 Kotlin 的 [ShizukuCommands]，
 * 可单测；本接口的实现只负责「以什么身份跑命令」—— 当前是 Shizuku
 * （adb shell 上下文），未来接 Root / 本地 Runtime.exec 时零业务改动。
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/** Shizuku 不可用（未运行 / 未授权）时的人话错误 —— UI 直接展示。 */
class ShizukuUnavailableException(message: String) : IllegalStateException(message)

fun interface ShellGateway {
    /** 在 adb shell 上下文执行单条命令（sh -c），返回 stdout/stderr/退出码。 */
    suspend fun exec(command: String): Result<ShellResult>
}

/** 把 [Result] 失败归一为人话摘要（伪装页面的错误条消费）。 */
fun Result<*>.failureSummary(): String =
    exceptionOrNull()?.message ?: "未知错误"
