plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gpsmapcamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.gpsmapcamera"
        minSdk = 26
        targetSdk = 36 // Google Play requires Android 16 (API 36) for new apps
        // GitHub Actions sets VERSION_CODE for every build, so each build is newer than the last.
        val buildNumber = (System.getenv("VERSION_CODE") ?: "4").toInt()
        versionCode = buildNumber
        versionName = "3.0.$buildNumber"
    }

    buildFeatures {
        buildConfig = true
    }

    // Two versions of the same app:
    //  - github: shared by download link, updates itself from GitHub Releases
    //  - play:   Google Play Store, own app ID, updated by Play (no self-updater)
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "SIDELOAD", "true")
        }
        create("play") {
            dimension = "distribution"
            applicationId = "in.avipsolar.tools"
            buildConfigField("boolean", "SIDELOAD", "false")
        }
    }

    signingConfigs {
        // Same key on every build, so new link versions install over old ones.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Google Play upload key: supplied by GitHub Actions secrets, never stored in the repo.
        val uploadStore = System.getenv("UPLOAD_KEYSTORE_FILE")
        if (uploadStore != null && file(uploadStore).exists()) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
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
    val camerax = "1.4.2"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Scanning (Google ML Kit through Google Play services: free, on-device, keeps the app small)
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
