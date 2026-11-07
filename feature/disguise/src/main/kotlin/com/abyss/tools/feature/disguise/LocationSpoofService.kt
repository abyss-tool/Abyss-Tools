package com.abyss.tools.feature.disguise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 定位伪装引擎（前台服务）。
 *
 * 工作原理（Fake GPS 同款官方通道，免 Root）：
 *  1. Shizuku 执行 `appops set <本包> android:mock_location allow`
 *     （新手引导 / 定位伪装页一键完成）；
 *  2. 本服务对 gps / network / fused 三个位置源调用
 *     LocationManager.addTestProvider —— 测试源生效后，全系统读取该源
 *     的 App（地图 / 打卡 / 网约车）拿到的都是我们注入的坐标；
 *  3. 前台服务每秒 setTestProviderLocation 持续供给。
 *
 * 移动模式（[MovementMode]）：
 *  - 静止：固定坐标（speed=0）；
 *  - 微漂移：±driftMeters 随机游走，对抗「位置完全静止」类检测；
 *  - 步行 / 骑行 / 驾车：航向缓慢随机变化（±0.15 rad/s），坐标按真实
 *    速度推进，speed / bearing / accuracy 相应自然化 —— 轨迹与传感器
 *    读数形态贴近真实出行，对抗轨迹风控。
 *
 * 生命周期：START_NOT_STICKY —— 系统不复活伪装（诚实语义：用户关掉的
 * 就是关掉的）；坐标经 companion 热更新（运行中改坐标 / 模式不重启服务）。
 */
class LocationSpoofService : Service() {

    private lateinit var locationManager: LocationManager

    /** 已成功注册的测试源（destroy 时逐一归还系统）。 */
    private val activeProviders = mutableSetOf<String>()

    @Volatile
    private var loopRunning = false

    private var loopThread: Thread? = null

    /** 运动航向（弧度，0 = 正北，顺时针）。 */
    private var heading = Random.nextDouble(2 * PI)

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!loopRunning) {
            loopRunning = true
            registerTestProviders()
            loopThread = thread(isDaemon = true, name = "abyss-location-spoof") {
                while (loopRunning) {
                    injectOnce()
                    runCatching { Thread.sleep(TICK_INTERVAL_MS) }
                        .onFailure { loopRunning = false }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loopRunning = false
        loopThread?.interrupt()
        runCatching {
            activeProviders.forEach { locationManager.removeTestProvider(it) }
        }
        activeProviders.clear()
        isSpoofing = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 测试源注册 / 注入 ─────────────────────────────────────────────

    private fun registerTestProviders() {
        // fused 在部分 ROM 不存在 → 逐源独立注册，缺谁跳谁（fail-closed：
        // 记录失败错误，绝不静默当作成功）
        lastError = null
        PROVIDERS.forEach { provider ->
            runCatching {
                locationManager.addTestProvider(
                    provider,
                    /* requiresNetwork = */ false,
                    /* requiresSatellite = */ false,
                    /* requiresCell = */ false,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    /* powerRequirement = */ 1,
                    /* accuracy = */ 1,
                )
                activeProviders.add(provider)
            }.onFailure { e ->
                if (activeProviders.isEmpty()) {
                    // 首源就挂：多半是 mock_location appop 未授权
                    lastError = "注册测试位置源失败（$provider）：" +
                        (e.message ?: e.javaClass.simpleName) +
                        " —— 请先在本页完成「授权模拟位置」"
                }
            }
        }
        if (activeProviders.isEmpty() && lastError == null) {
            lastError = "无可用位置源"
        }
        isSpoofing = activeProviders.isNotEmpty()
        if (!isSpoofing) stopSelf()
    }

    /**
     * 单帧注入：按移动模式计算本 tick 坐标 / 速度 / 航向 / 精度。
     * 每源独立注入（同一坐标多源读数完全一致反而可疑 —— 加每源微扰）。
     */
    private fun injectOnce() {
        val mode = movementMode
        val baseLat = currentLat
        val baseLng = currentLng
        var lat = baseLat
        var lng = baseLng
        var speed = 0f
        var bearing = 0f
        var accuracy = 1.5f

        when (mode) {
            MovementMode.STATIC -> Unit // 固定坐标，speed/bearing 恒 0

            MovementMode.JITTER -> {
                // ±driftMeters 随机游走 + 微速度（真实 GPS 静止读数也在抖）
                val driftDeg = driftMeters / METERS_PER_DEGREE
                lat = baseLat + Random.nextDouble(-driftDeg, driftDeg)
                lng = baseLng + Random.nextDouble(-driftDeg, driftDeg)
                speed = Random.nextFloat() * 0.3f
                bearing = Random.nextFloat() * 360f
                accuracy = 3f + Random.nextFloat() * 5f
            }

            MovementMode.WALK, MovementMode.RIDE, MovementMode.DRIVE -> {
                // 航向缓慢变化（±0.15 rad/tick，避免直角转弯的不自然轨迹）
                heading = (heading + Random.nextDouble(-0.15, 0.15) + 2 * PI) % (2 * PI)
                val meters = mode.speedMps * (TICK_INTERVAL_MS / 1000.0)
                val dNorth = meters * cos(heading)
                val dEast = meters * sin(heading)
                lat = (baseLat + dNorth / METERS_PER_DEGREE).coerceIn(-90.0, 90.0)
                lng = (baseLng + dEast / (METERS_PER_DEGREE * cos(Math.toRadians(baseLat))))
                    .coerceIn(-180.0, 180.0)
                // 推进后的坐标回写（下一 tick 从新位置继续 —— 真实轨迹）
                currentLat = lat
                currentLng = lng
                speed = mode.speedMps.toFloat() * (0.9f + Random.nextFloat() * 0.2f)
                bearing = (((Math.toDegrees(heading) % 360.0) + 360.0) % 360.0).toFloat()
                accuracy = when (mode) {
                    MovementMode.WALK -> 4f + Random.nextFloat() * 4f
                    MovementMode.RIDE -> 3f + Random.nextFloat() * 3f
                    else -> 3f + Random.nextFloat() * 5f
                }
            }
        }

        activeProviders.forEach { provider ->
            // 每源亚米级微扰（多源读数完全一致是模拟痕迹）
            val pLat = if (mode == MovementMode.STATIC) lat else lat + Random.nextDouble(-0.000001, 0.000001)
            val pLng = if (mode == MovementMode.STATIC) lng else lng + Random.nextDouble(-0.000001, 0.000001)
            runCatching {
                locationManager.setTestProviderLocation(
                    provider,
                    buildLocation(provider, pLat, pLng, speed, bearing, accuracy),
                )
            }.onFailure { e ->
                lastError = "位置注入中断：${e.message ?: e.javaClass.simpleName}"
                loopRunning = false
                isSpoofing = false
                stopSelf()
            }
        }
    }

    private fun buildLocation(
        provider: String,
        lat: Double,
        lng: Double,
        speedMps: Float,
        bearingDeg: Float,
        accuracyMeters: Float,
    ): Location = Location(provider).apply {
        latitude = lat
        longitude = lng
        accuracy = accuracyMeters
        altitude = 50.0
        speed = speedMps
        bearing = bearingDeg
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    // ── 前台通知 ──────────────────────────────────────────────────────

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "定位伪装",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "定位伪装引擎运行状态（低打扰：无声音无横幅）"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, LocationSpoofService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("定位伪装运行中")
            .setContentText("模式：${movementMode.label} · 点击通知内「停止」归还真实定位")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_location),
                    "停止伪装",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {

        /** GPS / 网络定位 / 融合定位 —— 三源齐注，读哪路都拿到伪装值。 */
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER,
        )

        private const val CHANNEL_ID = "abyss_location_spoof"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.abyss.tools.action.STOP_LOCATION_SPOOF"
        private const val TICK_INTERVAL_MS = 1000L

        /** 纬度 1° ≈ 111320 米（赤道经度同值，纬度越高经线圈越短）。 */
        private const val METERS_PER_DEGREE = 111_320.0

        // ── 进程级运行态（UI 侧读取，volatile 直读）────────────────────

        @Volatile
        var isSpoofing: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var currentLat: Double = 0.0

        @Volatile
        var currentLng: Double = 0.0

        @Volatile
        var movementMode: MovementMode = MovementMode.JITTER

        @Volatile
        var driftMeters: Int = 3

        fun start(
            context: Context,
            lat: Double,
            lng: Double,
            mode: MovementMode,
            drift: Int,
        ) {
            currentLat = lat
            currentLng = lng
            movementMode = mode
            driftMeters = drift
            context.startForegroundService(
                Intent(context, LocationSpoofService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationSpoofService::class.java))
        }
    }
}
