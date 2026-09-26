plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "pt.garagem.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "pt.garagem.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("garagem") {
            storeFile = file("garagem.keystore.jks")
            storePassword = "garagem123"
            keyAlias = "garagem"
            keyPassword = "garagem123"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("garagem")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("garagem")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.webkit:webkit:1.9.0") }
