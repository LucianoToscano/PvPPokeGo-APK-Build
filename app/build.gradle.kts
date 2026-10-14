plugins { id("com.android.application") }
android {
    namespace = "com.lucianotoscano.pokesearchoverlay"
    compileSdk = 36
    defaultConfig {
        // Separate distribution identity: avoids signature conflicts with previous alpha APKs.
        applicationId = "com.lucianotoscano.pokesearch"
        minSdk = 26
        targetSdk = 32
        versionCode = 35
        versionName = "0.35-beta"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
