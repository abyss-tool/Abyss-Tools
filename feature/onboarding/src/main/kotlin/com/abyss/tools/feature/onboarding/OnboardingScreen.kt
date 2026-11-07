package com.abyss.tools.feature.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.abyss.tools.core.designsystem.component.ChipTone
import com.abyss.tools.core.shell.ShizukuStatus

/**
 * 首启新手引导 —— 权限一站式申请（完成前不进主界面）。
 *
 * 设计纪律：权限不是一次性全要 —— 每一步讲清楚「干什么用 / 不给会怎样」：
 *  0. 欢迎 + 免责声明（系统级能力，学习研究用途）；
 *  1. Shizuku 授权 —— 全部系统级功能的钥匙（可跳过，用到时再引导）；
 *  2. 安装未知应用 —— 插件安装 / App 自更新（可跳过）；
 *  3. 可选权限（定位基准 / 通知）+ 完成。
 */
private const val TOTAL_STEPS = 4

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

    // 步 2：安装未知应用（进入该步时重查真值）
    var installGranted by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step == 2) {
            installGranted = runCatching {
                context.packageManager.canRequestPackageInstalls()
            }.getOrDefault(false)
        }
    }

    // 步 3：可选权限
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var notificationsEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(step) {
        if (step == 3) {
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

        // ── 步骤圆点 ───────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            repeat(TOTAL_STEPS) { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == step) 22.dp else 8.dp, 8.dp)
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

        // ── 步骤内容 ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (step) {
                0 -> WelcomeStep(disclaimerAccepted) { disclaimerAccepted = it }

                1 -> ShizukuStep(
                    shizukuStatus = shizukuStatus,
                    onRequestPermission = onRequestShizukuPermission,
                    onOpenShizuku = onOpenShizukuApp,
                )

                2 -> InstallPermStep(
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

                3 -> OptionalPermStep(
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

// ── 步骤 0：欢迎 + 免责声明 ─────────────────────────────────────────────

@Composable
private fun WelcomeStep(disclaimerAccepted: Boolean, onDisclaimerChange: (Boolean) -> Unit) {
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
            "🔋" to "伪装四件套：机型 / 电量 / 信号 / 定位（Shizuku 免 Root 实时生效）",
            "🧩" to "七大重型插件：终端 / Python / 沙箱双开 / 应用构建器…按需安装",
            "🩹" to "补丁热更新：主壳与插件全走增量补丁通道",
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

// ── 步骤 1：Shizuku ────────────────────────────────────────────────────

@Composable
private fun ShizukuStep(
    shizukuStatus: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val running = shizukuStatus as? ShizukuStatus.Running
    Text(
        text = "第一步 · Shizuku 授权",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "Shizuku 是全部系统级功能的钥匙：伪装四件套、应用管理、插件底座都以" +
            "adb shell 上下文执行 —— 免 Root 的前提就是它。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionCard(
        title = "当前状态",
        rows = listOf(
            "运行状态" to if (running != null) "运行中（v${running.version ?: "?"}）" else "未运行",
            "授权状态" to when {
                running?.authorized == true -> "已授权 ✅"
                running != null -> "未授权 —— 点下方立即授权"
                else -> "等待 Shizuku 启动"
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
            Button(onClick = onOpenShizuku) { Text("打开 / 安装 Shizuku") }
        } else if (!running.authorized) {
            Button(onClick = onRequestPermission) { Text("立即授权") }
        }
    }
    Text(
        text = "还没准备好？可以先跳过 —— 用到系统级功能时会再次引导。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── 步骤 2：安装未知应用 ───────────────────────────────────────────────

@Composable
private fun InstallPermStep(
    granted: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Text(
        text = "第二步 · 允许安装未知应用",
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

// ── 步骤 3：可选权限 ──────────────────────────────────────────────────

@Composable
private fun OptionalPermStep(
    locationGranted: Boolean,
    onRequestLocation: () -> Unit,
    notificationsEnabled: Boolean,
    onOpenNotificationSettings: () -> Unit,
) {
    Text(
        text = "第三步 · 可选权限（按需）",
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
    Text(
        text = "准备就绪 —— 深渊工具主界面见。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
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
            .clip(RoundedCornerShape(20.dp))
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
