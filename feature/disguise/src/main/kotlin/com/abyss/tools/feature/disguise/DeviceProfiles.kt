package com.abyss.tools.feature.disguise

/**
 * 机型伪装档案库。
 *
 * 档案分两层消费：
 *  1. 系统设备名（Settings.Global.DEVICE_NAME）—— Shizuku 写入即刻生效；
 *  2. 完整 Build 指纹档案 —— 导出为 JSON，由沙箱双开插件的 Hook 层在
 *     虚拟空间内对目标 App 生效（Build.MODEL / BRAND / FINGERPRINT …）。
 *
 * 指纹格式对齐 AOSP Build.FINGERPRINT：
 * brand/product/device:release/id/version_incremental
 */
data class ModelProfile(
    val id: String,
    val name: String,
    val brand: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val fingerprint: String,
)

object DeviceProfiles {

    /** 预置旗舰档案（覆盖主流厂商，字段为公开 Build 参数形态）。 */
    val presets: List<ModelProfile> = listOf(
        ModelProfile(
            id = "pixel-9-pro",
            name = "Pixel 9 Pro",
            brand = "google",
            manufacturer = "Google",
            model = "Pixel 9 Pro",
            device = "tokay",
            product = "tokay",
            fingerprint = "google/tokay/tokay:15/AP4A.250105.002/12838999:user/release-keys",
        ),
        ModelProfile(
            id = "pixel-8-pro",
            name = "Pixel 8 Pro",
            brand = "google",
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            device = "husky",
            product = "husky",
            fingerprint = "google/husky/husky:14/UQ1a.240205.004/11259548:user/release-keys",
        ),
        ModelProfile(
            id = "galaxy-s24-ultra",
            name = "Galaxy S24 Ultra",
            brand = "samsung",
            manufacturer = "samsung",
            model = "SM-S9280",
            device = "s5k3lu",
            product = "s5k3luzhx",
            fingerprint = "samsung/s5k3luzhx/s5k3lu:14/UP1A.231005.007/S9280ZHU2AXBC:user/release-keys",
        ),
        ModelProfile(
            id = "xiaomi-14-ultra",
            name = "Xiaomi 14 Ultra",
            brand = "Xiaomi",
            manufacturer = "Xiaomi",
            model = "24030PN60G",
            device = "shennong",
            product = "shennong_global",
            fingerprint = "Xiaomi/shennong_global/shennong:14/UKQ1.230917.001/V816.0.5.0.UNACNXM:user/release-keys",
        ),
        ModelProfile(
            id = "redmi-k70-pro",
            name = "Redmi K70 Pro",
            brand = "Redmi",
            manufacturer = "Xiaomi",
            model = "23117RK66C",
            device = "manet",
            product = "manet",
            fingerprint = "Redmi/manet/manet:14/UKQ1.230804.001/V816.0.5.0.UMNCNXM:user/release-keys",
        ),
        ModelProfile(
            id = "mate-60-pro",
            name = "HUAWEI Mate 60 Pro",
            brand = "HUAWEI",
            manufacturer = "HUAWEI",
            model = "ALN-AL10",
            device = "HNALN",
            product = "ALN-AL10",
            fingerprint = "HUAWEI/ALN-AL10/HWALN:14/HUAWEIALN-AL10/110008C004:user/HUAWEISDCAMP-keys",
        ),
        ModelProfile(
            id = "oneplus-12",
            name = "OnePlus 12",
            brand = "OnePlus",
            manufacturer = "OnePlus",
            model = "CPH2581",
            device = "OP5D27L1",
            product = "CPH2581",
            fingerprint = "OnePlus/CPH2581/OP5D27L1:14/UKQ1.230917.001/T.R4T3.1716982977625:user/release-keys",
        ),
        ModelProfile(
            id = "find-x7-ultra",
            name = "OPPO Find X7 Ultra",
            brand = "OPPO",
            manufacturer = "OPPO",
            model = "PHZ110",
            device = "OP5D79L1",
            product = "PHZ110",
            fingerprint = "OPPO/PHZ110/OP5D79L1:14/UKQ1.230917.001/T.R1T3.1708989482713:user/release-keys",
        ),
        ModelProfile(
            id = "vivo-x100-pro",
            name = "vivo X100 Pro",
            brand = "vivo",
            manufacturer = "vivo",
            model = "V2311A",
            device = "PD2318",
            product = "PD2318",
            fingerprint = "vivo/PD2318/PD2318:14/UP1A.231005.007/compiler04031626:user/release-keys",
        ),
        ModelProfile(
            id = "honor-magic6-pro",
            name = "荣耀 Magic6 Pro",
            brand = "HONOR",
            manufacturer = "HONOR",
            model = "BVL-AN16",
            device = "HNBVL",
            product = "BVL-AN16",
            fingerprint = "HONOR/BVL-AN16/HNBVL:14/UD1A.230817.005/110008C004:user/release-keys",
        ),
    )

    fun byId(id: String): ModelProfile? = presets.firstOrNull { it.id == id }

    /** JSON 转义（导出档案用，避免引序列化编译插件依赖）。 */
    fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** 档案 → 沙箱 Hook 层可消费的 JSON（字段名与 Build 读取项一一对应）。 */
    fun exportJson(profile: ModelProfile): String = """
        |{
        |  "schema": "abyss-model-profile-v1",
        |  "id": ${jsonString(profile.id)},
        |  "name": ${jsonString(profile.name)},
        |  "brand": ${jsonString(profile.brand)},
        |  "manufacturer": ${jsonString(profile.manufacturer)},
        |  "model": ${jsonString(profile.model)},
        |  "device": ${jsonString(profile.device)},
        |  "product": ${jsonString(profile.product)},
        |  "fingerprint": ${jsonString(profile.fingerprint)},
        |  "buildFields": {
        |    "BRAND": ${jsonString(profile.brand)},
        |    "MANUFACTURER": ${jsonString(profile.manufacturer)},
        |    "MODEL": ${jsonString(profile.model)},
        |    "DEVICE": ${jsonString(profile.device)},
        |    "PRODUCT": ${jsonString(profile.product)},
        |    "FINGERPRINT": ${jsonString(profile.fingerprint)}
        |  }
        |}
    """.trimMargin()
}
