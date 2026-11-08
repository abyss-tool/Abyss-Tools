package com.abyss.tools

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.abyss.tools.core.common.RenderSafeMode
import com.abyss.tools.core.shell.ShizukuMonitor
import com.abyss.tools.core.designsystem.theme.AbyssTheme
import com.abyss.tools.feature.onboarding.OnboardingScreen
import com.abyss.tools.ui.AbyssRoot
import com.abyss.tools.ui.rememberShizukuStatus
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ── 渲染安全模式断路器（v0.7.3）────────────────────────────────
        // 原生崩溃（RenderThread SIGSEGV 类）Java 层拦不住 —— 从「进程死亡
        // → 秒级重启」的外部特征识别闪退循环：连续快速冷启动 ≥ 3 次即令
        // 本次会话 glassEnabled=false（全玻璃 Frosted 降级，零 RenderEffect
        // / 零 backdrop 采样），保证主壳能打开、诊断可读。会话存活 45s
        // 自动恢复（下次冷启动回到正常渲染档）。
        val safeModeFile = File(filesDir, RenderSafeMode.FILE_NAME)
        val safeModeDecision = if (!processGateEntered) {
            processGateEntered = true
            RenderSafeMode.onColdStart(safeModeFile)
        } else {
            // 温重建（旋转 / 进程内重建）：不计入闪退循环
            RenderSafeMode.currentDecision(safeModeFile)
        }
        stableExitHandler.removeCallbacksAndMessages(null)
        stableExitHandler.postDelayed(
            { RenderSafeMode.markStable(safeModeFile) },
            RenderSafeMode.STABLE_AFTER_MS,
        )

        setContent {
            AbyssTheme {
                val registry = ServiceRegistry
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
                            ShizukuMonitor.shizukuAppIntent(this)?.let(::startActivity)
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
                            ShizukuMonitor.shizukuAppIntent(this)?.let(::startActivity)
                        },
                        onReopenOnboarding = { onboardingDone = false },
                        glassEnabled = !safeModeDecision.engage,
                    )
                }
            }
        }
    }

    private companion object {
        /** 进程门闩：区分冷启动（首次 onCreate）与温重建（旋转等）。 */
        @Volatile
        var processGateEntered = false

        /** 存活计时（进程死亡即失效 —— 正是所需语义：早死不清零）。 */
        private val stableExitHandler = Handler(Looper.getMainLooper())
    }
}
