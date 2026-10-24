plugins {
    id("com.android.application")
}

android {
    namespace = "id.ringan.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.ringan.launcher"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Ditandatangani dengan kunci debug supaya APK release bisa langsung dipasang ke TV sendiri.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
