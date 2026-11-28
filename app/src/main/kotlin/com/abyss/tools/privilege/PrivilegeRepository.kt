package com.abyss.tools.privilege

import com.abyss.tools.core.common.PrivilegeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * NB-29（深挖第三轮）：探测下沉 Dispatchers.Default —— 旧实现在主线程同步
 * 执行全部探测（含 Dhizuku.init 的跨应用 ContentProvider 调用与约 8 次
 * PackageManager 查询），冷启动 / 每次 onResume 都可能卡顿甚至 ANR。
 * 现在探测在后台执行、完成后写 StateFlow（赋值线程安全，Compose 按值收敛）；
 * 首帧到首份探测结果之间的短暂空窗是 fail-closed 的（门禁页 / 锁定态），
 * 不会出现「假装能力可用」的假态。
 *
 * 高危动作执行前仍需二次校验，**不信任**本快照。
 */
class PrivilegeRepository(
    private val probe: PrivilegeProbe,
) {
    private val _snapshot = MutableStateFlow(PrivilegeSnapshot())

    /** 探测协程域：SupervisorJob —— 单次探测失败不杀死域，后续 refresh 照常。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** init 即触发首份探测（后台）：结果经 StateFlow 扇出到首帧之后的重组。 */
    init {
        refresh()
    }

    val snapshot: StateFlow<PrivilegeSnapshot> = _snapshot.asStateFlow()

    /** 最近一份快照（探测在途时为上一次结果；进程刚起为空快照 fail-closed）。 */
    val current: PrivilegeSnapshot get() = _snapshot.value

    fun refresh() {
        scope.launch {
            val probed = runCatching { probe.snapshot() }.getOrElse { e ->
                // 探测整体失败 → 空能力集 + 人话原因，绝不假装任何能力可用。
                PrivilegeSnapshot(
                    capabilities = emptySet(),
                    environmentNotes = listOf("权限探测失败：${e.message ?: e.javaClass.simpleName}"),
                )
            }
            _snapshot.value = probed
        }
    }
}
