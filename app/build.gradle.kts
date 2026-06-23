plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.prixref.ao"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.prixref.ao"
        // Android 8.0 (Oreo) et plus.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Clé de signature stable et partagée par tous les builds (CI inclus),
        // afin que les nouvelles versions s'installent par-dessus l'ancienne
        // sans avoir à désinstaller l'application.
        create("stable") {
            storeFile = file("prixref-release.jks")
            storePassword = "prixref2026"
            keyAlias = "prixref"
            keyPassword = "prixref2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle + coroutines pour exécuter les accès base hors du thread UI.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room — persistance locale SQLite de l'historique des analyses.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Gson — sérialisation JSON des analyses complètes.
    implementation("com.google.code.gson:gson:2.11.0")

    // Retrofit + OkHttp — appel du backend d'analyse (scraping Playwright).
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Tests unitaires du moteur de calcul.
    testImplementation("junit:junit:4.13.2")
}
