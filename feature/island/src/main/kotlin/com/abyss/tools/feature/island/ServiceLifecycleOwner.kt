package com.abyss.tools.feature.island

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Service 内 ComposeView 的 ViewTree 桥接宿主：LifecycleOwner +
 * ViewModelStoreOwner + SavedStateRegistryOwner 三合一。
 *
 * Activity 天然提供这三个 Owner，Service 没有 —— 而 ComposeView 的渲染链路
 * （window recomposer 的创建与调度、rememberSaveable 的存取）硬依赖它们，
 * 所以 Service 里挂 Compose 前必须自建宿主并注入 ViewTree：
 *
 * ```
 * ViewTreeLifecycleOwner.set(view, owner)          // Recomposer 生命周期感知
 * ViewTreeViewModelStoreOwner.set(view, owner)     // viewModel() 作用域
 * ViewTreeSavedStateRegistryOwner.set(view, owner) // rememberSaveable 语义
 * ```
 *
 * 生命周期时序（与 Activity 对照，全部主线程）：
 *  1. 构造 + init：SavedStateRegistryController.attach → performRestore(null)。
 *     attach 必须先于任何 ON_CREATE 事件派发（SavedStateRegistry 硬约束）；
 *  2. [start]（windowManager.addView 之前）：INITIALIZED →(ON_CREATE)→ CREATED
 *     →(ON_START)→ STARTED →(ON_RESUME)→ RESUMED —— 生命周期感知的 Recomposer
 *     只有到 RESUMED 才开始驱动帧，先起宿主再挂窗口；
 *  3. Compose 内容常驻 RESUMED（Service 无暂停语义，球 / 面板一直可重组）；
 *  4. [destroy]（windowManager.removeView 之后）：→(ON_PAUSE/ON_STOP/ON_DESTROY)
 *     → DESTROYED，随后 viewModelStore.clear() —— 先拆窗口释放 composition，
 *     再销毁宿主，顺序不可反。
 *
 * 实现取舍：
 *  - SavedState 只做「空恢复」：本服务每次冷启动都是全新会话，overlay 不参与
 *    进程死亡状态回填，因此 performRestore(null)（而非真实 Bundle）即可 ——
 *    rememberSaveable 语义完整（写入侧正常工作），读取侧永远为空。若未来
 *    Service 需要状态恢复（如 onStartCommand 传参），改从持久层读出 Bundle
 *    再 performRestore(bundle) 即可，桥接层不用动；
 *  - 两个 overlay 窗口（球 + 面板）共享同一个宿主：生命周期代表的是「本服务
 *    的窗口存续期」，与一个 Activity 宿多个 ComposeView 的结构同构；面板
 *    removeView 只拆 composition，不动宿主；
 *  - ViewTree 注入用官方静态方法（ViewTreeLifecycleOwner.set 等，lifecycle
 *    2.5+ 的现行 API），不用已废弃的 -ktx 扩展函数。
 */
internal class ServiceLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = viewModelStore

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    init {
        // attach 先于一切事件派发；本服务无历史状态 → 空恢复。
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
    }

    /** addView 之前调用：级联派发 ON_CREATE → ON_START → ON_RESUME 至 RESUMED。 */
    fun start() {
        check(lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            "ServiceLifecycleOwner 只允许 start 一次（当前 ${lifecycleRegistry.currentState}）"
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** removeView 之后调用：级联派发 ON_PAUSE → ON_STOP → ON_DESTROY，再清空 ViewModelStore。 */
    fun destroy() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        viewModelStore.clear()
    }
}
