package com.abyss.tools.core.common

/**
 * 底栏滑动指示器（液滴小椭圆）渲染档位（FEAT-033）。
 *
 * 纯 JVM 类型源（零 Android 依赖，可单测）；偏好只存字符串
 * （[FROSTED.storageValue] / [CLEAR.storageValue]），不存 ordinal / 类名。
 */
enum class AbyssIndicatorStyle(val storageValue: String) {
    /** 霜面液滴（现状默认，零回归）：磨砂模糊 + 透镜折射 + 速度驱动流光。 */
    FROSTED("frosted"),

    /** 清透玻璃（新增）：零模糊，仅边缘静态玻璃高光 + 边缘带折射。 */
    CLEAR("clear"),
    ;

    companion object {

        /** 默认档 = 键缺失 = 老用户 = v0.9.10 现状外观。 */
        val DEFAULT = FROSTED

        /**
         * fail-closed 解析：null / 空串 / 未知值（含大小写不符）一律回退
         * [DEFAULT]，不抛、不写回覆盖用户原值（下次读仍回退）。
         */
        fun from(raw: String?): AbyssIndicatorStyle = when (raw) {
            FROSTED.storageValue -> FROSTED
            CLEAR.storageValue -> CLEAR
            else -> DEFAULT
        }
    }
}
