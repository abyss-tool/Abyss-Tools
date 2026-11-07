package com.abyss.tools.feature.qrcraft

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 历史记录单条：生成（kind = generate，format 为 null）或扫描
 * （kind = scan，format = QR_CODE / CODE_128 …）。
 */
@Serializable
data class QrHistoryItem(
    val kind: String,
    val content: String,
    val format: String? = null,
    val timestamp: Long,
) {
    companion object {
        const val KIND_GENERATE = "generate"
        const val KIND_SCAN = "scan"
    }
}

/** 纯序列化 / 裁剪逻辑（零 IO，JVM 可单测）。 */
object QrHistoryCodec {

    /** 最近 30 条裁剪上限。 */
    const val MAX_ITEMS = 30

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(QrHistoryItem.serializer())

    fun serialize(items: List<QrHistoryItem>): String = json.encodeToString(serializer, items)

    /** fail-closed：坏 JSON → failure（绝不猜测、绝不静默清空历史）。 */
    fun deserialize(raw: String): Result<List<QrHistoryItem>> = runCatching {
        json.decodeFromString(serializer, raw)
    }.recoverCatching { e ->
        throw IllegalStateException("历史数据损坏（无效 JSON）：${e.message}", e)
    }

    /** 头插新记录并裁剪到最近 [MAX_ITEMS] 条（时间倒序语义由调用方维持）。 */
    fun push(items: List<QrHistoryItem>, item: QrHistoryItem): List<QrHistoryItem> =
        (listOf(item) + items).take(MAX_ITEMS)
}

/**
 * 历史持久化：filesDir/qr-craft/history.json（由 UI 层在 Dispatchers.IO 上调用）。
 *
 * 写入走「临时文件 → 原子改名」：失败自动保留旧档，不产生半截 JSON。
 * 读档遇坏 JSON 直接 failure（fail-closed）—— UI 报错并保留文件，
 * 用户可用「清空」自愈重建。
 */
class QrHistoryFile(private val file: File) {

    fun load(): Result<List<QrHistoryItem>> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        QrHistoryCodec.deserialize(file.readText()).getOrThrow()
    }

    /** 读档 → 头插 → 裁剪 → 落盘，返回新列表。坏档时 push 失败（不静默覆盖）。 */
    fun push(item: QrHistoryItem): Result<List<QrHistoryItem>> {
        val current = load().getOrElse { e ->
            return Result.failure(IllegalStateException("历史写入中止：${e.message}", e))
        }
        val updated = QrHistoryCodec.push(current, item)
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

    private fun save(items: List<QrHistoryItem>): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()
        if (file.exists() && !file.isFile) {
            throw IOException("历史路径不是普通文件：${file.absolutePath}")
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(QrHistoryCodec.serialize(items))
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
