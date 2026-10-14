plugins { id("com.android.application") }
android {
    namespace = "com.lucianotoscano.pokesearchoverlay"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.lucianotoscano.pokesearchoverlay"
        minSdk = 26
        targetSdk = 32
        versionCode = 11
        versionName = "0.11-alpha"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
