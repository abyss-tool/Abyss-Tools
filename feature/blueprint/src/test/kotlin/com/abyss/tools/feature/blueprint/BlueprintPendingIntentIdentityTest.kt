package com.abyss.tools.feature.blueprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * B-13 回归：蓝图调度 PendingIntent 的身份隔离。
 *
 * 旧形态 intent 只带 extras（不参与 Intent.filterEquals）+ requestCode
 * = blueprintId.hashCode —— 哈希碰撞的两个蓝图共用同一 PendingIntent，
 * FLAG_UPDATE_CURRENT 相互覆盖，其中一个永不触发。修复后 intent 带
 * 单射 data URI（abyss://blueprint/<id>），本测试锁定该单射契约
 *（含经典碰撞对 "Aa"/"BB" —— 两者 hashCode 相等，威胁真实存在）。
 */
class BlueprintPendingIntentIdentityTest {

    @Test
    fun `data uri 前缀形态与唯一性契约`() {
        assertEquals("abyss://blueprint/bp-001", ScheduleAlarmReceiver.blueprintDataUriString("bp-001"))
        assertEquals("abyss://blueprint/每日清理", ScheduleAlarmReceiver.blueprintDataUriString("每日清理"))
    }

    @Test
    fun `hashCode 碰撞对也被 data URI 隔离（B-13 核心用例）`() {
        // 威胁锚：经典碰撞对 —— 两字符串 hashCode 相等
        assertEquals("Aa".hashCode(), "BB".hashCode())
        // data URI 单射：碰撞对的 URI 必不相等 → Intent.filterEquals
        // 必不相等 → PendingIntent 天然隔离
        assertNotEquals(
            ScheduleAlarmReceiver.blueprintDataUriString("Aa"),
            ScheduleAlarmReceiver.blueprintDataUriString("BB"),
        )
    }

    @Test
    fun `任意两个不同蓝图的 data URI 必不相等`() {
        val ids = listOf("a", "b", "bp-1", "bp-2", "每日清理", "深链清理", "x", "Aa", "BB")
        val uris = ids.map { ScheduleAlarmReceiver.blueprintDataUriString(it) }
        assertEquals("data URI 必须单射（不同蓝图不同 URI）", ids.size, uris.toSet().size)
    }
}
