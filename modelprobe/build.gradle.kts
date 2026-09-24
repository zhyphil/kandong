import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.GZIPInputStream

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
    sourceSets.getByName("test").java.srcDir("src/contextTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/contextTest/java")
    // Reuse the pure synthetic context contract in tests only; no OCR/product dependency.
    sourceSets.getByName("test").java.srcDir(rootProject.file("ocrlab/src/contextShared/java"))
    sourceSets.getByName("androidTest").java.srcDir(rootProject.file("ocrlab/src/contextShared/java"))
    sourceSets.getByName("test").java.srcDir("src/testShared/java")
    sourceSets.getByName("androidTest").java.srcDir("src/testShared/java")
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("docs/fixtures/recognition-prep-v1"))
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("detector-assets"))
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("geometry-assets"))
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("polygon-offset-assets"))
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("box-trace-assets"))
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("crop-recognition-assets"))
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
    androidTestImplementation("org.opencv:opencv:5.0.0.1")
    // Packaged synthetic language screening only; never a main/product dependency.
    androidTestImplementation("com.google.mlkit:language-id:17.0.6")
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
        check(artifact.moduleVersion.id.version == "5.0.0.1" && artifact.file.length() == 153_027_846L)
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == "edb1406a223d2820460b8366a790238b400f5d5c9ea2e98d44b889f0f3c66849")
    }
}
tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach { dependsOn(verifyOpenCvTestArtifact) }

// Geometry is an independent androidTest namespace. Do NOT apply an include filter
// to the shared assets SourceDirectorySet: doing so would remove older probe assets.
val geometryManifestSha = "1aa14f2efb50e62c5a2d95b45b7d5b5044d4ec90afa47a976e8dea50bbe466a6"
val geometryIds = listOf("en-quality-16", "fr-nonrefundable-16", "zh-hans-quality-16", "zh-hant-quality-16", "mixed-quality-16",
    "blank-negative-24", "color-control", "wide-959", "wide-1499", "wide-2001", "threshold-equal", "below-box-score",
    "tiny-negative", "two-blocks", "edge-touching", "vertical")
val geometryCropCounts = listOf(1, 1, 2, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 1, 1)
val geometryNames = geometryIds.flatMapIndexed { i, id ->
    listOf("$id-source.png", "$id-probability.f32z", "$id-mask.u8z", "$id-dilated.u8z") +
        List(geometryCropCounts[i]) { "$id-crop-${it.toString().padStart(2, '0')}.png" }
}.toSet()
val geometryRoot = rootProject.file("docs/fixtures/detector-geometry-v1")
fun geometryAssetSpecs(): Map<String, DetectorAssetSpec> {
    check(geometryNames.size == 77)
    val manifestFile = geometryRoot.resolve("manifest.json")
    check(manifestFile.isFile && manifestFile.length() in 1..131072L)
    val manifest = DetectorAssetSpec(manifestFile, manifestFile.length().toInt(), geometryManifestSha, 131072)
    val m = groovy.json.JsonSlurper().parseText(String(detectorRead(manifest), Charsets.UTF_8)) as Map<*, *>
    check((m["schema"] as Number).toInt() == 1 && m["parentManifestSha256"] == detectorManifestSha)
    val files = m["files"] as Map<*, *>; check(files.keys == geometryNames)
    val cases = m["cases"] as List<*>; check(cases.size == 16)
    cases.forEachIndexed { i, entry ->
        val c = entry as Map<*, *>; val id = geometryIds[i]; check(c["id"] == id)
        check(c["source"] == "$id-source.png" && c["probability"] == "$id-probability.f32z" &&
            c["mask"] == "$id-mask.u8z" && c["dilatedMask"] == "$id-dilated.u8z")
        val shape = (c["probabilityShape"] as List<*>).map { (it as Number).toLong() }
        check(shape.size == 4 && shape[0] == 1L && shape[1] == 1L && shape[2] in 1..4096L && shape[3] in 1..4096L)
        val pixels = shape[2] * shape[3]; check(pixels in 1..1_048_576L)
        mapOf("$id-probability.f32z" to pixels * 4, "$id-mask.u8z" to pixels, "$id-dilated.u8z" to pixels).forEach { (name, size) ->
            check(((files[name] as Map<*, *>)["decodedBytes"] as Number).toLong() == size)
        }
        val rows = c["boxes"] as List<*>; check(rows.size == geometryCropCounts[i])
        rows.forEachIndexed { rank, row ->
            val r = row as Map<*, *>
            check(r["crop"] == "$id-crop-${rank.toString().padStart(2, '0')}.png" && (r["readingOrder"] as Number).toInt() == rank)
        }
    }
    val specs = linkedMapOf("manifest.json" to manifest)
    geometryNames.forEach { name ->
        val f = files[name] as Map<*, *>; val size = (f["bytes"] as Number).toLong()
        check(size in 1..2_097_152L && (f["sha256"] as String).matches(Regex("[0-9a-f]{64}")))
        if (name.endsWith(".png")) {
            val w = (f["width"] as Number).toLong(); val h = (f["height"] as Number).toLong()
            check(w in 1..4096L && h in 1..4096L && w * h <= 1_048_576L)
            check((f["rawBgrSha256"] as String).matches(Regex("[0-9a-f]{64}")))
        } else {
            check((f["decodedBytes"] as Number).toLong() in 1..4_194_304L &&
                (f["decodedSha256"] as String).matches(Regex("[0-9a-f]{64}")))
        }
        specs[name] = DetectorAssetSpec(geometryRoot.resolve(name), size.toInt(), f["sha256"] as String, 2_097_152)
    }
    check(geometryRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(geometryRoot).invariantSeparatorsPath }.toSet() == specs.keys)
    return specs
}
val stageGeometryProbeAssets by tasks.registering(Sync::class) {
    // Local copies exist ONLY under build/, and never in a committed assets directory.
    from(rootProject.file("docs/fixtures")) {
        include((geometryNames + "manifest.json").map { "detector-geometry-v1/$it" })
    }
    into(layout.buildDirectory.dir("geometry-assets"))
    inputs.dir(geometryRoot) // Extra/missing files invalidate the task too.
    inputs.property("frozenManifestSha256", geometryManifestSha)
    doFirst { geometryAssetSpecs().values.forEach { detectorRead(it) } }
    doLast {
        val stagedRoot = layout.buildDirectory.dir("geometry-assets").get().asFile
        val specs = geometryAssetSpecs()
        check(stagedRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(stagedRoot).invariantSeparatorsPath }.toSet() ==
            specs.keys.map { "detector-geometry-v1/$it" }.toSet())
        specs.forEach { (name, spec) -> detectorRead(spec.copy(file = stagedRoot.resolve("detector-geometry-v1/$name"))) }
    }
}
// Lint also reads androidTest asset outputs; declare the same producer dependency.
// This remains limited to test-variant consumers, never preBuild/main/JVM tasks.
tasks.matching { it.name in setOf("mergeDebugAndroidTestAssets", "packageDebugAndroidTest",
    "generateDebugAndroidTestLintModel", "lintAnalyzeDebugAndroidTest") }.configureEach {
    dependsOn(stageGeometryProbeAssets)
}
tasks.matching { it.name == "assembleDebugAndroidTest" }.configureEach {
    doLast {
        val expected = geometryAssetSpecs()
        ZipFile(layout.buildDirectory.file("outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk").get().asFile).use { apk ->
            val entries = apk.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("assets/detector-geometry-v1/") }.toList()
            check(entries.size == 78 && entries.map { it.name }.toSet() == expected.keys.map { "assets/detector-geometry-v1/$it" }.toSet())
            expected.forEach { (name, spec) ->
                val entry = apk.getEntry("assets/detector-geometry-v1/$name"); check(entry.size == spec.bytes.toLong())
                val delivered = apk.getInputStream(entry).use { it.readNBytes(spec.bytes + 1) }
                check(delivered.contentEquals(detectorRead(spec))) { "Packaged geometry bytes changed: $name" }
            }
        }
    }
}


// Pinned synthetic polygon probe: these Java sources compile in test/testShared ONLY.
// This check never validates models, geometry fixtures or downloaded dependencies.
val polygonSourceIdentity = mapOf(
    "Clipper.java" to (1301 to "d463987032c73c6dcc982a90ecb768c594214f05dd800262950e9d64c98fa28f"),
    "ClipperBase.java" to (23027 to "145907a66fc1438256070603877d2fdc16409bc7289b3029e24f3ef5d0eccdeb"),
    "ClipperOffset.java" to (19358 to "c19ec2bc4ff28d97498d7a6ff11b7ad3beee9f52ee7e2ec7e64c30bfcbf25db8"),
    "DefaultClipper.java" to (95075 to "27c89abdbe193bfb62ca1ca2076d153f1ce4051dfb4d35e5c186f11f3e913273"),
    "Edge.java" to (10159 to "08b674ab34fe970f782d4c89ad6b2351b85f143e58ceadc3008e1c0b5fde7881"),
    "LongRect.java" to (457 to "7ec0fa84decb6d15e525ffdc872429de031f385a409ededb10a9a4b32c790f4c"),
    "Path.java" to (13090 to "ab5e1a63707e2a21cb8309e451048007bce730864f21bf2cf9fabf50cdd6ae7a"),
    "Paths.java" to (3436 to "5d4757692ea0fdd33904efc424383d0cd5d48799bf76180a9e085784d542c3c7"),
    "Point.java" to (6543 to "4d399a9fac02d3d9403c6b8aad1b2630e6257fb176222ff950055d1f465e9c78"),
    "PolyNode.java" to (2386 to "f5e15da1395b5f1af4d82340b0ad9108e7b2cdbad6bc2bf69d421eb759ecfe5b"),
    "PolyTree.java" to (789 to "7c2c7af75e347acebe24afbd7e1e58d193177fba70b8939fac2d96e7e9db15a1"))
val polygonLegalIdentity = mapOf(
    "LICENSE.txt" to (1338 to "c9bff75738922193e67fa726fa225535870d2aa1059f91452c411736284ad566"),
    "NOTICE.txt" to (1241 to "6912a835b4057a4ad3f39daa0e6feff0a1a38d8474e99e95353f4825a65e1c2c"),
    "provenance.json" to (6431 to "b555bf04f526ef853787ff306e0e814999106c5360d02ae5767a0092f285ee5d"))
val polygonPatchSha = "8c913466b38a9d7d2dc44a95bcc84d04088dc3d7c9ecc0559c2bcdf7dd5613cb"
val polygonFixtureIdentity = mapOf(
    "manifest.json" to (1086 to "e4415247a09d954501e0c5f87a2e5a6e35cdeef9ffc5ff5349aaf0a1a0f9e8ea"),
    "cases.json" to (1225517 to "972e5c809dfaea5744caa05ceb7920383a99dca1d923e2bf69c950a707c09576"))
fun polygonRead(root: java.io.File, name: String, identity: Pair<Int, String>): ByteArray {
    val f = root.resolve(name)
    check(f.isFile && f.length() == identity.first.toLong()) { "Polygon file length: $name" }
    val bytes = f.inputStream().use { it.readNBytes(identity.first + 1) }
    check(bytes.size == identity.first && detectorDigest(bytes) == identity.second) { "Polygon file fingerprint: $name" }
    return bytes
}
fun polygonFileSet(root: java.io.File, names: Set<String>) {
    check(root.isDirectory && root.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }.toSet() == names) { "Polygon exact file set: $root" }
}
val verifyPolygonProbeVendor by tasks.registering {
    doLast {
        val source = file("src/testShared/java/de/lighti/clipper")
        val legal = file("src/androidTest/assets/polygon-offset-legal")
        check(polygonSourceIdentity.size == 11 && polygonSourceIdentity.values.sumOf { it.first } == 175621)
        polygonFileSet(source, polygonSourceIdentity.keys); polygonFileSet(legal, polygonLegalIdentity.keys)
        polygonSourceIdentity.forEach { (name, identity) -> polygonRead(source, name, identity) }
        polygonLegalIdentity.forEach { (name, identity) -> polygonRead(legal, name, identity) }
        val provenance = groovy.json.JsonSlurper().parseText(String(
            polygonRead(legal, "provenance.json", polygonLegalIdentity.getValue("provenance.json")), Charsets.UTF_8)) as Map<*, *>
        check(provenance["sourceCommit"] == "5ef8c0a467023c495e44e582e9cbd8ca7308a590")
        check((provenance["patch"] as Map<*, *>)["sha256"] == polygonPatchSha)
        check((provenance["sources"] as List<*>).size == 11)
    }
}
tasks.matching { it.name in setOf("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac",
    "compileDebugAndroidTestKotlin", "compileDebugAndroidTestJavaWithJavac",
    "compileReleaseUnitTestKotlin", "compileReleaseUnitTestJavaWithJavac") }.configureEach {
    dependsOn(verifyPolygonProbeVendor)
}
val polygonFixtureRoot = rootProject.file("docs/fixtures/polygon-offset-v1")
val stagePolygonOffsetProbeAssets by tasks.registering(Sync::class) {
    from(polygonFixtureRoot) {
        include("manifest.json", "cases.json")
        into("polygon-offset-v1")
    }
    into(layout.buildDirectory.dir("polygon-offset-assets"))
    inputs.dir(polygonFixtureRoot)
    inputs.property("frozenPolygonIdentity", polygonFixtureIdentity.toString())
    doFirst {
        polygonFileSet(polygonFixtureRoot, polygonFixtureIdentity.keys)
        polygonFixtureIdentity.forEach { (name, identity) -> polygonRead(polygonFixtureRoot, name, identity) }
    }
}
val validatePolygonOffsetProbeAssets by tasks.registering {
    dependsOn(stagePolygonOffsetProbeAssets, verifyPolygonProbeVendor)
    doLast {
        val stage = layout.buildDirectory.dir("polygon-offset-assets").get().asFile
        polygonFileSet(polygonFixtureRoot, polygonFixtureIdentity.keys)
        polygonFileSet(stage, polygonFixtureIdentity.keys.map { "polygon-offset-v1/$it" }.toSet())
        polygonFixtureIdentity.forEach { (name, identity) ->
            val source = polygonRead(polygonFixtureRoot, name, identity)
            check(polygonRead(stage, "polygon-offset-v1/$name", identity).contentEquals(source))
        }
    }
}
// Only instrumentation merge/package/lint consume these fixtures. Never preBuild/main/JVM/compat.
tasks.matching { it.name in setOf("mergeDebugAndroidTestAssets", "packageDebugAndroidTest",
    "generateDebugAndroidTestLintModel", "lintAnalyzeDebugAndroidTest") }.configureEach {
    dependsOn(validatePolygonOffsetProbeAssets)
}
// Check source-set isolation on a newly built main APK. Machine-specific signed APK
// identity belongs to the parent acceptance record, not portable build configuration.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        ZipFile(layout.buildDirectory.file("outputs/apk/debug/modelprobe-debug.apk").get().asFile).use { apk ->
            check(apk.entries().asSequence().none { it.name.startsWith("assets/polygon-offset-") })
            apk.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.forEach { entry ->
                val strings = apk.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                check(!strings.contains("Lde/lighti/clipper/")) { "Polygon vendor leaked into main DEX" }
            }
        }
    }
}
tasks.matching { it.name == "assembleDebugAndroidTest" }.configureEach {
    doLast {
        val expected = polygonFixtureIdentity.mapKeys { "assets/polygon-offset-v1/${it.key}" } +
            polygonLegalIdentity.mapKeys { "assets/polygon-offset-legal/${it.key}" }
        val stage = layout.buildDirectory.dir("polygon-offset-assets/polygon-offset-v1").get().asFile
        val legal = file("src/androidTest/assets/polygon-offset-legal")
        polygonFileSet(stage, polygonFixtureIdentity.keys); polygonFileSet(legal, polygonLegalIdentity.keys)
        ZipFile(layout.buildDirectory.file("outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk").get().asFile).use { apk ->
            val entries = apk.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("assets/polygon-offset-") }.toList()
            check(entries.size == 5 && entries.map { it.name }.toSet() == expected.keys) { "Packaged polygon set/duplicates" }
            expected.forEach { (name, identity) ->
                val original = polygonRead(if (name.startsWith("assets/polygon-offset-v1/")) stage else legal,
                    name.substringAfterLast('/'), identity)
                val entry = apk.getEntry(name); check(entry.size == identity.first.toLong())
                val packaged = apk.getInputStream(entry).use { it.readNBytes(identity.first + 1) }
                check(packaged.contentEquals(original)) { "Packaged polygon bytes changed: $name" }
            }
        }
    }
}

// Frozen DB trace fixtures: logical .json.gz becomes ONLY physical .jsonz, because
// AAPT rewrites gzip suffixes. The manifest remains byte-for-byte unchanged.
val boxTraceManifestSha = "91cc2279377e8809ba3898bd31784f79df307dc0ad816a826fac139de7184118"
val boxTraceExtraIds = listOf("candidate-count-1000", "candidate-count-1001", "candidate-count-4096", "score-equal",
    "score-below", "final-size-three", "minimum-side-two", "collinear-height-one")
val boxTraceIds = geometryIds + boxTraceExtraIds
val boxTraceMapping = (boxTraceIds.map { "$it-trace.json.gz" } +
    boxTraceExtraIds.map { "$it-probability.f32z" } + "manifest.json").associateWith {
    if (it.endsWith(".json.gz")) it.removeSuffix(".json.gz") + ".jsonz" else it
}
val boxTraceRoot = rootProject.file("docs/fixtures/detector-box-trace-v1")
fun boxTraceAssetSpecs(): Map<String, DetectorAssetSpec> {
    polygonFileSet(boxTraceRoot, boxTraceMapping.keys)
    val manifest = DetectorAssetSpec(boxTraceRoot.resolve("manifest.json"), 22930, boxTraceManifestSha, 128 * 1024)
    val m = groovy.json.JsonSlurper().parseText(String(detectorRead(manifest), Charsets.UTF_8)) as Map<*, *>
    check((m["schema"] as Number).toInt() == 1 && m["parentManifestSha256"] == geometryManifestSha)
    check((m["cases"] as List<*>).map { (it as Map<*, *>)["id"] } == boxTraceIds)
    val files = m["files"] as Map<*, *>; check(files.keys == boxTraceMapping.keys - "manifest.json")
    val specs = linkedMapOf("manifest.json" to manifest)
    files.forEach { (key, value) ->
        val name = key as String; val meta = value as Map<*, *>
        val bytes = (meta["bytes"] as Number).toLong(); check(bytes in 1..2_097_152L)
        val spec = DetectorAssetSpec(boxTraceRoot.resolve(name), bytes.toInt(), meta["sha256"] as String, 2 * 1024 * 1024)
        val raw = detectorRead(spec)
        val cap = if (name.endsWith(".json.gz")) 1_048_576 else 4_194_304
        val decodedBytes = (meta["decodedBytes"] as Number).toLong(); check(decodedBytes in 1..cap.toLong())
        val decoded = GZIPInputStream(raw.inputStream()).use { it.readNBytes(decodedBytes.toInt() + 1) }
        check(decoded.size.toLong() == decodedBytes && detectorDigest(decoded) == meta["decodedSha256"])
        specs[name] = spec
    }
    check(specs.size == 33 && specs.values.sumOf { it.bytes } == 112533)
    return specs
}
val stageBoxTraceProbeAssets by tasks.registering(Sync::class) {
    from(boxTraceRoot) {
        include(boxTraceMapping.keys)
        rename { boxTraceMapping.getValue(it) }
        into("detector-box-trace-v1")
    }
    into(layout.buildDirectory.dir("box-trace-assets"))
    inputs.dir(boxTraceRoot)
    inputs.property("frozenBoxTraceManifest", boxTraceManifestSha)
    inputs.property("boxTraceLogicalToPhysical", boxTraceMapping.toString())
    doFirst { boxTraceAssetSpecs() }
}
val validateBoxTraceProbeAssets by tasks.registering {
    dependsOn(stageBoxTraceProbeAssets)
    doLast {
        val specs = boxTraceAssetSpecs()
        val stage = layout.buildDirectory.dir("box-trace-assets").get().asFile
        polygonFileSet(stage, boxTraceMapping.values.map { "detector-box-trace-v1/$it" }.toSet())
        specs.forEach { (logical, spec) ->
            detectorRead(spec.copy(file = stage.resolve("detector-box-trace-v1/${boxTraceMapping.getValue(logical)}")))
        }
    }
}
tasks.matching { it.name in setOf("mergeDebugAndroidTestAssets", "packageDebugAndroidTest",
    "generateDebugAndroidTestLintModel", "lintAnalyzeDebugAndroidTest") }.configureEach {
    dependsOn(validateBoxTraceProbeAssets)
}
tasks.matching { it.name == "assembleDebugAndroidTest" }.configureEach {
    doLast {
        val specs = boxTraceAssetSpecs()
        ZipFile(layout.buildDirectory.file("outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk").get().asFile).use { apk ->
            val entries = apk.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("assets/detector-box-trace-v1/") }.toList()
            val expected = boxTraceMapping.values.map { "assets/detector-box-trace-v1/$it" }.toSet()
            check(entries.size == 33 && entries.map { it.name }.toSet() == expected)
            specs.forEach { (logical, spec) ->
                val entry = apk.getEntry("assets/detector-box-trace-v1/${boxTraceMapping.getValue(logical)}")
                check(entry.size == spec.bytes.toLong())
                val raw = apk.getInputStream(entry).use { it.readNBytes(spec.bytes + 1) }
                check(raw.contentEquals(detectorRead(spec))) { "Packaged box trace identity: $logical" }
            }
        }
    }
}
tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        ZipFile(layout.buildDirectory.file("outputs/apk/debug/modelprobe-debug.apk").get().asFile).use { apk ->
            check(apk.entries().asSequence().none { it.name.startsWith("assets/detector-box-trace-v1/") })
            apk.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.forEach { entry ->
                val strings = apk.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                check(listOf("BoxPipeline", "BoxTrace").none { strings.contains("Lcom/kandong/modelprobe/$it") })
            }
        }
    }
}

// Actual crop-to-recognition references: only instrumentation merge/package/lint consume them.
val cropRecognitionRoot = rootProject.file("docs/fixtures/crop-recognition-v1")
val cropRecognitionSha = "bbbfd058777e51e4fb7769764d2b49dac7618c784b8e0ef652af84d973f64f6a"
val cropRecognitionNames = (listOf("manifest.json") + geometryIds.flatMapIndexed { i, id ->
    val count = geometryCropCounts[i]
    if (count == 0) emptyList() else List(count) { "$id-row-$it-resized.png" } +
        List(count) { "$id-row-$it-resized.argbz" } + "$id.f32z" + listOf("ch", "latin").map { "$id-$it-argmax.i32z" }
}).toSet()
fun cropRecognitionSpecs(): Map<String, DetectorAssetSpec> {
    polygonFileSet(cropRecognitionRoot, cropRecognitionNames)
    val manifest = DetectorAssetSpec(cropRecognitionRoot.resolve("manifest.json"), 50281, cropRecognitionSha, 131072)
    val m = groovy.json.JsonSlurper().parseText(String(detectorRead(manifest), Charsets.UTF_8)) as Map<*, *>
    check(m["parentManifestSha256"] == geometryManifestSha && m["probeManifestSha256"] == "126d8d3860d5a4ea098f0875838dbed51a55d8a321f50d409805d394a460061c")
    check((m["cases"] as List<*>).map { (it as Map<*, *>)["id"] } == geometryIds)
    val files = m["files"] as Map<*, *>; check(files.keys == cropRecognitionNames - "manifest.json")
    val result = linkedMapOf("manifest.json" to manifest)
    files.forEach { (key, value) ->
        val name = key as String; val f = value as Map<*, *>; val size = (f["bytes"] as Number).toLong()
        check(size in 1..1_048_576)
        val spec = DetectorAssetSpec(cropRecognitionRoot.resolve(name), size.toInt(), f["sha256"] as String, 1_048_576)
        val raw = detectorRead(spec)
        if (!name.endsWith(".png")) {
            val bytes = (f["decodedBytes"] as Number).toLong(); check(bytes in 1..4_718_592)
            val decoded = GZIPInputStream(raw.inputStream()).use { it.readNBytes(bytes.toInt() + 1) }
            check(decoded.size.toLong() == bytes && detectorDigest(decoded) == f["decodedSha256"])
        }
        result[name] = spec
    }
    check(result.size == 51 && result.values.sumOf { it.bytes } == 682245); return result
}
val stageCropRecognitionProbeAssets by tasks.registering(Sync::class) {
    from(cropRecognitionRoot) { include(cropRecognitionNames); into("crop-recognition-v1") }
    into(layout.buildDirectory.dir("crop-recognition-assets"))
    inputs.dir(cropRecognitionRoot); inputs.property("frozenManifest", cropRecognitionSha)
    doFirst { cropRecognitionSpecs() }
    doLast {
        val root = layout.buildDirectory.dir("crop-recognition-assets").get().asFile
        polygonFileSet(root, cropRecognitionNames.map { "crop-recognition-v1/$it" }.toSet())
        cropRecognitionSpecs().forEach { (name, spec) -> detectorRead(spec.copy(file = root.resolve("crop-recognition-v1/$name"))) }
    }
}
tasks.matching { it.name in setOf("mergeDebugAndroidTestAssets", "packageDebugAndroidTest", "generateDebugAndroidTestLintModel", "lintAnalyzeDebugAndroidTest") }.configureEach {
    dependsOn(stageCropRecognitionProbeAssets)
}
tasks.matching { it.name == "assembleDebugAndroidTest" }.configureEach {
    doLast {
        val specs = cropRecognitionSpecs()
        ZipFile(layout.buildDirectory.file("outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk").get().asFile).use { apk ->
            val entries = apk.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("assets/crop-recognition-v1/") }.toList()
            check(entries.size == 51 && entries.map { it.name }.toSet() == cropRecognitionNames.map { "assets/crop-recognition-v1/$it" }.toSet())
            specs.forEach { (name, spec) ->
                val entry = apk.getEntry("assets/crop-recognition-v1/$name"); check(entry.size == spec.bytes.toLong())
                check(apk.getInputStream(entry).use { it.readNBytes(spec.bytes + 1) }.contentEquals(detectorRead(spec)))
            }
        }
    }
}
tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        ZipFile(layout.buildDirectory.file("outputs/apk/debug/modelprobe-debug.apk").get().asFile).use { apk ->
            check(apk.entries().asSequence().none { it.name.startsWith("assets/crop-recognition-v1/") })
            apk.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.forEach { entry ->
                val strings = apk.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                check(!strings.contains("Lcom/kandong/modelprobe/CropRecognition"))
            }
        }
    }
}
