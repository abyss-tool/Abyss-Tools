import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.abyss.tools.core.shell"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Shizuku：api = 客户端接口（binder / newProcess / 授权）；provider = 宿主侧
    // ShizukuProvider 声明（manifest 自动合并进 :app，无需手工注册）。
    api(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
