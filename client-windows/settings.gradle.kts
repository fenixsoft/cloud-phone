// cloudphone Windows 桌面客户端（design D1：独立 Gradle 工程，复合构建共享引用 ../protocol）
// 协议/传输零重写：cloudphone:protocol:0.1.0 经下方 includeBuild 替换为本地 ../protocol 模块，
// protocol 类仅来自 ../protocol 单一事实源，本工程不 fork 任何协议实现。
pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}
rootProject.name = "cloudphone-client-windows"
includeBuild("../protocol")
