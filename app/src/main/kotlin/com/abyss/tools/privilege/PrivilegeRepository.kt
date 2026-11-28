package com.abyss.tools.privilege

import com.abyss.tools.core.common.PrivilegeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 权限快照单一事实源（ARC-011 FR-008）。
 *
 * StateFlow 去重：[PrivilegeSnapshot] 为 data class，相同快照不触发重组。
 * 刷新时机（全部不跨会话缓存）：
 *  - Shizuku binder 生死 / 授权结果（AbyssApplication 注册监听）；
 *  - `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` ContentObserver；
 *  - `DeviceAdminReceiver.onEnabled / onDisabled`；
 *  - `Activity.onResume`（安装未知应用 / 悬浮窗 / Dhizuku 授权页返回）。
 *
 * 探测在调用线程同步执行（均为只读系统查询，毫秒级）；需要离线时调用方
 * 自行切 IO。高危动作执行前仍需二次校验，**不信任**本快照。
 */
class PrivilegeRepository(
    private val probe: PrivilegeProbe,
) {
    private val _snapshot = MutableStateFlow(PrivilegeSnapshot())

    /** 初始即探测一次：首帧不展示「空能力」假态。 */
    init {
        refresh()
    }

    val snapshot: StateFlow<PrivilegeSnapshot> = _snapshot.asStateFlow()

    val current: PrivilegeSnapshot get() = _snapshot.value

    fun refresh() {
        _snapshot.value = runCatching { probe.snapshot() }.getOrElse { e ->
            // 探测整体失败 → 空能力集 + 人话原因，绝不假装任何能力可用。
            PrivilegeSnapshot(
                capabilities = emptySet(),
                environmentNotes = listOf("权限探测失败：${e.message ?: e.javaClass.simpleName}"),
            )
        }
    }
}
