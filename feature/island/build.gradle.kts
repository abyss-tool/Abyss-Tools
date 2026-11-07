import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 与 feature:disguise / appmgr 模板同构，差异：
//  1) 不依赖 core:shell —— 灵动岛无 Shizuku 诉求（快捷面板只做展示类动作，
//     不承诺 dumpsys battery reset 等需要 shell 的能力，诚实边界）；
//  2) 增加 androidx-lifecycle-runtime-ktx —— FloatingBallService 里的
//     ServiceLifecycleOwner 桥接（LifecycleRegistry / ViewModelStore）是
//     ComposeView 在 Service 中渲染的 ViewTree 前提；
//  3) 无单测（Android WindowManager / 前台服务依赖重），不引 testImplementation。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.abyss.tools.feature.island"
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
    // 电池图标不在 core 集 —— IslandUi.kt 用 Canvas 自绘（外壳 + 真实电量填充 +
    // 充电闪电），零新增依赖。
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // ServiceLifecycleOwner（LifecycleRegistry / ViewModelStore 桥接 ComposeView）
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ViewTree 三桥接的另两件套：lifecycle-viewmodel（ViewTreeViewModelStoreOwner.set）
    // 与 savedstate（SavedStateRegistryController / ViewTreeSavedStateRegistryOwner.set）。
    // 这两个构件在依赖树里可能只以 implementation 传递（不进编译类路径）—— 显式
    // 声明保证 Service 内 ComposeView 桥接可编译（版本与 catalog lifecycle 线对齐）。
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
}
