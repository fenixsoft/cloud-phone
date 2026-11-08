plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "cloudphone.source"
    compileSdk = 34

    defaultConfig {
        applicationId = "cloudphone.source"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    // composite build 依赖替换：解析为根目录 protocol/ 模块（group=cloudphone, name=protocol）
    implementation("cloudphone:protocol:0.1.0")
}
