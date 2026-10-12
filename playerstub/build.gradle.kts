plugins {
    id("com.android.application")
}

android {
    namespace = "com.dylenq.playerstub"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dylenq.playerstub"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
