import java.util.Properties                          // keystore.properties reader

// The app module: Kotlin + Jetpack Compose UI, and the native decoders (open-source C/C++) built with the NDK (src/main/cpp).
plugins {
    alias(libs.plugins.android.application)          // Android app
    alias(libs.plugins.kotlin.compose)               // Compose compiler
}

// ARCH-705 release signing comes from the private build-server environment.
val signing = Properties().apply {
    val store = System.getenv("IC705_KEYSTORE")
    if (!store.isNullOrBlank()) {
        setProperty("storeFile", store)
        setProperty("storePassword", System.getenv("IC705_KEYSTORE_PASSWORD") ?: "")
        setProperty("keyAlias", System.getenv("IC705_KEY_ALIAS") ?: "")
        setProperty("keyPassword", System.getenv("IC705_KEY_PASSWORD") ?: "")
    }
}

val buildingRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (buildingRelease && signing.getProperty("storeFile") == null) {
    throw GradleException("ARCH-705 release signing is required: set IC705_KEYSTORE, IC705_KEYSTORE_PASSWORD, IC705_KEY_ALIAS and IC705_KEY_PASSWORD")
}

android {
    namespace = "uk.hamdigital"                      // Kotlin package / R class
    compileSdk = 37                                  // Android 17 APIs (the current Compose libraries need them)
    ndkVersion = "30.0.16248370"                     // NDK from the SDK Manager

    defaultConfig {
        applicationId = "cn.arch.ic705remote"              // installed app id
        minSdk = 26                                  // Android 8.0 and newer
        targetSdk = 35                               // Android 15 behaviour
        versionCode = 30
        versionName = "2.3.9"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } // phones and tablets (ARM); x86_64 (the emulator, a few old Intel tablets) left out from 0.12.0: 28 MB smaller - add it back here to run in the emulator
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") { // the release key
            storeFile = file(signing.getProperty("storeFile"))     // keystore
            storePassword = signing.getProperty("storePassword")  // its password
            keyAlias = signing.getProperty("keyAlias")            // the key in it
            keyPassword = signing.getProperty("keyPassword")      // the key's password
        }
    }

    buildTypes {
        debug {
            // Debug builds use the Android debug key, never the release key.
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it } // signed with the release key when it is set up
            isMinifyEnabled = false                  // no shrinking (keeps JNI names simple)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 // Java 17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true } // Jetpack Compose; BuildConfig (VERSION_NAME for the startup screen and Guide)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt") // the decoders
            version = "4.1.2"                        // CMake from the SDK Manager
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)                     // Kotlin helpers for Android
    implementation(libs.androidx.lifecycle.runtime.ktx)        // lifecycle scopes
    implementation(libs.androidx.lifecycle.viewmodel.compose)  // viewModel() in Compose
    implementation(libs.androidx.lifecycle.runtime.compose)    // collectAsStateWithLifecycle
    implementation(libs.androidx.activity.compose)             // setContent
    implementation(platform(libs.androidx.compose.bom))        // Compose versions
    implementation(libs.androidx.ui)                           // Compose UI
    implementation(libs.androidx.ui.graphics)                  // drawing
    implementation(libs.androidx.ui.tooling.preview)           // previews
    implementation(libs.androidx.material3)                    // Material 3
    implementation(libs.androidx.material.icons.extended)      // icons
    implementation(libs.androidx.datastore.preferences)        // settings
    implementation(libs.kotlinx.coroutines.android)            // coroutines
    implementation(libs.usb.serial)                            // USB serial (CDC-ACM) for the IC-705 CI-V port (MIT)
    debugImplementation(libs.androidx.ui.tooling)              // layout inspector
    testImplementation("junit:junit:4.13.2")
}



