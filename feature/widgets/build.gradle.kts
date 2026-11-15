import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 组件工坊（v0.8.0）：黑洞动态时钟桌面组件 —— AppWidgetProvider 每分钟
// 重绘位图（AlarmManager 精确到分的自续期链 + 开机重排）+ Compose 组件
// 工坊实时预览（与 RemoteViews 共用同一绘制实现，所见即所得 —— 对齐
// SignalStatusArt 的单一实现纪律）。
//
// 与 feature:wallpaper 同构的纪律：
//  - 无 Shizuku / shell 诉求，不依赖 core:shell；
//  - 纯几何 / 时间数学（BlackHoleMath）零 Android 依赖可单测；
//  - RemoteViews 位图绘制部分由真机肉眼验收，不进单测。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.abyss.tools.feature.widgets"
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
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
