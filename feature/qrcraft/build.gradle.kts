import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 与 feature:disguise 模板同构，差异：
//  1) plugins 增加 kotlin.serialization（历史记录 JSON 持久化）；
//  2) 依赖增加 zxing:core（纯 JVM 编解码引擎，字符串坐标直写，不动 version catalog）
//     与 kotlinx-serialization-json；
//  3) 不依赖 core:shell —— 二维码工坊无 Shizuku 需求（纯本地编解码 + 图片 IO），
//     也不依赖 core:common（无远端数据 / UiState 诉求）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.abyss.tools.feature.qrcraft"
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
    // 共享组件（SectionHeader / StatusChip / EmptyState / ErrorState），色彩全部由 AbyssTheme 派生
    implementation(project(":core:designsystem"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // zxing core：纯 JVM 条码引擎（零 Android 依赖，QR / Code 128 / EAN-13 / UPC-A / ITF）。
    // 版本 3.5.3 按任务规格以字符串坐标直写，不进 version catalog。
    implementation("com.google.zxing:core:3.5.3")

    // 历史记录持久化（filesDir/qr-craft/history.json）
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
