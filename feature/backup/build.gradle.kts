// 备份迁移（v0.8.0 插件内置化）—— APK 资料库 / 配置与插件包导出（zip）/ 恢复（zip-slip 防护）；
// 应用私有数据抽取需插件能力（路线图，诚实边界）。
//
// 依赖口径：
//  - core:common（AppInfo.VERSION_NAME 写入 manifest 来源版本）+ core:designsystem
//    （SectionHeader / InfoRow / StatusChip / UiState 纪律组件）；
//  - manifest.json 读写用 org.json（Android 平台内置，零新增依赖）；
//    纯 JVM 单测侧的 manifest 编解码用本仓手写模板 + 正则（见
//    BackupManifestIo 的 why 注释）—— 不引 kotlinx-serialization；
//  - 不依赖 core:shell —— 备份导出 / 恢复全走应用自身能力（MediaStore /
//    filesDir），无 Shizuku 诉求；/data/data 级抽取才是插件能力（诚实边界）。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // v0.8.x：BackupArchive（蓝图层备份包）的 @Serializable manifest 走
    // kotlinx-serialization —— 纯 JVM 单测同样依赖编译器插件生成 serializer。
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.abyss.tools.feature.backup"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // 纪律：只用 material-icons-core 已有图标（Share / PlayArrow / Clear /
    // Search / ArrowBack），不引 extended。
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
