plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.clmulator"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.clmulator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("CLM_KS_PASS") ?: "clmulator"
                keyAlias = "clmulator"
                keyPassword = System.getenv("CLM_KS_PASS") ?: "clmulator"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (rootProject.file("release.jks").exists())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    packaging {
        // QEMU и его библиотеки должны лежать на диске как файлы (исполняем через ProcessBuilder)
        jniLibs.useLegacyPackaging = true
    }
    androidResources {
        noCompress += listOf("img", "iso")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
