package com.abyss.tools.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Parcelable
import com.abyss.tools.core.update.ApkInstallGateway
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真实 APK 安装网关（PackageInstaller session 提交 + 结果广播等待）。
 *
 * 信任链（fail-closed 纪律，v0.6.0 起收紧）：
 *  1. 包信息可读 —— 无法解析的 APK 直接拒绝；
 *  2. 签名信息可读且非空 —— 空 / 不可读的签名集一律 SecurityException
 *     （不再放行，评审 #5：空数组门禁属 fail-open）；API 28+ 用
 *     GET_SIGNING_CERTIFICATES + SigningInfo（GET_SIGNATURES 弃用，
 *     多签名 / 密钥轮换场景会漏判），26/27 降级老常量；
 *  3. 宿主自更新（包名 = 本包）额外硬校验发布者指纹 —— 待装签名
 *     必须命中 [PublisherSignatures.PINNED_SHA256]；
 *  4. 同签名门禁 —— 目标包已在设备上时，待装签名集与在装版本一致
 *     （任一侧不可读 → 拒绝，绝不降级放行）；
 *  5. 会话写盘 → commit（PendingIntent 广播回报）→ 等待最终结果；
 *     STATUS_PENDING_USER_ACTION 时自动转发系统确认页（未知来源安装的
 *     用户授权），并继续等待确认后的最终广播；
 *  6. 超时（120s）视作失败，会话 abandon。
 *
 * 本方法为阻塞调用 —— 调用方必须 withContext(Dispatchers.IO)。
 * targetSdk=28：registerReceiver 的 RECEIVER_* 导出标志仅对 targetSdk≥34
 * 的应用强制；但 Android 13+（含 15/16）上显式声明 RECEIVER_NOT_EXPORTED
 * 可消除安全审计告警并前向兼容（本广播只收本应用 PendingIntent 的
 * 定向回调，NOT_EXPORTED 即最小暴露面）。
 */
class PackageInstallerApkGateway(private val context: Context) : ApkInstallGateway {

    override fun install(apk: File): Result<Unit> = runCatching {
        if (!apk.exists() || apk.length() == 0L) {
            throw IOException("安装包不存在或为空：${apk.absolutePath}")
        }

        val pm = context.packageManager

        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, signatureFlags())
            ?: throw IOException("无法读取 APK 包信息（文件损坏或非有效安装包）：${apk.name}")

        val packageName = archive.packageName
            ?: throw IOException("APK 缺少包名：${apk.name}")

        // ── 门禁 1：待装签名集非空（fail-closed，空即拒）──────────────
        val incoming = signaturesOf(archive)
            ?: throw SecurityException("无法读取待装 APK 签名（fail-closed 拒绝）：${apk.name}")
        if (incoming.isEmpty()) {
            throw SecurityException("待装 APK 无签名信息（fail-closed 拒绝）：${apk.name}")
        }

        // ── 门禁 2：宿主自更新 → 发布者指纹硬校验 ──────────────────────
        if (packageName == context.packageName) {
            val hit = incoming.any { PublisherSignatures.matches(PublisherSignatures.sha256Fingerprint(it)) }
            if (!hit) {
                throw SecurityException(
                    "宿主自更新签名指纹不符（期望 ${PublisherSignatures.PINNED_SHA256.substring(0, 17)}…）—— 拒绝安装",
                )
            }
        }

        // ── 门禁 3：目标包已安装 → 同签名门禁（两侧都可读才比对）──────
        val installedInfo: PackageInfo? = try {
            pm.getPackageInfo(packageName, signatureFlags())
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (installedInfo != null) {
            val current = signaturesOf(installedInfo)
            if (current == null || current.isEmpty()) {
                throw SecurityException(
                    "在装版本 $packageName 签名不可读 —— 拒绝覆盖（fail-closed）",
                )
            }
            if (!sameSignatures(current, incoming)) {
                throw SecurityException("签名不一致：拒绝覆盖已安装的 $packageName（fail-closed）")
            }
        }

        // ── 会话提交 ────────────────────────────────────────────────────
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val sessionId = installer.createSession(params)

        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(sessionId)
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            awaitCommitResult(installer, session, sessionId, packageName)
        } finally {
            runCatching { session?.abandon() }
        }
    }

    /** 跨线程可见的安装结果持有器（广播回调主线程 ↔ 阻塞工作线程）。 */
    private class InstallStatus {
        @Volatile var status: Int = Int.MIN_VALUE
        @Volatile var message: String? = null
    }

    /** 注册结果广播 → commit → 等待最终状态（含用户确认页转发）。 */
    private fun awaitCommitResult(
        installer: PackageInstaller,
        session: PackageInstaller.Session,
        sessionId: Int,
        packageName: String,
    ) {
        val action = "$ACTION_RESULT.$sessionId"
        val latch = CountDownLatch(1)
        val result = InstallStatus()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                if (s == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // 未知来源安装：转发系统确认页，等待确认后的最终广播
                    val confirm = extractParcelableIntent(intent)
                    if (confirm != null) {
                        runCatching {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(confirm)
                        }
                    }
                    return
                }
                result.status = s
                result.message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                latch.countDown()
            }
        }

        registerResultReceiver(action, receiver)
        try {
            val broadcast = Intent(action).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                broadcast,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            session.commit(pending.intentSender)

            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                installer.abandonSession(sessionId)
                throw IOException("安装超时：系统未在 ${TIMEOUT_SECONDS}s 内回报结果（$packageName）")
            }

            when (result.status) {
                PackageInstaller.STATUS_SUCCESS -> Unit
                else -> throw IOException(
                    "安装失败（status=${result.status}）：${result.message ?: "无附加信息"}（$packageName）"
                )
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Android 13+ 显式 RECEIVER_NOT_EXPORTED（本广播仅收本应用定向回调）；
     *  老系统无此重载，降级为默认注册。 */
    private fun registerResultReceiver(action: String, receiver: BroadcastReceiver) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(action),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(action))
        }
    }

    @Suppress("DEPRECATION")
    private fun extractParcelableIntent(intent: Intent): Intent? =
        intent.getParcelableExtra<Parcelable>(Intent.EXTRA_INTENT) as? Intent

    // ── 签名读取（API 28+ SigningInfo / 26-27 老常量）────────────────

    /** 读签名的 PackageManager 标志位：API 28+ 用 SigningInfo 系。 */
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /**
     * 从 PackageInfo 抽签名集：多签名者取 apkContentsSigners；单签名者取
     * 签名链历史（含轮换前任，覆盖安装比对语义正确）；不可读 → null。
     */
    private fun signaturesOf(info: PackageInfo): Array<Signature>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            return if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        }
        @Suppress("DEPRECATION")
        return info.signatures
    }

    private fun sameSignatures(
        a: Array<Signature>,
        b: Array<Signature>,
    ): Boolean {
        if (a.size != b.size) return false
        val left = a.map { it.toCharsString() }.toSet()
        val right = b.map { it.toCharsString() }.toSet()
        return left.isNotEmpty() && left == right
    }

    private companion object {
        const val ACTION_RESULT = "com.abyss.tools.install.INSTALL_RESULT"
        const val TIMEOUT_SECONDS = 120L
    }
}
