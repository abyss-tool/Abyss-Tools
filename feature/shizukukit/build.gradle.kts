import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 与 feature:disguise 模板同构，差异：
//  1) plugins 增加 kotlin.serialization（执行历史 JSON 持久化）；
//  2) 依赖增加 kotlinx-serialization-json（version catalog 已有条目，
//     不新增第三方坐标）；
//  3) testImplementation junit —— KitCommands 纯函数单测（对齐 core:shell
//     的 ShizukuCommandsTest 形态）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.abyss.tools.feature.shizukukit"
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
    implementation(project(":core:shell"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // 纪律：只用 material-icons-core 已有图标（ArrowBack / KeyboardArrowDown），
    // 不引 extended。
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 执行历史持久化（filesDir/shizuku-kit/history.json）
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
