plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tuo.navi"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tuo.navi"
        minSdk = 30
        targetSdk = 35
        versionCode = 11
        versionName = "0.9.1-custode"
        ndk { abiFilters += listOf("arm64-v8a") } // Moto g32 e quasi tutti i telefoni recenti
    }
    buildTypes {
        // Build di sviluppo: Navi al livello 100, tutte le abilità sbloccate
        debug { buildConfigField("int", "NAVI_DEV_LEVEL", "100") }
        release { isMinifyEnabled = false; buildConfigField("int", "NAVI_DEV_LEVEL", "0") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // Ritaglio soggetto on-device (fase 1)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    // IA locale opzionale (Gemma 3 270M / 1B in formato .task)
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    testImplementation("junit:junit:4.13.2")
}
