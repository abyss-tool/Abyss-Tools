package com.abyss.tools.core.common

/**
 * App 版本单一事实源（v0.6.0 起全仓唯一持有处）：app/build.gradle.kts
 * 经正则从本文件推导 versionName/versionCode，发布流水线（release.yml）
 * 与 tools/validate_manifests.py 同源校验 —— 升版只改这里 + 打 tag。
 */
object AppInfo {
    const val VERSION_NAME: String = "0.7.2"
    const val VERSION_CODE: Int = 10

    val version: SemVer
        get() = requireNotNull(SemVer.parse(VERSION_NAME)) {
            "AppInfo.VERSION_NAME 必须是合法 SemVer：$VERSION_NAME"
        }
}
