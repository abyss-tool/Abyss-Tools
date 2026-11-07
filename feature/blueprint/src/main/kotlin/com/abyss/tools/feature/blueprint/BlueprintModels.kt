package com.abyss.tools.feature.blueprint

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 任务蓝图的纯 JVM 数据契约（kotlinx.serialization，零 Android 依赖 —— 可单测）。
 *
 * 一张蓝图 = 元信息 + 步骤链 + 可选定时调度：
 *  - [BlueprintStep.type] 为 [StepType.SHELL] 时 content 是经 Shizuku（adb shell
 *    上下文）执行的命令原文；
 *  - [StepType.TOAST] 时 content 是提示文案（引擎恒成功，UI 层展示为系统 Toast）；
 *  - [StepType.DELAY] 时 content 是毫秒数（1..600000，反序列化后校验）。
 *
 * fail-closed 纪律（对齐仓库数据契约铁律）：非法蓝图在 [BlueprintParser.parse]
 * 就地拒绝并给出带字段名的人话错误，绝不猜测补全、绝不静默放行。
 */

/** 步骤类型（序列化按枚举名，白名单之外的值在反序列化即被拒）。 */
enum class StepType {
    SHELL,
    TOAST,
    DELAY,
}

/** 单个步骤。 */
@Serializable
data class BlueprintStep(
    val type: StepType,
    val content: String,
    /** SHELL 专属：成功探测子串（exit 0 之外还需 stdout 包含它才算成功）。 */
    val successProbe: String? = null,
    /** 本步失败后是否继续执行后续步骤（默认中断整链）。 */
    val continueOnFailure: Boolean = false,
    /** SHELL 专属：单步超时秒数（默认 30；引擎按不小于 1 秒执行）。 */
    val timeoutSeconds: Int = 30,
)

/** 定时调度（AlarmManager 精确闹钟，每 intervalMinutes 分钟一轮）。 */
@Serializable
data class BlueprintSchedule(
    val intervalMinutes: Int,
)

/** 蓝图清单（filesDir/blueprints/&lt;id&gt;.json 的落盘形态）。 */
@Serializable
data class BlueprintManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val steps: List<BlueprintStep>,
    val schedule: BlueprintSchedule? = null,
)

/**
 * 蓝图解析 + 校验（纯函数）：
 *  - kotlinx JSON 反序列化（ignoreUnknownKeys，向前兼容）；
 *  - 反序列化后校验：id 非空且 ^[a-z0-9-]+$（id 同时是存储文件名）、
 *    name 非空、steps 非空、SHELL 命令非空、DELAY 毫秒 1..600000、
 *    调度间隔 ≥ 15 分钟（省电纪律）；
 *  - 任何失败 → Result.failure（人话错误带字段名，绝不部分放行）。
 */
object BlueprintParser {

    /** 调度最小间隔（分钟）。 */
    const val MIN_INTERVAL_MINUTES = 15

    /** DELAY 毫秒合法区间。 */
    const val DELAY_MIN_MS = 1
    const val DELAY_MAX_MS = 600_000

    private val ID_PATTERN = Regex("^[a-z0-9-]+$")

    /** kotlinx「缺字段」报文的两种历史形态（1.5- 与 1.6+），统一抽取字段名。 */
    private val MISSING_FIELD = Regex("Missing field (?:for a required parameter )?'([A-Za-z0-9_]+)'")

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** JSON 文本 → 校验通过的蓝图；坏 JSON / 校验不过 → 人话 failure。 */
    fun parse(raw: String): Result<BlueprintManifest> = runCatching {
        val manifest = decode(raw)
        validate(manifest)
        manifest
    }

    /** 反序列化后校验（[BlueprintStore.saveUser] 落盘前复用同一道闸）。 */
    fun validate(manifest: BlueprintManifest) {
        require(manifest.id.isNotEmpty()) { "id 不能为空" }
        require(ID_PATTERN.matches(manifest.id)) {
            "id「${manifest.id}」非法 —— 只允许小写字母、数字和连字符（-）"
        }
        require(manifest.name.isNotBlank()) { "name 不能为空" }
        require(manifest.steps.isNotEmpty()) { "steps 不能为空 —— 至少要有一个步骤" }

        manifest.steps.forEachIndexed { index, step ->
            val where = "第 ${index + 1} 步（${step.type}）"
            when (step.type) {
                StepType.SHELL -> require(step.content.isNotBlank()) {
                    "$where 的 content（SHELL 命令）不能为空"
                }

                StepType.DELAY -> {
                    val ms = step.content.trim().toIntOrNull()
                    require(ms != null) {
                        "$where 的 content 必须是整数毫秒数，当前是「${step.content}」"
                    }
                    require(ms in DELAY_MIN_MS..DELAY_MAX_MS) {
                        "$where 的毫秒数必须在 $DELAY_MIN_MS..$DELAY_MAX_MS 之间，当前是 $ms"
                    }
                }

                StepType.TOAST -> Unit // 文案无格式约束，引擎恒成功
            }
        }

        manifest.schedule?.let { schedule ->
            require(schedule.intervalMinutes >= MIN_INTERVAL_MINUTES) {
                "schedule.intervalMinutes 必须 ≥ $MIN_INTERVAL_MINUTES 分钟（省电纪律），" +
                    "当前是 ${schedule.intervalMinutes}"
            }
        }
    }

    /**
     * 编辑器预填文本：pretty JSON，且剥离 schedule —— 调度由编辑页的开关 +
     * 间隔输入独立控制（单一事实来源，避免 JSON 与开关两处打架）。
     */
    fun toEditableJson(manifest: BlueprintManifest): String =
        prettyJson.encodeToString(BlueprintManifest.serializer(), manifest.copy(schedule = null))

    private fun decode(raw: String): BlueprintManifest = try {
        json.decodeFromString(BlueprintManifest.serializer(), raw)
    } catch (e: Exception) {
        // 坏 JSON / 缺字段 / 枚举值非法 / 类型不匹配 → 统一人话
        throw IllegalArgumentException(humanizeDecodeError(e))
    }

    private fun humanizeDecodeError(e: Exception): String {
        val raw = e.message ?: e.javaClass.simpleName
        val missingField = MISSING_FIELD.find(raw)?.groupValues?.get(1)
        return if (missingField != null) {
            "缺少必填字段「$missingField」（字段名拼写或 JSON 层级是否正确？）"
        } else {
            "JSON 解析失败：$raw"
        }
    }
}
