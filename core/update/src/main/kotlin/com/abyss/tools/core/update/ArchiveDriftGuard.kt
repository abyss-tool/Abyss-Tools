package com.abyss.tools.core.update

import com.abyss.tools.core.common.diagnostics.DiagnosticsLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * N-ENG-009 · 归档仓历史清单漂移治理
 *
 * 治理策略：
 *   1. 清单 schema 必须是 abyss-version-v1 / abyss-version-v2
 *   2. 必需字段（versionName/versionCode/download.url）缺失视为漂移
 *   3. sha256 如果存在必须是 64 字符十六进制
 *   4. 检测到漂移时记录到 DiagnosticsLog，并抛 ManifestDriftException
 */
object ArchiveDriftGuard {

    private const val TAG = "DriftGuard"
    private const val MIN_PROTOCOL = 1
    private const val MAX_PROTOCOL = 2

    private val json = Json { ignoreUnknownKeys = true }
    private val kind = DiagnosticsLog.Kind.UPDATE_CHECK

    class ManifestDriftException(
        val field: String,
        val reason: String,
        val seenProtocol: Int?
    ) : Exception("manifest drift: $field - $reason (protocol=$seenProtocol)")

    fun validateVersionJson(raw: String, mirrorId: String) {
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (t: Throwable) {
            DiagnosticsLog.warn(kind, TAG, "drift: not a valid JSON object mirror=$mirrorId: ${t.message}")
            throw ManifestDriftException("root", "not a valid JSON object", null)
        } ?: run {
            DiagnosticsLog.warn(kind, TAG, "drift: root is not object mirror=$mirrorId")
            throw ManifestDriftException("root", "root is not a JSON object", null)
        }

        val schema = obj["schema"]?.jsonPrimitive?.contentOrNull
        val protocol = when (schema) {
            "abyss-version-v1" -> 1
            "abyss-version-v2" -> 2
            null -> 1
            else -> {
                DiagnosticsLog.warn(kind, TAG, "drift: unknown schema=$schema mirror=$mirrorId")
                throw ManifestDriftException("schema", "unknown schema '$schema'", null)
            }
        }
        if (protocol < MIN_PROTOCOL || protocol > MAX_PROTOCOL) {
            DiagnosticsLog.warn(kind, TAG, "drift: protocol=$protocol not in [$MIN_PROTOCOL,$MAX_PROTOCOL] mirror=$mirrorId")
            throw ManifestDriftException("schema", "protocol $protocol not supported", protocol)
        }

        val versionName = obj["versionName"]?.jsonPrimitive?.contentOrNull
        if (versionName.isNullOrBlank()) {
            DiagnosticsLog.warn(kind, TAG, "drift: versionName missing mirror=$mirrorId protocol=$protocol")
            throw ManifestDriftException("versionName", "required field missing", protocol)
        }
        val versionCode = obj["versionCode"]?.jsonPrimitive?.intOrNull
        if (versionCode == null || versionCode <= 0) {
            DiagnosticsLog.warn(kind, TAG, "drift: versionCode invalid mirror=$mirrorId")
            throw ManifestDriftException("versionCode", "invalid versionCode", protocol)
        }
        val download = obj["download"] as? JsonObject
        val url = download?.get("url")?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) {
            DiagnosticsLog.warn(kind, TAG, "drift: download.url missing mirror=$mirrorId")
            throw ManifestDriftException("download.url", "required field missing", protocol)
        }
        val sha256 = download?.get("sha256")?.jsonPrimitive?.contentOrNull
        if (!sha256.isNullOrBlank()) {
            if (sha256.length != 64 || !sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                DiagnosticsLog.warn(kind, TAG, "drift: sha256 malformed len=${sha256.length} mirror=$mirrorId")
                throw ManifestDriftException("download.sha256", "must be 64 hex chars (got ${sha256.length})", protocol)
            }
        }
        if (protocol >= 2 && obj["patch"] != null && obj["patch"] !is JsonObject) {
            DiagnosticsLog.warn(kind, TAG, "drift: v2 patch must be object mirror=$mirrorId")
        }
        DiagnosticsLog.info(kind, TAG, "version.json validated schema=$schema version=$versionName mirror=$mirrorId")
    }
}
