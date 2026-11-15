package com.abyss.tools.feature.sandbox

import com.abyss.tools.core.shell.ShizukuCommands

/**
 * 隔离空间的系统级指令编排（纯 Kotlin，零 Android 依赖 —— 可单测）。
 *
 * 能力说明（v0.8.0 沙箱插件内置化第一阶）：
 *  - 引擎 = Android 原生多用户（pm create-user / am start-user / pm install --user），
 *    经 Shizuku 以 adb shell 上下文执行 —— 免 Root；
 *  - 「双开」= pm install --user N --existing PKG：把本机已装应用注册进目标用户，
 *    免重装 APK、免传输，该空间内即得第二份独立数据（/data/user/<id>/…）；
 *  - VirtualApp 级免安装运行与 Hook 沙箱属插件能力（路线图），本层不伪装具备。
 *
 * 命令纪律（对齐 core:shell 的 ShizukuCommands / appmgr 的 AppManagerCommands）：
 * UI 层只挑命令 + 解析输出，禁止散落字符串拼接；fail-closed：非法输入就地
 * 拒绝，解析不出就返回 null（绝不猜、绝不静默当作成功）。
 *
 * OEM 诚实边界：部分 ROM（厂商阉割）禁用多用户 —— createUser 会直接失败，
 * 本层不吞错误、不降级、不猜：失败原文（stderr）原样交 UI 展示。
 */
object SandboxCommands {

    /** pm list users 的解析产物：单个 Android 用户的快照。 */
    data class AndroidUser(
        val id: Int,
        val name: String,
        /** 行尾带 " running" 字面量 = 该用户已启动（pm 的诚实信号，别的不猜）。 */
        val running: Boolean,
        /** 与外部传入的当前用户 id 相等（真源 = am get-current-user）。 */
        val isCurrent: Boolean,
    )

    /**
     * 用户 id 收敛上界：全用户 id 远小于 1000（0..~50 量级），999 防荒谬值下发
     * （负数 / 天文数字只会来自坏输入，绝不原样拼进命令）。
     */
    const val MAX_USER_ID: Int = 999

    /**
     * current 未知哨兵：am get-current-user 未成功时 parseUsers 传入该值 ——
     * 所有行的 isCurrent = false（「当前」标记缺失），绝不猜 0（猜错会把
     * Owner 误标成「非当前」，双开目标选择随之错位）。
     */
    const val UNKNOWN_USER_ID: Int = -1

    /**
     * pm list users 输出行形态（经典）：
     * ```
     * Users:
     * 	UserInfo{0:Owner:13} running
     * 	UserInfo{11:深渊空间:0}
     * ```
     * 三段 = id : 名字 : flags int；部分版本 flags 带 c 前缀（c13）；
     * 行尾 " running" 字面量 = 已启动。
     *
     * isCurrent 为什么不从 flags 位推：UserInfo flags 的位定义随 Android 版本
     * 漂移（且厂商魔改），按位猜「current」会翻车 —— 用 am get-current-user
     * 单独取得的 id 比对（诚实优先，双真源互证）。
     */
    private val USER_INFO = Regex("UserInfo\\{(\\d+):([^:}]*):c?(\\d+)\\}( running)?")

    /** 全用户列表（读侧第一步；current 判定所需的 id 由 [getCurrentUser] 另取）。 */
    fun listUsers(): String = "pm list users"

    /**
     * 解析 pm list users 输出 → 用户快照（按 id 升序稳定排列）。
     * 无 UserInfo 行 / 坏行一律跳过（绝不产出半猜用户）；名字为空串保留
     * （真实形态 —— UI 显示「未知」，不替系统编名字）。
     */
    fun parseUsers(output: String, currentUserId: Int): List<AndroidUser> =
        USER_INFO.findAll(output)
            .mapNotNull { match ->
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                AndroidUser(
                    id = id,
                    name = match.groupValues[2],
                    running = match.groupValues[4].isNotBlank(),
                    isCurrent = id == currentUserId,
                )
            }
            .sortedBy { it.id }
            .toList()

    /** 当前前台用户 id（isCurrent 判定的唯一真源，与 list users 顺序两步取）。 */
    fun getCurrentUser(): String = "am get-current-user"

    /**
     * am get-current-user 输出 = 单行纯数字（如 "0"）。容忍首尾空白 / \r\n；
     * 多行 / 非数字 / 负数 → null（fail-closed：绝不猜默认 0）。
     */
    fun parseCurrentUserId(output: String): Int? =
        output.trim().toIntOrNull()?.takeIf { it >= 0 }

    /**
     * 创建全用户（隔离空间）。名称是**自由文本**（中文名 / 带空格名都合法）→
     * 走 sh 单引号安全引用（shellQuote：'…' 内的 ' → '\''，唯一可靠的 sh 引用
     * 形态），绝不裸拼；换行 / 回车就地拒绝（跨行参数是注入面）。
     * 诚实边界：部分 ROM 禁用多用户 —— 本命令直接失败，stderr 原样透传。
     */
    fun createUser(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "空间名称不能为空" }
        require(!trimmed.contains('\n') && !trimmed.contains('\r')) {
            "空间名称不能包含换行"
        }
        return "pm create-user " + ShizukuCommands.shellQuote(trimmed)
    }

    /** pm create-user 成功输出形态："Success: created user id 12"。 */
    private val CREATED_USER = Regex("Success: created user id (\\d+)")

    /**
     * 解析新空间 id：成功文本 → id；失败文本 / 空 / 形态异常 → null
     * （拿不到 id 绝不猜 —— UI 只在解析到 id 时才报「已创建 + id」）。
     */
    fun parseCreatedUserId(output: String): Int? =
        CREATED_USER.find(output)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 移除全用户（空间的 /data/user/<id> 数据一并删除，不可恢复）。
     * id 先收敛到 0..[MAX_USER_ID]（防荒谬值下发）；id 0（Owner）**永不移除**
     * —— 拆 Owner = 拆掉整台机的数据主体，UI 从不渲染该入口，这里命令层
     * 兜底 require（纵深防御：AOSP 的 pm 侧也会拒绝，但绝不依赖系统兜底）。
     */
    fun removeUser(id: Int): String {
        val userId = id.coerceIn(0, MAX_USER_ID)
        require(userId != 0) { "拒绝移除 Owner（id 0）—— 系统主体不可拆" }
        return "pm remove-user $userId"
    }

    /**
     * 拉起用户（不切前台）。刻意不带 -w：-w 会阻塞到该用户完成启动才返回
     * （慢设备上命令长时间挂住，还要赌 Shizuku 网关 30s 看门狗不先杀）；
     * 异步拉起 + 稍后刷新列表看真实 running 态，比阻塞等待更诚实。
     */
    fun startUser(id: Int): String =
        "am start-user " + id.coerceIn(0, MAX_USER_ID)

    /**
     * 停止用户。-w：等停止真正完成才返回 —— 返回后 pm list users 的
     * running 态即为真值（避免 UI 空刷新看到「看似还在跑」的旧态）。
     */
    fun stopUser(id: Int): String =
        "am stop-user -w " + id.coerceIn(0, MAX_USER_ID)

    /**
     * 切换前台用户（系统进入该用户的解锁 / 桌面；当前应用随前台切换退后台）。
     */
    fun switchUser(id: Int): String =
        "am switch-user " + id.coerceIn(0, MAX_USER_ID)

    /**
     * **双开核心**：把本机已装（系统级已安装的 APK）注册进目标用户 ——
     * 免重装 APK、免传输；该空间内即得第二份独立数据。
     * pkg 经 requireSafeToken 白名单（包名是结构化标识符 —— 注入面就地关闭，
     * 语义严于引用且命令形态与 adb 手敲一致，可人工对照验证）。
     * 诚实边界：个别 ROM 的形态是 `pm install-existing --user N PKG` —— 失败时
     * stderr 原文透传（不猜形态、不静默重试换命令），UI 如实展示。
     */
    fun installExisting(pkg: String, userId: Int): String =
        "pm install --user " + userId.coerceIn(0, MAX_USER_ID) +
            " --existing " + ShizukuCommands.requireSafeToken(pkg, "包名")

    /**
     * 从目标用户移除已注册应用（该空间内的副本与其数据一并清除；
     * 其它用户 / 主用户的安装不受影响）。
     */
    fun uninstallForUser(pkg: String, userId: Int): String =
        "pm uninstall --user " + userId.coerceIn(0, MAX_USER_ID) +
            " " + ShizukuCommands.requireSafeToken(pkg, "包名")
}
