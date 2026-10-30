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

    buildTypes {
        release {
            // 框架期：R8 混淆与签名收编留待 v0.2 发布流水线接入
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
