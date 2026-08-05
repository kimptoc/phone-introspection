plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "net.kimptoc.introspect"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.kimptoc.introspect"
        // T0 relies on PowerManager.getThermalHeadroom() and
        // ActivityManager.getHistoricalProcessExitReasons(), both added in API 30.
        // That is the floor for every T0 signal, so it is also the floor for the app.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.room:room-runtime:2.7.0-beta01")
    implementation("androidx.room:room-ktx:2.7.0-beta01")
    ksp("androidx.room:room-compiler:2.7.0-beta01")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
