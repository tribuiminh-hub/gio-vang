plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.giovang.child"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.giovang.child"
        minSdk = 26
        targetSdk = 34
        // Mỗi lần GitHub build, số phiên bản tăng lên để cài đè được bản cũ
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Khóa ký cố định để các bản cập nhật cài đè lên nhau được
    signingConfigs {
        create("family") {
            storeFile = file("giovang.keystore")
            storePassword = "giovang-family"
            keyAlias = "giovang"
            keyPassword = "giovang-family"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("family")
        }
        debug {
            signingConfig = signingConfigs.getByName("family")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
}
