plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.paparazzi")
}

android {
    namespace = "com.emcniece.trigger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.emcniece.trigger"
        minSdk = 26
        targetSdk = 35
        // Release CI passes these from the git tag; local builds use the defaults.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
    }

    // Release signing comes from the environment (GitHub Actions secrets), never from the repo.
    val keystorePath: String? = System.getenv("TRIGGER_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TRIGGER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TRIGGER_KEY_ALIAS")
                keyPassword = System.getenv("TRIGGER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Separate app ID so debug and release builds can be installed side by side.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}

// Renders the README screenshots (ReadmeScreenshots.kt) with Paparazzi and copies them to docs/screenshots.
tasks.register<Copy>("updateScreenshots") {
    dependsOn("recordPaparazziDebug")
    from("src/test/snapshots/images")
    include("*_ReadmeScreenshots_*.png")
    into(rootProject.file("docs/screenshots"))
    rename(".*_ReadmeScreenshots_(.+)\\.png", "$1.png")
}
