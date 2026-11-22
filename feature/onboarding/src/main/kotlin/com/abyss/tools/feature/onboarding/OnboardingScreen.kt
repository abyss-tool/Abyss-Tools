package com.abyss.tools.feature.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.designsystem.component.NAV_TRANSITION_MS
import com.abyss.tools.core.designsystem.component.rememberReducedMotion
import com.abyss.tools.core.designsystem.component.StatusChip
import com.abyss.tools.core.shell.ShizukuStatus
import com.abyss.tools.core.shell.ShizukuVariant
import com.abyss.tools.core.shell.ShizukuVariants

/**
 * 首启新手引导 —— 权限一站式申请（完成前不进主界面）。
 *
 * v0.8.5 丰富为七步（用户反馈「引导页太单薄」）：
 *  0. 欢迎 + 免责声明（系统级能力，学习研究用途）；
 *  1. 认识 Shizuku —— 它是什么 / 为什么免 Root / 兼容哪些实现；
 *  2. 启动并授权 Shizuku —— 实时状态 + 无线调试激活教程（可跳过）；
 *  3. 安装未知应用 —— 插件安装 / App 自更新（可跳过）；
 *  4. 可选权限（定位基准 / 通知）；
 *  5. 功能导览 —— 主界面有什么、去哪找；
 *  6. 一切就绪 —— 状态回顾 + 上手贴士。
 *
 * 设计纪律：权限不是一次性全要 —— 每一步讲清楚「干什么用 / 不给会怎样」。
 */
private const val TOTAL_STEPS = 7

@Composable
fun OnboardingScreen(
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizukuApp: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current

    var step by remember { mutableStateOf(0) }
    var disclaimerAccepted by remember { mutableStateOf(false) }

    // 步 3：安装未知应用（进入该步时重查真值）
    var installGranted by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step == 3) {
            installGranted = runCatching {
                context.packageManager.canRequestPackageInstalls()
            }.getOrDefault(false)
        }
    }

    // 步 4：可选权限
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var notificationsEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(step) {
        if (step == 4) {
            locationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            notificationsEnabled = runCatching {
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            }.getOrDefault(true)
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> locationGranted = grants.values.any { it } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── 页头：进度 ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "🕳 深渊工具",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "新手引导 · ${step + 1} / $TOTAL_STEPS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 步骤圆点（当前步形变为药丸，宽窄过渡动画）──────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            repeat(TOTAL_STEPS) { index ->
                val width by animateDpAsState(
                    targetValue = if (index == step) 22.dp else 8.dp,
                    animationSpec = tween(NAV_TRANSITION_MS),
                    label = "onboarding_dot_$index",
                )
                Box(
                    modifier = Modifier
                        .size(width, 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index <= step) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        // ── 步骤内容（切换随步进方向滑动 —— 前进自右入，后退自左入；
        //    系统移除动画时直切，见 rememberReducedMotion）──────────
        val reducedMotion = rememberReducedMotion()
        AnimatedContent(
            targetState = step,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            transitionSpec = {
                if (reducedMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState > initialState
                    val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(NAV_TRANSITION_MS)
                    (
                        slideInHorizontally(slideSpec) { if (forward) it / 5 else -it / 5 } +
                            fadeIn(tween(NAV_TRANSITION_MS))
                        ) togetherWith (
                        slideOutHorizontally(slideSpec) { if (forward) -it / 5 else it / 5 } +
                            fadeOut(tween(NAV_TRANSITION_MS))
                        )
                }
            },
            label = "onboarding-step",
        ) { currentStep ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (currentStep) {
                    0 -> WelcomeStep(disclaimerAccepted) { disclaimerAccepted = it }

                    1 -> ShizukuExplainerStep(shizukuStatus = shizukuStatus)

                    2 -> ShizukuSetupStep(
                        shizukuStatus = shizukuStatus,
                        onRequestPermission = onRequestShizukuPermission,
                        onOpenShizuku = onOpenShizukuApp,
                    )

                    3 -> InstallPermStep(
                        granted = installGranted,
                        onOpenSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        },
                        onRefresh = {
                            installGranted = runCatching {
                                context.packageManager.canRequestPackageInstalls()
                            }.getOrDefault(false)
                        },
                    )

                    4 -> OptionalPermStep(
                        locationGranted = locationGranted,
                        onRequestLocation = {
                            locationLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        notificationsEnabled = notificationsEnabled,
                        onOpenNotificationSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            }
                        },
                    )

                    5 -> FeatureTourStep()

                    6 -> ReadyStep(shizukuStatus = shizukuStatus)
                }
            }
        }

        // ── 底部导航 ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (step > 0) {
                OutlinedButton(onClick = { step -= 1 }, modifier = Modifier.weight(1f)) {
                    Text("上一步")
                }
            }
            Button(
                onClick = { if (step < TOTAL_STEPS - 1) step += 1 else onDone() },
                enabled = step != 0 || disclaimerAccepted,
                modifier = Modifier.weight(1.6f),
            ) {
                Text(if (step == TOTAL_STEPS - 1) "进入深渊工具" else "下一步")
            }
        }
    }
}

// ── 步 0：欢迎 + 免责声明 ───────────────────────────────────────────────

@Composable
private fun WelcomeStep(disclaimerAccepted: Boolean, onDisclaimerChange: (Boolean) -> Unit) {
    Text(
        text = "🕳",
        style = MaterialTheme.typography.displayMedium,
    )
    Text(
        text = "欢迎来到深渊工具",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "面向极客的系统级超级工具箱 —— 免 Root，Shizuku + 沙箱 + 脚本引擎。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionCard(
        title = "你将获得",
        rows = listOf(
            "🕳" to "大内置化：二十余项系统级工具全部主壳内置 —— 应用管理 / 终端 / 脚本 / 沙箱 / 备份 / 系统控制…",
            "🛡" to "免 Root 提权：全部系统级能力经 Shizuku 兼容通道执行（无需解锁 Bootloader）",
            "🧩" to "引擎级插件：应用构建器 / 病毒拦截 / 虚拟机 —— 按需安装，独立更新",
            "🩹" to "补丁热更新：主壳与插件全走增量补丁通道，小版本免重装",
        ),
    )
    PermissionCard(
        title = "免责声明（必读）",
        rows = listOf(
            "⚖" to "本软件为受限许可（UGRL v1.0）：仅供个人学习与安全研究，禁止再分发与商业使用。",
            "⚠" to "内置系统级能力（Shell 执行 / 设备伪装 / 沙箱虚拟化），滥用可能违反你所在司法辖区的法律，风险由使用者独自承担。",
        ),
        tone = ChipTone.WARN,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = disclaimerAccepted, onCheckedChange = onDisclaimerChange)
        Text(
            text = "我已阅读并同意上述声明",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ── 步 1：认识 Shizuku ─────────────────────────────────────────────────

@Composable
private fun ShizukuExplainerStep(shizukuStatus: ShizukuStatus) {
    val context = LocalContext.current
    val installedVariants = remember(shizukuStatus) { ShizukuVariants.installed(context) }

    Text(
        text = "认识 Shizuku",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "Shizuku 是一个免 Root 提权框架：在设备上以 adb shell（uid 2000）权限" +
            "运行一个小型服务端，普通应用（比如深渊工具）经它执行系统级操作 —— " +
            "伪装机型 / 冻结应用 / 改系统设置都不需要解锁 Bootloader。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionCard(
        title = "深渊工具拿它做什么",
        rows = listOf(
            "伪装四件套" to "机型 / 电量 / 信号 / 定位的实时伪装指令",
            "系统控制中心" to "深色 / 亮度 / DPI / 分辨率 / 授权总管",
            "应用管理器" to "冻结 / 隐藏 / 强停 / 提取 APK",
            "终端与脚本" to "真实 adb shell 上下文的命令执行",
        ),
    )
    PermissionCard(
        title = "兼容实现（装其一即可）",
        rows = ShizukuVariant.entries.map { variant ->
            variant.displayName to variant.detail
        },
        tone = ChipTone.INFO,
    )
    if (installedVariants.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "本机已装：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            installedVariants.forEach { variant ->
                StatusChip(text = "${variant.displayName} ✓", tone = ChipTone.OK)
            }
        }
    }
    Text(
        text = "Sui 为 Root 集成形态（Magisk 模块），深渊工具会在启动时自动接线，无需手动打开；" +
            "其余实现装好后在下一步启动并授权。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── 步 2：启动并授权 Shizuku ───────────────────────────────────────────

@Composable
private fun ShizukuSetupStep(
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val context = LocalContext.current
    val running = shizukuStatus as? ShizukuStatus.Running
    val installedVariants = remember(shizukuStatus) { ShizukuVariants.installed(context) }

    Text(
        text = "启动并授权 Shizuku",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "Shizuku 是全部系统级功能的钥匙。先把它的服务端跑起来，再给深渊工具授权 —— " +
            "授权一次，之后一直有效。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionCard(
        title = "当前状态",
        rows = listOf(
            "服务端" to if (running != null) "运行中（v${running.version ?: "?"}）" else "未运行",
            "授权状态" to when {
                running?.authorized == true -> "已授权 ✅"
                running != null -> "未授权 —— 点下方立即授权"
                else -> "等待服务端启动"
            },
            "已装变体" to if (installedVariants.isEmpty()) {
                "未检测到（可点按钮去下载页）"
            } else {
                installedVariants.joinToString(" / ") { it.displayName }
            },
        ),
        tone = when {
            running?.authorized == true -> ChipTone.OK
            running != null -> ChipTone.WARN
            else -> ChipTone.NEUTRAL
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (running == null) {
            Button(onClick = onOpenShizuku) {
                Text(
                    if (installedVariants.isEmpty()) "获取 Shizuku（下载页）"
                    else "打开 ${installedVariants.first().displayName}",
                )
            }
        } else if (!running.authorized) {
            Button(onClick = onRequestPermission) { Text("立即授权") }
        }
    }
    PermissionCard(
        title = "无线调试激活教程（Android 11+，推荐）",
        rows = listOf(
            "①" to "系统设置 → 关于手机 → 连点「版本号」7 次开启开发者选项",
            "②" to "开发者选项 → 无线调试 → 「使用配对码配对设备」完成配对",
            "③" to "回到 Shizuku → 配对 → 通过无线调试启动（无需电脑）",
            "④" to "启动后回到本页 → 点「立即授权」允许深渊工具",
        ),
        tone = ChipTone.INFO,
    )
    Text(
        text = "有电脑？USB 连接后 Shizuku 内可一键「通过连接电脑启动」；Root 用户直接在 Shizuku 内" +
            "「通过 Root 启动」。还没准备好也可以先跳过 —— 用到系统级功能时会再次引导。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── 步 3：安装未知应用 ─────────────────────────────────────────────────

@Composable
private fun InstallPermStep(
    granted: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Text(
        text = "允许安装未知应用",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "插件安装与深渊工具自更新都走系统 PackageInstaller 通道 ——" +
            "需要一次「允许安装未知应用」授权（系统级开关，只授一次）。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionCard(
        title = "当前状态",
        rows = listOf(
            "安装未知应用" to if (granted) "已允许 ✅" else "未允许",
        ),
        tone = if (granted) ChipTone.OK else ChipTone.WARN,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!granted) {
            Button(onClick = onOpenSettings) { Text("前往系统设置") }
        }
        TextButton(onClick = onRefresh) { Text("我已开启，刷新状态") }
    }
}

// ── 步 4：可选权限 ─────────────────────────────────────────────────────

@Composable
private fun OptionalPermStep(
    locationGranted: Boolean,
    onRequestLocation: () -> Unit,
    notificationsEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
) {
    Text(
        text = "可选权限（按需）",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "以下权限都不影响核心功能 —— 关闭也完全可用，用到时随时在功能页内授权。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionCard(
        title = "精确定位（可选）",
        rows = listOf(
            "用途" to "「定位伪装」的「以当前位置为基准」一键带入坐标",
            "状态" to if (locationGranted) "已授权 ✅" else "未授权（不影响伪装功能）",
        ),
    )
    if (!locationGranted) {
        OutlinedButton(onClick = onRequestLocation) { Text("授权定位") }
    }
    PermissionCard(
        title = "通知（可选）",
        rows = listOf(
            "用途" to "定位伪装前台服务状态 / 更新进度提示",
            "状态" to if (notificationsEnabled) "已开启 ✅" else "已关闭（前台服务仍正常工作）",
        ),
    )
    if (!notificationsEnabled) {
        OutlinedButton(onClick = onOpenNotificationSettings) { Text("前往通知设置") }
    }
}

// ── 步 5：功能导览 ─────────────────────────────────────────────────────

@Composable
private fun FeatureTourStep() {
    Text(
        text = "功能导览",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "主界面分四个 tab：仪表盘看状态、功能页进全部工具、插件管引擎级插件、设置管更新与权限。" +
            "功能页里你能找到这些分类 ——",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionCard(
        title = "功能分类一览（${ToolCatalog.tools.size} 项工具 · ${ToolCatalog.categories.size} 个分类）",
        rows = ToolCatalog.categories
            .map { category ->
                category.title to "${category.description}（${ToolCatalog.toolsOf(category).size} 项）"
            },
    )
    PermissionCard(
        title = "插件（独立 tab）",
        rows = listOf(
            "插件中心" to "应用构建器 / 病毒拦截 / 虚拟机 —— 三个引擎级大插件按需安装",
        ),
        tone = ChipTone.INFO,
    )
    Text(
        text = "小技巧：在 设置 → 权限与新手引导 里可随时重跑本引导。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── 步 6：一切就绪 ─────────────────────────────────────────────────────

@Composable
private fun ReadyStep(shizukuStatus: ShizukuStatus) {
    val running = shizukuStatus as? ShizukuStatus.Running
    Text(
        text = "🌌",
        style = MaterialTheme.typography.displayMedium,
    )
    Text(
        text = "一切就绪",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "深渊工具主界面见 —— 仪表盘会实时显示 Shizuku 通道与插件状态。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionCard(
        title = "状态回顾",
        rows = listOf(
            "免责声明" to "已同意 ✓",
            "Shizuku 通道" to when {
                running?.authorized == true -> "已就绪 ✅（全部系统级功能可用）"
                running != null -> "运行中但未授权 —— 需要时在功能页内一键授权"
                else -> "暂未启动 —— 用到时各功能页会再次引导"
            },
        ),
        tone = if (running?.authorized == true) ChipTone.OK else ChipTone.NEUTRAL,
    )
    PermissionCard(
        title = "上手贴士",
        rows = listOf(
            "补丁更新" to "设置 → 检查更新：主壳与插件走增量补丁，小版本免重装",
            "新手引导" to "设置 → 权限与新手引导 → 重新运行（本引导随时可重看）",
            "安全模式" to "若遇渲染闪退会自动降级玻璃效果，设置 → 诊断可查看现场",
        ),
    )
}

// ── 通用卡片 ──────────────────────────────────────────────────────────

@Composable
private fun PermissionCard(
    title: String,
    rows: List<Pair<String, String>>,
    tone: ChipTone = ChipTone.NEUTRAL,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(tone.container.copy(alpha = 0.35f))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = tone.content,
        )
        Spacer(Modifier.size(6.dp))
        rows.forEach { (k, v) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
            ) {
                Text(
                    text = k,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.3f),
                )
                Text(
                    text = v,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
