// cloudphone Windows 桌面客户端构建（design D1/D2）：
// Kotlin JVM 17 + Compose Desktop；JavaCV 按 classifier 仅引入 windows-x86_64/linux-x86_64 natives
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    application
}

group = "cloudphone"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    // 协议消费（D1）：坐标经 settings.gradle.kts includeBuild("../protocol") 复合构建替换为本地模块
    implementation("cloudphone:protocol:0.1.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // JavaCV/FFmpeg（D2/D3）：javacv-platform 的 classifier 受限等价物——仅 windows-x86_64 +
    // linux-x86_64 两平台 natives（javacv-platform 聚合会拖入全平台数百 MB，按设计裁掉）。
    // linux 用 -gpl 变体：单测需 libx264 合成 H.264 样例流；-gpl 构建为超集（解码路径不受影响）。
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.javacpp) { classifier("windows-x86_64") })
    runtimeOnly(variantOf(libs.javacpp) { classifier("linux-x86_64") })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier("linux-x86_64-gpl") })

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("cloudphone.desktop.MainKt")
}
