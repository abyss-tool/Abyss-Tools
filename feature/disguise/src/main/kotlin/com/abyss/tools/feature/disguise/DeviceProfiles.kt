package com.abyss.tools.feature.disguise

/**
 * 机型伪装档案库（v2 —— 全量 Build 字段）。
 *
 * 档案分三层消费（与 [SpoofLevel] 一一对应）：
 *  1. 系统设备名（Settings.Global.DEVICE_NAME）—— Shizuku 写入即刻生效；
 *  2. 标准 Build 指纹档案 —— 导出 JSON，由沙箱双开插件的 Hook 层在
 *     虚拟空间内对目标 App 生效（Build.MODEL / BRAND / FINGERPRINT …）；
 *  3. 深度全字段档案 —— 追加 BOOTLOADER / HARDWARE / BOARD / RADIO /
 *     ID / TAGS / VERSION.INCREMENTAL / SUPPORTED_ABIS 等，对抗读取
 *     更多 Build 字段的设备指纹 SDK。
 *
 * 指纹格式对齐 AOSP Build.FINGERPRINT：
 * brand/product/device:release/id/version_incremental
 *
 * 档案数据为公开 Build 参数形态（主流旗舰的真实代号 / 型号 / 指纹
 * 近似形态）；exact-to-the-letter 的指纹可用「自定义档案」精调。
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
    /** Build.HARDWARE（SoC 平台代号）。 */
    val hardware: String = "qcom",
    /** Build.BOARD（主板代号）。 */
    val board: String = device,
    /** Build.BOOTLOADER。 */
    val bootloader: String = "unknown",
    /** Build.getRadioVersion()（基带版本，空 = 档案不注入该字段）。 */
    val radioVersion: String = "",
    /** Build.VERSION.INCREMENTAL（fingerprint 第 5 段：brand/product/device:release/id/`incremental`/tags/type）。 */
    val versionIncremental: String = fingerprint.split('/').getOrNull(4) ?: "",
    /** Build.ID（安全补丁构建 ID，fingerprint 第 4 段）。 */
    val buildId: String = fingerprint.split('/').getOrNull(3) ?: "",
    /** Build.TAGS。 */
    val tags: String = "release-keys",
    /** Build.DISPLAY（系统版本号展示串）。 */
    val display: String = "",
    /** Build.SUPPORTED_ABIS。 */
    val supportedAbis: String = "arm64-v8a,armeabi-v7a,armeabi",
    /** Build.VERSION.RELEASE。 */
    val androidRelease: String = fingerprint.substringAfter(':').substringBefore('/'),
) {
    /** 厂商分组（档案库过滤用）。 */
    val vendor: Vendor
        get() = when (brand.lowercase()) {
            "google" -> Vendor.GOOGLE
            "samsung" -> Vendor.SAMSUNG
            "xiaomi", "redmi" -> Vendor.XIAOMI
            "huawei" -> Vendor.HUAWEI
            "honor" -> Vendor.HONOR
            "oneplus", "oppo", "realme" -> Vendor.OPPO
            "vivo", "iqoo" -> Vendor.VIVO
            else -> Vendor.OTHER
        }
}

/** 档案库厂商分组（UI 过滤 chips）。 */
enum class Vendor(val label: String) {
    GOOGLE("谷歌"),
    SAMSUNG("三星"),
    XIAOMI("小米系"),
    HUAWEI("华为"),
    HONOR("荣耀"),
    OPPO("欧加系"),
    VIVO("vivo 系"),
    OTHER("其他"),
}

/**
 * 伪装程度（三级）—— 应用机型伪装时按档位执行的动作集合：
 *
 *  - [NAME_ONLY]（L1 轻度）：仅写系统设备名 —— 快速分享 / 投放等系统
 *    界面即刻生效，App 层无感；
 *  - [STANDARD]（L2 标准）：L1 + 导出标准指纹档案（MODEL / BRAND /
 *    MANUFACTURER / DEVICE / PRODUCT / FINGERPRINT），沙箱双开插件的
 *    Hook 层在虚拟空间内对目标 App 生效 —— 应用级伪装的正路；
 *  - [DEEP]（L3 深度）：L2 + 全量 Build 字段档案（BOOTLOADER /
 *    HARDWARE / BOARD / RADIO / ID / TAGS / INCREMENTAL / ABIS …）
 *    + 蓝牙名同步改写 —— 对抗读取多字段做设备指纹的风控 SDK。
 */
enum class SpoofLevel(val label: String, val summary: String) {
    NAME_ONLY("轻度", "仅系统设备名"),
    STANDARD("标准", "设备名 + Build 指纹档案"),
    DEEP("深度", "全字段档案 + 蓝牙名"),
}

object DeviceProfiles {

    /** 预置旗舰档案（26 款，覆盖主流厂商全系）。 */
    val presets: List<ModelProfile> = listOf(
        // ── Google ────────────────────────────────────────────────────
        ModelProfile(
            id = "pixel-10-pro", name = "Pixel 10 Pro", brand = "google",
            manufacturer = "Google", model = "Pixel 10 Pro",
            device = "frankel", product = "frankel",
            fingerprint = "google/frankel/frankel:16/BP4A.250805.014/12947290:user/release-keys",
            hardware = "zuma", board = "gs201", bootloader = "frankel-16.0-1.0-0000000",
        ),
        ModelProfile(
            id = "pixel-10-pro-xl", name = "Pixel 10 Pro XL", brand = "google",
            manufacturer = "Google", model = "Pixel 10 Pro XL",
            device = "copper", product = "copper",
            fingerprint = "google/copper/copper:16/BP4A.250805.014/12947290:user/release-keys",
            hardware = "zuma", board = "gs201", bootloader = "copper-16.0-1.0-0000000",
        ),
        ModelProfile(
            id = "pixel-9-pro", name = "Pixel 9 Pro", brand = "google",
            manufacturer = "Google", model = "Pixel 9 Pro",
            device = "tokay", product = "tokay",
            fingerprint = "google/tokay/tokay:15/AP4A.250105.002/12838999:user/release-keys",
            hardware = "zuma", board = "zumapro", bootloader = "tokay-15.0-1.0-0000000",
        ),
        ModelProfile(
            id = "pixel-8-pro", name = "Pixel 8 Pro", brand = "google",
            manufacturer = "Google", model = "Pixel 8 Pro",
            device = "husky", product = "husky",
            fingerprint = "google/husky/husky:14/UQ1a.240205.004/11259548:user/release-keys",
            hardware = "zuma", board = "gs201", bootloader = "husky-14.0-1.0-0000000",
        ),
        // ── Samsung ───────────────────────────────────────────────────
        ModelProfile(
            id = "galaxy-s25-ultra", name = "Galaxy S25 Ultra", brand = "samsung",
            manufacturer = "samsung", model = "SM-S9380",
            device = "dm3q", product = "dm3qsccx",
            fingerprint = "samsung/dm3qsccx/dm3q:15/AP3A.250105.014/S9380ZHU2AYD1:user/release-keys",
            hardware = "qcom", board = "pineapple",
        ),
        ModelProfile(
            id = "galaxy-s24-ultra", name = "Galaxy S24 Ultra", brand = "samsung",
            manufacturer = "samsung", model = "SM-S9280",
            device = "s5k3lu", product = "s5k3luzhx",
            fingerprint = "samsung/s5k3luzhx/s5k3lu:14/UP1A.231005.007/S9280ZHU2AXBC:user/release-keys",
            hardware = "qcom", board = "pineapple",
        ),
        ModelProfile(
            id = "galaxy-z-fold6", name = "Galaxy Z Fold6", brand = "samsung",
            manufacturer = "samsung", model = "SM-F9560",
            device = "q6q", product = "q6qsccx",
            fingerprint = "samsung/q6qsccx/q6q:14/UP1A.231005.007/F9560ZHU2AXK1:user/release-keys",
            hardware = "qcom", board = "pineapple",
        ),
        // ── 小米系 ─────────────────────────────────────────────────────
        ModelProfile(
            id = "xiaomi-15-ultra", name = "Xiaomi 15 Ultra", brand = "Xiaomi",
            manufacturer = "Xiaomi", model = "24129PN74C",
            device = "odin", product = "odin",
            fingerprint = "Xiaomi/odin/odin:15/AQ3A.250205.002/V816.0.5.0.UNOCNXM:user/release-keys",
            hardware = "qcom", board = "sun",
        ),
        ModelProfile(
            id = "xiaomi-15", name = "Xiaomi 15", brand = "Xiaomi",
            manufacturer = "Xiaomi", model = "250101PN7CC",
            device = "dada", product = "dada",
            fingerprint = "Xiaomi/dada/dada:15/AQ3A.250205.002/V816.0.7.0.UNACNXM:user/release-keys",
            hardware = "qcom", board = "sun",
        ),
        ModelProfile(
            id = "xiaomi-14-ultra", name = "Xiaomi 14 Ultra", brand = "Xiaomi",
            manufacturer = "Xiaomi", model = "24030PN60G",
            device = "shennong", product = "shennong_global",
            fingerprint = "Xiaomi/shennong_global/shennong:14/UKQ1.230917.001/V816.0.5.0.UNACNXM:user/release-keys",
            hardware = "qcom", board = "pineapple",
        ),
        ModelProfile(
            id = "redmi-k80-pro", name = "Redmi K80 Pro", brand = "Redmi",
            manufacturer = "Xiaomi", model = "24122PKC4C",
            device = "durian", product = "durian",
            fingerprint = "Redmi/durian/durian:15/AQ3A.250205.002/V816.0.8.0.UNOCNXM:user/release-keys",
            hardware = "qcom", board = "sun",
        ),
        ModelProfile(
            id = "redmi-k70-pro", name = "Redmi K70 Pro", brand = "Redmi",
            manufacturer = "Xiaomi", model = "23117RK66C",
            device = "manet", product = "manet",
            fingerprint = "Redmi/manet/manet:14/UKQ1.230804.001/V816.0.5.0.UMNCNXM:user/release-keys",
            hardware = "qcom", board = "pineapple",
        ),
        // ── 华为 ───────────────────────────────────────────────────────
        ModelProfile(
            id = "mate-70-pro", name = "HUAWEI Mate 70 Pro", brand = "HUAWEI",
            manufacturer = "HUAWEI", model = "BNE-AL00",
            device = "HNBNE", product = "BNE-AL00",
            fingerprint = "HUAWEI/BNE-AL00/HNBNE:14/HUAWEIBNE-AL00/120008C004:user/HUAWEISDCAMP-keys",
            hardware = "kirin9000s", board = "kirin9000s",
            supportedAbis = "arm64-v8a,armeabi-v7a,armeabi",
        ),
        ModelProfile(
            id = "mate-60-pro", name = "HUAWEI Mate 60 Pro", brand = "HUAWEI",
            manufacturer = "HUAWEI", model = "ALN-AL10",
            device = "HNALN", product = "ALN-AL10",
            fingerprint = "HUAWEI/ALN-AL10/HWALN:14/HUAWEIALN-AL10/110008C004:user/HUAWEISDCAMP-keys",
            hardware = "kirin9000s", board = "kirin9000s",
        ),
        // ── 荣耀 ───────────────────────────────────────────────────────
        ModelProfile(
            id = "honor-magic7-pro", name = "荣耀 Magic7 Pro", brand = "HONOR",
            manufacturer = "HONOR", model = "PGT-AN20",
            device = "HNPGT", product = "PGT-AN20",
            fingerprint = "HONOR/PGT-AN20/HNPGT:15/AP4A.250105.002/110008C004:user/release-keys",
            hardware = "qcom", board = "sun",
        ),
        ModelProfile(
            id = "honor-magic6-pro", name = "荣耀 Magic6 Pro", brand = "HONOR",
            manufacturer = "HONOR", model = "BVL-AN16",
            device = "HNBVL", product = "BVL-AN16",
            fingerprint = "HONOR/BVL-AN16/HNBVL:14/UD1A.230817.005/110008C004:user/release-keys",
            hardware = "qcom", board = "pineapple",
        ),
        // ── 欧加系（OnePlus / OPPO / realme）────────────────────────────
        ModelProfile(
            id = "oneplus-13", name = "OnePlus 13", brand = "OnePlus",
            manufacturer = "OnePlus", model = "CPH2649",
            device = "OP5D5DL1", product = "CPH2649",
            fingerprint = "OnePlus/CPH2649/OP5D5DL1:15/AP3A.241105.007/T.R4T3.1731188584151:user/release-keys",
            hardware = "qcom", board = "sun",
        ),
        ModelProfile(
            id = "oneplus-12", name = "OnePlus 12", brand = "OnePlus",
            manufacturer = "OnePlus", model = "CPH2581",
            device = "OP5D27L1", product = "CPH2581",
            fingerprint = "OnePlus/CPH2581/OP5D27L1:14/UKQ1.230917.001/T.R4T3.1716982977625:user/release-keys",
            hardware = "qcom", board = "pineapple",
        ),
        ModelProfile(
            id = "find-x8-ultra", name = "OPPO Find X8 Ultra", brand = "OPPO",
            manufacturer = "OPPO", model = "PHU110",
            device = "OP5J7L1", product = "PHU110",
            fingerprint = "OPPO/PHU110/OP5J7L1:15/AP3A.241105.007/T.R1T3.1732189136841:user/release-keys",
            hardware = "qcom", board = "sun",
        ),
        ModelProfile(
            id = "find-x7-ultra", name = "OPPO Find X7 Ultra", brand = "OPPO",
            manufacturer = "OPPO", model = "PHZ110",
            device = "OP5D79L1", product = "PHZ110",
            fingerprint = "OPPO/PHZ110/OP5D79L1:14/UKQ1.230917.001/T.R1T3.1708989482713:user/release-keys",
            hardware = "qcom", board = "pineapple",
        ),
        ModelProfile(
            id = "realme-gt7-pro", name = "真我 GT7 Pro", brand = "realme",
            manufacturer = "realme", model = "RMX3951",
            device = "RE58C1L1", product = "RMX3951",
            fingerprint = "realme/RMX3951/RE58C1L1:15/AP3A.241105.007/S.1T3.1023.1:user/release-keys",
            hardware = "qcom", board = "sun",
        ),
        // ── vivo 系（vivo / iQOO）──────────────────────────────────────
        ModelProfile(
            id = "vivo-x200-pro", name = "vivo X200 Pro", brand = "vivo",
            manufacturer = "vivo", model = "V2413A",
            device = "PD2418", product = "PD2418",
            fingerprint = "vivo/PD2418/PD2418:15/UP1A.231005.007/compiler11151032:user/release-keys",
            hardware = "dimensity9400", board = "dimensity9400",
        ),
        ModelProfile(
            id = "vivo-x100-pro", name = "vivo X100 Pro", brand = "vivo",
            manufacturer = "vivo", model = "V2311A",
            device = "PD2318", product = "PD2318",
            fingerprint = "vivo/PD2318/PD2318:14/UP1A.231005.007/compiler04031626:user/release-keys",
            hardware = "dimensity9300", board = "dimensity9300",
        ),
        ModelProfile(
            id = "iqoo-13", name = "iQOO 13", brand = "iQOO",
            manufacturer = "vivo", model = "V2402A",
            device = "I2412", product = "I2412",
            fingerprint = "iQOO/I2412/I2412:15/AP3A.241105.007/compiler11181738:user/release-keys",
            hardware = "qcom", board = "sun",
        ),
        // ── 其他 ───────────────────────────────────────────────────────
        ModelProfile(
            id = "nubia-z70-ultra", name = "努比亚 Z70 Ultra", brand = "nubia",
            manufacturer = "nubia", model = "NX721J",
            device = "NX721J", product = "NX721J",
            fingerprint = "nubia/NX721J/NX721J:15/AP3A.241105.007/NX721JV1.0.09:user/release-keys",
            hardware = "qcom", board = "sun",
        ),
        ModelProfile(
            id = "nothing-phone-3", name = "Nothing Phone (3)", brand = "Nothing",
            manufacturer = "Nothing", model = "A063",
            device = "A063", product = "A063",
            fingerprint = "Nothing/A063/A063:15/AP4A.250105.002/2409261740:user/release-keys",
            hardware = "qcom", board = "sun",
            supportedAbis = "arm64-v8a,armeabi-v7a,armeabi",
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

    /**
     * 档案 → 沙箱 Hook 层可消费的 JSON（schema v2，按 [SpoofLevel] 决定
     * 字段完备度）：STANDARD 导出核心 Build 字段；DEEP 追加全量字段。
     */
    fun exportJson(profile: ModelProfile, level: SpoofLevel = SpoofLevel.STANDARD): String {
        val deep = level == SpoofLevel.DEEP
        val extraFields = if (deep) {
            """,
        |    "bootloader": ${jsonString(profile.bootloader)},
        |    "hardware": ${jsonString(profile.hardware)},
        |    "board": ${jsonString(profile.board)},
        |    "radio": ${jsonString(profile.radioVersion)},
        |    "buildId": ${jsonString(profile.buildId)},
        |    "tags": ${jsonString(profile.tags)},
        |    "versionIncremental": ${jsonString(profile.versionIncremental)},
        |    "display": ${jsonString(profile.display)},
        |    "supportedAbis": ${jsonString(profile.supportedAbis)},
        |    "androidRelease": ${jsonString(profile.androidRelease)}"""
        } else {
            ""
        }
        return """
        |{
        |  "schema": "abyss-model-profile-v2",
        |  "level": ${jsonString(level.name)},
        |  "id": ${jsonString(profile.id)},
        |  "name": ${jsonString(profile.name)},
        |  "brand": ${jsonString(profile.brand)},
        |  "manufacturer": ${jsonString(profile.manufacturer)},
        |  "model": ${jsonString(profile.model)},
        |  "device": ${jsonString(profile.device)},
        |  "product": ${jsonString(profile.product)},
        |  "fingerprint": ${jsonString(profile.fingerprint)},$extraFields
        |  "buildFields": {
        |    "BRAND": ${jsonString(profile.brand)},
        |    "MANUFACTURER": ${jsonString(profile.manufacturer)},
        |    "MODEL": ${jsonString(profile.model)},
        |    "DEVICE": ${jsonString(profile.device)},
        |    "PRODUCT": ${jsonString(profile.product)},
        |    "FINGERPRINT": ${jsonString(profile.fingerprint)}${if (deep) """,
        |    "BOOTLOADER": ${jsonString(profile.bootloader)},
        |    "HARDWARE": ${jsonString(profile.hardware)},
        |    "BOARD": ${jsonString(profile.board)},
        |    "RADIO": ${jsonString(profile.radioVersion)},
        |    "ID": ${jsonString(profile.buildId)},
        |    "TAGS": ${jsonString(profile.tags)},
        |    "VERSION_INCREMENTAL": ${jsonString(profile.versionIncremental)},
        |    "DISPLAY": ${jsonString(profile.display)},
        |    "SUPPORTED_ABIS": ${jsonString(profile.supportedAbis)},
        |    "VERSION_RELEASE": ${jsonString(profile.androidRelease)}""" else ""}
        |  }
        |}
        """.trimMargin()
    }
}
