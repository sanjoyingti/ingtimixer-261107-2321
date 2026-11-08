plugins {
    id("org.jetbrains.kotlin.kapt")
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ingti.mixer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ingti.mixer"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    composeOptions {
        kotlinCompilerExtensionVersion = "2.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    
    // Resolve dependency conflicts
    configurations.all {
        resolutionStrategy {
            // Force compatible versions
            force("androidx.lifecycle:lifecycle-common:2.8.7")
            force("androidx.lifecycle:lifecycle-runtime:2.8.7")
            force("androidx.annotation:annotation:1.9.0")
            force("androidx.collection:collection:1.5.0")
        }
    }

    // Signing configuration for release builds (works in CI/Codespaces via env vars)
    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_FILE")
            val storePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")
            if (storeFilePath != null && storePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(storeFilePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else {
                // Unsigned release build for testing in Codespaces
                println("Warning: Keystore env vars not set. Building unsigned release APK.")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            // Generate APK instead of AAB for direct installation in Codespaces
            isDebuggable = false
        }
        getByName("debug") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("release") // Use same config for debug if needed
        }
    }

    // Packaging options for native libraries
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
        // Prevent duplicate native libs
        pickFirsts += listOf(
            "lib/arm64-v8a/libc++_shared.so",
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/arm64-v8a/libsherpa-onnx-c-api.so"
        )
    }

    // Ensure we can build APK for Codespaces
    bundle {
        abi {
            enableSplit = false
        }
        density {
            enableSplit = false
        }
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("de.sciss:jump3r:1.0.5")
    implementation("com.bihe0832.android:lib-sherpa-onnx:6.16.12")
    // Remove @aar classifier - use standard JAR
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
