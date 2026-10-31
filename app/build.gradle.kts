plugins {
    id("com.android.application")
}

android {
    namespace = "io.dsh.screenstream"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.dsh.screenstream"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
