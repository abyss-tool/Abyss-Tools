package com.abyss.tools

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.abyss.tools.core.common.AbyssChannels
import com.abyss.tools.core.common.AppInfo
import com.abyss.tools.core.common.CrashGuard
import com.abyss.tools.core.common.LauncherIconGateway
import com.abyss.tools.core.common.SemVer
import com.abyss.tools.core.common.diagnostics.DiagnosticsLog
import com.abyss.tools.core.common.diagnostics.DiagnosticExporter
import com.abyss.tools.core.plugin.DependencyResolver
import com.abyss.tools.core.plugin.PluginBroker
import com.abyss.tools.core.plugin.PluginChannelClient
import com.abyss.tools.core.plugin.PluginInstaller
import com.abyss.tools.core.plugin.PluginRepository
import com.abyss.tools.core.plugin.SessionGrantIssuer
import com.abyss.tools.core.shell.ShellGateway
import com.abyss.tools.core.shell.ShizukuMonitor
import com.abyss.tools.core.shell.ShizukuShellGateway
import com.abyss.tools.core.update.AgentReleaseManager
import com.abyss.tools.core.update.HttpTransport
import com.abyss.tools.core.update.MirrorRouter
import com.abyss.tools.core.update.MirroredTransport
import com.abyss.tools.core.update.OkHttpTransport
import com.abyss.tools.core.update.SignedHttpTransport
import com.abyss.tools.core.update.UpdateManager
import com.abyss.tools.core.update.signed
import com.abyss.tools.feature.appmgr.AppInfoProvider
import com.abyss.tools.feature.appmgr.PackageManagerProvider
import com.abyss.tools.feature.disguise.DisguiseSettings
import com.abyss.tools.feature.settings.SettingsRepository
import com.abyss.tools.install.PackageInstallerApkGateway
import com.abyss.tools.install.PublisherSignatures
import com.abyss.tools.plugin.api.PluginContract
import com.abyss.tools.privilege.AndroidPrivilegeProbe
import com.abyss.tools.privilege.PrivilegeRepository
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

    /** 桌面图标三选一网关（FEAT-036，PackageManager 别名互斥启停实现）。 */
    lateinit var launcherIconGateway: LauncherIconGateway

    /** 深渊agent 直达下载（FEAT-037，Guru 发布仓通道 + 镜像链 + 网关安装）。 */
    lateinit var agentRelease: AgentReleaseManager

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

    /**
     * 权限能力快照单一事实源（ARC-011，v0.10.2）：StateFlow 去重扇出，
     * 仪表盘 / 功能页 / 引导页 / 伪装模式卡全部消费同一份快照。
     */
    lateinit var privilegeRepository: PrivilegeRepository

    /**
     * 宿主 PluginBroker（ARC-104 §4）：主壳内部模块消费 Linux 底座的唯一入口
     * `getInternalSession()` —— 宿主自校验 + 能力收敛 + 60s 内部 grant + 审计分账；
     * 底座未装即 E_RUNTIME_UNAVAILABLE（UI 一键引导），绝不谎报可用。
     */
    lateinit var pluginBroker: PluginBroker

    val ready: Boolean
        get() = ::settings.isInitialized

    fun init(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext

        settings = SettingsRepository(appContext)
        disguiseSettings = DisguiseSettings(appContext)
        appInfoProvider = PackageManagerProvider(appContext)

        // ── N-EXP-003 诊断日志（M1）：本地沙箱环形缓冲 + 滚动文件，零外传 ──
        DiagnosticsLog.attachLogDir(File(appContext.filesDir, "diag"))
        DiagnosticExporter.bind(DiagnosticsLog)

        // 传输层（v0.7.0）：镜像感知装饰器包住 OkHttp —— 更新检查 / 插件
        // 索引 / APK 与补丁下载全链路按「用户偏好 / 延迟实测」择优镜像，
        // 单镜像失败自动回退下一镜像（国内网络下载可用性的一揽子修复）。
        mirrorRouter.preferenceProvider = { settings.downloadMirror }
        val baseTransport: HttpTransport = MirroredTransport(OkHttpTransport(), mirrorRouter)

        // ── N-SEC-001 清单 detached 签名验签装饰器（M1 默认开启，fail-closed） ──
        // 版本清单 / 插件索引 / 插件补丁通道 三份清单均走 .minisig 验签；
        // APK 与补丁二进制不走 minisign（走 SHA-256 摘要 + Android 安装器签名校验）。
        val versionSigned = baseTransport.signed("version.json")
        val changelogSigned = baseTransport.signed("changelog.json")
        val pluginIndexSigned = baseTransport.signed("plugins.json")
        val pluginPatchSigned = baseTransport.signed("patches/index.json")

        // APK 安装网关（真实实现）：宿主自更新与 APK 插件共用同一条
        // PackageInstaller 通道（同签名门禁 + 用户确认页转发 + 结果广播等待）。
        val apkGateway = PackageInstallerApkGateway(appContext)

        // FEAT-036：桌面图标三选一（activity-alias 互斥启停）
        launcherIconGateway = PackageLauncherIconGateway(appContext)

        // ARC-011：权限能力探测 + 快照仓储（init 即探测一次，首帧不展示假态）
        privilegeRepository = PrivilegeRepository(AndroidPrivilegeProbe(appContext))

        // ARC-104 §4：宿主自消费通道 —— 宿主身份 = 自身包名 + 发布者指纹常量；
        // 审计走 DiagnosticsLog（PLUGIN_SESSION 分账，requesterId 为 subject）。
        pluginBroker = PluginBroker(
            resolver = DependencyResolver(hostAppVersion = AppInfo.version),
            grantIssuer = SessionGrantIssuer(),
            hostPackage = appContext.packageName,
            hostSignerSha256 = PublisherSignatures.PINNED_SHA256,
            onAudit = { ev ->
                DiagnosticsLog.info(
                    DiagnosticsLog.Kind.PLUGIN_SESSION,
                    ev.requesterId,
                    "${ev.outcome} ${ev.capability} ${ev.sessionId}" +
                        (ev.baseVersion?.let { " base=$it" } ?: ""),
                )
            },
        )

        // FEAT-037：深渊agent 直达下载（Guru 发布仓 version.json 固定通道；
        // 走同一镜像感知 transport —— 下载资产自动吃 MirrorRouter 镜像链）
        agentRelease = AgentReleaseManager(
            transport = baseTransport,
            versionJsonUrl = AbyssChannels.AGENT_VERSION_JSON,
            cacheDir = File(appContext.filesDir, "agent"),
            apkGateway = apkGateway,
        )

        // 插件域：索引（插件中心仓）+ 补丁通道 + 安装器
        pluginRepository = PluginRepository(pluginIndexSigned, AbyssChannels.PLUGIN_INDEX_JSON)
        channelClient = PluginChannelClient(pluginPatchSigned)
        pluginInstaller = PluginInstaller(
            pluginsRoot = File(appContext.filesDir, PluginContract.PLUGINS_DIR),
            transport = baseTransport,
            apkGateway = apkGateway,
        )

        // App 自身更新域：发布仓 version.json / changelog.json 固定读取点
        updateManager = UpdateManager(
            transport = baseTransport,
            versionSigned = versionSigned,
            changelogSigned = changelogSigned,
            versionJsonUrl = AbyssChannels.VERSION_JSON,
            cacheDir = File(appContext.filesDir, "updates"),
            changelogJsonUrl = AbyssChannels.CHANGELOG_JSON,
            apkGateway = apkGateway,
        )
    }

    /** 本机已装插件版本账本（PluginBroker.getInternalSession 的 installed 入参）。 */
    fun installedPluginVersions(): Map<String, SemVer> =
        runCatching {
            pluginInstaller.installed().mapNotNull { m ->
                SemVer.parse(m.version)?.let { m.id to it }
            }.toMap()
        }.getOrDefault(emptyMap())

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
        // Shizuku 变体接线（v0.8.5）：Sui（Root 集成形态）的 binder 必须经
        // Sui.init 显式挂载，且要先于一切 Shizuku API 调用 —— 官方 Shizuku /
        // Shevery / Shizuku+ 不需要，但也在此幂等探测一次。
        ShizukuMonitor.ensureInit(this)
        ServiceRegistry.init(this)

        // ── ARC-011 FR-008 快照刷新时机（全部不跨会话缓存）────────────
        // ① Shizuku binder 生死 / 授权结果 → 重算快照（监听器首调即同步一次）
        runCatching {
            ShizukuMonitor.addListener { ServiceRegistry.privilegeRepository.refresh() }
        }
        // ② 无障碍开关：Secure 设置变更即刷（系统设置里直接关闭也能实时降档）
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        ServiceRegistry.privilegeRepository.refresh()
                    }
                },
            )
        }
        // ③ DeviceAdminReceiver.onEnabled/onDisabled 在 receiver 内直接刷新
        // ④ 安装未知应用 / 悬浮窗 / Dhizuku 授权页返回：MainActivity.onResume
    }
}
