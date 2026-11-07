package com.abyss.tools.feature.wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * 深渊动态壁纸服务（液态玻璃光斑，双渲染层中的 android.graphics 端）。
 *
 * 纪律：
 *  - 不持有任何 Activity 引用 —— 引擎由系统绑定后独立存活，设置页只经
 *    WallpaperManager Intent 请系统代为设置（见 WallpaperScreen）；
 *  - 不长期持有 holder：每帧现取 [Engine.surfaceHolder]，引擎销毁即断引用；
 *  - prefs 不热更新：每次恢复可见（onVisibilityChanged(true)）重读一次 ——
 *    在设置页改完主题 / 速度，回到桌面即生效。
 */
class AbyssWallpaperService : WallpaperService() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(WallpaperPrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreateEngine(): Engine = AbyssEngine()

    /**
     * 渲染引擎：33ms/帧（30 fps 省电甜点位）。
     *
     * 可见性是帧循环的唯一开关 —— 不可见必停（省电铁律）；surface 销毁 /
     * 引擎销毁时把 Handler 残留回调一并清空（removeCallbacksAndMessages(null)
     * 只清理本 Handler 的待处理消息，不误伤主线程其他任务）。
     */
    private inner class AbyssEngine : Engine() {

        private val frameHandler = Handler(Looper.getMainLooper())

        /** 帧循环：画一帧 → 仍可见才续订下一帧（不可见时自然熄火）。 */
        private val frameLoop = object : Runnable {
            override fun run() {
                drawOneFrame()
                if (visible && surfaceReady) {
                    frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
                }
            }
        }

        private var theme: WallpaperTheme = WallpaperThemes.byId(WallpaperPrefs.readThemeId(prefs))
        private var speedFactor: Float = WallpaperPrefs.readSpeedFactor(prefs)
        private var scene: List<Orb> = emptyList()

        private var surfaceW = 0
        private var surfaceH = 0
        private var surfaceReady = false
        private var visible = false

        /** 主页 xOffset（0–1），叠加为光斑横向视差。 */
        private var parallax = 0f

        /** 时间原点：elapsedRealtime 跨休眠连续 —— 暂停 / 恢复不跳帧。 */
        private val epoch = SystemClock.elapsedRealtime()

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            surfaceReady = true
            val frame = holder?.surfaceFrame
            if (frame != null) {
                rebuildScene(frame.width(), frame.height())
            }
            if (visible) scheduleFrameLoop()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            // 尺寸变化 → 光斑半径 / 锚点全部依赖 w/h，重建场景
            rebuildScene(width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                reloadPrefs() // 回到桌面：重读设置（主题 / 速度可能在设置页改过）
                if (surfaceReady) scheduleFrameLoop()
            } else {
                stopFrameLoop() // 不可见必停 —— 省电铁律
            }
        }

        /**
         * 主页滑动视差：只记录 xOffset 并立即补一帧，
         * 不动帧循环节奏（系统滑动时循环本身也在跑）。
         */
        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xStep: Float,
            yStep: Float,
            xPixels: Int,
            yPixels: Int,
        ) {
            parallax = xOffset
            if (visible && surfaceReady) drawOneFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            frameHandler.removeCallbacksAndMessages(null)
        }

        /** 壁纸期望尺寸由系统决定（跟随屏幕），此处刻意忽略。 */
        override fun onDesiredSizeChanged(desiredWidth: Int, desiredHeight: Int) = Unit

        override fun onDestroy() {
            frameHandler.removeCallbacksAndMessages(null)
            super.onDestroy()
        }

        // ── 内部 ──────────────────────────────────────────────────────────

        private fun rebuildScene(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            surfaceW = w
            surfaceH = h
            scene = buildScene(theme, w, h)
        }

        /** 每次恢复可见时重读 prefs：主题变了才重建场景（同主题布局确定性不变）。 */
        private fun reloadPrefs() {
            speedFactor = WallpaperPrefs.readSpeedFactor(prefs)
            val next = currentTheme(prefs)
            if (next.id != theme.id) {
                theme = next
                rebuildScene(surfaceW, surfaceH)
            }
        }

        private fun scheduleFrameLoop() {
            frameHandler.removeCallbacks(frameLoop) // 防重复排程
            frameHandler.postDelayed(frameLoop, FRAME_INTERVAL_MS)
        }

        private fun stopFrameLoop() {
            frameHandler.removeCallbacks(frameLoop)
        }

        private fun drawOneFrame() {
            if (!surfaceReady || scene.isEmpty() || surfaceW <= 0 || surfaceH <= 0) return
            val holder = surfaceHolder // 每帧现取，不缓存引用
            val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                val t = (SystemClock.elapsedRealtime() - epoch) / 1000f *
                    theme.driftSpeedFactor *
                    speedFactor
                drawFrame(canvas, theme, scene, t, surfaceW, surfaceH, parallax)
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    private companion object {
        /** 30 fps：光斑漂移极缓，肉眼无感差异、功耗减半（预览页同帧率节流）。 */
        const val FRAME_INTERVAL_MS = 33L
    }
}
