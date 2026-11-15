plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建号：GitHub 上是 run_number，本地编是 0
val buildNumber = ((findProperty("buildNumber") as String?) ?: System.getenv("GITHUB_RUN_NUMBER"))?.toIntOrNull() ?: 0

android {
    namespace = "com.langhua98.vania"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.langhua98.vania"
        // 3D 角色要 WebGL 2，Android 8 以下的 WebView 跑不动
        minSdk = 26
        targetSdk = 35
        // 每次出新版都要改版本号（AGENTS.md）：versionName 和 versionCode 一起加
        versionCode = 5
        versionName = "0.5"
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        // 只打真手机用的两种 CPU（ML Kit 带的原生库每种都有一份，全打进去安装包要多一百多 MB）
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // 固定签名（signing/vania.jks，见 AGENTS.md「签名」）：每个版本签名一样，手机上才能直接覆盖安装
        create("fixed") {
            storeFile = rootProject.file("signing/vania.jks")
            storePassword = "vania-android"
            keyAlias = "vania"
            keyPassword = "vania-android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // 角色模型很大，打包时不压缩（压了也小不了多少，读起来还慢）
    androidResources {
        noCompress += listOf("glb", "jpg", "png")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // 单元测试在电脑上跑，安卓的类都是空壳：调到了就返回默认值，不报错
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // 用 https://appassets.androidplatform.net/ 读 App 里的网页，模型、贴图都算同源
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 拍照（不用预览）、以后的跟着人转和眼睛
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 跟着人转、眼睛：ML Kit 的模型打包在 App 里，在手机本地识别，不联网、不要谷歌服务
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // 安卓自带的 org.json 在电脑上跑测试时是空壳，测试用真的
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
