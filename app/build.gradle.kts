import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

val signingPropertiesFile = rootProject.file("signing/release-signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.galiehneh.gridcraft"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.galiehneh.gridcraft"
        minSdk = 24
        targetSdk = 35
        versionCode = 20261017
        versionName = "2026.10.16.1"
    }

    signingConfigs {
        if (signingPropertiesFile.isFile) {
            create("release") {
                val storeFilePath = signingProperties.getProperty("storeFile")
                storeFile = rootProject.file("signing").resolve(storeFilePath)
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingPropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.splashscreen)

    testImplementation(libs.junit)
}
