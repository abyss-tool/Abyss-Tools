package com.abyss.tools.feature.netaudit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NetStatsParser 契约测试：夹具全部以 trimIndent() 内嵌本文件（不落资源
 * 文件）—— netstats 的 dump 格式没有稳定契约可镜像（跨版本 / OEM 漂移，
 * 这正是解析器选择行级 token 归因的原因），夹具按 Android 8~13 实测形态
 * 手写；「未知归属不进榜」「Xt 分区闸」「重复桶如实累加」这些**行为**才是
 * 本模块的契约，由断言钉死。
 */
class NetStatsParserTest {

    // ── Android 11-ish 常规形态：Dev 在前 Xt 在后 ─────────────────────

    private val android11Dump = """
        Dev stats:
          ident={type=ALL, subType=COMBINED, tag=0, rtTime=0} uid=0 set=ALL tag=0x0
            networkMobile [1624523568000-1627115568000] rxBytes=1234 rxPackets=10 txBytes=567 txPackets=5
          ident={type=ALL, subType=COMBINED, tag=0, rtTime=0} uid=10163 set=ALL tag=0x0
            networkAll [1624523568000-1627115568000] rxBytes=999999 rxPackets=800 txBytes=888888 txPackets=700
        Xt stats:
          ident={type=ALL, subType=COMBINED, tag=0, rtTime=0} uid=0 set=ALL tag=0x0
            networkAll [1624523568000-1627115568000] rxBytes=50 rxPackets=1 txBytes=60 txPackets=1
    """.trimIndent()

    @Test
    fun `Dev 分区优先，Xt 在 Dev 之后被整段排除`() {
        val snapshot = NetStatsParser.parse(android11Dump)
        // Xt 的 50/60 绝不混进总量（双记账止血带）
        assertEquals(1_001_233L, snapshot.totalRx) // 1234 + 999999
        assertEquals(889_455L, snapshot.totalTx) // 567 + 888888
        assertEquals(2, snapshot.parsedLines) // Dev 两条桶行；Xt 行不吃
    }

    @Test
    fun `uid 维度聚合与降序排序`() {
        val snapshot = NetStatsParser.parse(android11Dump)
        assertEquals(listOf(10163, 0), snapshot.perUid.map { it.uid }) // 总量大者在前
        assertEquals(999_999L, snapshot.perUid[0].rxBytes)
        assertEquals(888_888L, snapshot.perUid[0].txBytes)
        assertEquals(1_888_887L, snapshot.perUid[0].total)
        assertEquals(1_234L, snapshot.perUid[1].rxBytes)
        assertEquals(567L, snapshot.perUid[1].txBytes)
    }

    @Test
    fun `总量并列时按 uid 升序（确定性排序）`() {
        val dump = """
            Dev stats:
              ident={type=ALL, subType=COMBINED, tag=0} uid=7 set=ALL tag=0x0
                networkAll [0-100] rxBytes=50 txBytes=50
              ident={type=ALL, subType=COMBINED, tag=0} uid=3 set=ALL tag=0x0
                networkAll [0-100] rxBytes=100 txBytes=0
        """.trimIndent()
        val snapshot = NetStatsParser.parse(dump)
        assertEquals(listOf(3, 7), snapshot.perUid.map { it.uid }) // 同为 100，uid 小者在前
        assertEquals(150L, snapshot.totalRx)
        assertEquals(50L, snapshot.totalTx)
    }

    // ── 分区闸的另一半：只有 Xt（无 Dev）时照常累计 ───────────────────

    @Test
    fun `仅 Xt 形态（无 Dev 分区）时照常累计`() {
        val dump = """
            Xt stats:
              ident={type=ALL, subType=COMBINED, tag=0, rtTime=0} uid=1000 set=ALL tag=0x0
                networkAll [1636070400000-1638316800000] rxBytes=10 rxPackets=1 txBytes=20 txPackets=2
        """.trimIndent()
        val snapshot = NetStatsParser.parse(dump)
        assertEquals(10L, snapshot.totalRx)
        assertEquals(20L, snapshot.totalTx)
        assertEquals(1, snapshot.parsedLines)
        assertEquals(listOf(1000), snapshot.perUid.map { it.uid })
    }

    // ── 未知归属（uid -1 桶）──────────────────────────────────────

    @Test
    fun `token 行先于任何 uid 行则落未知桶（不进榜但计入总量）`() {
        val dump = """
            Dev stats:
                networkAll [0-999] rxBytes=777 txBytes=333
              ident={type=ALL, subType=COMBINED, tag=0} uid=1021 set=ALL tag=0x0
                networkAll [0-999] rxBytes=100 txBytes=50
        """.trimIndent()
        val snapshot = NetStatsParser.parse(dump)
        // 未知桶不进榜（绝不能把 777/333 挂给 1021），但总量如实保留
        assertEquals(listOf(1021), snapshot.perUid.map { it.uid })
        assertEquals(100L, snapshot.perUid[0].rxBytes)
        assertEquals(50L, snapshot.perUid[0].txBytes)
        assertEquals(877L, snapshot.totalRx) // 777（未知）+ 100
        assertEquals(383L, snapshot.totalTx) // 333（未知）+ 50
        assertEquals(2, snapshot.parsedLines)
    }

    @Test
    fun `uid=-1 设备级桶绝不张冠李戴到上一个应用`() {
        val dump = """
            Dev stats:
              ident={type=ALL, subType=COMBINED, tag=0} uid=10163 set=ALL tag=0x0
                networkAll [0-100] rxBytes=10 txBytes=5
              ident={type=ALL, subType=COMBINED, tag=0} uid=-1 set=ALL tag=0x0
                networkAll [0-100] rxBytes=1000 txBytes=2000
        """.trimIndent()
        val snapshot = NetStatsParser.parse(dump)
        assertEquals(listOf(10163), snapshot.perUid.map { it.uid })
        assertEquals(10L, snapshot.perUid[0].rxBytes) // 1000 没被挂给 10163
        assertEquals(5L, snapshot.perUid[0].txBytes)
        assertEquals(1_010L, snapshot.totalRx) // 设备级字节留在总量（未知归属）
        assertEquals(2_005L, snapshot.totalTx)
        assertTrue(snapshot.perUid.all { it.uid >= 0 })
    }

    // ── 重复周期桶：如实累加（上限估计口径，页面同步声明）────────────

    @Test
    fun `重复周期桶如实累加（上限估计口径，绝不静默去重）`() {
        val dump = """
            Dev stats:
              ident={type=ALL, subType=COMBINED, tag=0} uid=10163 set=ALL tag=0x0
                networkAll [t1-t2] rxBytes=100 txBytes=40
              ident={type=ALL, subType=COMBINED, tag=0} uid=10163 set=ALL tag=0x0
                networkAll [t1-t2] rxBytes=100 txBytes=40
            Xt stats:
              ident={type=ALL, subType=COMBINED, tag=0} uid=10163 set=ALL tag=0x0
                networkAll [t1-t2] rxBytes=100 txBytes=40
        """.trimIndent()
        val snapshot = NetStatsParser.parse(dump)
        // Dev 区两条同名桶相加（200/80）；Xt 区的同名桶被分区闸排除
        assertEquals(200L, snapshot.totalRx)
        assertEquals(80L, snapshot.totalTx)
        assertEquals(1, snapshot.perUid.size)
        assertEquals(280L, snapshot.perUid[0].total)
        assertEquals(2, snapshot.parsedLines)
    }

    // ── 同行 uid 与多 token ────────────────────────────────────────

    @Test
    fun `同行 uid 与多个字节 token 全部归因`() {
        val dump = """
            Dev stats:
              ident={type=ALL, subType=COMBINED, tag=0} uid=5 set=ALL tag=0x0 rxBytes=1 rxBytes=2 txBytes=3 txBytes=4
        """.trimIndent()
        val snapshot = NetStatsParser.parse(dump)
        assertEquals(3L, snapshot.totalRx) // 1 + 2
        assertEquals(7L, snapshot.totalTx) // 3 + 4
        assertEquals(1, snapshot.parsedLines)
        assertEquals(listOf(5), snapshot.perUid.map { it.uid })
    }

    @Test
    fun `CRLF 输出容忍`() {
        val dump = "Dev stats:\r\n" +
            "  ident={type=ALL} uid=42 set=ALL tag=0x0\r\n" +
            "    networkAll rxBytes=99 txBytes=88\r\n"
        val snapshot = NetStatsParser.parse(dump)
        assertEquals(99L, snapshot.totalRx)
        assertEquals(88L, snapshot.totalTx)
        assertEquals(1, snapshot.parsedLines)
        assertEquals(listOf(42), snapshot.perUid.map { it.uid })
    }

    // ── 空 / 垃圾输入：fail-closed，零值快照，绝不抛 ─────────────────

    @Test
    fun `空输入返回零值快照`() {
        val snapshot = NetStatsParser.parse("")
        assertEquals(0L, snapshot.totalRx)
        assertEquals(0L, snapshot.totalTx)
        assertTrue(snapshot.perUid.isEmpty())
        assertEquals(0, snapshot.parsedLines)
    }

    @Test
    fun `空白输入返回零值快照`() {
        val snapshot = NetStatsParser.parse("\n \n\t\n")
        assertEquals(0L, snapshot.totalRx)
        assertEquals(0L, snapshot.totalTx)
        assertTrue(snapshot.perUid.isEmpty())
        assertEquals(0, snapshot.parsedLines)
    }

    @Test
    fun `垃圾输入不解析不抛异常`() {
        val dump = listOf(
            "随机的垃圾输出 no tokens here",
            "rxBytes=notanumber txBytes=-42", // 非数字 / 负数 → 不认
            "uid=-100 something",
            "完全无关 ident={type=WIFI}",
        ).joinToString(separator = "\n")
        val snapshot = NetStatsParser.parse(dump)
        assertEquals(0L, snapshot.totalRx)
        assertEquals(0L, snapshot.totalTx)
        assertTrue(snapshot.perUid.isEmpty())
        assertEquals(0, snapshot.parsedLines)
    }

    // ── Bytes.format 边界（1000 进制，一位小数）─────────────────────

    @Test
    fun `字节格式化边界`() {
        assertEquals("0 B", NetStatsParser.Bytes.format(0L))
        assertEquals("512 B", NetStatsParser.Bytes.format(512L))
        assertEquals("1.5 KB", NetStatsParser.Bytes.format(1_536L))
        assertEquals("1.0 MB", NetStatsParser.Bytes.format(1_000_000L))
        assertEquals("1.5 GB", NetStatsParser.Bytes.format(1_500_000_000L))
    }

    @Test
    fun `进位阈值与负值防御`() {
        // 四舍五入到 1000.0 的值升一位单位（防 "1000.0 KB" 丑值）
        assertEquals("999.9 KB", NetStatsParser.Bytes.format(999_949L))
        assertEquals("1.0 MB", NetStatsParser.Bytes.format(999_999L))
        // 负值防御性按 0 呈现（解析产物不可能为负，出现即调用方出错）
        assertEquals("0 B", NetStatsParser.Bytes.format(-1L))
    }
}
