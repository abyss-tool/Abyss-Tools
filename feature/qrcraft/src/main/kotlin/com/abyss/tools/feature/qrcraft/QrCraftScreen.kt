package com.abyss.tools.feature.qrcraft

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.EmptyState
import com.abyss.tools.core.designsystem.component.ErrorState
import com.abyss.tools.core.designsystem.component.SectionHeader
import com.abyss.tools.core.designsystem.component.StatusChip
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 二维码工坊：单页双 Tab（生成 / 扫描）+ 底部历史区。
 *
 * 全本地实现（zxing 编解码 + Photo Picker / MediaStore / FileProvider），
 * 不需要 Shizuku；唯一外部依赖是 API 26–28 保存到相册时的
 * WRITE_EXTERNAL_STORAGE 运行时权限（按需申请）。
 */
@Composable
fun QrCraftScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // ── 历史持久化（filesDir/qr-craft/history.json，全部走 Dispatchers.IO）──
    val historyFile = remember { QrHistoryFile(File(context.filesDir, "qr-craft/history.json")) }
    var history by remember { mutableStateOf<List<QrHistoryItem>>(emptyList()) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { historyFile.load() }
            .onSuccess { history = it }
            .onFailure { e -> historyError = "历史加载失败：${e.message ?: e.javaClass.simpleName}" }
    }

    // ── 反馈条 ────────────────────────────────────────────────────────────
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackError by remember { mutableStateOf(false) }
    var feedbackDetail by remember { mutableStateOf<String?>(null) }

    fun feedback(message: String, isError: Boolean, detail: String? = null) {
        feedbackMessage = message
        feedbackError = isError
        feedbackDetail = detail
    }

    fun pushHistory(item: QrHistoryItem) {
        scope.launch {
            withContext(Dispatchers.IO) { historyFile.push(item) }
                .onSuccess { list ->
                    history = list
                    historyError = null
                }
                .onFailure { e ->
                    historyError = "历史写入失败：${e.message ?: e.javaClass.simpleName}（可用「清空」重建）"
                }
        }
    }

    // ── 页面状态 ──────────────────────────────────────────────────────────
    var tab by remember { mutableIntStateOf(0) } // 0 = 生成，1 = 扫描
    var content by remember { mutableStateOf("") }
    var ecOption by remember { mutableStateOf(EC_OPTIONS[1]) } // M 为推荐默认
    var sizePx by remember { mutableIntStateOf(512) }
    var paletteIndex by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }

    // 扫描侧状态
    var decoding by remember { mutableStateOf(false) }
    var decoded by remember { mutableStateOf<DecodedContent?>(null) }
    var scanFailure by remember { mutableStateOf<String?>(null) }

    // 实时预览编码（zxing 纯 CPU 毫秒级；内容空白时不编码）
    val matrix: Result<BitMatrix>? = remember(content, ecOption) {
        if (content.isBlank()) null else QrCodec.encode(content.trim(), ecOption.level)
    }

    // ── 导出（保存 / 分享）──────────────────────────────────────────────
    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingSave
        pendingSave = null
        if (granted) {
            action?.invoke()
        } else {
            feedback("未获存储写入权限 —— Android 9 及以下写系统相册需要它", isError = true)
        }
    }

    fun exportMatrix(): BitMatrix? {
        val m = matrix?.getOrNull()
        if (m == null) feedback("请先输入可编码的内容", isError = true)
        return m
    }

    fun performSavePng() {
        val m = exportMatrix() ?: return
        if (exporting) return
        exporting = true
        val preset = QrPalette.presets[paletteIndex]
        scope.launch {
            withContext(Dispatchers.IO) {
                QrImageIo.savePng(
                    context, m, sizePx,
                    preset.foreground.toArgb(), preset.background.toArgb(),
                    QrImageIo.newDisplayName(),
                )
            }.onSuccess { uri ->
                feedback("已保存到相册 Pictures/QRCraft", isError = false, detail = uri.toString())
                pushHistory(
                    QrHistoryItem(
                        kind = QrHistoryItem.KIND_GENERATE,
                        content = content.trim(),
                        format = null,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { e ->
                feedback("保存失败：${e.message ?: e.javaClass.simpleName}", isError = true)
            }
            exporting = false
        }
    }

    fun savePng() {
        // API 29+ 走 MediaStore 免权限；API 26–28 公共目录直写需要运行时权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = { performSavePng() }
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            performSavePng()
        }
    }

    fun share() {
        val m = exportMatrix() ?: return
        if (exporting) return
        exporting = true
        val preset = QrPalette.presets[paletteIndex]
        scope.launch {
            withContext(Dispatchers.IO) {
                QrImageIo.writeShareCache(
                    context, m, sizePx,
                    preset.foreground.toArgb(), preset.background.toArgb(),
                    QrImageIo.newDisplayName(),
                )
            }.onSuccess { file ->
                QrImageIo.launchShare(context, file)
                    .onSuccess {
                        feedback("已调起系统分享", isError = false)
                        pushHistory(
                            QrHistoryItem(
                                kind = QrHistoryItem.KIND_GENERATE,
                                content = content.trim(),
                                format = null,
                                timestamp = System.currentTimeMillis(),
                            ),
                        )
                    }
                    .onFailure { e ->
                        feedback("分享失败：${e.message ?: e.javaClass.simpleName}", isError = true)
                    }
            }.onFailure { e ->
                feedback("导出失败：${e.message ?: e.javaClass.simpleName}", isError = true)
            }
            exporting = false
        }
    }

    // ── 扫描结果动作 ──────────────────────────────────────────────────────
    fun copyText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("二维码工坊", text))
        feedback("已复制到剪贴板", isError = false)
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())))
        } catch (e: ActivityNotFoundException) {
            feedback("未找到可打开此链接的浏览器", isError = true)
        }
    }

    /** 「重新生成」/ 历史生成项点击：跳生成 Tab 并预填内容。 */
    fun jumpToGenerate(text: String) {
        tab = 0
        content = text
        scope.launch { scrollState.animateScrollTo(0) }
    }

    fun clearHistory() {
        scope.launch {
            withContext(Dispatchers.IO) { historyFile.clear() }
                .onSuccess {
                    history = emptyList()
                    historyError = null
                }
                .onFailure { e ->
                    historyError = "清空失败：${e.message ?: e.javaClass.simpleName}"
                }
        }
    }

    // ── 相册选图（Photo Picker，免存储权限）──────────────────────────────
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // 用户取消，保持安静
        decoding = true
        decoded = null
        scanFailure = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext Result.failure<DecodedContent>(
                            IllegalStateException("无法读取所选图片"),
                        )
                    QrImageIo.decodeImageBytes(bytes)
                } catch (e: Exception) {
                    Result.failure<DecodedContent>(e)
                }
            }
            result
                .onSuccess { d ->
                    decoded = d
                    pushHistory(
                        QrHistoryItem(
                            kind = QrHistoryItem.KIND_SCAN,
                            content = d.text,
                            format = d.format,
                            timestamp = System.currentTimeMillis(),
                        ),
                    )
                }
                .onFailure { e ->
                    scanFailure = e.message ?: e.javaClass.simpleName
                }
            decoding = false
        }
    }

    // ── 布局 ──────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        QrCraftHeader(onBack = onBack)

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("生成") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("扫描") })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.size(12.dp))

            Crossfade(targetState = tab, label = "qrcraft-tab") { current ->
                when (current) {
                    0 -> GeneratePane(
                        content = content,
                        onContentChange = { content = it },
                        matrix = matrix,
                        ecOption = ecOption,
                        onEcOptionChange = { ecOption = it },
                        sizePx = sizePx,
                        onSizePxChange = { sizePx = it },
                        paletteIndex = paletteIndex,
                        onPaletteIndexChange = { paletteIndex = it.coerceIn(0, QrPalette.presets.lastIndex) },
                        exporting = exporting,
                        onSavePng = { savePng() },
                        onShare = { share() },
                    )

                    else -> ScanPane(
                        decoding = decoding,
                        decoded = decoded,
                        failure = scanFailure,
                        onPickImage = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onCopy = { copyText(it) },
                        onOpenUrl = { openUrl(it) },
                        onRegenerate = { jumpToGenerate(it) },
                    )
                }
            }

            Spacer(Modifier.size(12.dp))
            FeedbackBar(message = feedbackMessage, isError = feedbackError, detail = feedbackDetail)

            HistorySection(
                items = history,
                error = historyError,
                onClear = { showClearDialog = true },
                onItemClick = { item ->
                    when (item.kind) {
                        QrHistoryItem.KIND_GENERATE -> jumpToGenerate(item.content)
                        else -> copyText(item.content)
                    }
                },
            )
            Spacer(Modifier.size(32.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空历史") },
            text = { Text("将删除本机历史记录文件（最近 30 条），删除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        clearHistory()
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

// ── 生成 Tab ──────────────────────────────────────────────────────────────

/** 纠错级别选项（SegmentedButton 数据源，L 7% / M 15% / Q 25% / H 30%）。 */
private data class EcOption(
    val level: ErrorCorrectionLevel,
    val label: String,
    val description: String,
)

private val EC_OPTIONS = listOf(
    EcOption(ErrorCorrectionLevel.L, "L", "容错 7% —— 图案最紧凑"),
    EcOption(ErrorCorrectionLevel.M, "M", "容错 15% —— 推荐默认"),
    EcOption(ErrorCorrectionLevel.Q, "Q", "容错 25% —— 适合户外张贴"),
    EcOption(ErrorCorrectionLevel.H, "H", "容错 30% —— 抗损最强，可叠 logo"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratePane(
    content: String,
    onContentChange: (String) -> Unit,
    matrix: Result<BitMatrix>?,
    ecOption: EcOption,
    onEcOptionChange: (EcOption) -> Unit,
    sizePx: Int,
    onSizePxChange: (Int) -> Unit,
    paletteIndex: Int,
    onPaletteIndexChange: (Int) -> Unit,
    exporting: Boolean,
    onSavePng: () -> Unit,
    onShare: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = content,
            onValueChange = onContentChange,
            label = { Text("内容（URL 或任意文本）") },
            placeholder = { Text("https://abyss.tools") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    text = if (content.isEmpty()) {
                        "支持中英文文本 / 链接 —— 内容越长图案越密"
                    } else {
                        "当前 ${content.length} 字符（编码前自动去除首尾空白）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )

        SectionHeader(title = "纠错级别", subtitle = "当前 ${ecOption.label} · ${ecOption.description}")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            EC_OPTIONS.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = ecOption == option,
                    onClick = { onEcOptionChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = EC_OPTIONS.size),
                ) {
                    Text(option.label)
                }
            }
        }

        SectionHeader(title = "导出尺寸", subtitle = "$sizePx × $sizePx px（PNG）")
        Slider(
            value = sizePx.toFloat(),
            onValueChange = { raw ->
                // 吸附到 64px 档位（256–1024 共 13 档），预览数值不抖
                onSizePxChange(((raw.toInt() + 32) / 64 * 64).coerceIn(256, 1024))
            },
            valueRange = 256f..1024f,
            steps = 12,
        )

        SectionHeader(title = "配色", subtitle = QrPalette.presets[paletteIndex].name)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QrPalette.presets.forEachIndexed { index, preset ->
                Box(modifier = Modifier.weight(1f)) {
                    ColorSwatch(
                        preset = preset,
                        selected = index == paletteIndex,
                        onClick = { onPaletteIndexChange(index) },
                    )
                }
            }
        }

        SectionHeader(title = "实时预览", subtitle = "所见即导出（含静区）")
        PreviewCard(matrix = matrix, preset = QrPalette.presets[paletteIndex])

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onSavePng,
                enabled = matrix?.isSuccess == true && !exporting,
            ) { Text("保存到相册") }
            OutlinedButton(
                onClick = onShare,
                enabled = matrix?.isSuccess == true && !exporting,
            ) { Text("分享") }
            if (exporting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun PreviewCard(matrix: Result<BitMatrix>?, preset: QrColorPreset) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            matrix == null -> EmptyState(
                title = "等待内容",
                message = "输入内容后，二维码在此实时渲染",
            )

            matrix.isFailure -> ErrorState(
                message = matrix.exceptionOrNull()?.message ?: "该内容无法编码为二维码",
            )

            else -> {
                val m = matrix.getOrThrow()
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    drawRect(color = preset.background)
                    // 整数模块尺寸 + 居中：模块间零缝隙，放大后边缘不发糊
                    val modulePx = (size.width / m.width).toInt().coerceAtLeast(1)
                    val totalPxX = modulePx * m.width
                    val totalPxY = modulePx * m.height
                    val offsetX = (size.width - totalPxX) / 2f
                    val offsetY = (size.height - totalPxY) / 2f
                    for (y in 0 until m.height) {
                        for (x in 0 until m.width) {
                            if (m.get(x, y)) {
                                drawRect(
                                    color = preset.foreground,
                                    topLeft = Offset(offsetX + x * modulePx, offsetY + y * modulePx),
                                    size = Size(modulePx.toFloat(), modulePx.toFloat()),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "${m.width} × ${m.height} 模块（含静区）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(preset: QrColorPreset, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(preset.background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 迷你码点示意：背景色打底 + 前景色方块
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(preset.foreground),
        )
    }
}

// ── 扫描 Tab ──────────────────────────────────────────────────────────────

@Composable
private fun ScanPane(
    decoding: Boolean,
    decoded: DecodedContent?,
    failure: String?,
    onPickImage: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRegenerate: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            title = "相册识别",
            subtitle = "系统照片选择器 —— 无需存储权限",
        )
        Button(onClick = onPickImage, enabled = !decoding) {
            if (decoding) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("识别中…")
            } else {
                Text("从相册选择")
            }
        }

        when {
            decoded != null -> DecodedCard(
                decoded = decoded,
                onCopy = onCopy,
                onOpenUrl = onOpenUrl,
                onRegenerate = onRegenerate,
            )

            failure != null -> EmptyState(
                title = "未识别出码",
                message = failure + "\n建议：确认图片里有二维码 / 条形码，避免过暗或过糊，或裁出码区再试。",
            )

            else -> EmptyState(
                title = "还没有扫描记录",
                message = "支持二维码与 Code 128 / EAN-13 / UPC-A / ITF 条形码",
            )
        }
    }
}

@Composable
private fun DecodedCard(
    decoded: DecodedContent,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRegenerate: (String) -> Unit,
) {
    val isUrl = QrCodec.isProbablyUrl(decoded.text)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(text = formatLabel(decoded.format), tone = ChipTone.OK)
            if (isUrl) StatusChip(text = "链接", tone = ChipTone.INFO)
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = decoded.text,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onCopy(decoded.text) }) { Text("复制") }
            if (isUrl) {
                Button(onClick = { onOpenUrl(decoded.text) }) { Text("打开链接") }
            }
            OutlinedButton(onClick = { onRegenerate(decoded.text) }) { Text("重新生成") }
        }
    }
}

// ── 历史区 ────────────────────────────────────────────────────────────────

@Composable
private fun HistorySection(
    items: List<QrHistoryItem>,
    error: String?,
    onClear: () -> Unit,
    onItemClick: (QrHistoryItem) -> Unit,
) {
    Column {
        SectionHeader(
            title = "历史",
            subtitle = "最近 ${QrHistoryCodec.MAX_ITEMS} 条 · 仅存本机",
            trailing = {
                if (items.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("清空") }
                }
            },
        )
        if (error != null) {
            FeedbackBar(message = error, isError = true)
            Spacer(Modifier.size(8.dp))
        }
        if (items.isEmpty()) {
            Text(
                text = "还没有记录 —— 生成或扫描后会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 高度封顶 320dp + LazyColumn 自滚动（overflow-y-auto 语义）
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items) { item ->
                    HistoryRow(item = item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: QrHistoryItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(
            text = if (item.kind == QrHistoryItem.KIND_GENERATE) "生成" else "扫描",
            tone = if (item.kind == QrHistoryItem.KIND_GENERATE) ChipTone.OK else ChipTone.INFO,
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatHistoryTime(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!item.format.isNullOrBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = formatLabel(item.format),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 页头 / 反馈条 / 小工具 ────────────────────────────────────────────────

@Composable
private fun QrCraftHeader(onBack: () -> Unit) {
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
            text = "二维码工坊",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = "内置 · ZXing 引擎", tone = ChipTone.OK)
    }
}

@Composable
private fun FeedbackBar(message: String?, isError: Boolean, detail: String? = null) {
    if (message == null) return
    val tone = if (isError) ChipTone.ERROR else ChipTone.OK
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tone.container.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = tone.content,
        )
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.size(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val HISTORY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatHistoryTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(HISTORY_TIME_FORMAT)

/** zxing 格式名 → 中文标签（未知格式原样透出，不撒谎）。 */
private fun formatLabel(raw: String): String = when (raw) {
    "QR_CODE" -> "二维码"
    "CODE_128" -> "Code 128"
    "EAN_13" -> "EAN-13"
    "UPC_A" -> "UPC-A"
    "ITF" -> "ITF"
    else -> raw
}
