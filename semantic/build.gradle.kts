plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * 端侧中文语义检索内核。
 *
 * 单独成模块是有意为之：这一层不依赖任何应用域类型（没有 Article / Interest / UI），
 * 只认「文本进、向量出」，所以它能直接编成 AAR 给别的应用用。
 *
 * 判断依据很简单：**看它有没有 import com.example.feedreader.*** —— 有就是应用域，
 * 不该放这里。排序器（Ranker）依赖 Interest/SynonymDict，因此留在 :app。
 */
android {
    namespace = "io.github.pt123123.semantic"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 必须是 api 而不是 implementation：native 库（libonnxruntime.so）就在这个 AAR 里，
    // 用 implementation 的话它不会进 POM，消费者引了 AAR 却会在运行时抛
    // UnsatisfiedLinkError —— 而且报错点在 native 层，极难定位。
    //
    // 选完整版而非 mobile 见根目录 NOTES 与 app/build.gradle.kts 的注释：
    // onnxruntime-mobile 只吃 .ort 格式，加载 .onnx 会直接失败。
    api("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
}
