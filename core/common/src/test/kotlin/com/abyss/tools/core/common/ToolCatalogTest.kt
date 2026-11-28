package com.abyss.tools.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolCatalog 数据契约测试（纯 JVM）。
 *
 * FEAT-034（v0.9.12）：插件分类（PLUGINS）与 plugin-hub 行项移除 ——
 * 引擎级插件能力由底部导航「插件」独立 tab 承载（AC-011）。
 *
 * UI-001（功能文案精简）：description / summary 字段已删除 ——
 * 目录不再持有装饰长文案，标题必须短（单行卡片语义）。
 */
class ToolCatalogTest {

    @Test
    fun `分类数 10（PLUGINS 分类已删除）`() {
        assertEquals(10, ToolCatalog.categories.size)
        assertTrue(ToolCatalog.categories.none { it.name == "PLUGINS" })
    }

    @Test
    fun `plugin-hub 行项已移除`() {
        assertNull(ToolCatalog.byId("plugin-hub"))
    }

    @Test
    fun `工具 id 全目录唯一`() {
        val ids = ToolCatalog.tools.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `插件承载条目必填 pluginId`() {
        ToolCatalog.tools
            .filter { it.status == ToolStatus.PLUGIN }
            .forEach { def ->
                assertTrue(
                    "PLUGIN 条目缺 pluginId: ${def.id}",
                    !def.pluginId.isNullOrBlank(),
                )
            }
    }

    @Test
    fun `首个分类为系统工具`() {
        assertEquals(ToolCategory.SYSTEM, ToolCatalog.categories.first())
    }

    // ── FEAT-039（v0.10.2）：权限矩阵数据契约 ───────────────────────────

    @Test
    fun `全部 27 条工具都登记了 access 且与权限矩阵一致`() {
        assertEquals(27, ToolCatalog.tools.size)
        val expected: Map<String, Set<Capability>> = mapOf(
            "app-builder" to setOf(Capability.SHIZUKU_SHELL, Capability.INSTALL_UNKNOWN),
            "linux-runtime" to setOf(Capability.SHIZUKU_SHELL, Capability.INSTALL_UNKNOWN),
            "app-manager" to setOf(Capability.SHIZUKU_SHELL),
            "system-control" to setOf(Capability.SHIZUKU_SHELL),
            "shizuku-kit" to setOf(Capability.SHIZUKU_SHELL),
            "battery-doctor" to emptySet(),
            "virus-guard" to setOf(Capability.SHIZUKU_SHELL, Capability.INSTALL_UNKNOWN, Capability.APP_OBSERVE),
            "terminal" to setOf(Capability.SHIZUKU_SHELL),
            "script-lab" to setOf(Capability.SHIZUKU_SHELL),
            "code-editor" to emptySet(),
            "task-blueprint" to setOf(Capability.SHIZUKU_SHELL),
            "sandbox-space" to setOf(Capability.SHIZUKU_SHELL),
            "dual-open" to setOf(Capability.SHIZUKU_SHELL),
            "battery-disguise" to setOf(Capability.SHIZUKU_SHELL),
            "location-disguise" to setOf(Capability.SHIZUKU_SHELL),
            "signal-disguise" to setOf(Capability.SHIZUKU_SHELL),
            "model-disguise" to setOf(Capability.SHIZUKU_SHELL),
            "privacy-guard" to emptySet(),
            "media-lab" to emptySet(),
            "qr-craft" to emptySet(),
            "automation" to setOf(Capability.SHIZUKU_SHELL, Capability.ACCESSIBILITY),
            "widget-studio" to emptySet(),
            "dynamic-island" to setOf(Capability.OVERLAY),
            "live-wallpaper" to emptySet(),
            "network-audit" to emptySet(),
            "backup-master" to emptySet(),
            "time-shift" to emptySet(),
        )
        assertEquals(expected.keys, ToolCatalog.tools.map { it.id }.toSet())
        ToolCatalog.tools.forEach { tool ->
            assertEquals("access.required 不符: ${tool.id}", expected.getValue(tool.id), tool.access.required)
        }
    }

    @Test
    fun `modes 的门槛不超出 required 加可选能力集 且非空时至少一条可用模式语义合法`() {
        // 可选能力 = 档案注入 / DO 托管 / 一键授予 / 状态栏覆盖层 / 恢复安装 等增强路径所用的能力
        val optionalUniverse = setOf(
            Capability.SELF_BRANDING, Capability.SHIZUKU_ROOT, Capability.ACCESSIBILITY,
            Capability.INSTALL_UNKNOWN, Capability.OVERLAY, Capability.APP_OBSERVE,
            Capability.DHIZUKU_ACTIVE, Capability.DEVICE_ADMIN_ACTIVE,
        )
        ToolCatalog.tools.forEach { tool ->
            tool.access.modes.forEach { m ->
                assertTrue(
                    "模式 ${tool.id}/${m.name} 的 required 含未知维度",
                    (m.required - optionalUniverse - tool.access.required).isEmpty(),
                )
                assertTrue("模式 ${tool.id}/${m.name} 缺说明", m.note.isNotBlank())
            }
        }
    }

    @Test
    fun `需 Shizuku 的工具都声明了零权限只读模式`() {
        ToolCatalog.tools
            .filter { it.access.required == setOf(Capability.SHIZUKU_SHELL) }
            .forEach { tool ->
                assertTrue(
                    "${tool.id} 应有零权限只读 / 预览模式",
                    tool.access.modes.any { it.required.isEmpty() },
                )
            }
    }

    @Test
    fun `普通用户可直接打开的工具集精确匹配`() {
        val zero = ToolCatalog.tools.filter { it.access.required.isEmpty() }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "qr-craft", "media-lab", "backup-master", "network-audit",
                "widget-studio", "live-wallpaper",
                "battery-doctor", "code-editor", "privacy-guard", "time-shift",
            ),
            zero,
        )
    }

    @Test
    fun `快捷工具单一数据源全部可解析且 count 由列表推导`() {
        val quick = ToolCatalog.QUICK_TOOL_IDS.mapNotNull(ToolCatalog::byId)
        assertEquals(ToolCatalog.QUICK_TOOL_IDS.size, quick.size)
        assertEquals(4, quick.size)
    }

    /** UI-001：目录不携带装饰长文案 —— 标题短句、无长文本标点。 */
    @Test
    fun `目录无长文案字段（UI-001 文案精简）`() {
        ToolCatalog.categories.forEach { category ->
            assertTrue(
                "分类标题超长（UI-001 违约）: ${category.title}",
                category.title.length <= 12,
            )
        }
        ToolCatalog.tools.forEach { tool ->
            assertTrue(
                "工具标题超长（UI-001 违约）: ${tool.title}",
                tool.title.length <= 12,
            )
            assertTrue(
                "工具标题含长文本标点（UI-001 违约）: ${tool.title}",
                tool.title.none { it in "。；——" },
            )
        }
    }
}
