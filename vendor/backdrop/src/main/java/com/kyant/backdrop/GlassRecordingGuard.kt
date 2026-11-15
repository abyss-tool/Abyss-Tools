package com.kyant.backdrop

import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 玻璃采样成环断路器（本仓补丁 v3，2026-11-15）—— 显示列表深度有界的
 * 通用硬保证，不依赖任何特定调用形态。
 *
 * 背景（bugreport PLE110 2026-11-15，存活 472s 后 RenderThread 原生栈溢出
 * SIGSEGV，prepareListAndChildren ↔ prepareTreeImpl 512 帧交替）：v1.1 的
 * isRecordingSelf 只防「玻璃件采样**正在录制的同一个** backdrop」——
 * 采样源录制期间同一 backdrop 的消费方跳过。但显示列表无界增长还存在
 * 更隐蔽的家族：**跨 backdrop 链式成环**。典型形态 A→B→C→A：
 *
 * ```
 * 玻璃 A 采样源 S，同时把自身视觉导出为源 E（exportedBackdrop）；
 * 玻璃 B 采样源 E，且 B 位于 S 的子树内 ——
 *   S 的录制包含 B 的层 ⊃ E 的层 ⊃ A 的层 ⊃ S 上一帧的层
 *   → 显示列表深度每帧 +4，无界增长 → prepareTree 栈溢出。
 * ```
 *
 * B 的守卫检查的是 E.isRecordingSelf（false），v1.1 防线对此完全失明。
 * 上游用户还可能以任意组合接线出同构的环 —— 逐形态打补丁不可穷尽，
 * 必须在**采样这个唯一咽喉**上做通用判定。
 *
 * 语义（两层防线）：
 *  1. **帧内防线**：维护「正在录制的采样源栈」。采样 X 时若 X 自身正在
 *     被录制（栈内出现）→ 拒绝 —— 等价于 isRecordingSelf 的推广，覆盖
 *     多源交叉录制。
 *  2. **跨帧防线**：每个 LayerBackdrop 持久维护 `transitiveSources`
 *     （其层内容**可传递地**嵌入了哪些采样源，含经玻璃层中转的）。
 *     采样 X 落入源 O 的录制时，若 O ∈ X.transitiveSources（X 的层内容
 *     已含 O 的层 —— 画进来即 O@本帧 ⊃ … ⊃ O@前帧，成环且每帧加深）
 *     → 拒绝。第一帧嵌入发生即登记，次帧起永久拒绝 → 增长上限 = 单次
 *     常数，不随帧数累加。
 *
 * 登记规则：采样 X 在源录制栈 [O₁, O₂, …] 内执行时，X 及其传递源一并
 * 记入**每个**活跃录制源（O₂ 的录制内容经玻璃层绘制会落入 O₁ 的录制，
 * 传递性必须沿栈向上传染）。
 *
 * 拒绝的视觉代价：被拒采样该次不绘制 → 玻璃件该次退为本层 tint /
 * 霜面（与 v1.1 自嵌套跳过同款降级），组合树与进程存活优先。
 *
 * 线程模型：Compose 绘制（含 GraphicsLayer.record）全部发生在主线程
 * draw pass —— 单线程访问，无需并发结构。集合大小 ≤ 全 app 采样源数
 * （个位数），单调增长有界，无泄漏风险。
 */
internal object GlassRecordingGuard {

    /** 正在录制中的采样源栈（内层在后）。 */
    private val recordingSources = ArrayList<LayerBackdrop>()

    /** 进入某采样源的录制（LayerBackdropNode / exported 录制块调用）。 */
    fun beginRecord(source: LayerBackdrop) {
        recordingSources.add(source)
    }

    /** 退出录制（try/finally 配对调用，异常安全）。 */
    fun endRecord(source: LayerBackdrop) {
        // 栈式配对弹出：防御乱序调用不误伤外层录制（取最后一个匹配）。
        for (i in recordingSources.indices.reversed()) {
            if (recordingSources[i] === source) {
                recordingSources.removeAt(i)
                return
            }
        }
    }

    /**
     * 采样判定：当前是否允许把 [source] 画进任何活跃录制。
     * false = 必须跳过本次采样绘制（成环或自嵌套）。
     */
    fun canSample(source: LayerBackdrop): Boolean {
        for (recording in recordingSources) {
            if (recording === source) return false // 自嵌套：源录制期间采样自身
            if (recording in source.transitiveSources) {
                return false // 跨帧成环：source 的层内容已传递含有本录制源
            }
        }
        return true
    }

    /**
     * 采样 [source] 实际执行后的登记：连同其传递源记入所有活跃录制源。
     */
    fun registerSample(source: LayerBackdrop) {
        if (recordingSources.isEmpty()) return
        for (recording in recordingSources) {
            recording.transitiveSources.add(source)
            recording.transitiveSources.addAll(source.transitiveSources)
        }
    }
}
