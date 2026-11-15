package com.abyss.tools.feature.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.abyss.tools.core.common.UiState
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.EmptyState
import com.abyss.tools.core.designsystem.component.ErrorState
import com.abyss.tools.core.designsystem.component.InfoRow
import com.abyss.tools.core.designsystem.component.LoadingState
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 媒体实验室：选源 → 探测 → 选操作（含裁剪区间）→ 任务队列 → 分享 / 打开。
 *
 * 纪律对照：
 *  - 源信息探测走 UiState 三态（Loading / Error / Data）——「加载中显示假数据」禁令
 *    对本地探测同样生效，探测不到的字段显示「未知」，不造数；
 *  - 颜色全部经 MaterialTheme / designsystem（ChipTone），不手写色彩；
 *  - API 26–28 保存公共媒体目录需要 WRITE_EXTERNAL_STORAGE（app manifest 已声明
 *    maxSdkVersion=28），运行时按需申请 —— 与二维码工坊同一模式；
 *  - 任务队列内存态：退出页面即清空（v0.8.0 诚实边界，不做持久化）。
 */
@Composable
fun MediaLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 页面状态（全部 remember / mutableStateOf，无 fake data）──────────
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var probeState by remember { mutableStateOf<UiState<MediaProbe>?>(null) }
    var probeAttempt by remember { mutableIntStateOf(0) } // 探测重发计数：换源与 ErrorState 重试都递增

    var op by remember { mutableStateOf(MediaOp.EXTRACT_AUDIO) }
    var trimStartMs by remember { mutableStateOf(0L) }
    var trimEndMs by remember { mutableStateOf(0L) }

    val jobs = remember { mutableStateListOf<MediaJob>() }
    val jobParams = remember { mutableMapOf<Long, JobParams>() } // 重试所需的发起上下文
    val jobOutputs = remember { mutableMapOf<Long, Uri>() } // 分享 / 打开用的输出 Uri
    var lastJobId by remember { mutableStateOf(0L) }

    var feedback by remember { mutableStateOf<Feedback?>(null) }
    var pendingRun by remember { mutableStateOf<(() -> Unit)?>(null) }

    val probe: MediaProbe? = when (val st = probeState) {
        is UiState.Data -> st.value
        else -> null
    }

    // ── 选源：系统照片选择器（仅视频，免存储权限）────────────────────────
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // 用户取消，保持安静
        selectedUri = uri
        probeState = UiState.Loading // 换源后旧探测结果立即失效
        sourceName = null
        // 重复选到同一文件也要重新探测：LaunchedEffect 键单调变化才重发
        probeAttempt++
    }

    // ── API 26–28 写权限（与二维码工坊同一申请模式）──────────────────────
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingRun
        pendingRun = null
        if (granted) {
            action?.invoke()
        } else {
            feedback = Feedback("未获存储写入权限 —— Android 9 及以下保存到公共媒体目录需要它", isError = true)
        }
    }

    // ── 探测（LaunchedEffect 换源 / 重试都重发；IO 由 MediaMux 内部调度）──
    LaunchedEffect(selectedUri, probeAttempt) {
        val uri = selectedUri ?: return@LaunchedEffect
        probeState = UiState.Loading
        sourceName = withContext(Dispatchers.IO) {
            queryDisplayName(context, uri) ?: "未知文件名"
        }
        MediaMux.probe(context, uri)
            .onSuccess { p ->
                probeState = UiState.Data(p)
                trimStartMs = 0L
                trimEndMs = p.durationMs.coerceAtLeast(0L)
            }
            .onFailure { e ->
                probeState = UiState.Error("探测失败：${e.message ?: e.javaClass.simpleName}")
            }
    }

    // ── 本地逻辑（任务队列的内存态读写）──────────────────────────────────

    fun feedbackMsg(message: String, isError: Boolean) {
        feedback = Feedback(message, isError)
    }

    fun updateJob(id: Long, transform: (MediaJob) -> MediaJob) {
        val index = jobs.indexOfFirst { it.id == id }
        if (index >= 0) jobs[index] = transform(jobs[index])
    }

    fun executeJob(id: Long, jobOp: MediaOp, uri: Uri, startMs: Long, endMs: Long) {
        scope.launch {
            updateJob(id) { it.copy(status = MediaJob.JobStatus.RUNNING, progress = 0f) }
            val onProgress: (Float) -> Unit = { fraction ->
                updateJob(id) { it.copy(progress = fraction.coerceIn(0f, 1f)) }
            }
            val result = when (jobOp) {
                MediaOp.EXTRACT_AUDIO -> MediaMux.extractAudio(context, uri, onProgress = onProgress)
                MediaOp.STRIP_AUDIO -> MediaMux.stripAudio(context, uri, onProgress = onProgress)
                MediaOp.TRIM -> MediaMux.trim(
                    context = context,
                    uri = uri,
                    startMs = startMs,
                    endMs = endMs,
                    onProgress = onProgress,
                )
            }
            result
                .onSuccess { outUri ->
                    jobOutputs[id] = outUri
                    val outName = withContext(Dispatchers.IO) { MediaMux.displayName(context, outUri) }
                    updateJob(id) {
                        it.copy(status = MediaJob.JobStatus.DONE, progress = 1f, outputPath = outName)
                    }
                }
                .onFailure { e ->
                    // 双通道取消都收进 CANCELLED：用户点「取消」或离开页面（协程注销）
                    val cancelledByUser = e is MediaMux.CancelledException || e is CancellationException
                    updateJob(id) {
                        it.copy(
                            status = if (cancelledByUser) {
                                MediaJob.JobStatus.CANCELLED
                            } else {
                                MediaJob.JobStatus.FAILED
                            },
                            error = if (cancelledByUser) "已取消" else (e.message ?: e.javaClass.simpleName),
                        )
                    }
                }
        }
    }

    fun launchJob(uri: Uri, name: String, jobOp: MediaOp, startMs: Long, endMs: Long) {
        // 时间戳即 id；同毫秒连点时单调递增兜底，防 indexOfFirst 命中错行
        val id = System.currentTimeMillis().coerceAtLeast(lastJobId + 1L)
        lastJobId = id
        jobs.add(
            MediaJob(
                id = id,
                op = jobOp,
                sourceName = name,
                createdAt = id,
                status = MediaJob.JobStatus.QUEUED,
            ),
        )
        jobParams[id] = JobParams(uri, jobOp, startMs, endMs)
        val action = { executeJob(id, jobOp, uri, startMs, endMs) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRun = action
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    fun retry(job: MediaJob) {
        val params = jobParams[job.id]
        if (params == null) {
            feedbackMsg("原任务上下文已失效（页面重建），请重新发起", isError = true)
            return
        }
        launchJob(params.uri, job.sourceName, job.op, params.startMs, params.endMs)
    }

    fun shareOutput(job: MediaJob) {
        val outUri = jobOutputs[job.id]
        if (outUri == null) {
            feedbackMsg("输出文件引用已失效，无法分享", isError = true)
            return
        }
        MediaMux.launchShare(context, outUri, outputMime(job.op))
            .onSuccess { feedbackMsg("已调起系统分享", isError = false) }
            .onFailure { e ->
                feedbackMsg("分享失败：${e.message ?: e.javaClass.simpleName}", isError = true)
            }
    }

    fun openOutput(job: MediaJob) {
        val outUri = jobOutputs[job.id]
        if (outUri == null) {
            feedbackMsg("输出文件引用已失效，无法打开", isError = true)
            return
        }
        MediaMux.launchOpen(context, outUri, outputMime(job.op))
            .onSuccess { feedbackMsg("已调起播放", isError = false) }
            .onFailure { e ->
                feedbackMsg("打开失败：${e.message ?: e.javaClass.simpleName}", isError = true)
            }
    }

    /** 开始处理的诚实门槛（EFAB 无 enabled 参数，用门槛 + 半透明 + 人话原因替代）。 */
    fun runDisabledReason(): String? {
        val busy = jobs.any {
            it.status == MediaJob.JobStatus.RUNNING || it.status == MediaJob.JobStatus.QUEUED
        }
        return when {
            selectedUri == null -> "请先选择视频"
            probeState == null || probeState is UiState.Loading -> "等待源信息探测完成"
            probeState is UiState.Error -> "源信息探测失败 —— 请重试探测或重选视频"
            busy -> "上一任务处理中（单任务串行模型，多任务流水线属插件路线）"
            op == MediaOp.TRIM && (probe?.durationMs ?: 0L) <= 0L -> "源时长未知，无法裁剪"
            op == MediaOp.TRIM && trimEndMs <= trimStartMs -> "裁剪区间无效（终点须大于起点）"
            else -> null
        }
    }

    // ── 布局 ─────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        MediaLabHeader(onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { IntroCard() }

            item {
                PickCard(
                    sourceName = sourceName,
                    onPick = {
                        pickVideo.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                )
            }

            item {
                ProbeCard(
                    state = probeState,
                    onRetry = { probeAttempt++ },
                )
            }

            item {
                OpChooserCard(
                    op = op,
                    onOpChange = { op = it },
                    durationMs = probe?.durationMs ?: 0L,
                    trimStartMs = trimStartMs,
                    trimEndMs = trimEndMs,
                    onTrimStartChange = { trimStartMs = it },
                    onTrimEndChange = { trimEndMs = it },
                )
            }

            item {
                val reason = runDisabledReason()
                RunSection(
                    disabledReason = reason,
                    onRun = {
                        if (reason != null) {
                            feedbackMsg(reason, isError = true)
                            return@RunSection
                        }
                        val uri = selectedUri
                        val name = sourceName
                        if (uri == null || name == null) return@RunSection
                        launchJob(uri, name, op, trimStartMs, trimEndMs)
                    },
                )
            }

            item {
                SectionHeader(
                    title = "任务队列",
                    subtitle = "内存态 · ${jobs.size} 项（退出页面即清空，无持久化）",
                )
            }
            if (jobs.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有任务",
                        message = "选择视频、确认源信息后，处理任务与进度会出现在这里。",
                    )
                }
            } else {
                items(jobs, key = { it.id }) { job ->
                    JobRow(
                        job = job,
                        onRetry = { retry(job) },
                        onCancel = { MediaMux.cancel() },
                        onShare = { shareOutput(job) },
                        onOpen = { openOutput(job) },
                    )
                }
            }

            val currentFeedback = feedback
            if (currentFeedback != null) {
                item {
                    FeedbackBar(message = currentFeedback.message, isError = currentFeedback.isError)
                }
            }
        }
    }
}

// ── 页头（DetailHeader 模式的模块内复刻，navhub 的 internal 件不跨模块引）──

@Composable
private fun MediaLabHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
            )
        }
        Text(
            text = "媒体实验室",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = "内置 · 原生引擎", tone = ChipTone.OK)
    }
}

// ── 诚实能力声明卡 ───────────────────────────────────────────────────────

@Composable
private fun IntroCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "能力边界（诚实声明）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "基于 Android 原生 MediaExtractor / MediaMuxer 的无重编码处理：音频提取（AAC→M4A）/ 去除音轨 / 关键帧对齐裁剪。全程零转码零质量损失；FFmpeg 级转码 / 批量流水线属插件能力（路线图）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 选源卡 ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickCard(
    sourceName: String?,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "选源",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "系统照片选择器 · 仅视频 · 免存储权限（本地读取，不联网）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Button(onClick = onPick) { Text("选择视频") }
        if (sourceName != null) {
            Spacer(Modifier.size(4.dp))
            ListItem(
                headlineContent = {
                    Text(
                        text = sourceName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    BadgedBox(
                        badge = { Badge { Text("已选择") } },
                    ) {
                        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
                    }
                },
            )
        }
    }
}

// ── 源信息卡（UiState 三态）─────────────────────────────────────────────

@Composable
private fun ProbeCard(
    state: UiState<MediaProbe>?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "源信息",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        when (val st = state) {
            null -> EmptyState(
                title = "尚未选择视频",
                message = "选择后自动探测时长 / 分辨率 / 编码 / 码率",
            )

            UiState.Loading -> LoadingState(message = "探测源信息中…")

            is UiState.Error -> ErrorState(message = st.message, onRetry = onRetry)

            is UiState.Data -> {
                val p = st.value
                InfoRow(label = "时长", value = MediaFormat.formatDuration(p.durationMs))
                InfoRow(label = "分辨率", value = resolutionText(p))
                InfoRow(
                    label = "视频编码",
                    value = MediaFormat.codecLabel(p.videoMime),
                    trailing = {
                        p.videoMime?.let { StatusChip(text = it, tone = ChipTone.INFO) }
                    },
                )
                InfoRow(
                    label = "音频编码",
                    value = if (p.audioMime == null) "无音轨" else MediaFormat.codecLabel(p.audioMime),
                    trailing = {
                        p.audioMime?.let { StatusChip(text = it, tone = ChipTone.INFO) }
                    },
                )
                InfoRow(
                    label = "码率",
                    value = p.bitrate?.let { MediaFormat.formatBitrate(it) } ?: "未知",
                )
            }
        }
    }
}

// ── 操作选择卡（SegmentedButton + 裁剪双滑杆）──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpChooserCard(
    op: MediaOp,
    onOpChange: (MediaOp) -> Unit,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    onTrimStartChange: (Long) -> Unit,
    onTrimEndChange: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Text(
            text = "操作",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(10.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            MediaOp.entries.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = op == candidate,
                    onClick = { onOpChange(candidate) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = MediaOp.entries.size),
                ) {
                    Text(candidate.title)
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = honestOpNote(op),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (op == MediaOp.TRIM) {
            if (durationMs > 0L) {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "起点 ${MediaFormat.formatDuration(trimStartMs)} · 终点 ${MediaFormat.formatDuration(trimEndMs)}（区间 ${MediaFormat.formatDuration((trimEndMs - trimStartMs).coerceAtLeast(0L))}）",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.size(2.dp))
                Slider(
                    value = trimStartMs.toFloat(),
                    onValueChange = { raw ->
                        // 双滑杆互相钳制：起点不越过终点（start <= end 恒成立）
                        onTrimStartChange(raw.toLong().coerceIn(0L, durationMs).coerceAtMost(trimEndMs))
                    },
                    valueRange = 0f..durationMs.toFloat(),
                )
                Slider(
                    value = trimEndMs.toFloat(),
                    onValueChange = { raw ->
                        onTrimEndChange(raw.toLong().coerceIn(0L, durationMs).coerceAtLeast(trimStartMs))
                    },
                    valueRange = 0f..durationMs.toFloat(),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "关键帧对齐：实际起点会回退到前一关键帧（无重编码裁剪的固有代价，导出片段可能比所选区间略长）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "源时长未知 —— 裁剪需要有效时长，请重选视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ── 开始处理（ExtendedFloatingActionButton）────────────────────────────

@Composable
private fun RunSection(
    disabledReason: String?,
    onRun: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ExtendedFloatingActionButton(
            onClick = onRun,
            icon = { Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null) },
            text = { Text("开始处理") },
            // EFAB 无 enabled 参数：门槛 + 半透明 + 下方人话原因共同表达「不可用」
            modifier = Modifier.alpha(if (disabledReason == null) 1f else 0.45f),
        )
        if (disabledReason != null) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = disabledReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 任务行 ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobRow(
    job: MediaJob,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
) {
    val supportingColor = if (job.status == MediaJob.JobStatus.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        headlineContent = {
            Text(
                text = "${job.op.title} · ${job.sourceName}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = statusText(job),
                style = MaterialTheme.typography.bodySmall,
                color = supportingColor,
            )
        },
        trailingContent = {
            when (job.status) {
                MediaJob.JobStatus.QUEUED -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )

                MediaJob.JobStatus.RUNNING -> Row(
                    modifier = Modifier.width(140.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { job.progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    TextButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(text = "取消", style = MaterialTheme.typography.labelMedium)
                    }
                }

                MediaJob.JobStatus.DONE -> Row {
                    IconButton(onClick = onShare) {
                        Icon(imageVector = Icons.Rounded.Share, contentDescription = "分享")
                    }
                    IconButton(onClick = onOpen) {
                        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = "打开")
                    }
                }

                MediaJob.JobStatus.FAILED -> AssistChip(
                    onClick = onRetry,
                    label = { Text("重试") },
                )

                MediaJob.JobStatus.CANCELLED -> Text(
                    text = "—",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

// ── 反馈条（分享 / 打开 / 权限的人话结果）───────────────────────────────

@Composable
private fun FeedbackBar(message: String, isError: Boolean) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isError) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

// ── 纯小工具 ─────────────────────────────────────────────────────────────

/** 任务的发起上下文（重试用；与任务列表同生命周期）。 */
private data class JobParams(
    val uri: Uri,
    val op: MediaOp,
    val startMs: Long,
    val endMs: Long,
)

private data class Feedback(val message: String, val isError: Boolean)

/** Photo Picker Uri 的显示名（OpenableColumns 投影查询，查不到回退 lastPathSegment）。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
        ?: uri.lastPathSegment
}.getOrNull()

/** "W × H @rotation°"；探测不到的维度原样缺席，不造数。 */
private fun resolutionText(probe: MediaProbe): String {
    val w = probe.width ?: return "未知"
    val h = probe.height ?: return "未知"
    val rotation = probe.rotation ?: return "$w × $h"
    return "$w × $h @$rotation°"
}

private fun statusText(job: MediaJob): String = when (job.status) {
    MediaJob.JobStatus.QUEUED -> "排队中"
    MediaJob.JobStatus.RUNNING -> "处理中 ${(job.progress * 100).toInt()}%"
    MediaJob.JobStatus.DONE -> "完成 · 输出 ${job.outputPath ?: "未知文件"}"
    MediaJob.JobStatus.FAILED -> "失败：${job.error ?: "未知错误"}"
    MediaJob.JobStatus.CANCELLED -> "已取消"
}

/** 各操作的诚实边界注脚（能力声明卡的按需复述）。 */
private fun honestOpNote(op: MediaOp): String = when (op) {
    MediaOp.EXTRACT_AUDIO -> "仅 AAC 音轨可直封 M4A；MP3 / Opus 等其他编码属 FFmpeg 级插件能力（路线图）。"
    MediaOp.STRIP_AUDIO -> "输出与源同画质同参数，仅丢弃音轨；多视频轨源只保留首条。"
    MediaOp.TRIM -> "无重编码裁剪：样本与编码参数原样搬运，画质零损失。"
}

private fun outputMime(op: MediaOp): String =
    if (op == MediaOp.EXTRACT_AUDIO) "audio/mp4" else "video/mp4"
