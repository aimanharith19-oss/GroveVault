plugins {
    id("com.android.application")
}

android {
    namespace = "org.grove.vault"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.grove.vault"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Reproducible, tracker-free: no Google dependency metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
