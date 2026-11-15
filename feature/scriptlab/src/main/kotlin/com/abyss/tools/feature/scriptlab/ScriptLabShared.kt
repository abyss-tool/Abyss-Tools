package com.abyss.tools.feature.scriptlab

import com.abyss.tools.core.shell.ShizukuCommands
import java.io.File
import java.io.IOException

/**
 * 脚本实验室的纯逻辑层（对齐 AGENTS.md 纪律 5：指令编排与存储零 UI 框架
 * 依赖，JVM 可单测 —— ScriptLabSharedTest 覆盖名字门禁矩阵 / 存储往返 /
 * 版本探测解析 / 命令构造纪律）。Compose 侧只消费这里的数据形态与纯函数，
 * 禁止在 UI 层散落字符串拼接（fail-closed：非法输入就地拒绝，不猜不补）。
 *
 * 诚实边界（v0.8.0 第一阶）：
 *  - Shell 脚本逐行真实执行（经 Shizuku 的 adb shell 上下文）；
 *  - Python 只做运行时能力探测 —— 设备自带 CPython 才可用，没有就如实
 *    标注不可用；完整 Python 引擎（Chaquopy）属主壳后续内置化路线，
 *    本层不提供任何「模拟 Python」。
 */

/** 脚本存储目录名（宿主 filesDir 下 —— 单一真源，UI 与文档共用）。 */
const val SCRIPTS_DIR_NAME = "scripts"

/** 脚本名长度上限（40 字符：文件名可读性与列表渲染的折中）。 */
const val MAX_NAME_LENGTH = 40

/** 脚本体经命令通道的体积上限（16KB：printf 管道 / binder 命令串防线，UTF-8 字节计）。 */
const val MAX_BODY_BYTES = 16 * 1024

/** 脚本语言（分段选择的数据真源；ext 即存储文件后缀 —— 保存 / 列表共用）。 */
enum class ScriptLang(val title: String, val ext: String) {
    SHELL("Shell", "sh"),
    PYTHON("Python", "py"),
}

/**
 * 已保存脚本快照（脚本库行 / 编辑器载入的数据形态）。
 *
 * [modifiedAt] = 文件 mtime（落盘时以文件系统为单一真源 —— save 忽略调用方
 * 传入的该字段，列表 / 载入时如实回读）。
 */
data class SavedScript(
    val name: String,
    val lang: ScriptLang,
    val body: String,
    val modifiedAt: Long,
)

/** 名字字符集（字母 / 数字 / . / _ / -；中划线置字符类尾端免转义歧义）。 */
private val NAME_PATTERN = Regex("^[A-Za-z0-9._-]+$")

/**
 * 脚本名门禁（fail-closed）：仅字母 / 数字 / . / _ / -，不能以点开头，
 * 长度 1..[MAX_NAME_LENGTH]。非法输入 → null —— 绝不修剪、绝不猜测：
 * 路径穿越（../evil）、隐藏文件（.hidden）、空白（a b）、空名、超长（41+）
 * 在此就地拒绝。名字即文件名 stem，过门禁后天然无路径语义。
 */
fun safeFileName(raw: String): String? {
    if (raw.length !in 1..MAX_NAME_LENGTH) return null
    if (raw[0] == '.') return null
    if (!NAME_PATTERN.matches(raw)) return null
    return raw
}

/**
 * 脚本存储（filesDir/scripts/ 的纯 JVM 薄壳，落盘纪律对齐 BlueprintStore）：
 *  - 文件 = &lt;name&gt;.&lt;ext&gt;（name 经 [safeFileName] 门禁后才作文件名
 *    —— 天然防路径穿越）；
 *  - 写入走「tmp → 原子改名」，半截脚本永不落盘（失败自动清理 tmp、保留旧档）；
 *  - [list] 只认 *.sh / *.py 且 stem 过名字门禁的普通文件 —— 其余文件
 *    （用户手放的 readme.txt / 隐藏文件 / 子目录）一律忽略，绝不当作脚本解析。
 *
 * 构造函数只依赖 java.io.File（TemporaryFolder 可单测）；宿主 Context 的
 * 组装在 UI 侧：ScriptStore(File(context.filesDir, SCRIPTS_DIR_NAME))。
 */
class ScriptStore(private val dir: File) {

    /** 全部脚本（按名字 + 后缀稳定排序；未知 / 非法 / 不可读文件被忽略）。 */
    fun list(): List<SavedScript> {
        val files = dir.listFiles { file -> file.isFile } ?: return emptyList()
        return files
            .mapNotNull { file -> readSaved(file) }
            .sortedWith(compareBy({ it.name }, { it.lang.ext }))
    }

    /**
     * 保存（覆盖同名同语言文件；tmp → 原子改名）。
     * 名字非法 → failure（fail-closed，绝不带引号硬拼）。
     */
    fun save(script: SavedScript): Result<Unit> = runCatching {
        val name = safeFileName(script.name)
            ?: throw IllegalArgumentException(
                "非法脚本名「${script.name}」（仅字母/数字/./_/-，不能以点开头，长度 1..$MAX_NAME_LENGTH）",
            )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建脚本目录：${dir.absolutePath}")
        }
        val target = File(dir, "$name.${script.lang.ext}")
        if (target.exists() && !target.isFile) {
            throw IOException("目标路径不是普通文件：${target.absolutePath}")
        }
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeText(script.body, Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                throw IOException("无法替换旧脚本：${target.absolutePath}")
            }
            if (!tmp.renameTo(target)) {
                throw IOException("脚本落盘改名失败：${target.absolutePath}")
            }
        } finally {
            // 失败路径清理 tmp：不留半截文件（成功路径上 tmp 已被 rename 消费）
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 删除。名字非法 / 文件不存在 / 删除失败 → false（结果如实返回，不抛）。 */
    fun delete(name: String, lang: ScriptLang): Boolean {
        if (safeFileName(name) == null) return false
        val file = File(dir, "$name.${lang.ext}")
        return file.exists() && file.delete()
    }

    /**
     * 读取单条脚本。名字非法 / 文件不存在 → null；文件存在但读取失败 →
     * IOException 上抛（可读性故障要「响」，绝不静默当作不存在 —— fail-closed）。
     */
    fun load(name: String, lang: ScriptLang): SavedScript? {
        if (safeFileName(name) == null) return null
        val file = File(dir, "$name.${lang.ext}")
        if (!file.isFile) return null
        return SavedScript(
            name = name,
            lang = lang,
            body = file.readText(Charsets.UTF_8),
            modifiedAt = file.lastModified(),
        )
    }

    /**
     * 单文件 → SavedScript（honest name+ext 解析）：后缀不在语言集 / stem
     * 不过名字门禁 → 忽略；读取失败 → 忽略（单文件读取走 [load] 的
     * IOException 通道，用户点进去会看到真实错误 —— 列表层不猜不抛）。
     */
    private fun readSaved(file: File): SavedScript? {
        val fileName = file.name
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return null // 无后缀，或「.sh」这类 stem 空 / 首字符即点 —— 不认
        val lang = ScriptLang.entries.firstOrNull { it.ext == fileName.substring(dot + 1) }
            ?: return null
        val name = safeFileName(fileName.substring(0, dot)) ?: return null
        val body = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return SavedScript(name = name, lang = lang, body = body, modifiedAt = file.lastModified())
    }
}

/** 运行时探测（纯函数 —— UI 只消费结果，探测语义不掺渲染决策）。 */
object RunDetectors {

    /** 探测命令：先 python3 再 python，stderr 静音（探测失败即空输出 → null）。 */
    const val PROBE_COMMAND = "python3 --version 2>/dev/null || python --version 2>/dev/null"

    /** 版本号形态：\d+\.\d+[\w.]*（如 3.11.4 / 3.8）。 */
    private val VERSION = Regex("""\d+\.\d+[\w.]*""")

    /**
     * 从 `--version` 风格输出解析版本（逐行扫到首条命中）。
     * "Python 3.11.4" → "3.11.4"；"sh: python3: inaccessible" / 空输出 → null
     * —— 绝不猜默认版本。
     */
    fun parseVersion(output: String): String? {
        for (line in output.lineSequence()) {
            val match = VERSION.find(line) ?: continue
            return match.value
        }
        return null
    }
}

/**
 * 构造「执行脚本体」的单命令（ShellGateway 的 sh -c 上下文消费）：
 *  - SHELL：`printf %s '<body>' | sh`
 *  - PYTHON：`printf %s '<body>' | python3`
 *
 * 为什么 printf 管道而非临时文件：脚本若落在应用私有目录（/data/data/&lt;pkg&gt;），
 * shell 用户（uid 2000）在现代 Android 上不可读 —— 「写文件再 sh 执行」是
 * 注定失败的假路子；把脚本体以单引号安全形态（[ShizukuCommands.shellQuote]，
 * 与伪装四件套同源的唯一可靠引用形态）嵌进命令、经 stdin 管道送进解释器，
 * 是本通道唯一真实可行的路线（诚实边界）。printf 的格式串固定为 %s，
 * 脚本体作为参数传入 —— 体内外层引号 / % / 元字符都不会被重新解释，
 * 注入面收敛为「脚本自己是什么就执行什么」。
 *
 * fail-closed：超 [MAX_BODY_BYTES]（UTF-8 字节计）或含 NUL 字符 →
 * IllegalArgumentException —— NUL 会截断 binder 命令串，静默截掉半个
 * 脚本是最不诚实的失败形态。
 */
fun buildRunCommand(lang: ScriptLang, body: String): String {
    requireBody(body)
    return "printf %s " + ShizukuCommands.shellQuote(body) + " | " + when (lang) {
        ScriptLang.SHELL -> "sh"
        ScriptLang.PYTHON -> "python3"
    }
}

/** Python 回退命令（python3 执行失败时改试 python —— 老设备常只有 python 一个入口）。 */
fun buildPythonFallbackCommand(body: String): String {
    requireBody(body)
    return "printf %s " + ShizukuCommands.shellQuote(body) + " | python"
}

/** 体校验（体积 + NUL）：两个命令构造器的同一道闸。 */
private fun requireBody(body: String) {
    require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) {
        "脚本体超过 16KB 上限（当前 ${body.toByteArray(Charsets.UTF_8).size} 字节）—— 请精简或拆分"
    }
    require('\u0000' !in body) {
        "脚本体含 NUL 字符 —— 命令通道无法如实传输（会被静默截断），已拒绝"
    }
}
