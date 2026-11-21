package com.abyss.tools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.abyss.tools.core.designsystem.theme.AbyssTheme
import com.abyss.tools.core.shell.ShizukuMonitor
import com.abyss.tools.feature.onboarding.OnboardingScreen
import com.abyss.tools.ui.AbyssRoot
import com.abyss.tools.ui.rememberShizukuStatus

/**
 * 主 Activity（v0.9.6 精简）。
 *
 * v0.9.6 移除了跨会话渲染安全模式断路器（RenderSafeMode）与管线探针
 * （GlassProbe）：
 *  - API 31+ RenderEffect blur 是 Android 官方公开 API（S/12L 稳定），
 *    AndroidLiquidGlass vendored 底座已内建 runCatching 单项效果降级
 *    + 重入守卫 + 深度预算 + 成环断路器，足够兜底；
 *  - 外断路器跨会话写文件计数闪退逻辑是 v0.7.x 早期 GPU 驱动兼容期
 *    的临时防御，成熟后属于冗余；
 *  - 原生崩溃（RenderThread SIGSEGV）Java 层拦不住，CrashGuard 落盘
 *    已能提供诊断数据，探针 tail 标记诊断价值有限。
 *
 * 保留全部有效安全防线（详见 core/common 文档）：
 *  - API 能力降级（SDK 守卫 + runCatching 单项降级）
 *  - GlassRecordingGuard 判环（拒绝跨 backdrop 链式成环）
 *  - isRecordingSelf 重入守卫 + MAX_GLASS_NESTING_DEPTH 深度预算
 *  - InstallExpectation 反回滚 + PackageInstallerApkGateway 同签名门禁
 *  - PublisherSignatures.PINNED_SHA256 发布者公钥指纹硬钉扎
 *  - CrashGuard Java 层崩溃落盘
 *  - targetSdk=28（SELinux W^X 红线，保终端/Python 引擎存活）
 */
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

                // 首启新手引导（权限一站式申请）：完成前不进主界面；
                // 设置页「重新运行新手引导」可随时重开。
                var onboardingDone by remember { mutableStateOf(registry.settings.onboardingCompleted) }

                if (!onboardingDone) {
                    OnboardingScreen(
                        shizukuStatus = shizukuStatus,
                        onRequestShizukuPermission = {
                            ShizukuMonitor.requestPermission()
                        },
                        onOpenShizukuApp = {
                            runCatching {
                                startActivity(ShizukuMonitor.shizukuEntryIntent(this))
                            }
                        },
                        onDone = {
                            registry.settings.onboardingCompleted = true
                            onboardingDone = true
                        },
                    )
                } else {
                    AbyssRoot(
                        shizukuStatus = shizukuStatus,
                        onRequestShizukuPermission = {
                            ShizukuMonitor.requestPermission()
                        },
                        onOpenShizuku = {
                            runCatching {
                                startActivity(ShizukuMonitor.shizukuEntryIntent(this))
                            }
                        },
                        onReopenOnboarding = { onboardingDone = false },
                    )
                }
            }
        }
    }
}
