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
    buildFeatures { buildConfig = true }
    flavorDimensions += "role"
    productFlavors {
        // Versão principal (a que já tens): lê e grava o ficheiro de dados.
        create("master") {
            dimension = "role"
            buildConfigField("boolean", "SLAVE", "false")
            resValue("string", "app_name", "RLTEC Ativos")
        }
        // Versão slave: tem TODAS as funcionalidades da principal e, além disso, importa regularmente
        // os dados do ficheiro JSON (também com a app fechada, como os avisos).
        // Tem outro identificador, por isso pode estar instalada ao lado da principal.
        create("slave") {
            dimension = "role"
            applicationIdSuffix = ".slave"
            versionNameSuffix = "-slave"
            buildConfigField("boolean", "SLAVE", "true")
            resValue("string", "app_name", "RLTEC Ativos Slave")
        }
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
    packaging {
        resources {
            excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md", "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }
}
dependencies {
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}

// Com duas versões (master e slave) os APK ficam em pastas diferentes. Depois de compilar, copia-os para
// app/build/outputs/apk/debug/ com nomes fixos, para qualquer workflow do GitHub os encontrar.
afterEvaluate {
    tasks.named("assembleDebug") {
        doLast {
            val out = layout.buildDirectory.get().asFile
            val dest = File(out, "outputs/apk/debug").apply { mkdirs() }
            File(out, "outputs/apk/master/debug/app-master-debug.apk").takeIf { it.exists() }
                ?.copyTo(File(dest, "app-debug.apk"), overwrite = true)
            File(out, "outputs/apk/slave/debug/app-slave-debug.apk").takeIf { it.exists() }
                ?.copyTo(File(dest, "app-slave-debug.apk"), overwrite = true)
        }
    }
}
