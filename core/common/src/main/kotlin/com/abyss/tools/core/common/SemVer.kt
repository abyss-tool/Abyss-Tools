package com.abyss.tools.core.common

/**
 * SemVer 2.0.0 语义化版本（深渊工具全仓版本语言的单一实现）。
 *
 * 使用范围：
 *  - App 自身版本 / 发布仓 `version.json` 的 `versionName`；
 *  - 插件仓 `plugin.json` 的 `version` / `minApp`；
 *  - 补丁链 `from → to` 的版本比较。
 *
 * 约束：主/次/修订必须为非负整数；预发布与构建元数据段遵循 SemVer 规范
 * （`-pre.release` / `+build.meta`）。解析失败返回 null —— 调用方负责
 * fail-closed（坏版本号一律视为「不可用」，绝不猜测）。
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
    val build: String? = null,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        // 1) 主.次.修订 数值比较
        compareMajorMinorPatch(other).takeIf { it != 0 }?.let { return it }

        // 2) 预发布优先级：无预发布 > 有预发布（1.0.0 > 1.0.0-alpha）
        val mine = preRelease
        val theirs = other.preRelease
        return when {
            mine == null && theirs == null -> 0
            mine == null -> 1
            theirs == null -> -1
            else -> comparePreRelease(mine, theirs)
        }
    }

    private fun compareMajorMinorPatch(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    /** 预发布段比较：`.` 分隔标识符，数值段按数值、字母段按 ASCII。 */
    private fun comparePreRelease(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        val n = minOf(left.size, right.size)
        for (i in 0 until n) {
            val x = left[i]
            val y = right[i]
            val xNum = x.toIntOrNull()
            val yNum = y.toIntOrNull()
            val result = when {
                xNum != null && yNum != null -> xNum.compareTo(yNum)
                xNum != null -> -1 // 数值段优先级恒低于字母段
                yNum != null -> 1
                else -> x.compareTo(y)
            }
            if (result != 0) return result
        }
        // 前缀相同时：标识符更多的一方优先级更高（1.0.0-alpha.1 > 1.0.0-alpha）
        return left.size.compareTo(right.size)
    }

    /** 带默认 `v` 前缀的 git tag 形态（与发布仓 tag 约定一致）。 */
    fun toTag(): String = "v$this"

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        preRelease?.let { append('-').append(it) }
        build?.let { append('+').append(it) }
    }

    companion object {
        private val PATTERN = Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$"""
        )

        /** 解析 `1.2.3` / `1.2.3-beta.1+001`；非法输入返回 null。 */
        fun parse(raw: String): SemVer? {
            val m = PATTERN.matchEntire(raw.trim()) ?: return null
            return SemVer(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].toInt(),
                preRelease = m.groupValues[4].ifEmpty { null },
                build = m.groupValues[5].ifEmpty { null },
            )
        }

        /** 解析 `v1.2.3` / `v1.2.3-beta` 形态的 tag。 */
        fun parseTag(tag: String): SemVer? =
            parse(tag.trim().removePrefix("v").removePrefix("V"))
    }
}
