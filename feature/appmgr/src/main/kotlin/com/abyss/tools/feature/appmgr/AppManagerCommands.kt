package com.abyss.tools.feature.appmgr

/**
 * 应用管理器的系统级指令编排（纯 Kotlin，零 Android 依赖 —— 可单测）。
 *
 * 工程纪律（对齐 core:shell 的 ShizukuCommands）：UI 层只挑命令 + 解析
 * 输出，禁止散落字符串拼接；fail-closed：非法输入就地拒绝，不猜不补。
 *
 * 包名注入防护（为什么不像设备名那样用 ShizukuCommands.shellQuote 引用）：
 *  - shellQuote 服务于**自由文本**（设备名等任意字符），把值包进单引号；
 *  - 包名是**结构化标识符**（Android 强约束字符集），走白名单校验
 *    [PACKAGE_NAME]：仅允许字母 / 数字 / 下划线 / 点，且至少一个点 ——
 *    该字符集不含任何 shell 元字符（空白 ; & | ` $ ' " < > 等），防护
 *    语义严于引用，且命令形态与 adb 手敲完全一致（可人工对照验证）；
 *  - 校验失败的包名直接抛 [IllegalArgumentException]（人话消息），
 *    绝不带着注入面下发命令。
 */
object AppManagerCommands {

    /** 合法包名白名单：段 = 字母/数字/下划线，至少两个段（≥1 个点）。 */
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

    /** `pm list packages` 输出的行前缀（经典形态 "package:com.foo.bar"）。 */
    private const val PACKAGE_LINE_PREFIX = "package:"

    /** 冻结（免 Root 停用 user 0 的应用）：图标消失、后台清零，数据保留。 */
    fun freeze(pkg: String): String = "pm disable-user --user 0 " + requireValidPackage(pkg)

    /** 解冻（恢复被 disable-user 停用的应用）。 */
    fun unfreeze(pkg: String): String = "pm enable " + requireValidPackage(pkg)

    /** 强制停止：立即终止应用全部进程（不删数据）。 */
    fun forceStop(pkg: String): String = "am force-stop " + requireValidPackage(pkg)

    /** 隐藏（pm hide：从系统整体隐藏，比冻结更彻底，数据保留）。 */
    fun hide(pkg: String): String = "pm hide " + requireValidPackage(pkg)

    /** 取消隐藏（pm unhide）。 */
    fun unhide(pkg: String): String = "pm unhide " + requireValidPackage(pkg)

    /** 已禁用（冻结）包名列表。 */
    fun listDisabled(): String = "pm list packages -d"

    /** 已隐藏包名列表（-u = 含已卸载但保留数据的包；-h = 过滤出隐藏态）。 */
    fun listHidden(): String = "pm list packages -u -h"

    /**
     * 解析 `pm list packages -d` 输出 → 禁用包名集合。
     *
     * 按行取 "package:" 之后的部分；坏行（无前缀 / 空包名 / 含空白或
     * 元字符）一律跳过，绝不猜测。
     */
    fun parseDisabledPackages(output: String): Set<String> = parsePackageList(output)

    /** 解析 `pm list packages -u -h` 输出 → 隐藏包名集合（解析规则同上）。 */
    fun parseHiddenPackages(output: String): Set<String> = parsePackageList(output)

    /** 包名白名单校验（列表页 / 详情页在构造命令前统一走这里）。 */
    fun isValidPackageName(pkg: String): Boolean = pkg.matches(PACKAGE_NAME)

    /** fail-closed 门卫：非法包名就地抛人话异常，合法包名原样放行。 */
    private fun requireValidPackage(pkg: String): String {
        require(isValidPackageName(pkg)) {
            "非法包名，已拒绝执行：$pkg（包名只允许字母/数字/下划线与点）"
        }
        return pkg
    }

    /**
     * `pm list packages` 系列输出的统一解析：容忍首尾空白 / \r\n / 重复行；
     * 每个候选包名都过 [PACKAGE_NAME] 白名单 —— 输出里混入的任何杂质行
     * 都不会进入集合（下游拿去做徽章 / 再拼命令都是安全的）。
     */
    private fun parsePackageList(output: String): Set<String> = buildSet {
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith(PACKAGE_LINE_PREFIX)) return@forEach
            val pkg = line.removePrefix(PACKAGE_LINE_PREFIX).trim()
            if (isValidPackageName(pkg)) add(pkg)
        }
    }
}
