// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

android {
    namespace = "com.yvesds.vt5"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.yvesds.vt5"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // Compose UIT — ViewBinding AAN
    buildFeatures {
        viewBinding = true
        compose = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Material Components (XML)
    implementation("com.google.android.material:material:1.12.0")

    // Lifecycle (optioneel handig)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // SAF helper
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Versleutelde opslag
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.7.3")

    // Fuzzy & phonetic
    implementation("commons-codec:commons-codec:1.17.1")
    implementation("org.apache.commons:commons-text:1.12.0")

    // HTTP + coroutines
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// Tests uit, zoals bij jou
tasks.matching {
    it.name.startsWith("test", ignoreCase = true) || it.name.startsWith("connectedAndroidTest", ignoreCase = true)
}.configureEach {
    this.enabled = false
}
