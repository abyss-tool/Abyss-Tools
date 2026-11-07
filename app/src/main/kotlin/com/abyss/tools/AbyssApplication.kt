package com.abyss.tools

import android.app.Application
import android.content.Context
import com.abyss.tools.core.common.AbyssChannels
import com.abyss.tools.core.common.CrashGuard
import com.abyss.tools.core.plugin.PluginChannelClient
import com.abyss.tools.core.plugin.PluginInstaller
import com.abyss.tools.core.plugin.PluginRepository
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuShellGateway
import com.abyss.tools.core.update.MirrorRouter
import com.abyss.tools.core.update.MirroredTransport
import com.abyss.tools.core.update.OkHttpTransport
import com.abyss.tools.core.update.UpdateManager
import com.abyss.tools.feature.appmgr.AppInfoProvider
import com.abyss.tools.feature.appmgr.PackageManagerProvider
import com.abyss.tools.feature.disguise.DisguiseSettings
import com.abyss.tools.feature.settings.SettingsRepository
import com.abyss.tools.install.PackageInstallerApkGateway
import com.abyss.tools.plugin.api.PluginContract
import java.io.File

/**
 * 手动 DI（框架期不引 Hilt —— 零代码生成依赖，KSP/kapt 面为零，
 * CI 编译面最小）。规模上来后迁移 Hilt 只改本文件。
 */
object ServiceRegistry {

    lateinit var settings: SettingsRepository
    lateinit var pluginRepository: PluginRepository
    lateinit var channelClient: PluginChannelClient
    lateinit var pluginInstaller: PluginInstaller
    lateinit var updateManager: UpdateManager

    /**
     * 镜像路由器（v0.7.0）：插件中心 / 设置页「测速」共享同一份探测缓存，
     * 所有下载经 [MirroredTransport] 按路由顺序自动择优 + 回退。
     */
    val mirrorRouter = MirrorRouter()

    /** 伪装四件套 / 内置工具的系统级指令通道（Shizuku adb shell 上下文）。 */
    val shellGateway: ShellGateway = ShizukuShellGateway()

    /** 伪装四件套的本地状态（用户上次的伪装配置）。 */
    lateinit var disguiseSettings: DisguiseSettings

    /** 应用管理器的已装应用数据源（PackageManager 实现，内存缓存）。 */
    lateinit var appInfoProvider: AppInfoProvider

    val ready: Boolean
        get() = ::settings.isInitialized

    fun init(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext

        settings = SettingsRepository(appContext)
        disguiseSettings = DisguiseSettings(appContext)
        appInfoProvider = PackageManagerProvider(appContext)

        // 传输层（v0.7.0）：镜像感知装饰器包住 OkHttp —— 更新检查 / 插件
        // 索引 / APK 与补丁下载全链路按「用户偏好 / 延迟实测」择优镜像，
        // 单镜像失败自动回退下一镜像（国内网络下载可用性的一揽子修复）。
        mirrorRouter.preferenceProvider = { settings.downloadMirror }
        val transport = MirroredTransport(OkHttpTransport(), mirrorRouter)

        // APK 安装网关（真实实现）：宿主自更新与 APK 插件共用同一条
        // PackageInstaller 通道（同签名门禁 + 用户确认页转发 + 结果广播等待）。
        val apkGateway = PackageInstallerApkGateway(appContext)

        // 插件域：索引（插件中心仓）+ 补丁通道 + 安装器
        pluginRepository = PluginRepository(transport, AbyssChannels.PLUGIN_INDEX_JSON)
        channelClient = PluginChannelClient(transport)
        pluginInstaller = PluginInstaller(
            pluginsRoot = File(appContext.filesDir, PluginContract.PLUGINS_DIR),
            transport = transport,
            apkGateway = apkGateway,
        )

        // App 自身更新域：发布仓 version.json / changelog.json 固定读取点
        updateManager = UpdateManager(
            transport = transport,
            versionJsonUrl = AbyssChannels.VERSION_JSON,
            cacheDir = File(appContext.filesDir, "updates"),
            changelogJsonUrl = AbyssChannels.CHANGELOG_JSON,
            apkGateway = apkGateway,
        )
    }

    /** 「安装未知应用」授权态（新手引导 / 仪表盘徽章共用）。 */
    fun installPermGranted(): Boolean = runCatching {
        appContextRef?.packageManager?.canRequestPackageInstalls() ?: false
    }.getOrDefault(false)

    private var appContextRef: Context? = null
}

class AbyssApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 崩溃防护网必须最先挂载（比任何服务初始化都早）：此后任何未捕获
        // 异常都会落盘 files/crash/last-crash.txt，设置页「诊断」可查看 ——
        // 侧载渠道无 Play 统计，闪退现场全靠本地取证（v0.7.0）。
        CrashGuard.install(filesDir)
        ServiceRegistry.init(this)
    }
}
