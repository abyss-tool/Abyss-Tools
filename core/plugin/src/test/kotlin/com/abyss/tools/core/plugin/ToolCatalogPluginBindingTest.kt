package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.ToolCatalog
import com.abyss.tools.core.common.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具目录 ↔ 插件索引一致性（SRS-102 AC-004 / T-008）：
 * ToolCatalog 的 PLUGIN 条目 pluginId 必须全部命中插件中心索引，
 * 归档插件（含已收编的 abyss-plugin-virtual-machine）不得再登记位次。
 */
class ToolCatalogPluginBindingTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

    @Test
    fun `PLUGIN 条目 pluginId 全命中插件中心索引`() {
        val index = PluginParsers.parseIndex(resource("plugins-index.json"))
        val indexIds = index.plugins.map { it.id }.toSet()
        val pluginIds = ToolCatalog.tools.filter { it.status == ToolStatus.PLUGIN }.mapNotNull { it.pluginId }
        assertTrue("ToolCatalog 存在 PLUGIN 条目缺 pluginId（SRS-102 FR-005）", pluginIds.size == ToolCatalog.tools.count { it.status == ToolStatus.PLUGIN })
        assertTrue("当前三引擎位次", pluginIds.size == 3)
        pluginIds.forEach { pid ->
            assertTrue("ToolCatalog.PLUGIN($pid) 未命中插件中心索引（索引与目录漂移）", pid in indexIds)
        }
        // 索引侧反向校验：引擎级条目在目录中均有 PLUGIN 位次（apk=null 预览期亦然）
        indexIds.forEach { pid ->
            val entry = ToolCatalog.tools.firstOrNull { it.pluginId == pid }
            assertTrue("索引条目($pid) 未在 ToolCatalog 登记 PLUGIN 位次", entry != null)
        }
    }

    @Test
    fun `虚拟机条目已收编 - 归档 id 不得复活为插件位次`() {
        // ADR-100 D-4 / SRS-004 FR-005：VM 仓归档，QEMU 能力由 Linux 插件 QEMU_VM 域承接
        assertTrue(
            "归档仓 abyss-plugin-virtual-machine 不得保留 PLUGIN 位次（HUB-014 归档纪律）",
            ToolCatalog.tools.none { it.pluginId == "abyss-plugin-virtual-machine" },
        )
        // 承接位：Linux 运行环境（QEMU_VM 执行域提供方）
        val linux = ToolCatalog.tools.first { it.pluginId == "abyss-plugin-linux" }
        assertEquals(ToolStatus.PLUGIN, linux.status)
        assertTrue(linux.summary.contains("linux-runtime-v1"))
    }

    @Test
    fun `Linux 运行环境条目形态`() {
        val linux = ToolCatalog.tools.first { it.pluginId == "abyss-plugin-linux" }
        assertEquals("linux-runtime", linux.id)
        assertTrue(linux.title.isNotEmpty())
        assertTrue(linux.summary.contains("双执行域"))
    }
}
