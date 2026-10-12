plugins {
    id("com.android.application")
}

android {
    namespace = "com.dylenq.eq"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dylenq.eq"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-spike"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
}
