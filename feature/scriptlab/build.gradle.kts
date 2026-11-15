import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 脚本实验室（v0.8.0 Python 插件内置化的第一阶）—— 编辑 + 保存 + 经 Shizuku 执行 Shell 脚本；
// Python 运行时能力探测（系统自带 / 无则诚实降级为只读提示），完整 Python 引擎（Chaquopy）
// 属后续内置化路线.
//
// 与 feature:disguise 模板同构（Android library / Kotlin 17 / Compose），差异：
//  1) 依赖增加 ui-graphics —— 等宽文本渲染路径的显式声明，防间接依赖漂移；
//  2) 不引 activity-compose —— 本页无 Activity 侧回调（相机 / 结果回传那种）需求；
//  3) testImplementation junit —— ScriptLabShared 纯逻辑单测（名字门禁矩阵 / 存储
//     原子写往返 / 版本探测解析 / 命令构造纪律），对齐 feature:terminal 的形态。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.abyss.tools.feature.scriptlab"
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
    // 纪律：只用 material-icons-core 已有图标（ArrowBack / PlayArrow / MoreVert），
    // 不引 extended。
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
