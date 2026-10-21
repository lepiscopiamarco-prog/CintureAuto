plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.marco.cintureauto"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.marco.cintureauto"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"

        // Solo i processori dei telefoni veri: l'APK resta più leggero
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Chiave di firma fissa (inclusa nel progetto): così ogni nuova versione compilata si installa
    // sopra la precedente senza disinstallare. È una chiave "di debug": va bene per un'app personale.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // Riconoscimento vocale offline (assistente «Ehi Mercedes»), licenza Apache 2.0
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
