plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.feedreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.feedreader"
        minSdk = 21
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        ndk {
            // 只出 64 位。端侧推理用完整版 onnxruntime（libonnxruntime.so 17.6MB/ABI），
            // 带上 armeabi-v7a 会让 APK 再多十几 MB，而 32 位 ARM 设备现在基本绝迹。
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 设置页要显示版本号
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 端侧语义检索内核（tokenizer / ONNX 编码器 / 向量缓存 / 模型下载）。
    // 拆成独立模块是为了将来能单独出 AAR 给别的应用用；onnxruntime 依赖随它传递过来。
    //
    // 运行时用**完整版 1.20.0** 而不是 mobile 1.18.0：mobile 是 minimal build，
    // 其 .so 里写着「Loading anything other than ORT format models is not enabled in this build」，
    // 只吃 .ort 格式，喂它 .onnx 会在建会话时直接失败。转 .ort 要多一道构建步骤
    // 和一次格式版本兼容验证，而 1.20.0 是**最后一个 minSdk 21 的版本**（1.23.0 起是 24），
    // 恰好满足本项目 minSdk 21。代价是 arm64 的 .so 从 3.6MB 涨到 17.6MB，
    // 所以 abiFilters 只留 arm64-v8a 把账压回来。
    implementation(project(":semantic"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 解析逻辑的单测：kxml2 提供的 XmlPullParser 实现让 RssParser 能脱离设备跑
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
