package com.abyss.tools

import android.app.Application
import android.content.Context
import com.abyss.tools.core.common.AbyssChannels
import com.abyss.tools.core.plugin.ApkInstallGateway
import com.abyss.tools.core.plugin.PluginChannelClient
import com.abyss.tools.core.plugin.PluginInstaller
import com.abyss.tools.core.plugin.PluginRepository
import com.abyss.tools.core.update.OkHttpTransport
import com.abyss.tools.core.update.UpdateManager
import com.abyss.tools.feature.settings.SettingsRepository
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

    val ready: Boolean
        get() = ::settings.isInitialized

    fun init(context: Context) {
        val appContext = context.applicationContext
        val transport = OkHttpTransport()

        settings = SettingsRepository(appContext)

        // 插件域：索引（插件中心仓）+ 补丁通道 + 安装器
        pluginRepository = PluginRepository(transport, AbyssChannels.PLUGIN_INDEX_JSON)
        channelClient = PluginChannelClient(transport)
        pluginInstaller = PluginInstaller(
            pluginsRoot = File(appContext.filesDir, PluginContract.PLUGINS_DIR),
            transport = transport,
            apkGateway = FrameworkApkGateway,
        )

        // App 自身更新域：发布仓 version.json 固定读取点
        updateManager = UpdateManager(
            transport = transport,
            versionJsonUrl = AbyssChannels.VERSION_JSON,
            cacheDir = File(appContext.filesDir, "updates"),
        )
    }
}

/**
 * APK 安装网关（框架占位）：PackageInstaller + 同签名校验随 v0.2
 * 发布流水线接入。core:plugin 保持纯 JVM 可单测，Android 侧依赖
 * 全部隔离在该网关后面。
 */
private object FrameworkApkGateway : ApkInstallGateway {
    override fun install(apk: File): Result<Unit> = Result.failure(
        IllegalStateException(
            "APK 插件安装将在 v0.2 发布流水线接入（PackageInstaller + 同签名校验 + 信任门）"
        )
    )
}

class AbyssApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceRegistry.init(this)
    }
}
