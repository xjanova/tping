plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.xjanova.tping"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xjanova.tping"
        minSdk = 26
        targetSdk = 34

        // === Semantic Versioning (source of truth) ===
        // Change this string to bump version. CI reads it for GitHub releases.
        val versionStr = "1.2.97"
        val parts = versionStr.split(".")
        versionCode = parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt()
        versionName = versionStr

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // APK signing cert SHA-256 for runtime integrity verification.
        // Must match the release keystore used for signing.
        buildConfigField(
            "String",
            "EXPECTED_SIGNING_CERT_HASH",
            "\"76e40e5c5193cd0c0b38ae8b7a1fd5f514bb55c95820b1d5ac7414966029caa7\""
        )
    }

    signingConfigs {
        create("release") {
            // Local: keystore/release.keystore
            // CI: decoded from KEYSTORE_BASE64 secret to same path
            val keystorePath = rootProject.file("keystore/release.keystore")
            if (keystorePath.exists()) {
                storeFile = keystorePath
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "TpingXman2024"
                keyAlias = "tping-release"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "TpingXman2024"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    lint {
        baseline = file("lint-baseline.xml")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose (BOM 2024.12.01 → Compose 1.7.6 with LazyColumn SlotTable fix)
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp for license API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Encrypted SharedPreferences for secure license storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OpenCV for slide puzzle CAPTCHA solving
    implementation("org.opencv:opencv:4.9.0")

    // ML Kit Barcode Scanning (QR Code scanner for license key)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // CameraX for QR scanner
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-mlkit-vision:1.4.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
