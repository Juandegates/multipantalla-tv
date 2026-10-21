// Solo para compilar: copia mínima de clases internas de Android (AudioPolicy).
// No se empaqueta en el APK; en la TV se usan las clases reales del sistema.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.juandegates.stubs"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
