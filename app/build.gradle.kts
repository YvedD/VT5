plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
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
        debug { isMinifyEnabled = false }
    }

    buildFeatures { compose = true }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Compose UI + Material3
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Material icons (oogje)
    implementation("androidx.compose.material:material-icons-extended")

    // SAF helper
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Versleutelde opslag
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // JSON (al aanwezig)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ★ Binaire serialisatie (compact en snel)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.7.3")
    // DoubleMetaphone, ColognePhonetic
    implementation("commons-codec:commons-codec:1.17.1")
    // LevenshteinDistance, JaroWinkler etc.
    implementation("org.apache.commons:commons-text:1.12.0")

    // HTTP client + coroutines
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}


/** Testtaken uit. */
tasks.matching {
    it.name.startsWith("test", ignoreCase = true) || it.name.startsWith("connectedAndroidTest", ignoreCase = true)
}.configureEach { this.enabled = false }
