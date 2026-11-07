import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 与 feature:shizukukit 模板同构，差异：
//  1) manifest 声明两个 receiver（ScheduleAlarmReceiver 精确闹钟到期执行 +
//     BootReceiver 开机重排），不做前台服务 —— goAsync 窗口足够覆盖常规
//     命令链（见 ScheduleAlarmReceiver KDoc）；
//  2) testImplementation 增加 kotlinx-coroutines-test（引擎超时/中断语义
//     用 runTest 虚拟时钟覆盖，catalog 已有条目，不新增第三方坐标）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.abyss.tools.feature.blueprint"
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
    // 纪律：只用 material-icons-core 已有图标（ArrowBack / Add /
    // KeyboardArrowDown / KeyboardArrowUp / PlayArrow / Close），不引 extended。
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 蓝图 / 执行历史 JSON 持久化（filesDir/blueprints/*.json + runs.json）
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // 引擎语义单测：runTest 虚拟时钟（DELAY 步骤 / 超时 / 中断）
    testImplementation(libs.kotlinx.coroutines.test)
}
