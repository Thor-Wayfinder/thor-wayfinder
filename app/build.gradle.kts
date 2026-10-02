import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: read from ~/.wayfinder/release.properties, never from
// the repo — storeFile, storePassword, keyAlias, keyPassword. Made by tools/make_release_key.ps1.
// Missing → the release APK comes out unsigned.
val releaseKey = Properties().apply {
    // WAYFINDER_KEY_DIR: another folder (a test key, a CI secret mount)
    val dir = System.getenv("WAYFINDER_KEY_DIR")?.let { File(it) } ?: File(System.getProperty("user.home"), ".wayfinder")
    val f = File(dir, "release.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "app.wayfinder"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.wayfinder"
        // Per-display tracking needs AccessibilityWindowInfo.getDisplayId() and
        // getWindowsOnAllDisplays(), both API 30 — the app cannot function below that.
        minSdk = 30
        targetSdk = 34
        versionCode = 8
        versionName = "1.4.1"

    }

    signingConfigs {
        if (releaseKey.getProperty("storeFile") != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Shrinks the LIBRARIES (unused Material icons etc.: 43 MB of code → far less) — the
            // service's start-up peak got it killed at boot. Wayfinder's own
            // code is kept whole (proguard-rules.pro).
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-release.pro"
            )
        }
        debug {
            // `-Pminify`: a debug build shrunk like the release (to measure / test it on the Thor)
            isMinifyEnabled = providers.gradleProperty("minify").isPresent
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
        buildConfig = true   // BuildConfig.DEBUG gates debug-only logging
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Persistence for input bindings, per-app config, and settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Shizuku — shell-level (UID 2000) authority for am commands and binder calls
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // Reflection access to hidden APIs (IActivityTaskManager.moveRootTaskToDisplay)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
