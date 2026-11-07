package com.abyss.tools.feature.shizukukit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abyss.tools.core.designsystem.component.SectionHeader

/**
 * Dumpsys 面板：白名单只读诊断命令卡片列表 → 点击执行 → 输出卡
 * （等宽字体 / 可滚动 max-h 320dp / 复制按钮；大输出截断到 64KB）。
 */
@Composable
internal fun DumpsysPanel(
    runner: KitRunner,
    record: ExecRecord?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "白名单诊断命令",
                subtitle = "只读 dumpsys 目标逐条执行，输出在下方结果卡查看",
            )
        }

        DumpsysCommands.whitelist.forEach { preset ->
            item {
                KitCommandCard(
                    title = preset.title,
                    subtitle = "dumpsys ${preset.args}",
                    detail = preset.detail,
                    enabled = !runner.busy,
                    onClick = {
                        runner.execute(
                            CommandType.DUMPSYS,
                            "Dumpsys · ${preset.title}",
                            DumpsysCommands.dumpsys(preset.args),
                        )
                    },
                )
            }
        }

        item { KitBusyLine(runner.busy) }
        item { ExecResultCard(record) }

        item {
            KitInfoCard(
                title = "为什么是白名单",
                body = "dumpsys 面板只放行只读诊断命令：写操作（如 battery set）在伪装四件套有" +
                    "专用页，自由拼接输入一律拒绝 —— fail-closed，防误伤系统服务。" +
                    "package / procstats 等输出量大，自动截断到 64KB 展示。",
            )
        }
    }
}
