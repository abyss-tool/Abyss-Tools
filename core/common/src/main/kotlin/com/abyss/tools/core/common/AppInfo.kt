package com.abyss.tools.core.common

/**
 * App 版本单一事实源（与 app/build.gradle.kts 的 versionName/versionCode
 * 人工同步；发布流水线接入后由 CI 注入校验，防漂移）。
 */
object AppInfo {
    const val VERSION_NAME: String = "0.1.1"
    const val VERSION_CODE: Int = 2

    val version: SemVer
        get() = requireNotNull(SemVer.parse(VERSION_NAME)) {
            "AppInfo.VERSION_NAME 必须是合法 SemVer：$VERSION_NAME"
        }
}
