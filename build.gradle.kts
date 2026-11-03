plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wifitv.receiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wifitv.receiver"
        minSdk = 24            // Android 7.0+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Release signing: supply these in ~/.gradle/gradle.properties (never commit them).
    //   RELEASE_STORE_FILE=/path/to/keystore.jks  RELEASE_STORE_PASSWORD=...  RELEASE_KEY_ALIAS=...  RELEASE_KEY_PASSWORD=...
    val hasReleaseKey = project.hasProperty("RELEASE_STORE_FILE")
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(project.property("RELEASE_STORE_FILE") as String)
            storePassword = project.property("RELEASE_STORE_PASSWORD") as String
            keyAlias = project.property("RELEASE_KEY_ALIAS") as String
            keyPassword = project.property("RELEASE_KEY_PASSWORD") as String
        }
    }
    buildTypes {
        // "debug" is automatically signed with the SDK's debug keystore => installable APK.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.getstream:stream-webrtc-android:1.1.1")   // prebuilt libwebrtc for Android
}
