import java.io.File
import java.util.Base64

// Anchor permanent debug keystore so every cloud build shares the exact same cryptographic signature
val keystoreFile = file("debug.keystore")
if (!keystoreFile.exists() || keystoreFile.length() == 0L) {
    val b64Keystore = """
    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
    SKWypwHTAgInEA==
    """.trimIndent().replace("\n", "").replace("\r", "").replace(" ", "")
    keystoreFile.writeBytes(Base64.getDecoder().decode(b64Keystore))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.haven.pacing"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.haven.pacing"
        minSdk = 26
        targetSdk = 34

        // Automatically increments with every GitHub Actions cloud build
        val buildRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 100
        versionCode = buildRun
        versionName = "1.0.$buildRun"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Health Connect Client
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
