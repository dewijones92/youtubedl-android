// L4: ytdlp-kt — a typed Kotlin (coroutines/Flow) SDK over the youtubedl-android API-23 runtime.
// Consumers (PipePipe, SmartTube, ...) depend on this clean abstraction instead of the CLI wrapper.
plugins {
    id("com.yausername.youtubedl_android")
    id("signing")
    id("com.android.library")
    id("maven-publish")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dewijones92.ytdlpkt"
    compileSdk = 34

    defaultConfig {
        minSdk = 23 // Android 6.0 — matches the from-source native runtime
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }
}

configurePublishing {
    artifactId = project.name
    isPublished = true
}

dependencies {
    // The native runtime layers this SDK wraps (init + execution happen via these).
    implementation(project(":library"))
    implementation(project(":ffmpeg"))
    implementation(project(":aria2c"))

    implementation("androidx.core:core-ktx:${rootProject.extra["coreKtxVer"]}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.extra["jacksonVer"]}")

    testImplementation("junit:junit:${rootProject.extra["junitVer"]}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
}
