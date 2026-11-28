package com.abyss.tools.privilege

import android.content.Context
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener

/**
 * Dhizuku 公开 API 的唯一调用点（io.github.iamr0s:Dhizuku-API 2.5.3）。
 *
 * 只用三个公开方法：init / isPermissionGranted / requestPermission。
 * `IDhizuku` AIDL 反射（v1/v2 双套、非 SDK 限制、兼容存根三重风险）不进
 * 主路径 —— 要看也只在 debug 诊断面板里看。所有调用 runCatching 吞异常：
 * 未安装 / 未激活 / 提供端崩溃都只是「能力不可用」，不是 App 故障。
 */
object DhizukuBridge {

    /** Dhizuku.init：连接提供端 ContentProvider 取 binder；失败即 false。 */
    fun init(context: Context): Boolean = runCatching { Dhizuku.init(context.applicationContext) }
        .getOrDefault(false)

    /** 是否已授权本应用使用 Dhizuku 通道（init 成功后才有意义）。 */
    fun isPermissionGranted(): Boolean = runCatching { Dhizuku.isPermissionGranted() }
        .getOrDefault(false)

    /**
     * 拉起 Dhizuku 授权请求（内部 startActivity 到 Dhizuku 管理器）。
     * 传入 Activity 上下文；结果经 [androidx.activity] 回调后由 onResume 重读真值，
     * 这里不缓存任何授权态。
     */
    // DhizukuRequestPermissionListener 是抽象类（继承 AIDL Stub）而非接口 —— 不能 SAM 转换
    fun requestPermission(context: Context): Boolean = runCatching {
        Dhizuku.requestPermission(
            context.applicationContext,
            object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(requestCode: Int) {
                    // 结果不缓存：返回 App 后由 onResume 重读真值
                }
            },
        )
    }.isSuccess

    /** 是否已安装任一 Dhizuku 提供端（只读探测，用于「去授权」落点选择）。 */
    @Suppress("DEPRECATION") // getPackageInfo(String, Int) 全版本可用；Flags 形态 API 33+ 才存在
    fun providerInstalled(context: Context): Boolean = runCatching {
        com.abyss.tools.core.shell.ShizukuVariants.DEVICE_OWNER_PROVIDERS.any { pkg ->
            context.packageManager.getPackageInfo(pkg, 0) != null
        }
    }.getOrDefault(false)
}
