plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sandbox6x"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sandbox6x"
        minSdk = 24
        targetSdk = 34
        // Her GitHub derlemesinde artar; böylece yeni APK eskisinin üzerine kurulur.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Sabit anahtar: her derleme aynı imzayı taşır, güncelleme veri kaybetmeden kurulur.
    signingConfigs {
        create("fixed") {
            storeFile = file("release.jks")
            storePassword = "sandbox6x"
            keyAlias = "sandbox"
            keyPassword = "sandbox6x"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
