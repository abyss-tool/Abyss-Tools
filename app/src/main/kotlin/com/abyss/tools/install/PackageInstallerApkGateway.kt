package com.abyss.tools.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Parcelable
import com.abyss.tools.core.update.ApkInstallGateway
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真实 APK 安装网关（PackageInstaller session 提交 + 结果广播等待）。
 *
 * 信任链（fail-closed 纪律）：
 *  1. 包信息可读 —— 无法解析的 APK 直接拒绝；
 *  2. 同签名门禁 —— 目标包已在设备上时，待装 APK 签名必须与在装版本一致
 *     （换签名覆盖一律 SecurityException 拒绝）；
 *  3. 会话写盘 → commit（PendingIntent 广播回报）→ 等待最终结果；
 *     STATUS_PENDING_USER_ACTION 时自动转发系统确认页（未知来源安装的
 *     用户授权），并继续等待确认后的最终广播；
 *  4. 超时（120s）视作失败，会话 abandon。
 *
 * 本方法为阻塞调用 —— 调用方必须 withContext(Dispatchers.IO)。
 * targetSdk=28：registerReceiver 无需 RECEIVER_NOT_EXPORTED 标志（该要求
 * 仅约束 targetSdk≥34 的应用）；PendingIntent 以 IMMUTABLE 提交。
 */
class PackageInstallerApkGateway(private val context: Context) : ApkInstallGateway {

    override fun install(apk: File): Result<Unit> = runCatching {
        if (!apk.exists() || apk.length() == 0L) {
            throw IOException("安装包不存在或为空：${apk.absolutePath}")
        }

        val pm = context.packageManager

        @Suppress("DEPRECATION")
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
            ?: throw IOException("无法读取 APK 包信息（文件损坏或非有效安装包）：${apk.name}")

        val packageName = archive.packageName
            ?: throw IOException("APK 缺少包名：${apk.name}")

        // ── 同签名门禁：目标包已安装时，签名必须一致 ─────────────────────
        @Suppress("DEPRECATION")
        val installedInfo: PackageInfo? = try {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (installedInfo != null) {
            val current = installedInfo.signatures ?: emptyArray()
            val incoming = archive.signatures ?: emptyArray()
            if (current.isNotEmpty() && incoming.isNotEmpty() && !sameSignatures(current, incoming)) {
                throw SecurityException("签名不一致：拒绝覆盖已安装的 $packageName（fail-closed）")
            }
        }

        // ── 会话提交 ────────────────────────────────────────────────────
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setInstallReason(PackageInstaller.SessionParams.INSTALL_REASON_USER)
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

    /** 注册结果广播 → commit → 等待最终状态（含用户确认页转发）。 */
    private fun awaitCommitResult(
        installer: PackageInstaller,
        session: PackageInstaller.Session,
        sessionId: Int,
        packageName: String,
    ) {
        val action = "$ACTION_RESULT.$sessionId"
        val latch = CountDownLatch(1)

        @Volatile var status: Int = Int.MIN_VALUE
        @Volatile var statusMessage: String? = null

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
                status = s
                statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                latch.countDown()
            }
        }

        context.registerReceiver(receiver, IntentFilter(action))
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

            when (status) {
                PackageInstaller.STATUS_SUCCESS -> Unit
                else -> throw IOException(
                    "安装失败（status=$status）：${statusMessage ?: "无附加信息"}（$packageName）"
                )
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractParcelableIntent(intent: Intent): Intent? =
        intent.getParcelableExtra<Parcelable>(Intent.EXTRA_INTENT) as? Intent

    private fun sameSignatures(
        a: Array<android.content.pm.Signature>,
        b: Array<android.content.pm.Signature>,
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
