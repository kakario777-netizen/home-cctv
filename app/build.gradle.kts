plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vName = (project.findProperty("versionName") as String?) ?: "0.1.0"
val vCode = vName.split(".").map { it.toIntOrNull() ?: 0 }.let {
    it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }
}

android {
    namespace = "com.kakario.homecctv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kakario.homecctv"
        minSdk = 21
        targetSdk = 34
        versionCode = vCode
        versionName = vName
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "homecctv"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.getstream:stream-webrtc-android:1.3.8")
}
