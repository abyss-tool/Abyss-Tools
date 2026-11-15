package com.abyss.tools.feature.terminal

import android.content.SharedPreferences

/**
 * 终端模块的纯逻辑层（对齐 AGENTS.md 纪律 5：指令编排零 UI 框架依赖，
 * JVM 可单测）。Compose 侧只消费这里的数据形态（[ConsoleLine] / 历史列表），
 * 不掺渲染决策之外的状态机 —— 换 UI 框架时本文件零改动。
 *
 * [PrefsHistoryStore] 是唯一碰 Android 的类（SharedPreferences 落地），
 * 隔离在 [HistoryStore] 接口后面：单测注入内存假件即可跑全量语义。
 */

/** 控制台行类型：输入回显 / stdout / stderr / 系统提示（exit 码、截断说明）。 */
enum class LineKind { INPUT, OUTPUT, ERROR, SYSTEM }

/** 控制台的一行。渲染层按 [kind] 派生颜色与字形（等宽 / 斜体），数据零渲染语义。 */
data class ConsoleLine(val text: String, val kind: LineKind)

/**
 * 有界控制台缓冲：最新 [maxLines] 行的稳定窗口。
 *
 * 为什么有界：dumpsys package / pm list 全量输出动辄上万行，无界存入内存
 * 会把 UI 线程拖死 —— 超限时丢最旧行，窗口恒定（滚动位置语义也稳定：
 * 永远滚到当前窗口末行，不会因丢行跳变到错误的历史行）。
 *
 * 线程语义：只在主线程调用（append 由 Compose 事件回调触发、lines 由重组
 * 读取），不做加锁 —— 若未来接多流异步上屏，再加同步或改 ConflatedChannel。
 */
class ConsoleBuffer(private val maxLines: Int = 400) {

    init {
        require(maxLines > 0) { "maxLines must be positive (was $maxLines)" }
    }

    private val backing = ArrayDeque<ConsoleLine>()

    /** 追加一行；超限即从最旧端裁掉（保窗口大小恒定）。 */
    fun append(line: ConsoleLine) {
        backing.addLast(line)
        while (backing.size > maxLines) backing.removeFirst()
    }

    /** 当前窗口快照（新 List，调用方持有不随后续 append 变化）。 */
    fun lines(): List<ConsoleLine> = backing.toList()

    /** 清空全部行（「清屏」动作），之后可继续 append。 */
    fun clear() {
        backing.clear()
    }
}

/**
 * 历史存储的极小抽象：[CommandHistory] 只依赖这两个函数 —— Android 侧
 * 用 SharedPreferences 落地，单测注入内存假件。别加第三个方法（越窄越稳）。
 */
interface HistoryStore {
    fun load(): List<String>
    fun save(items: List<String>)
}

/**
 * SharedPreferences 落地。存储格式 = 换行拼接的字符串（最新在前）：
 * StringSet 不保序，弃用；单条命令本身不含换行（输入框 singleLine），
 * 且 CommandHistory.record 入口再做一次单行化兜底 —— 双保险防格式撕裂。
 */
class PrefsHistoryStore(private val prefs: SharedPreferences) : HistoryStore {

    override fun load(): List<String> =
        (prefs.getString(KEY, null) ?: "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    override fun save(items: List<String>) {
        prefs.edit().putString(KEY, items.joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val KEY = "command_history"
        const val SEPARATOR = "\n"
    }
}

/**
 * 命令历史（最新在前，上限 [cap] 条）。语义：
 *  - 连续重复去重（回车按歪 / 同一条命令反复跑是常态，重复记只会把
 *    有用的历史挤出去）；非连续重复照记 —— 那是真实的回访轨迹；
 *  - 超上限裁最旧；
 *  - 空白命令不记（没执行过的东西不该留痕）。
 */
class CommandHistory(private val store: HistoryStore, private val cap: Int = 50) {

    init {
        require(cap > 0) { "cap must be positive (was $cap)" }
    }

    private val entries = ArrayDeque<String>(store.load())

    /** 记录一条命令（单行化兜底：换行替换为空格，防存储格式撕裂）。 */
    fun record(command: String) {
        val cmd = command.trim().replace('\n', ' ').trim()
        if (cmd.isEmpty()) return
        if (entries.firstOrNull() == cmd) return // 连续重复：不记
        entries.addFirst(cmd)
        while (entries.size > cap) entries.removeLast()
        store.save(entries.toList())
    }

    /** 最近 [count] 条（最新在前）；越界按全量/空处理，不炸。 */
    fun recent(count: Int): List<String> = all().take(count.coerceAtLeast(0))

    /** 全量历史快照（最新在前）。 */
    fun all(): List<String> = entries.toList()

    /** 清空历史（内存 + 落地存储一并清）。 */
    fun clear() {
        entries.clear()
        store.save(emptyList())
    }
}
