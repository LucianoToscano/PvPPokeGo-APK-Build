plugins { id("com.android.application") }
android {
    namespace = "com.lucianotoscano.pokesearchoverlay"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.lucianotoscano.pokesearchoverlay"
        minSdk = 26
        targetSdk = 32
        versionCode = 7
        versionName = "0.7-alpha"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
