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
    sourceSets.getByName("test").java.srcDir("src/testShared/java")
    sourceSets.getByName("androidTest").java.srcDir("src/testShared/java")
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("docs/fixtures/recognition-prep-v1"))
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("detector-assets"))
    sourceSets.getByName("test").resources {
        srcDir(rootProject.file("docs/fixtures/recognition-prep-v1"))
        srcDir("src/androidTest/assets")
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.opencv:opencv:4.14.0")
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

// Detector fixtures/models belong ONLY to the instrumentation APK. Never attach this
// validation to preBuild, main debug, JVM tests, or another module's task graph.
// .f32z/.u8z/.argbz are intentional: AAPT rewrites .gz assets.
data class DetectorAssetSpec(val file: java.io.File, val bytes: Int, val sha: String, val cap: Int)
val detectorManifestSha = "74aa1e39c8d3187ee2388ad07bb228e3c876c0486ccd02c47208942d26f2c80a"
val detectorModelSha = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
val detectorCaseIds = listOf("en-quality-16", "fr-nonrefundable-16", "zh-hans-quality-16", "zh-hant-quality-16",
    "mixed-quality-16", "blank-negative-24", "color-control", "wide-959", "wide-1499", "wide-2001")

fun detectorDigest(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
fun detectorRead(spec: DetectorAssetSpec): ByteArray {
    check(spec.bytes in 1..spec.cap && spec.file.isFile && spec.file.length() == spec.bytes.toLong()) {
        "Missing/invalid detector test asset: ${spec.file}. Prepare explicitly: python3 scripts/prepare-detector-probe.py --model <localpath>"
    }
    val raw = spec.file.inputStream().use { it.readNBytes(spec.bytes + 1) }
    check(raw.size == spec.bytes && detectorDigest(raw) == spec.sha) { "Detector test asset identity mismatch: ${spec.file}" }
    return raw
}
fun detectorAssetSpecs(): Map<String, DetectorAssetSpec> {
    val fixtures = file("src/androidTest/assets/detector-v1")
    val stage = layout.buildDirectory.dir("detector-assets/detector-model").get().asFile
    val expected = linkedMapOf<String, DetectorAssetSpec>()
    val manifestSpec = DetectorAssetSpec(fixtures.resolve("manifest.json"), 28958, detectorManifestSha, 128 * 1024)
    val manifest = groovy.json.JsonSlurper().parseText(String(detectorRead(manifestSpec), Charsets.UTF_8)) as Map<*, *>
    expected["assets/detector-v1/manifest.json"] = manifestSpec
    val files = manifest["files"] as Map<*, *>
    val names = detectorCaseIds.flatMap { id -> listOf("$id-source.png", "$id-resized.png", "$id-resized.argbz",
        "$id-input.f32z", "$id-output.f32z", "$id-mask.u8z") }.toSet() + "manifest.properties"
    check(files.keys == names && (manifest["schema"] as Number).toInt() == 1)
    val cases = manifest["cases"] as List<*>
    check(cases.map { (it as Map<*, *>)["id"] } == detectorCaseIds)
    cases.forEach { entry ->
        val c = entry as Map<*, *>
        val sw = (c["sourceWidth"] as Number).toLong(); val sh = (c["sourceHeight"] as Number).toLong()
        val w = (c["width"] as Number).toLong(); val h = (c["height"] as Number).toLong()
        check(sw in 1..4096 && sh in 1..4096 && w in 1..2048 && h in 1..2048 && w % 32 == 0L && h % 32 == 0L)
        val pixels = w * h; check(pixels in 1..1_048_576)
        check((c["inputShape"] as List<*>).map { (it as Number).toLong() } == listOf(1L, 3L, h, w))
        check((c["outputShape"] as List<*>).map { (it as Number).toLong() } == listOf(1L, 1L, h, w))
        mapOf("resizedArgb" to pixels * 4, "input" to pixels * 12, "output" to pixels * 4, "mask" to pixels).forEach { (key, size) ->
            val meta = files[c[key]] as Map<*, *>
            check((meta["decodedBytes"] as Number).toLong() == size)
            check((meta["decodedSha256"] as String).matches(Regex("[0-9a-f]{64}")))
        }
    }
    files.forEach { (name, entry) ->
        val meta = entry as Map<*, *>; val bytes = (meta["bytes"] as Number).toLong()
        check(bytes in 1..2L * 1024 * 1024)
        expected["assets/detector-v1/$name"] = DetectorAssetSpec(fixtures.resolve(name as String), bytes.toInt(), meta["sha256"] as String, 2 * 1024 * 1024)
    }
    val model = manifest["model"] as Map<*, *>
    check(model["asset"] == "detector-model/ch_PP-OCRv5_det_mobile.onnx" && model["sha256"] == detectorModelSha &&
        (model["bytes"] as Number).toInt() == 4_819_576)
    expected["assets/detector-model/ch_PP-OCRv5_det_mobile.onnx"] =
        DetectorAssetSpec(stage.resolve("ch_PP-OCRv5_det_mobile.onnx"), 4_819_576, detectorModelSha, 6 * 1024 * 1024)
    val legal = mapOf(
        "ModelScope-README.md.txt" to (1140 to "3dc91bb3cb667df783178917d69b38bfabb8c935596f34243a1c9f0d36916b6e"),
        "ORT-LICENSE.txt" to (1073 to "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c"),
        "ORT-ThirdPartyNotices.txt" to (338088 to "143764b952fdb1a7c69ce653bfba74a7744d6a8a573bfb73e235fba356c83de3"),
        "PaddleOCR-LICENSE.txt" to (11376 to "3840c5c0c61c294264d2dd77b8777be6ddd90121ef4e0e64abcd22edea581d6e"),
        "RapidOCR-LICENSE.txt" to (11422 to "3e0af25fdd06aa9586ae97adb00ea927ebe5a3805ac77d2d3a81ce5f55693333"),
        "NOTICE.txt" to (823 to "99d897a42f7740b564ed67ee38ae40dcbef17c3c4196fef28f6083ad76b3a3b5"),
        "provenance.json" to (1508 to "f67c1bf49934917d35826599f994b8565dacdd694682ac4dbfeaf02b167185a6"))
    legal.forEach { (name, identity) ->
        expected["assets/detector-model/legal/$name"] = DetectorAssetSpec(stage.resolve("legal/$name"), identity.first, identity.second, 512 * 1024)
    }
    val fixturePaths = fixtures.walkTopDown().filter { it.isFile }.map { "assets/detector-v1/${it.relativeTo(fixtures).invariantSeparatorsPath}" }.toSet()
    val stagePaths = stage.walkTopDown().filter { it.isFile }.map { "assets/detector-model/${it.relativeTo(stage).invariantSeparatorsPath}" }.toSet()
    check(fixturePaths + stagePaths == expected.keys) { "Detector test asset path set changed or local model is not prepared; run prepare-detector-probe.py --model <localpath>" }
    return expected
}
val validateDetectorProbeAssets by tasks.registering {
    doLast { detectorAssetSpecs().values.forEach { detectorRead(it) } }
}
tasks.matching { it.name == "mergeDebugAndroidTestAssets" || it.name == "packageDebugAndroidTest" }.configureEach {
    dependsOn(validateDetectorProbeAssets)
}
tasks.matching { it.name == "assembleDebugAndroidTest" }.configureEach {
    doLast {
        val expected = detectorAssetSpecs()
        val artifact = layout.buildDirectory.file("outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk").get().asFile
        ZipFile(artifact).use { apk ->
            val actual = apk.entries().asSequence().filter { !it.isDirectory &&
                (it.name.startsWith("assets/detector-v1/") || it.name.startsWith("assets/detector-model/")) }.toList()
            check(actual.size == expected.size && actual.map { it.name }.toSet() == expected.keys) { "Packaged detector paths/duplicates changed" }
            expected.forEach { (name, spec) ->
                val original = detectorRead(spec)
                val entry = apk.getEntry(name)
                check(entry.size == spec.bytes.toLong()) { "Packaged detector byte length changed: $name" }
                val packaged = apk.getInputStream(entry).use { it.readNBytes(spec.bytes + 1) }
                check(packaged.contentEquals(original)) { "Packaged detector bytes changed: $name" }
            }
        }
    }
}

// Official publication has stale .module size/hash metadata. Pin the actual AAR
// whose SHA-256 matches Maven Central's separate checksum; test configuration only.
val verifyOpenCvTestArtifact by tasks.registering {
    doLast {
        val artifacts = configurations.getByName("debugAndroidTestRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val artifact = artifacts.single { it.moduleVersion.id.group == "org.opencv" && it.name == "opencv" }
        check(artifact.moduleVersion.id.version == "4.14.0" && artifact.file.length() == 123_380_341L)
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == "6d11b40f6a54113dafe8540b1237b637193cb21e83deb11bc53d6757d35d494d")
    }
}
tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach { dependsOn(verifyOpenCvTestArtifact) }
