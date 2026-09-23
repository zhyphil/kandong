plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.kandong.qualitylab"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.kandong.qualitylab"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.0.3-viewport-experiment"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":graphics"))
    testImplementation("junit:junit:4.13.2")
}
