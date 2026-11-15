import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 模块使命：内置 Shizuku Shell 终端 —— v0.8.0 从 abyss-plugin-terminal 插件内置化
// （原插件仓归档，功能并入主壳，不再占用插件中心位次；与路线图上的本地终端
// 引擎（Termux 血统）互补：本页只做 adb shell 上下文的单命令顺序执行）。
//
// 与 feature:disguise 模板同构（Android library / Kotlin 17 / Compose），差异：
//  1) 依赖增加 ui-graphics —— 终端等宽文本渲染路径的显式声明，防间接依赖漂移；
//  2) 不引 activity-compose —— 终端页无 Activity 侧回调（相机 / 结果回传那种）需求；
//  3) testImplementation junit —— TerminalShared 纯逻辑单测（ConsoleBuffer 裁剪 /
//     CommandHistory 去重与裁剪），对齐 core:shell 的 ShizukuCommandsTest 形态。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.abyss.tools.feature.terminal"
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
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // 纪律：只用 material-icons-core 已有图标（ArrowBack / Send / List / Delete），
    // 不引 extended。
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
