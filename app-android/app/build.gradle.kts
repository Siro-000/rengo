import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La clave de firma del club vive fuera del repo. keystore.properties (que tampoco se sube,
// ver .gitignore) dice dónde está y con qué contraseña se abre. Si no existe, la versión
// release sale sin firmar y la de desarrollo compila igual.
val firma = Properties().apply {
    val archivo = rootProject.file("keystore.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}

android {
    namespace = "com.rengo.tuner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rengo.tuner"
        minSdk = 24
        targetSdk = 34
        // Subirlo en cada versión que se reparta: si no sube, Android no la instala encima.
        versionCode = 1
        versionName = "1.0"
        resValue("string", "app_name", "Rengo Tuner")
    }

    signingConfigs {
        create("club") {
            if (!firma.isEmpty) {
                storeFile = file(firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!firma.isEmpty) signingConfig = signingConfigs.getByName("club")
        }
        // La versión de desarrollo es otra app en el celular: la firma la clave automática de
        // la compu y no la del club, y dos firmas distintas no pueden instalarse una encima
        // de la otra. Así conviven la oficial y la de pruebas.
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Rengo Tuner (debug)")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
