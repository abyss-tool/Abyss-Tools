package com.abyss.tools.feature.shizukukit

import java.io.File
import java.io.IOException

/**
 * 历史持久化：filesDir/shizuku-kit/history.json（由 UI 层在 Dispatchers.IO
 * 上调用，纯函数序列化在 [HistoryStore]）。
 *
 * 写入走「临时文件 → 原子改名」：失败自动保留旧档，不产生半截 JSON。
 * 读档遇坏 JSON 直接 failure（fail-closed）—— UI 报错并保留文件，
 * 用户可用「清空历史」自愈重建（历史仅是指令日志，可安全丢弃）。
 */
internal class CommandHistoryFile(private val file: File) {

    fun load(): Result<List<CommandHistoryItem>> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        HistoryStore.deserialize(file.readText()).getOrThrow()
    }

    /** 读档 → 头插 → 裁剪 → 落盘，返回新列表。坏档时 push 失败（不静默覆盖）。 */
    fun push(item: CommandHistoryItem): Result<List<CommandHistoryItem>> {
        val current = load().getOrElse { e ->
            return Result.failure(IllegalStateException("历史写入中止：${e.message}", e))
        }
        val updated = HistoryStore.push(current, item)
        save(updated).getOrElse { e ->
            return Result.failure(IllegalStateException("历史落盘失败：${e.message}", e))
        }
        return Result.success(updated)
    }

    fun clear(): Result<Unit> = runCatching {
        if (file.exists() && !file.delete()) {
            throw IOException("无法删除历史文件：${file.absolutePath}")
        }
    }

    private fun save(items: List<CommandHistoryItem>): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()
        if (file.exists() && !file.isFile) {
            throw IOException("历史路径不是普通文件：${file.absolutePath}")
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(HistoryStore.serialize(items))
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw IOException("无法替换历史文件：${file.absolutePath}")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("历史文件改名失败：${file.absolutePath}")
        }
    }
}
