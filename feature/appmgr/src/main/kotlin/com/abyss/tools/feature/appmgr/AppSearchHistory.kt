package com.abyss.tools.feature.appmgr

import android.content.SharedPreferences

/**
 * 应用搜索关键词历史（最新在前，上限 [cap] 条，全局去重、只记非空词）。
 *
 * 为什么照 TerminalShared.PrefsHistoryStore 的形态而不是 StringSet：
 * SharedPreferences 的 StringSet 不保序，而「最近搜索」必须按时间序展示
 * —— 换行拼接字符串是保序的最小实现。单条关键词不含换行（搜索框
 * singleLine + 落盘前单行化兜底），加载端再过滤空行，双保险防格式撕裂。
 *
 * 上限 6 而建议区只展示 5 条：多存 1 条作轮换余量 —— 历史是辅助不是
 * 档案，够用就走（Google 搜索同款「少存一点」纪律）。
 */
internal class AppSearchHistory(
    private val prefs: SharedPreferences,
    private val cap: Int = DEFAULT_CAP,
) {

    init {
        require(cap > 0) { "cap must be positive (was $cap)" }
    }

    private val entries = ArrayDeque<String>(loadFromPrefs())

    /** 全量关键词快照（最新在前，新 List 调用方可安全持有）。 */
    fun all(): List<String> = entries.toList()

    /**
     * 记录一次搜索提交：已存在的关键词提升到最前（重搜是更强的回访
     * 信号），超上限裁最旧；空白词不记（没提交过的东西不该留痕）。
     */
    fun record(keyword: String) {
        val word = keyword.trim().replace('\n', ' ').trim()
        if (word.isEmpty()) return
        entries.remove(word)
        entries.addFirst(word)
        while (entries.size > cap) entries.removeLast()
        persist()
    }

    /** 清空全部关键词（内存 + 落地存储一并清）。 */
    fun clear() {
        entries.clear()
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY, entries.joinToString(SEPARATOR)).apply()
    }

    private fun loadFromPrefs(): List<String> =
        (prefs.getString(KEY, null) ?: "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    private companion object {
        const val KEY = "app_search_history"
        const val SEPARATOR = "\n"
        const val DEFAULT_CAP = 6
    }
}
