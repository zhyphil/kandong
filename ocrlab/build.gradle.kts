plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.kandong.ocrlab"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.kandong.ocrlab"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-synthetic-ocr"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDir("src/contextShared/java")
    // Metadata-only privacy cases exercise the same contract on JVM and Android.
    sourceSets.getByName("test").java.srcDir("src/captureTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/captureTest/java")
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
