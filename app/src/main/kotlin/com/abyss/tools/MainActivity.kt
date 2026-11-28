package com.abyss.tools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.abyss.tools.core.common.Capability
import com.abyss.tools.core.shell.ShizukuMonitor
import com.abyss.tools.core.designsystem.theme.AbyssTheme
import com.abyss.tools.feature.onboarding.OnboardingScreen
import com.abyss.tools.privilege.CapabilityGuideIntents
import com.abyss.tools.ui.AbyssRoot
import com.abyss.tools.ui.rememberPrivilegeSnapshot
import com.abyss.tools.ui.rememberShizukuStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // v0.8.0：动态取色（Material You）—— 读取设置仓储的 Compose 状态，
            // 开关切换即时重换整套色板；老系统 / 关闭档回落深渊默认色板。
            val registry = ServiceRegistry
            AbyssTheme(dynamicColor = registry.settings.dynamicColor) {
                val shizukuStatus by rememberShizukuStatus(ShizukuMonitor)
                // ARC-011（v0.10.2）：权限能力快照 —— 仪表盘档位徽章 / 功能页门禁 /
                // 引导页档位中心 / 伪装模式卡共用的单一事实源。
                val privilegeSnapshot by rememberPrivilegeSnapshot(registry.privilegeRepository)

                val requestShizukuPermission: () -> Unit = { ShizukuMonitor.requestPermission() }
                val openShizuku: () -> Unit = {
                    runCatching {
                        startActivity(ShizukuMonitor.shizukuEntryIntent(this))
                    }
                }
                // 「去授权」统一落点：能力 → 系统设置页 / 授权请求（FEAT-038 FR-007）
                val openCapability: (Capability) -> Unit = { capability ->
                    CapabilityGuideIntents.open(
                        context = this,
                        capability = capability,
                        shizukuStatus = shizukuStatus,
                        onRequestShizukuPermission = requestShizukuPermission,
                        onOpenShizuku = openShizuku,
                    )
                }

                // 首启新手引导（权限一站式申请）：完成前不进主界面；
                // 设置页「重新运行新手引导」可随时重开。
                var onboardingDone by remember { mutableStateOf(registry.settings.onboardingCompleted) }

                if (!onboardingDone) {
                    OnboardingScreen(
                        shizukuStatus = shizukuStatus,
                        onRequestShizukuPermission = requestShizukuPermission,
                        onOpenShizukuApp = openShizuku,
                        privilegeSnapshot = privilegeSnapshot,
                        onOpenCapability = openCapability,
                        onDone = {
                            registry.settings.onboardingCompleted = true
                            onboardingDone = true
                        },
                    )
                } else {
                    AbyssRoot(
                        shizukuStatus = shizukuStatus,
                        onRequestShizukuPermission = requestShizukuPermission,
                        onOpenShizuku = openShizuku,
                        onReopenOnboarding = { onboardingDone = false },
                        privilegeSnapshot = privilegeSnapshot,
                        onOpenCapability = openCapability,
                        // v0.9.6：移除跨会话 RenderSafeMode 断路器；玻璃能力改为
                        // 单次会话内的 API 能力降级（runCatching + SDK 守卫），
                        // 不再持久化崩溃计数、不再依赖跨进程文件状态机。
                        glassEnabled = true,
                        lensEnabled = true,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ARC-011 FR-008：从系统设置返回（安装未知应用 / 悬浮窗 / Dhizuku 授权 /
        // 无障碍 / 设备管理员）后重读真值 —— 这几项没有通用广播可用。
        if (ServiceRegistry.ready) ServiceRegistry.privilegeRepository.refresh()
    }
}
