plugins {
    id("com.android.application")
}

// Outputs Wareye-debug.apk / Wareye-release.apk
base {
    archivesName.set("Wareye")
}

android {
    namespace = "com.raunak.wareye"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.raunak.wareye"
        minSdk = 23 // LiteRT 2.x minimum
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Debug-signed so the release APK installs over USB without a keystore.
            // Replace with your own signingConfig before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
    androidResources {
        noCompress += "tflite"
        localeFilters += "en"
    }
    packaging {
        // Compress .so files inside the APK (smaller APK; Android extracts them on install).
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/**", "kotlin/**", "**.kotlin_builtins", "DebugProbesKt.bin") }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")

    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("com.google.ai.edge.litert:litert:2.1.5") {
        // Only needed for Play AI-pack model downloads (ModelProvider/ModelSelector), which we do not use.
        exclude(group = "com.google.android.play", module = "ai-delivery")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-guava")
        exclude(group = "com.google.guava", module = "guava")
    }
}
