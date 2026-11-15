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
        versionCode = 2
        versionName = "0.2"
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
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

    testImplementation("junit:junit:4.13.2")
    // 安卓自带的 org.json 在电脑上跑测试时是空壳，测试用真的
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
