import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing is never checked into the repository (see spec 41 - no hard-coded
 * credentials).  The keystore is supplied by the build environment; when it is absent
 * the release build simply stays unsigned instead of failing.
 */
val keystorePath: String? = System.getenv("POCKETAI_KEYSTORE")
    ?: providers.gradleProperty("pocketai.keystore").orNull
val keystorePassword: String? = System.getenv("POCKETAI_KEYSTORE_PASSWORD")
    ?: providers.gradleProperty("pocketai.keystorePassword").orNull
val keyAlias0: String? = System.getenv("POCKETAI_KEY_ALIAS")
    ?: providers.gradleProperty("pocketai.keyAlias").orNull
val keyPassword0: String? = System.getenv("POCKETAI_KEY_PASSWORD")
    ?: providers.gradleProperty("pocketai.keyPassword").orNull
val hasSigning = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

// Native inference is only built when the vendored llama.cpp checkout is present.
val llamaCppDir = file("src/main/cpp/llama.cpp")
val hasNativeSources = llamaCppDir.resolve("CMakeLists.txt").exists()
val vulkanEnabled = (providers.gradleProperty("pocketai.vulkan").orNull ?: "false").toBoolean()

android {
    namespace = "com.pocketai.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.pocketai.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            // 64-bit ARM only: every device that can realistically run local LLM
            // inference is arm64, and dropping 32-bit keeps the APK small.
            abiFilters += "arm64-v8a"
        }

        if (hasNativeSources) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DPOCKETAI_VULKAN=${if (vulkanEnabled) "ON" else "OFF"}"
                    )
                    cppFlags += "-O3"
                }
            }
        }
    }

    if (hasNativeSources) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAlias0
                keyPassword = keyPassword0
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
        }
        jniLibs {
            // ggml loads its CPU/GPU backend variants with dlopen() from the app's
            // nativeLibraryDir, so the .so files must exist as real files on disk.
            useLegacyPackaging = true
        }
    }

    androidResources {
        // Keep every density/locale: the app ships a single universal arm64 APK.
        noCompress += "gguf"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
