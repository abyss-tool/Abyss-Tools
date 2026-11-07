package com.abyss.tools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.abyss.tools.core.shell.ShizukuMonitor
import com.abyss.tools.core.designsystem.theme.AbyssTheme
import com.abyss.tools.feature.onboarding.OnboardingScreen
import com.abyss.tools.ui.AbyssRoot
import com.abyss.tools.ui.rememberShizukuStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    )
                }
            }
        }
    }
}
