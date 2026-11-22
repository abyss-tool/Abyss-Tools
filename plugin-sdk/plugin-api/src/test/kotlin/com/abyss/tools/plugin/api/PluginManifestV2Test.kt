package com.abyss.tools.plugin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HOST_API v2 契约与 plugin.json v2 增量字段（DC-100 / ARC-102 §2）。
 *
 * 覆盖（ARC-102 AC-005 / DC-100 AC-003 的契约面）：
 *  - v1 manifest 在 v2 常量下照常解析（向后兼容，ignoreUnknownKeys）；
 *  - v2 四字段（provides / depends / capabilities / assetChannel）解析；
 *  - DependSpec / ProvideSpec 形态校验（fail-closed）；
 *  - minHost=2 插件语义与宿主侧 fail-closed 判定锚点。
 */
class PluginManifestV2Test {

    private val v1Manifest = """
        {
          "id": "abyss-plugin-virus-guard",
          "name": "病毒拦截",
          "version": "0.1.0",
          "minHost": 1,
          "minApp": "0.8.0",
          "category": "system",
          "summary": "安装事件拦截 + 病毒特征引擎。",
          "shizuku": {"ownAuth": true, "reason": "安装事件拦截需要 shell 上下文（人话说明）。"}
        }
    """.trimIndent()

    private val linuxManifest = """
        {
          "id": "abyss-plugin-linux",
          "name": "Linux 运行环境",
          "version": "0.2.0",
          "minHost": 2,
          "minApp": "0.10.0",
          "category": "tools",
          "summary": "自包含 Linux 运行环境：PRoot 用户态 + QEMU 完整系统双执行域。",
          "provides": [
            {"capability": "linux-runtime-v1", "since": "0.2.0", "stable": true}
          ],
          "capabilities": {
            "linux-runtime-v1": {
              "hostApi": 2,
              "domains": [
                {"id": "PROOT_USERLAND", "kernel": "shared-android", "coldStartMs": 8000,
                 "features": ["EXEC", "PTY", "FILE_IO", "PKG_INSTALL"], "assets": ["rootfs", "toolchain"]},
                {"id": "QEMU_VM", "kernel": "guest-linux", "coldStartMs": 180000,
                 "features": ["EXEC", "PTY", "FILE_IO", "PKG_INSTALL", "VNC", "SNAPSHOT", "PORT_FORWARD"],
                 "assets": ["image"]}
              ],
              "arch": ["arm64"],
              "limits": {"maxMemoryMb": 2048, "maxExecTimeoutSec": 1800, "maxOutputBytes": 1048576,
                         "maxConcurrentExec": 1, "defaultQuotaGb": 8},
              "auth": {"shizuku": "ownAuth", "grantTtlSec": 300, "mediator": "PluginBroker"}
            }
          },
          "assetChannel": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/index.json"
        }
    """.trimIndent()

    private val builderManifest = """
        {
          "id": "abyss-plugin-app-builder",
          "name": "应用构建器",
          "version": "0.2.0",
          "minHost": 2,
          "minApp": "0.10.0",
          "category": "dev",
          "summary": "手机端 APK 构建：静态预检 + 三路径，依赖 Linux 插件提供的运行环境。",
          "depends": [
            {
              "pluginId": "abyss-plugin-linux",
              "capability": "linux-runtime-v1",
              "versionRange": ">=0.2.0 <1.0.0",
              "optional": false,
              "reason": "构建需要 Linux 运行环境，由 Linux 插件统一提供并持有 Shizuku 授权。"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `v1 manifest 在 v2 常量下照常解析（向后兼容）`() {
        val m = PluginManifest.parse(v1Manifest)
        assertEquals("abyss-plugin-virus-guard", m.id)
        assertEquals(1, m.minHost)
        assertTrue(m.provides.isEmpty())
        assertTrue(m.depends.isEmpty())
        assertNull(m.capabilities)
        assertNull(m.assetChannel)
        // v1 插件在 v2 宿主可加载（宿主侧判定锚点：minHost ≤ HOST_API_VERSION）
        assertTrue(m.minHost <= PluginContract.HOST_API_VERSION)
    }

    @Test
    fun `HOST_API v2 常量增量齐全`() {
        assertEquals(2, PluginContract.HOST_API_VERSION)
        assertEquals("com.abyss.tools.plugin.action.PLUGIN", PluginContract.ACTION_PLUGIN)
        assertEquals(
            "com.abyss.tools.plugin.action.LINUX_RUNTIME",
            PluginContract.ACTION_LINUX_RUNTIME,
        )
        assertEquals("com.abyss.tools.plugin.CAPABILITIES", PluginContract.META_CAPABILITIES)
        assertEquals("linux-runtime-v1", PluginContract.CAPABILITY_LINUX_RUNTIME_V1)
        assertTrue(PluginContract.CAPABILITY_PATTERN.matches("linux-runtime-v1"))
        assertTrue(!PluginContract.CAPABILITY_PATTERN.matches("linux-runtime"))
        assertEquals(300, PluginContract.GRANT_TTL_MAX_SEC)
        // 能力 id 与插件 id 的形态纪律互斥
        assertTrue(!PluginContract.ID_PATTERN.matches("linux-runtime-v1"))
    }

    @Test
    fun `Linux 插件 manifest - provides 与能力描述解析`() {
        val m = PluginManifest.parse(linuxManifest)
        assertEquals("abyss-plugin-linux", m.id)
        assertEquals(2, m.minHost)
        assertEquals(1, m.provides.size)
        val provide = m.provides.single()
        assertEquals("linux-runtime-v1", provide.capability)
        assertEquals("0.2.0", provide.since)
        assertTrue(provide.stable)
        assertTrue(m.providesLinuxRuntime)
        assertTrue(!m.dependsOnLinuxRuntime)
        assertNotNull(m.capabilities)
        val cap = m.capabilities!!.getValue("linux-runtime-v1").toString()
        assertTrue(cap.contains("PROOT_USERLAND"))
        assertTrue(cap.contains("QEMU_VM"))
        assertEquals(
            "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/index.json",
            m.assetChannel,
        )
        // v2 插件在 v1 宿主 fail-closed 判定锚点（宿主侧：minHost > HOST_API_VERSION → 拒）
        assertTrue(m.minHost == PluginContract.HOST_API_VERSION)
    }

    @Test
    fun `应用构建器 manifest - depends 解析（瘦客户端）`() {
        val m = PluginManifest.parse(builderManifest)
        assertEquals("abyss-plugin-app-builder", m.id)
        assertEquals(2, m.minHost)
        assertNull(m.shizuku) // APP-100 FR-001：瘦客户端不得声明 Shizuku
        assertEquals(1, m.depends.size)
        val dep = m.depends.single()
        assertEquals("abyss-plugin-linux", dep.pluginId)
        assertEquals("linux-runtime-v1", dep.capability)
        assertEquals(">=0.2.0 <1.0.0", dep.versionRange)
        assertTrue(!dep.optional)
        assertTrue(dep.reason.length >= 10)
        assertTrue(m.dependsOnLinuxRuntime)
        assertTrue(!m.providesLinuxRuntime)
        assertTrue(m.provides.isEmpty())
    }

    @Test
    fun `DependSpec optional=false 缺 reason 必须被拒`() {
        try {
            DependSpec(
                pluginId = "abyss-plugin-linux",
                capability = "linux-runtime-v1",
                versionRange = ">=0.2.0",
                optional = false,
                reason = "",
            )
            fail("optional=false 且 reason 为空必须被拒绝（DC-100 §3）")
        } catch (expected: IllegalArgumentException) {
        }
        // optional=true 时 reason 可空（过渡期形态）
        DependSpec(
            pluginId = "abyss-plugin-linux",
            capability = "linux-runtime-v1",
            versionRange = ">=0.2.0",
            optional = true,
            reason = "",
        )
    }

    @Test
    fun `DependSpec 形态校验 fail-closed`() {
        try {
            DependSpec(pluginId = "Not_Kebab", capability = "linux-runtime-v1")
            fail("pluginId 形态非法必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            DependSpec(pluginId = "abyss-plugin-linux", capability = "linux-runtime")
            fail("capability 形态非法必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `ProvideSpec 形态校验 fail-closed`() {
        try {
            ProvideSpec(capability = "linux-runtime") // 缺主版本号
            fail("capability 形态非法必须被拒绝（^[a-z0-9-]+-v[0-9]+$）")
        } catch (expected: IllegalArgumentException) {
        }
        ProvideSpec(capability = "linux-runtime-v2") // 主版本变化新开 id —— 合法形态
    }

    @Test
    fun `shizuku ownAuth 且短 reason 必须被拒（LIN-005 FR-001）`() {
        try {
            PluginShizukuDecl(ownAuth = true, reason = "太短")
            fail("ownAuth=true 且 reason<10 字符必须被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
        PluginShizukuDecl(ownAuth = true, reason = "PRoot 引擎需要 shell 上下文拉起（人话）。")
        PluginShizukuDecl(ownAuth = false, reason = "")
    }

    @Test
    fun `序列化往返保持 v2 字段（本地账本同构）`() {
        val m = PluginManifest.parse(linuxManifest)
        val out = PluginManifest.serialize(m)
        val back = PluginManifest.parse(out)
        assertEquals(m, back)
        assertEquals(m.provides, back.provides)
        assertEquals(m.depends, back.depends)
        assertEquals(m.capabilities, back.capabilities)
        assertEquals(m.assetChannel, back.assetChannel)
    }
}
