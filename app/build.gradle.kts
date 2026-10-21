plugins {
    id("com.android.application")
}

// GitHub Actions da un número que sube en cada compilación; en local queda en 1.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.juandegates.multipantalla"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.juandegates.multipantalla"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.3.$buildNumber"
    }

    signingConfigs {
        getByName("debug") {
            // Llave fija para que cada APK nuevo se instale encima del anterior.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
