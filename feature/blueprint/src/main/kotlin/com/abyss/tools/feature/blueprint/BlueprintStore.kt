package com.abyss.tools.feature.blueprint

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 蓝图库快照（loadAll 的产物）：
 *  - [blueprints]：内置 + 用户合并后的最终目录（用户同 id 覆盖内置）；
 *  - [warnings]：无法解析的用户文件的人话警告（fail-closed：坏文件跳过、
 *    保留原文件、绝不静默当作不存在，也绝不猜测出一张蓝图）；
 *  - [userIds]：来自用户目录的 id 集（UI 的「用户 / 内置」徽章依据）。
 */
data class LibrarySnapshot(
    val blueprints: List<BlueprintManifest>,
    val warnings: List<String>,
    val userIds: Set<String>,
)

/**
 * 蓝图存储（Android 侧薄壳，filesDir/blueprints/）：
 *  - 用户蓝图：&lt;id&gt;.json（id 经 ^[a-z0-9-]+$ 校验后才作文件名 —— 天然防
 *    路径穿越）；写入走「tmp → 原子改名」，半截 JSON 永不落盘；
 *  - 执行历史：runs.json（最近 [MAX_RUNS] 条，最新在前）；
 *  - 内置蓝图：内存常量（[builtins]），用户同 id 覆盖后呈现。
 *
 * 构造函数只依赖 java.io.File（纯 JVM，TemporaryFolder 可单测）；
 * [create] 是宿主 Context 入口。
 */
class BlueprintStore(private val dir: File) {

    /** 内置 + 用户合并目录（用户同 id 覆盖内置；内置次序保持稳定）。 */
    fun loadAll(): LibrarySnapshot {
        val warnings = mutableListOf<String>()
        val userManifests = mutableListOf<BlueprintManifest>()
        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".json") && file.name != RUNS_FILE_NAME
        }.orEmpty()

        for (file in files.sortedBy { it.name }) {
            val loaded = runCatching { file.readText() }
                .mapCatching { raw -> BlueprintParser.parse(raw).getOrThrow() }
                .getOrElse { e ->
                    warnings += "用户蓝图 ${file.name} 加载失败已跳过：${e.message}"
                    null
                }
            if (loaded != null) userManifests += loaded
        }

        val byId = LinkedHashMap<String, BlueprintManifest>()
        for (builtin in builtins) byId[builtin.id] = builtin
        for (user in userManifests) byId[user.id] = user

        return LibrarySnapshot(
            blueprints = byId.values.toList(),
            warnings = warnings,
            userIds = userManifests.map { it.id }.toSet(),
        )
    }

    /** 保存用户蓝图（落盘前过同一道 parse 校验闸；id 即文件名）。 */
    fun saveUser(manifest: BlueprintManifest): Result<Unit> = runCatching {
        BlueprintParser.validate(manifest)
        require(manifest.id != RUNS_STEM) {
            "id「${manifest.id}」是保留字（与执行历史文件冲突），请换一个 id"
        }
        writeAtomically(userFile(manifest.id), blueprintJson.encodeToString(BlueprintManifest.serializer(), manifest))
    }

    /** 删除用户蓝图（内置不可删；删除后同 id 内置自然回归）。 */
    fun deleteUser(id: String): Result<Unit> = runCatching {
        require(ID_FILE_PATTERN.matches(id)) { "非法蓝图 id「$id」" }
        val file = userFile(id)
        if (file.exists() && !file.delete()) {
            throw IOException("无法删除蓝图文件：${file.absolutePath}")
        }
    }

    /** 读执行历史（坏 JSON → failure，绝不静默当作空历史）。 */
    fun loadRuns(): Result<List<BlueprintRun>> = runCatching {
        val file = runsFile()
        if (!file.exists()) return@runCatching emptyList()
        try {
            runsJson.decodeFromString(runsSerializer, file.readText())
        } catch (e: Exception) {
            throw IllegalStateException("执行历史数据损坏（无效 JSON）：${e.message}", e)
        }
    }

    /** 追加一条执行记录并裁剪到最近 [MAX_RUNS] 条（最新在前）。 */
    fun appendRun(run: BlueprintRun): Result<List<BlueprintRun>> = runCatching {
        val current = loadRuns().getOrElse { e ->
            throw IllegalStateException("执行历史写入中止：${e.message}", e)
        }
        val updated = (listOf(capOutput(run)) + current).take(MAX_RUNS)
        writeAtomically(runsFile(), runsJson.encodeToString(runsSerializer, updated))
        updated
    }

    // ── 文件布局 ──────────────────────────────────────────────────────────

    private fun userFile(id: String): File = File(dir, "$id.json")
    private fun runsFile(): File = File(dir, RUNS_FILE_NAME)

    /** 原子写：tmp → rename；任何失败自动清理 tmp、保留旧档。 */
    private fun writeAtomically(file: File, content: String) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建蓝图目录：${dir.absolutePath}")
        }
        if (file.exists() && !file.isFile) {
            throw IOException("目标路径不是普通文件：${file.absolutePath}")
        }
        val tmp = File(dir, file.name + ".tmp")
        try {
            tmp.writeText(content)
            if (file.exists() && !file.delete()) {
                throw IOException("无法替换文件：${file.absolutePath}")
            }
            if (!tmp.renameTo(file)) {
                throw IOException("文件改名失败：${file.absolutePath}")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {

        /** 执行历史上限（runs.json 最近 20 条）。 */
        const val MAX_RUNS = 20

        private const val RUNS_FILE_NAME = "runs.json"
        private const val RUNS_STEM = "runs"
        private const val MAX_STEP_OUTPUT_CHARS = 16_000
        private val ID_FILE_PATTERN = Regex("^[a-z0-9-]+$")

        private val blueprintJson = Json {
            ignoreUnknownKeys = true
            prettyPrint = true // 用户可直接手改的文件，可读性优先
        }
        private val runsJson = Json { ignoreUnknownKeys = true }
        private val runsSerializer = ListSerializer(BlueprintRun.serializer())

        /** 宿主入口：filesDir/blueprints/。 */
        fun create(context: Context): BlueprintStore =
            BlueprintStore(File(context.filesDir, "blueprints"))

        /**
         * 内置蓝图（5 个，命令与 adb 手敲形态一字不差，全部系统官方通道）。
         * 设计取舍：飞行 / 网络类内置均做单步幂等蓝图（「读改写」需要把
         * 上一步输出当变量，作为内置示例太脆；开/关各一张、意图明确最稳）。
         */
        val builtins: List<BlueprintManifest> = listOf(
            BlueprintManifest(
                id = "battery-reset",
                name = "电池状态复位",
                description = "撤销伪装四件套的电池伪装，恢复系统真实电量读数（dumpsys battery reset）。",
                steps = listOf(
                    BlueprintStep(type = StepType.SHELL, content = "dumpsys battery reset"),
                ),
            ),
            BlueprintManifest(
                id = "airplane-on",
                name = "一键开启飞行模式",
                description = "cmd connectivity airplane-mode enable —— 系统官方通道，比直接改 settings 更稳。",
                steps = listOf(
                    BlueprintStep(type = StepType.SHELL, content = "cmd connectivity airplane-mode enable"),
                ),
            ),
            BlueprintManifest(
                id = "airplane-off",
                name = "一键关闭飞行模式",
                description = "cmd connectivity airplane-mode disable —— 与「一键开启」互逆，可自由编排。",
                steps = listOf(
                    BlueprintStep(type = StepType.SHELL, content = "cmd connectivity airplane-mode disable"),
                ),
            ),
            BlueprintManifest(
                id = "network-off",
                name = "一键断网",
                description = "svc data disable && svc wifi disable —— 移动数据与 Wi-Fi 一起关闭（信号盲区模拟）。",
                steps = listOf(
                    BlueprintStep(type = StepType.SHELL, content = "svc data disable && svc wifi disable"),
                ),
            ),
            BlueprintManifest(
                id = "network-on",
                name = "一键恢复网络",
                description = "svc wifi enable && svc data enable —— 与「一键断网」互逆，双通道一起恢复。",
                steps = listOf(
                    BlueprintStep(type = StepType.SHELL, content = "svc wifi enable && svc data enable"),
                ),
            ),
        )

        /** runs.json 体积防线：单步 stdout/stderr 超长截断（引擎与实时展示不受影响）。 */
        internal fun capOutput(run: BlueprintRun): BlueprintRun = run.copy(
            results = run.results.map { result ->
                result.copy(
                    stdout = capText(result.stdout),
                    stderr = result.stderr?.let(::capText),
                )
            },
        )

        private fun capText(text: String): String =
            if (text.length <= MAX_STEP_OUTPUT_CHARS) {
                text
            } else {
                text.take(MAX_STEP_OUTPUT_CHARS) + "\n…（已截断，原输出过长）"
            }
    }
}
