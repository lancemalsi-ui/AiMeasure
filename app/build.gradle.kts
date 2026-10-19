import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String, default: String) = localProps.getProperty(name, default)

android {
    namespace = "com.example.aimeasure"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aimeasure"
        minSdk = 24
        targetSdk = 34
        // CI sets these so every GitHub build installs over the previous one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: "1.0-dev"
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${prop("ANTHROPIC_API_KEY", "")}\"")
        buildConfigField("String", "ANTHROPIC_BASE_URL", "\"${prop("ANTHROPIC_BASE_URL", "https://api.anthropic.com")}\"")
        buildConfigField("String", "CLAUDE_MODEL", "\"${prop("CLAUDE_MODEL", "claude-sonnet-5-5")}\"")
    }

    buildFeatures { buildConfig = true }

    // A shared debug key (committed on purpose) so APKs from different CI runs can update each other.
    // It is NOT a secret. Use your own private key before publishing to the Play Store.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.ar:core:1.44.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
