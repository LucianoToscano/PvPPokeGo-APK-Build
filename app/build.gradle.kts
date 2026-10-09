import java.util.Properties

val releaseSigningProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun releaseSigningValue(propertyName: String, environmentName: String): String? =
    releaseSigningProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }

val releaseKeystorePath = releaseSigningValue("storeFile", "PVPPG_KEYSTORE_PATH")
val releaseKeystorePassword = releaseSigningValue("storePassword", "PVPPG_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "PVPPG_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "PVPPG_KEY_PASSWORD")
val hasPermanentReleaseSigning = listOf(
    releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lucianotoscano.pvppokego"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lucianotoscano.pvppokego"
        minSdk = 29
        targetSdk = 36
        versionCode = 35
        versionName = "0.5.30"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    flavorDimensions += "captureMode"
    productFlavors {
        create("standard") {
            dimension = "captureMode"
            buildConfigField("boolean", "RECORDER_COMPAT", "false")
        }
        create("recorderCompat") {
            dimension = "captureMode"
            buildConfigField("boolean", "RECORDER_COMPAT", "true")
            versionNameSuffix = "-recorder"
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    signingConfigs {
        create("permanentRelease") {
            if (hasPermanentReleaseSigning) {
                storeFile = rootProject.file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        // Keep the installable QA APK on the real applicationId so there is only one
        // PvPPokeGo on the device and launcher/icon/cache cannot point to an older app.
        debug { }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasPermanentReleaseSigning) {
                signingConfig = signingConfigs.getByName("permanentRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
