import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.abyss.tools"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abyss.tools"
        minSdk = 26
        // ── targetSdk 钉 28：深渊工具的内核是终端/脚本执行（Termux 血统）────
        // targetSdk ≥ 29 的 app 运行在 SELinux untrusted_app_29+ 域，Android 10
        // 起该域禁止执行 app_data_file（W^X 强制），内置终端与 Python 引擎
        // 将不可用。Termux 至今钉 targetSdk 28 正是同因。本项目经 GitHub
        // Releases 侧载分发，不受 Play targetSdk 政策约束。
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    // ── 发布签名：入库 keystore（GitHub Releases 侧载分发的标准做法，────
    // Termux / F-Droid 同款；对齐 Android-Guru-Agent 体系）。CI 与本地共用
    // 同一份 → 签名指纹恒定 → 补丁更新 / 覆盖安装无签名冲突（宿主自更新
    // 网关的同签名门禁依赖此恒定性）。逃生门：ABYSS_KEYSTORE_FILE /
    // ABYSS_KEYSTORE_PASSWORD / ABYSS_KEY_ALIAS / ABYSS_KEY_PASSWORD
    // 环境变量可覆盖（CI secret 注入场景）。
    signingConfigs {
        create("release") {
            val keystoreFile = (project.findProperty("abyssKeystoreFile") as String?)
                ?: System.getenv("ABYSS_KEYSTORE_FILE")
                ?: rootProject.file("keystore/abyss-release.jks").absolutePath
            storeFile = file(keystoreFile)
            storePassword = (project.findProperty("abyssKeystorePassword") as String?)
                ?: System.getenv("ABYSS_KEYSTORE_PASSWORD") ?: "abyss-release-2026"
            keyAlias = (project.findProperty("abyssKeyAlias") as String?)
                ?: System.getenv("ABYSS_KEY_ALIAS") ?: "abyss-release"
            keyPassword = (project.findProperty("abyssKeyPassword") as String?)
                ?: System.getenv("ABYSS_KEY_PASSWORD") ?: "abyss-release-2026"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 混淆收编留待 v0.2 发布流水线（先保证签名通道可验证）
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation(project(":core:update"))
    implementation(project(":core:plugin"))
    implementation(project(":plugin-sdk:plugin-api"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:navhub"))
    implementation(project(":feature:plugins"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
