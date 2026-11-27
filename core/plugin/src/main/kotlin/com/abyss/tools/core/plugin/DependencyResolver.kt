package com.abyss.tools.core.plugin

import com.abyss.tools.core.common.SemVer
import com.abyss.tools.core.common.SemVerRange
import com.abyss.tools.plugin.api.PluginContract

/**
 * 依赖解析器（ARC-102 §3 —— HOST_API v2 的核心新增职责，纯 JVM 可单测）。
 *
 * 输入：plugins.json 全量条目（[PluginIndex]）+ 本地安装账本
 * （installed: 插件 id → 当前版本）；输出：安装/启用拓扑序
 * （[Resolution.InstallPlan]）或人话拒绝原因（[Resolution.Rejection]）。
 *
 * ```
 * resolve(targetId):
 *   1. 收集 target 的 depends[]，逐条校验：
 *      a. capability 必须命中当前注册能力（linux-runtime-v1）
 *      b. pluginId 存在于索引且未被归档（索引不含归档条目 → 缺失即拒）
 *      c. versionRange 用 SemVer 区间解析（禁止 split('.')）
 *      d. 索引条目的 provides[].capability 含该能力
 *   2. 建 DAG（pluginId → 被依赖 pluginId），环检测 → E_DEPENDENCY_CYCLE
 *   3. 拓扑排序 → installOrder = [abyss-plugin-linux, abyss-plugin-app-builder]
 *   4. 对 installOrder 每项校验 minHost ≤ HOST_API、minApp ≤ 宿主版本、
 *      repo/patchChannel 过 ChannelPolicy
 *   5. 任一项不满足 → 整批拒绝 + 人话原因，不安装半成品
 * ```
 *
 * 场景对照（ARC-102 §3 表）：
 *  - Linux 插件未安装 → 计划包含之（一键「一并安装」按拓扑序装）；
 *  - 已安装但版本 < minVersion → E_DEP_VERSION（引导升级）；
 *  - 循环依赖 → E_DEPENDENCY_CYCLE（索引 CI 亦拦截，运行时兜底）；
 *  - 第三方伪造同名插件 → 索引侧被拒收 / 绑定侧 E_SIGNATURE_MISMATCH
 *   （[CapabilityBinder] 四重校验）。
 */
class DependencyResolver(
    private val hostApiVersion: Int = PluginContract.HOST_API_VERSION,
    private val hostAppVersion: SemVer,
) {

    sealed interface Resolution {

        /** 依赖解析通过：拓扑安装序 + 待装/待升级清单。 */
        data class InstallPlan(
            val targetId: String,
            /** 拓扑序（被依赖方在前；目标插件收尾）。 */
            val installOrder: List<PluginIndexEntry>,
            /** 尚未安装的依赖（「一并安装」清单）。 */
            val missingDeps: List<PluginIndexEntry>,
            /** 已安装依赖的版本快照（升级引导 / 详情页展示用）。 */
            val depVersions: Map<String, SemVer>,
        ) : Resolution

        /** 整批拒绝（fail-closed：不安装半成品）。code ∈ DC-102 错误码表。 */
        data class Rejection(
            val code: String,
            /** 人话原因（UI 直出；不暴露错误码原文，APP-100 FR-007）。 */
            val humanReason: String,
        ) : Resolution
    }

    fun resolve(targetId: String, index: PluginIndex, installed: Map<String, SemVer>): Resolution {
        val target = index.plugins.firstOrNull { it.id == targetId }
            ?: return reject("E_DEP_MISSING", "插件 $targetId 不在插件中心索引（可能已归档）—— 无法解析依赖")

        // 步骤 4 的目标侧预检：宿主版本门槛（v1 宿主加载 minHost=2 → fail-closed）
        hostGate(target)?.let { return it }

        val order = mutableListOf<PluginIndexEntry>()
        val visiting = linkedSetOf<String>() // DFS 进行中（环检测）
        val done = mutableSetOf<String>()    // 已完成（拓扑去重）
        val depVersions = mutableMapOf<String, SemVer>()

        fun visit(entry: PluginIndexEntry, chain: List<String>): Resolution? {
            if (entry.id in done) return null
            if (entry.id in visiting) {
                return reject(
                    "E_DEPENDENCY_CYCLE",
                    "插件依赖成环：${(chain + entry.id).joinToString(" → ")} —— 请反馈插件依赖配置异常",
                )
            }
            visiting += entry.id
            for (dep in entry.depends) {
                if (dep.optional) {
                    // optional 仅过渡期形态（ADR-100 §5）；M2 后必须收口 fail-closed
                    continue
                }
                // a. 能力必须是当前注册能力（宿主侧白名单，防未注册能力透传）
                if (dep.capability != PluginContract.CAPABILITY_LINUX_RUNTIME_V1) {
                    return reject(
                        "E_CAPABILITY_MISMATCH",
                        "依赖能力未注册：${dep.capability}（当前唯一注册能力 linux-runtime-v1）",
                    )
                }
                // b. 提供方必须在索引（索引不含归档条目 → 缺失即「未安装或已归档」）
                val provider = index.plugins.firstOrNull { it.id == dep.pluginId }
                    ?: return reject(
                        "E_DEP_MISSING",
                        "${entry.name}需要 ${dep.pluginId}（提供运行环境），该插件未安装或已归档 —— 请先安装",
                    )
                // d. 提供方 provides[].capability 必须命中
                if (provider.provides.none { it.capability == dep.capability }) {
                    return reject(
                        "E_CAPABILITY_MISMATCH",
                        "${dep.pluginId} 未提供 ${dep.capability} 能力（能力版本不兼容）",
                    )
                }
                // 提供方宿主门槛
                hostGate(provider)?.let { return it }
                // c. 区间解析（统一 SemVer 比较，禁止 split('.')）
                val range = SemVerRange.parse(dep.versionRange)
                    ?: return reject(
                        "E_CAPABILITY_MISMATCH",
                        "依赖版本区间非法：${dep.versionRange}（应为 SemVer 区间形态，如 >=0.2.0 <1.0.0）",
                    )
                val installedVersion = installed[dep.pluginId]
                if (installedVersion == null) {
                    // 未安装 → 并入「一并安装」清单（拓扑序先装）
                    visit(provider, chain + entry.id)?.let { return it }
                } else {
                    depVersions[dep.pluginId] = installedVersion
                    if (!range.matches(installedVersion)) {
                        return reject(
                            "E_DEP_VERSION",
                            "${provider.name}当前版本 $installedVersion 不满足 $range —— 请先升级依赖插件",
                        )
                    }
                    // 已安装且版本满足：不重装，但继续展开其自身依赖（通用性）
                    visit(provider, chain + entry.id)?.let { return it }
                }
            }
            // 后序插入：被依赖方在前（拓扑序）
            order += entry
            visiting -= entry.id
            done += entry.id
            return null
        }

        visit(target, emptyList())?.let { return it }

        // 步骤 4 的通道策略复核（整批：坏条目不进入安装序）
        for (entry in order) {
            ChannelPolicy.validateEntry(entry)?.let { reason ->
                return reject("E_CAPABILITY_MISMATCH", "依赖插件渠道不合规（$reason）—— 已整批拒收")
            }
        }

        val missing = order.filter { it.id !in installed }
        return Resolution.InstallPlan(
            targetId = targetId,
            installOrder = order.toList(),
            missingDeps = missing,
            depVersions = depVersions,
        )
    }

    /** minHost ≤ HOST_API 且 minApp ≤ 宿主版本（PLG-001 FR-001 fail-closed）。 */
    private fun hostGate(entry: PluginIndexEntry): Resolution.Rejection? {
        if (entry.minHost > hostApiVersion) {
            return reject(
                "E_HOST_API_TOO_LOW",
                "请升级深渊工具主壳：${entry.name} 需要 HOST_API ≥ ${entry.minHost}（当前 $hostApiVersion）",
            )
        }
        // NB-5（fail-closed 收口）：minApp 解析失败必须拒绝 —— 静默跳过门槛
        // 等于坏值（如带 v 前缀）反而「永过低门槛」，违反 PLG-001 FR-001。
        val minApp = SemVer.parse(entry.minApp)
            ?: return reject(
                "E_BAD_MIN_APP",
                "插件 ${entry.name} 的 minApp 版本号非法（${entry.minApp}）—— 通道索引被污染，已拒绝解析",
            )
        if (hostAppVersion < minApp) {
            return reject(
                "E_HOST_API_TOO_LOW",
                "请升级深渊工具到 ${entry.minApp} 以上：${entry.name} 依赖新版宿主能力",
            )
        }
        return null
    }

    private fun reject(code: String, human: String): Resolution.Rejection = Resolution.Rejection(code, human)
}
