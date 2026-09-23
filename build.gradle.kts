plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.feedreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "feedreader"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Enable RSA encryption for secure storage
        buildFeatures {
            rsaEncryption = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.foundation:foundation-ktx:1.5.0")
    implementation("com.squareup.recyclerview:recyclerview:1.3.2")
    implementation("com.google.code.gson:gson:2.10.1")
}
