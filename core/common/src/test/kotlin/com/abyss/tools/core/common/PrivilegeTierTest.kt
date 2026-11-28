package com.abyss.tools.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARC-011 档位判定与能力集合语义（纯 JVM）。
 *
 * 重点断言两处「不许倒挂」：
 *  - ultra 缺无障碍仍是 ultra（ultra 不隐含无障碍，ARC-011 FR-003）；
 *  - max 缺 INSTALL_UNKNOWN 仍是 max（特殊授权不升档也不降档）。
 */
class PrivilegeTierTest {

    private fun snapshot(vararg caps: Capability) = PrivilegeSnapshot(capabilities = caps.toSet())

    @Test
    fun `空能力集为普通用户`() {
        assertEquals(PrivilegeTier.BASIC, snapshot().tier)
    }

    @Test
    fun `仅自身组件与特殊授权仍为普通用户`() {
        val s = snapshot(
            Capability.SELF_BRANDING,
            Capability.INSTALL_UNKNOWN,
            Capability.OVERLAY,
            Capability.APP_OBSERVE,
        )
        assertEquals(PrivilegeTier.BASIC, s.tier)
    }

    @Test
    fun `仅无障碍为进阶用户`() {
        assertEquals(PrivilegeTier.ADVANCED, snapshot(Capability.ACCESSIBILITY).tier)
    }

    @Test
    fun `仅 Shizuku 为高级用户`() {
        assertEquals(PrivilegeTier.SENIOR, snapshot(Capability.SHIZUKU_SHELL).tier)
    }

    @Test
    fun `Shizuku 加无障碍为 abyss-max 且不要求安装未知应用`() {
        val s = snapshot(Capability.SHIZUKU_SHELL, Capability.ACCESSIBILITY)
        assertEquals(PrivilegeTier.ABYSS_MAX, s.tier)
        assertFalse("max 不要求 INSTALL_UNKNOWN", s.missing(setOf(Capability.SHIZUKU_SHELL, Capability.ACCESSIBILITY)).isNotEmpty())
    }

    @Test
    fun `ultra 三项齐全为 abyss-ultra 即使缺无障碍`() {
        val s = snapshot(
            Capability.SHIZUKU_SHELL,
            Capability.DHIZUKU_ACTIVE,
            Capability.DEVICE_ADMIN_ACTIVE,
        )
        assertEquals(PrivilegeTier.ABYSS_ULTRA, s.tier)
        assertFalse(s.has(Capability.ACCESSIBILITY))
    }

    @Test
    fun `ultra 缺设备管理员回落到 Shizuku 档而非 ultra`() {
        val s = snapshot(Capability.SHIZUKU_SHELL, Capability.DHIZUKU_ACTIVE)
        assertEquals(PrivilegeTier.SENIOR, s.tier)
    }

    @Test
    fun `Shizuku root 不单独升档`() {
        assertEquals(PrivilegeTier.SENIOR, snapshot(Capability.SHIZUKU_SHELL, Capability.SHIZUKU_ROOT).tier)
    }

    @Test
    fun `allows 是超集判定且档位不参与放行`() {
        // SENIOR 用户 + 安装未知应用：按能力集合可以打开 app-builder（required = SH + INST），
        // 即使 app-builder 的推荐档位是 abyss-max。
        val s = snapshot(Capability.SHIZUKU_SHELL, Capability.INSTALL_UNKNOWN)
        assertEquals(PrivilegeTier.SENIOR, s.tier)
        assertTrue(s.allows(setOf(Capability.SHIZUKU_SHELL, Capability.INSTALL_UNKNOWN)))
        assertFalse(s.allows(setOf(Capability.SHIZUKU_SHELL, Capability.ACCESSIBILITY)))
    }

    @Test
    fun `missing 按引导优先级排序 Shizuku 优先`() {
        val missing = snapshot().missingOrdered(
            setOf(Capability.DEVICE_ADMIN_ACTIVE, Capability.ACCESSIBILITY, Capability.SHIZUKU_SHELL),
        )
        assertEquals(Capability.SHIZUKU_SHELL, missing.first())
        assertEquals(3, missing.size)
    }

    @Test
    fun `minimumTier 把 required 当能力集合算档位`() {
        assertEquals(
            PrivilegeTier.ABYSS_MAX,
            TierCalculator.minimumTier(setOf(Capability.SHIZUKU_SHELL, Capability.ACCESSIBILITY)),
        )
        assertEquals(
            PrivilegeTier.ABYSS_ULTRA,
            TierCalculator.minimumTier(setOf(Capability.SHIZUKU_SHELL, Capability.DHIZUKU_ACTIVE, Capability.DEVICE_ADMIN_ACTIVE)),
        )
        assertEquals(PrivilegeTier.SENIOR, TierCalculator.minimumTier(setOf(Capability.SHIZUKU_SHELL, Capability.INSTALL_UNKNOWN)))
    }

    @Test
    fun `nextTier 沿阶梯推进且最高档为空`() {
        assertEquals(PrivilegeTier.ADVANCED, TierCalculator.nextTier(PrivilegeTier.BASIC))
        assertEquals(PrivilegeTier.SENIOR, TierCalculator.nextTier(PrivilegeTier.ADVANCED))
        assertEquals(PrivilegeTier.ABYSS_MAX, TierCalculator.nextTier(PrivilegeTier.SENIOR))
        assertEquals(PrivilegeTier.ABYSS_ULTRA, TierCalculator.nextTier(PrivilegeTier.ABYSS_MAX))
        assertNull(TierCalculator.nextTier(PrivilegeTier.ABYSS_ULTRA))
    }

    @Test
    fun `toNextTier 给出到下一档的缺失能力`() {
        val (next, missing) = requireNotNull(snapshot(Capability.ACCESSIBILITY).toNextTier)
        assertEquals(PrivilegeTier.SENIOR, next)
        assertEquals(setOf(Capability.SHIZUKU_SHELL), missing)
        assertNull(snapshot(Capability.SHIZUKU_SHELL, Capability.DHIZUKU_ACTIVE, Capability.DEVICE_ADMIN_ACTIVE).toNextTier)
    }

    @Test
    fun `requirements 与 compute 互为逆映射`() {
        TierCalculator.requirements.forEach { (tier, caps) ->
            assertEquals("requirements[$tier] 应恰好达成该档", tier, TierCalculator.compute(caps))
        }
    }

    @Test
    fun `lockedLabel 按优先级拼接短标签`() {
        assertEquals(
            "需 Shizuku、无障碍",
            lockedLabel(setOf(Capability.ACCESSIBILITY, Capability.SHIZUKU_SHELL)),
        )
        assertEquals("", lockedLabel(emptySet()))
    }
}

/** FEAT-040 伪装五级模式的生效真值（纯 JVM）。 */
class DisguiseModeEffectTest {

    private fun snapshot(vararg caps: Capability) = PrivilegeSnapshot(capabilities = caps.toSet())

    @Test
    fun `空能力集只有 low 生效且最高生效为 low`() {
        val s = snapshot()
        assertEquals(ModeState.EFFECTIVE, DisguiseModes.effect(DisguiseMode.LOW, s).state)
        assertEquals(ModeState.UNAVAILABLE, DisguiseModes.effect(DisguiseMode.HIGH, s).state)
        assertEquals(DisguiseMode.LOW, DisguiseModes.highestEffective(s))
    }

    @Test
    fun `仅 Shizuku 时 high 生效 max 不可用且缺失无障碍`() {
        val s = snapshot(Capability.SHIZUKU_SHELL)
        assertEquals(ModeState.EFFECTIVE, DisguiseModes.effect(DisguiseMode.HIGH, s).state)
        val max = DisguiseModes.effect(DisguiseMode.MAX, s)
        assertEquals(ModeState.UNAVAILABLE, max.state)
        assertEquals(setOf(Capability.ACCESSIBILITY), max.missing)
        assertEquals(DisguiseMode.HIGH, DisguiseModes.highestEffective(s))
    }

    @Test
    fun `max 能力齐全为 EFFECTIVE 且 note 标明档案仅导出`() {
        val s = snapshot(Capability.SHIZUKU_SHELL, Capability.ACCESSIBILITY)
        val max = DisguiseModes.effect(DisguiseMode.MAX, s)
        assertEquals(ModeState.EFFECTIVE, max.state)
        assertTrue(max.missing.isEmpty())
        assertTrue(max.note.contains("仅导出"))
        assertEquals(DisguiseMode.MAX, DisguiseModes.highestEffective(s))
    }

    @Test
    fun `ultra 三项齐全仍为 PROFILE_ONLY 待设备策略面板`() {
        val s = snapshot(Capability.SHIZUKU_SHELL, Capability.DHIZUKU_ACTIVE, Capability.DEVICE_ADMIN_ACTIVE)
        val ultra = DisguiseModes.effect(DisguiseMode.ULTRA, s)
        assertEquals(ModeState.PROFILE_ONLY, ultra.state)
        assertTrue(ultra.missing.isEmpty())
        assertTrue(ultra.note.contains("待落地"))
        // 最高「生效」模式不会停在仅档案的 ultra
        assertEquals(DisguiseMode.HIGH, DisguiseModes.highestEffective(s))
    }

    @Test
    fun `ultra 缺 Dhizuku 与设备管理员时如实列出缺失`() {
        val s = snapshot(Capability.SHIZUKU_SHELL, Capability.ACCESSIBILITY)
        val ultra = DisguiseModes.effect(DisguiseMode.ULTRA, s)
        assertEquals(ModeState.UNAVAILABLE, ultra.state)
        assertEquals(setOf(Capability.DHIZUKU_ACTIVE, Capability.DEVICE_ADMIN_ACTIVE), ultra.missing)
        assertEquals(DisguiseMode.MAX, DisguiseModes.highestEffective(s))
    }

    @Test
    fun `偏好解析坏值 fail-closed 为 null`() {
        assertEquals(DisguiseMode.HIGH, DisguiseModes.fromNameOrNull("HIGH"))
        assertNull(DisguiseModes.fromNameOrNull("bogus"))
        assertNull(DisguiseModes.fromNameOrNull(null))
    }
}
