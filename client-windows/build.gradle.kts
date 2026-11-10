// cloudphone Windows 桌面客户端构建（design D1/D2）：
// Kotlin JVM 17 + Compose Desktop；JavaCV 按 classifier 仅引入 windows-x86_64/linux-x86_64 natives
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
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

// 4.1 jpackage 打包（compose.desktop.application DSL）：
// Windows 侧 packageMsi/packageExe；Linux 构建机经 createDistributable 产出 app-image
// 验证配置（spec 验收）。JavaCV/FFmpeg 经 javacpp 运行时解包 natives，
// 运行需 jdk.unsupported（Unsafe）；VM 无 GPU 场景默认以软件渲染参数启动（见 docs/windows-client.md）。
compose.desktop.application {
    mainClass = "cloudphone.desktop.MainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Msi, TargetFormat.Exe)
        packageName = "CloudPhoneDesktop"
        packageVersion = "0.1.0"
        description = "云手机 Windows 桌面串流客户端"
        vendor = "cloudphone"
        copyright = "© 2024 cloudphone"
        includeAllModules = true // JavaCV/FFmpeg natives 与 Unsafe 需要，避免缺模块运行时崩溃
        linux {
            iconFile.set(project.file("src/main/resources/icon.png"))
        }
        windows {
            menu = true
            shortcut = true
            dirChooser = true
            perUserInstall = true
            menuGroup = "CloudPhone"
        }
    }
}
