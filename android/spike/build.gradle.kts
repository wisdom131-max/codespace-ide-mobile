import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// UI SPIKE (advisor order 2026-10-27): a SEPARATE debug APK that installs BESIDE
// the main app (applicationId com.codespace.ide.spike, label "VN Spike") and
// touches none of its data. One screen, built two ways — native Compose with
// design tokens (pane A) vs bundled WebView (pane B, WebViewAssetLoader, no
// remote content, fonts/CSS/JS in the APK, narrow typed bridge, CSP) — plus a
// Run button that drives both and prints a COPYABLE results block. Decision
// rule: WebView (B) only ships if its frame time ties or beats native (A) and
// PSS memory stays within +25 MB. Safe to uninstall afterwards: standalone app.

android {
    namespace = "com.codespace.ide.spike"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.codespace.ide.spike"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
        // Same BUILD STAMP pattern as the main app so results identify the APK.
        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER") ?: "local"
        buildConfigField("String", "CI_RUN_NUMBER", "\"$ciRunNumber\"")
        val buildTime = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())
        } catch (_: Exception) { "unknown" }
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
        getByName("debug") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
