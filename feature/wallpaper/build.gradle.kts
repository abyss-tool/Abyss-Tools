import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 与 feature:disguise 模板同构，差异：
//  1) 不依赖 core:shell —— 壁纸纯本地渲染（Canvas 光斑 + SharedPreferences），
//     无 Shizuku / shell 诉求；
//  2) manifest 声明 AbyssWallpaperService（BIND_WALLPAPER，随模块 manifest
//     合并进 app，app 侧零改动）；
//  3) testImplementation junit —— WallpaperScene 建场 / 漂移几何纯 JVM 单测
//     （drawFrame 的 android.graphics 部分由真机肉眼验收，不进单测）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.abyss.tools.feature.wallpaper"
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
    // 纪律：只用 material-icons-core 已有图标（ArrowBack / Check），不引 extended。
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
