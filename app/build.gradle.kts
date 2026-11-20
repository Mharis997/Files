import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dualframe.recorder"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dualframe.recorder"
        minSdk = 30
        targetSdk = 36
        versionCode = 11
        versionName = "2.2"
    }
    // Fixed debug key in the repo, so every build installs over the previous one. Not for Play Store releases.
    signingConfigs {
        getByName("debug") { storeFile = file("debug.keystore") }
        // Play upload key: keystore.properties and the .jks are kept out of git (see .gitignore)
        val props = rootProject.file("keystore.properties")
        if (props.exists()) create("release") {
            val p = Properties().apply { props.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
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
