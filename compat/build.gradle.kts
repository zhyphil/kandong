import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val releaseCredentialsFile = rootProject.file(".signing/release.properties")
val releaseCredentials = Properties().apply {
    if (releaseCredentialsFile.isFile) releaseCredentialsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.kandong.compat"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.kandong.compat"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.0"
    }
    signingConfigs {
        if (releaseCredentialsFile.isFile) {
            create("release") {
                fun credential(name: String): String = requireNotNull(releaseCredentials.getProperty(name)) {
                    "Missing release signing property: $name"
                }
                storeFile = rootProject.file(credential("storeFile"))
                storePassword = credential("storePassword")
                keyAlias = credential("keyAlias")
                keyPassword = credential("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (releaseCredentialsFile.isFile) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { testImplementation("junit:junit:4.13.2") }
