import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.kandong.modelprobe"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.kandong.modelprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-synthetic-model-probe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("docs/fixtures/recognition-prep-v1"))
    sourceSets.getByName("test").resources {
        srcDir(rootProject.file("docs/fixtures/recognition-prep-v1"))
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// This is deliberately task-time validation, never a configuration-time download.
val validateProbeAssets by tasks.registering {
    doLast {
        val root = file("src/main/assets")
        fun verify(path: String, bytes: Long?, sha: String, cap: Long) {
            val f = root.resolve(path)
            check(f.isFile && f.length() <= cap && (bytes == null || f.length() == bytes)) {
                "Missing/invalid probe assets. Run explicitly: python3 scripts/prepare-modelprobe-models.py"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    check(total <= cap) { "Probe asset exceeds cap" }
                    digest.update(buffer, 0, n)
                }
                check(bytes == null || total == bytes) { "Probe asset length changed" }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == sha) {
                "Probe asset digest mismatch. Run explicitly: python3 scripts/prepare-modelprobe-models.py"
            }
        }
        verify("probes/manifest.json", null, "126d8d3860d5a4ea098f0875838dbed51a55d8a321f50d409805d394a460061c", 131072)
        // Parse the same bounded, authenticated bytes, including if a file changes mid-task.
        val manifestBytes = root.resolve("probes/manifest.json").inputStream().use { it.readNBytes(131073) }
        check(manifestBytes.size <= 131072 && MessageDigest.getInstance("SHA-256")
            .digest(manifestBytes).joinToString("") { "%02x".format(it) } ==
            "126d8d3860d5a4ea098f0875838dbed51a55d8a321f50d409805d394a460061c")
        val manifest = groovy.json.JsonSlurper().parseText(String(manifestBytes, Charsets.UTF_8)) as Map<*, *>
        val models = manifest["models"] as List<*>
        val tasks = manifest["tasks"] as List<*>
        check(models.size == 2 && tasks.size == 10)
        models.forEach { entry ->
            val m = entry as Map<*, *>
            verify(m["asset"] as String, (m["bytes"] as Number).toLong(), m["sha256"] as String, 20L * 1024 * 1024)
            verify(m["dictionaryAsset"] as String, null, m["dictionarySha256"] as String, 256L * 1024)
        }
        tasks.forEach { entry ->
            val t = entry as Map<*, *>
            verify(t["asset"] as String, (t["compressedBytes"] as Number).toLong(), t["compressedSha256"] as String, 5L * 1024 * 1024)
        }
    }
}
tasks.named("preBuild") { dependsOn(validateProbeAssets) }

// AAPT rewrites .gz assets, so .f32z is intentional. Verify the delivered bytes,
// not only the source assets, before treating an assembled APK as usable.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val source = file("src/main/assets/probes")
        val expected = source.listFiles()!!.filter { it.isFile }.associateBy { "assets/probes/${it.name}" }
        ZipFile(layout.buildDirectory.file("outputs/apk/debug/modelprobe-debug.apk").get().asFile).use { apk ->
            val actual = apk.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("assets/probes/") }
                .map { it.name }.toSet()
            check(actual == expected.keys) { "Packaged probe asset paths changed" }
            expected.forEach { (name, original) ->
                val packaged = apk.getInputStream(apk.getEntry(name)).use { it.readBytes() }
                check(packaged.contentEquals(original.readBytes())) { "Packaged probe bytes changed: $name" }
            }
        }
    }
}
