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

        // ── 渲染安全模式断路器（v0.7.3 → v2，v0.7.4）──────────────────
        // 原生崩溃（RenderThread SIGSEGV 类）Java 层拦不住 —— 从「进程死亡
        // → 秒级重启」的外部特征识别闪退循环：连续快速冷启动 ≥ 3 次即令
        // 本次会话 glassEnabled=false（全玻璃 Frosted 降级，零 RenderEffect
        // / 零 backdrop 采样），保证主壳能打开、诊断可读。会话存活 45s
        // 自动恢复（下次冷启动回到正常渲染档）。
        // v2 起另认慢性循环：连续 3 次「异常终止」（崩溃 / 被杀，无干净
        // 退出标记）同样触发、与间隔无关 —— 存活数分钟才崩的渲染栈慢性
        // 增长类闪退（PLE110 2026-11-15：472s）不再漏网。
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

    override fun onDestroy() {
        super.onDestroy()
        // 干净退出标记（v2）：isFinishing 的销毁 = 用户主动离开（返回键 /
        // finish()）。崩溃、系统杀死（LRU 回收、滑卡清理）走不到这里 ——
        // 下次冷启动即按「上一会话异常终止」计连崩（慢性闪退循环防线，
        // bugreport PLE110 2026-11-15：存活 472s 后崩，v1 快速窗口盖不住）。
        if (isFinishing) {
            RenderSafeMode.markCleanExit(File(filesDir, RenderSafeMode.FILE_NAME))
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
