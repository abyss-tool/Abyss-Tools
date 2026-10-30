import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 插件契约模块：宿主与插件两侧共同消费的稳定 ABI —— 独立成模块，
// 未来拆分为独立发布构件（com.abyss.tools:plugin-api）时零迁移成本。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
