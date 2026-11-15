import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 媒体实验室（v0.8.0 插件内置化）—— MediaExtractor/MediaMuxer 级音视频处理
// （提取音频 / 去除音轨 / 无重编码裁剪），纯系统 API 无 native 依赖。
// 与 feature:qrcraft 同构（Photo Picker 选源 + MediaStore 双分支保存 + 分享授权），
// 差异：
//  1) 依赖 core:common —— 源信息探测走 UiState 三态（Loading / Error / Data，
//     「加载中显示假数据」禁令对本地探测同样生效）；
//  2) 无持久化 —— 任务队列是内存态（页面即生命周期），kotlinx.serialization 不引入；
//  3) testImplementation junit —— MediaJobs 纯 JVM 单测（时长 / 字节 / 码率格式化
//     + MediaJob 默认值语义）；MediaMux 的 android.media 部分由真机肉眼验收，不进单测。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.abyss.tools.feature.media"
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
    // UiState 三态（探测出口 Result → UiState 的本地版约定）
    implementation(project(":core:common"))
    // 共享组件（SectionHeader / InfoRow / StatusChip / LoadingState / ErrorState / EmptyState）
    implementation(project(":core:designsystem"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // 纪律：只用 material-icons-core 已有图标（PlayArrow / Share / ArrowBack），不引 extended。
    implementation(libs.compose.material.icons.core)
    // Photo Picker（PickVisualMedia.VideoOnly）+ API 26–28 写公共目录的运行时权限申请
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
