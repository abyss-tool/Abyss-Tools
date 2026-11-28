package com.abyss.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import com.abyss.tools.core.common.PrivilegeSnapshot
import com.abyss.tools.privilege.PrivilegeRepository

/**
 * 权限快照 → Compose 状态（ARC-011 唯一桥接点）。
 *
 * `PrivilegeRepository` 是 StateFlow 单一事实源（app 模块，不依赖 Compose 之外
 * 的任何 UI 框架）；Compose 侧只在这里 collect，data class 相等即去重，
 * 不会因无关刷新触发重组。刷新时机由 Application / Receiver / Activity.onResume
 * 推动，本函数不做任何探测。
 */
@Composable
fun rememberPrivilegeSnapshot(repository: PrivilegeRepository): State<PrivilegeSnapshot> =
    repository.snapshot.collectAsState()
