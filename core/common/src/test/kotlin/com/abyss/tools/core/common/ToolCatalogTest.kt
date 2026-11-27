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
