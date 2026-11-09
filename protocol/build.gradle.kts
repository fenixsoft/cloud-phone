import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
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
    testImplementation(libs.junit)
}

application {
    // natlab 对端 JVM 进程（p2p-crossnet-traversal 4.2）：installDist 后经
    // ip netns exec 直接运行（纯 JVM transport 栈，无 Android 依赖）
    mainClass.set("cloudphone.natlab.NatlabPeerKt")
    applicationName = "natlab-peer"
}
