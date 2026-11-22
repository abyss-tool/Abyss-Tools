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
 */
class ToolCatalogTest {

    @Test
    fun `分类数 11（PLUGINS 分类已删除）`() {
        assertEquals(11, ToolCatalog.categories.size)
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
}
