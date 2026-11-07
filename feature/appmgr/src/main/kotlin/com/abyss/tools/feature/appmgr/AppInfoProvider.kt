package com.abyss.tools.feature.appmgr

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单个已安装应用的快照（列表页 / 详情页共用；经 PackageManager 一次
 * 拉全量得到）。
 *
 * 注意：[icon] 是 Drawable（引用语义），**不要**用本类的 equals 比较
 * 两个实例 —— 一律以 [packageName] 为唯一键。
 */
data class AbyssAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    /** 系统应用（FLAG_SYSTEM，含「系统应用 + 已更新」形态）。 */
    val isSystem: Boolean,
    val installTime: Long,
    val lastUpdateTime: Long,
    val sourceDir: String,
    val apkSize: Long,
    val icon: Drawable?,
)

/**
 * 应用数据源接口（手动 DI 纪律：UI 只认接口，测试 / 预览可替换实现）。
 *
 * 一次拉全量 + 内存缓存（应用列表页无分页需求）；[invalidate] 清缓存，
 * 在状态变更动作（冻结 / 隐藏 / 卸载）后调用，保证下次进入页面拿到真值。
 */
interface AppInfoProvider {

    suspend fun installedApps(): List<AbyssAppInfo>

    fun invalidate()
}

/**
 * 真实实现：PackageManager 全量读取（[Dispatchers.IO]）。
 *
 * 坏包处理（fail-closed）：每个包的转换都在 runCatching 内 —— 无
 * applicationInfo / 无 packageName 等结构性坏包整包跳过，单个包坏绝不
 * 拖垮整页；loadLabel / loadIcon 这两个官方文档明示可能抛异常的读取点
 * 单点兜底（label 回退为包名、icon 置 null 占位图标），避免「仅图标
 * 资源损坏就整行消失」的过度丢弃。
 */
class PackageManagerProvider(context: Context) : AppInfoProvider {

    private val appContext = context.applicationContext

    @Volatile
    private var cache: List<AbyssAppInfo>? = null

    override fun invalidate() {
        cache = null
    }

    override suspend fun installedApps(): List<AbyssAppInfo> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            val apps = loadInstalledApps()
            cache = apps
            apps
        }
    }

    private fun loadInstalledApps(): List<AbyssAppInfo> {
        val pm = appContext.packageManager
        // 为什么是 MATCH_UNINSTALLED_PACKAGES 而不是 0：
        //  - 「GET_MATCH_DEFAULT_ONLY 语义用 0」指不需要按 intent 匹配过滤
        //    （本页不筛 MATCH_DEFAULT_ONLY 集合），这部分按 spec 不传；
        //  - 但 pm hide 的应用在 PackageManager 视角等同「已卸载但保留
        //    数据」，不带 MATCH_UNINSTALLED_PACKAGES 会被直接过滤掉 ——
        //    隐藏后的应用从列表消失，「取消隐藏」就永远点不到了。带上
        //    该 flag 才能支撑「隐藏 → 列表可见 + 已隐藏徽章 → 取消隐藏」
        //    的完整闭环（Ice Box 等冻结工具同款做法）。
        //  - targetSdk 28（本仓钉死）：不受 Android 11+ 包可见性过滤影响。
        val packages: List<PackageInfo> =
            pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        return packages
            .mapNotNull { packageInfo ->
                runCatching { packageInfo.toAbyssAppInfo(pm) }.getOrNull()
            }
            .filter { it.sourceDir.isNotBlank() }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
    }

    /** 坏包（无 applicationInfo / label 或 icon 加载抛异常）→ 抛出，由上游跳过。 */
    @Suppress("DEPRECATION") // PackageInfo.versionCode：minSdk 26 < API 28 的回退分支
    private fun PackageInfo.toAbyssAppInfo(pm: PackageManager): AbyssAppInfo {
        val appInfo: ApplicationInfo =
            applicationInfo ?: error("包 $packageName 无 applicationInfo（跳过）")
        val pkg: String = packageName ?: error("包信息缺失 packageName（跳过）")
        val sourceDir: String = appInfo.sourceDir ?: ""
        return AbyssAppInfo(
            packageName = pkg,
            label = runCatching { appInfo.loadLabel(pm).toString() }
                .getOrDefault(pkg),
            versionName = versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode
            } else {
                versionCode.toLong()
            },
            isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            installTime = firstInstallTime,
            lastUpdateTime = lastUpdateTime,
            sourceDir = sourceDir,
            apkSize = if (sourceDir.isBlank()) 0L else File(sourceDir).length(),
            icon = runCatching { appInfo.loadIcon(pm) }.getOrNull(),
        )
    }
}
