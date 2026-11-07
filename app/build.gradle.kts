import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ── 版本单一事实源：AppInfo.kt（评审 #13）────────────────────────────
// build.gradle.kts 不再持有版本字面量 —— 从 core/common 的 AppInfo.kt 推导
// （正则解析 const），升版只改 AppInfo 一处；发布流水线与
// tools/validate_manifests.py 同源校验，彻底消灭「三处同步」。
val appInfoFile = rootProject.file("core/common/src/main/kotlin/com/abyss/tools/core/common/AppInfo.kt")
val appInfoVersionName = Regex("""VERSION_NAME:\s*String\s*=\s*\"([^\"]+)\"""")
    .find(appInfoFile.readText())?.groupValues?.getOrNull(1)
    ?: throw GradleException("AppInfo.kt 解析失败：VERSION_NAME 缺失（$appInfoFile）")
val appInfoVersionCode = Regex("""VERSION_CODE:\s*Int\s*=\s*(\d+)""")
    .find(appInfoFile.readText())?.groupValues?.getOrNull(1)?.toInt()
    ?: throw GradleException("AppInfo.kt 解析失败：VERSION_CODE 缺失（$appInfoFile）")

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
        versionCode = appInfoVersionCode
        versionName = appInfoVersionName
    }

    // ── 发布签名（v0.6.0 起口令与 keystore 出库，经机密注入）────────────
    // 供给链：CI 由 GitHub Actions secrets（ABYSS_KEYSTORE_B64 解码到临时
    // 文件 + ABYSS_KEYSTORE_PASSWORD / ABYSS_KEY_ALIAS / ABYSS_KEY_PASSWORD
    // 环境变量）；本地构建用 local.properties 的 abyssKeystoreFile /
    // abyssKeystorePassword / abyssKeyAlias / abyssKeyPassword 或同名环境变量。
    //
    // ⚠ 诚实声明（历史风险，有意取舍）：v0.5.0 期间 jks 与口令曾入库公开，
    //   理论上应视为已泄露并轮换 —— 但轮换会破坏「指纹恒定 → 覆盖安装 /
    //   补丁更新链」导致存量用户卸载重装。当前决策：保持 meng411722 身份
    //   不轮换（指纹硬校验见 install/PublisherSignatures.PINNED_SHA256），
    //   未来若轮换走 docs/SECURITY.md 迁移路径。
    val releaseKeystoreFile = (project.findProperty("abyssKeystoreFile") as String?)
        ?: System.getenv("ABYSS_KEYSTORE_FILE")
    val releaseStorePassword = (project.findProperty("abyssKeystorePassword") as String?)
        ?: System.getenv("ABYSS_KEYSTORE_PASSWORD")
    val releaseKeyAlias = (project.findProperty("abyssKeyAlias") as String?)
        ?: System.getenv("ABYSS_KEY_ALIAS") ?: "meng411722"
    val releaseKeyPassword = (project.findProperty("abyssKeyPassword") as String?)
        ?: System.getenv("ABYSS_KEY_PASSWORD")

    // 材料缺失（fork PR / 未配机密）：release 构建降级为未签名产物 ——
    // 编译门禁照常生效，发布门禁由 release.yml 的指纹校验兜底（主仓必有机密）。
    val releaseSigningReady = releaseKeystoreFile != null && file(releaseKeystoreFile).exists() &&
        !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk 26：v2/v3 全覆盖，v1 仅为 < Nougat 老系统（评审 #14）
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("release") else null
            // R8 混淆收编留待 v0.2 发布流水线（先保证签名通道可验证）
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // targetSdk=28 是刻意工程决策（Termux 同款：untrusted_app_29+ 域的
        // W^X 强制会击杀终端 / Python 引擎的 execve）。本项目经 GitHub
        // Releases 侧载分发，不受 Play targetSdk 政策约束 —— 该检查项
        // 对本渠道语义不适用，显式关闭（其余 lint 门禁照常生效）。
        disable += "ExpiredTargetSdkVersion"
    }

    // ── Android 15/16 适配声明 ────────────────────────────────────────────
    // 16KB page size（Android 15 起部分设备强制）：本仓主壳无任何 native
    // 库（纯 Kotlin/Compose，依赖链亦无 .so），自动满足 16KB 对齐要求；
    // 显式声明 useLegacyPackaging=false 前向兼容（未来引入 .so 时，AGP
    // 会按 16KB 对齐标准打包，见 docs/ANDROID_COMPAT.md 适配矩阵）。
    packaging {
        jniLibs {
            useLegacyPackaging = false
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
    implementation(project(":core:shell"))
    implementation(project(":plugin-sdk:plugin-api"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:navhub"))
    implementation(project(":feature:plugins"))
    implementation(project(":feature:disguise"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:appmgr"))
    implementation(project(":feature:shizukukit"))
    implementation(project(":feature:qrcraft"))
    implementation(project(":feature:blueprint"))
    implementation(project(":feature:island"))
    implementation(project(":feature:wallpaper"))

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
