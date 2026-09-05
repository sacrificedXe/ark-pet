plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arkpet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arkpet"
        minSdk = 28
        targetSdk = 28
        versionCode = 12
        versionName = "0.5.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // 图片解码改用平台 ImageDecoder（API 28+），Glide 不再需要：
    // Glide 4.16 本体没有动画 WebP 解码器，多帧 VP8X 只解第一帧，动作会定格。
    implementation("dev.rikka.shizuku:api:13.1.5")
    // provider 是独立 artifact。上一版只加了 api 就在 manifest 声明
    // rikka.shizuku.ShizukuProvider，类不在包里 → App 启动实例化 provider 时
    // ClassNotFoundException → 进程当场死，表现为「一打开就闪退」。
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
