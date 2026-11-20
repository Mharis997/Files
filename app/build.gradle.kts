plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dualframe.recorder"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dualframe.recorder"
        minSdk = 30
        targetSdk = 35
        versionCode = 9
        versionName = "2.0"
    }
    // Fixed debug key in the repo, so every build installs over the previous one. Not for Play Store releases.
    signingConfigs {
        getByName("debug") { storeFile = file("debug.keystore") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
