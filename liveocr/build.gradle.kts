import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kandong.liveocr"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("liveocr-assets"))
    androidResources { noCompress += "onnx" }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("org.opencv:opencv:5.0.0.1")
    testImplementation("junit:junit:4.13.2")
}

// Local task-time staging only. No downloads, fixture directories, task manifests or tracked ONNX.
// Recognition pins were extracted from the authenticated probes/manifest.json models array.
data class OcrAssetSpec(val source: String, val path: String, val bytes: Int, val sha: String)
val liveOcrAssets = listOf(
    OcrAssetSpec("liveocr/third_party/CURATION.txt", "liveocr/legal/CURATION.txt", 1393,
        "9c524f8759cc3b49cd16ef3bdd23d715e489fd731a6f0a16c98de5cadaf75ac6"),
    OcrAssetSpec("modelprobe/build/detector-assets/detector-model/ch_PP-OCRv5_det_mobile.onnx", "liveocr/models/ch_PP-OCRv5_det_mobile.onnx", 4819576,
        "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"),
    OcrAssetSpec("modelprobe/src/main/assets/models/ch_PP-OCRv5_rec_mobile.onnx", "liveocr/models/ch_PP-OCRv5_rec_mobile.onnx", 16631306,
        "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5"),
    OcrAssetSpec("modelprobe/src/main/assets/probes/ch-dictionary.json", "liveocr/probes/ch-dictionary.json", 110794,
        "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af"),
    OcrAssetSpec("modelprobe/src/main/assets/models/latin_PP-OCRv5_rec_mobile.onnx", "liveocr/models/latin_PP-OCRv5_rec_mobile.onnx", 7904513,
        "b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a"),
    OcrAssetSpec("modelprobe/src/main/assets/probes/latin-dictionary.json", "liveocr/probes/latin-dictionary.json", 2654,
        "d80d308e9759b3ea3aaf6e57fa51b39ef0a2eeb3145a9b23df819f0f0279af44"),
    OcrAssetSpec("modelprobe/src/main/assets/legal/ModelScope-README.md.txt", "liveocr/legal/recognition/ModelScope-README.md.txt", 1140,
        "3dc91bb3cb667df783178917d69b38bfabb8c935596f34243a1c9f0d36916b6e"),
    OcrAssetSpec("modelprobe/src/main/assets/legal/NOTICE.txt", "liveocr/legal/recognition/NOTICE.txt", 1374,
        "fe060f4eafb3837acb25061e7e9ee1b5d9aceec19192ea1f7b83fdfe80cee3ad"),
    OcrAssetSpec("modelprobe/src/main/assets/legal/ORT-LICENSE.txt", "liveocr/legal/recognition/ORT-LICENSE.txt", 1073,
        "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c"),
    OcrAssetSpec("modelprobe/src/main/assets/legal/ORT-ThirdPartyNotices.txt", "liveocr/legal/recognition/ORT-ThirdPartyNotices.txt", 338088,
        "143764b952fdb1a7c69ce653bfba74a7744d6a8a573bfb73e235fba356c83de3"),
    OcrAssetSpec("modelprobe/src/main/assets/legal/PaddleOCR-LICENSE.txt", "liveocr/legal/recognition/PaddleOCR-LICENSE.txt", 11376,
        "3840c5c0c61c294264d2dd77b8777be6ddd90121ef4e0e64abcd22edea581d6e"),
    OcrAssetSpec("modelprobe/src/main/assets/legal/RapidOCR-LICENSE.txt", "liveocr/legal/recognition/RapidOCR-LICENSE.txt", 11422,
        "3e0af25fdd06aa9586ae97adb00ea927ebe5a3805ac77d2d3a81ce5f55693333"),
    OcrAssetSpec("modelprobe/src/main/assets/legal/provenance.json", "liveocr/legal/recognition/provenance.json", 1117,
        "36d0c1fbf6f045f35bd0472b452dd8270ffa84ccd3ee322b6cf2ace6028d0f07"),
    OcrAssetSpec("modelprobe/build/detector-assets/detector-model/legal/ModelScope-README.md.txt", "liveocr/legal/detector/ModelScope-README.md.txt", 1140,
        "3dc91bb3cb667df783178917d69b38bfabb8c935596f34243a1c9f0d36916b6e"),
    OcrAssetSpec("modelprobe/build/detector-assets/detector-model/legal/NOTICE.txt", "liveocr/legal/detector/NOTICE.txt", 823,
        "99d897a42f7740b564ed67ee38ae40dcbef17c3c4196fef28f6083ad76b3a3b5"),
    OcrAssetSpec("modelprobe/build/detector-assets/detector-model/legal/ORT-LICENSE.txt", "liveocr/legal/detector/ORT-LICENSE.txt", 1073,
        "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c"),
    OcrAssetSpec("modelprobe/build/detector-assets/detector-model/legal/ORT-ThirdPartyNotices.txt", "liveocr/legal/detector/ORT-ThirdPartyNotices.txt", 338088,
        "143764b952fdb1a7c69ce653bfba74a7744d6a8a573bfb73e235fba356c83de3"),
    OcrAssetSpec("modelprobe/build/detector-assets/detector-model/legal/PaddleOCR-LICENSE.txt", "liveocr/legal/detector/PaddleOCR-LICENSE.txt", 11376,
        "3840c5c0c61c294264d2dd77b8777be6ddd90121ef4e0e64abcd22edea581d6e"),
    OcrAssetSpec("modelprobe/build/detector-assets/detector-model/legal/RapidOCR-LICENSE.txt", "liveocr/legal/detector/RapidOCR-LICENSE.txt", 11422,
        "3e0af25fdd06aa9586ae97adb00ea927ebe5a3805ac77d2d3a81ce5f55693333"),
    OcrAssetSpec("modelprobe/build/detector-assets/detector-model/legal/provenance.json", "liveocr/legal/detector/provenance.json", 1508,
        "f67c1bf49934917d35826599f994b8565dacdd694682ac4dbfeaf02b167185a6"),
    OcrAssetSpec("modelprobe/src/androidTest/assets/opencv-legal/NOTICE.txt", "liveocr/legal/opencv/NOTICE.txt", 592,
        "f7b2dac35bcb96ac55e5b88efa0b434ec8a8a66b6e9cf281eda86eac4e4fc405"),
    OcrAssetSpec("modelprobe/src/androidTest/assets/opencv-legal/OpenCV-LICENSE.txt", "liveocr/legal/opencv/OpenCV-LICENSE.txt", 11358,
        "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
    OcrAssetSpec("liveocr/third_party/clipper/LICENSE.txt", "liveocr/legal/clipper/LICENSE.txt", 1338,
        "c9bff75738922193e67fa726fa225535870d2aa1059f91452c411736284ad566"),
    OcrAssetSpec("liveocr/third_party/clipper/NOTICE.txt", "liveocr/legal/clipper/NOTICE.txt", 1241,
        "6912a835b4057a4ad3f39daa0e6feff0a1a38d8474e99e95353f4825a65e1c2c"),
    OcrAssetSpec("liveocr/third_party/clipper/half-away-rounding.patch", "liveocr/legal/clipper/half-away-rounding.patch", 9745,
        "8c913466b38a9d7d2dc44a95bcc84d04088dc3d7c9ecc0559c2bcdf7dd5613cb"),
    OcrAssetSpec("liveocr/third_party/clipper/provenance.json", "liveocr/legal/clipper/provenance.json", 6431,
        "b555bf04f526ef853787ff306e0e814999106c5360d02ae5767a0092f285ee5d"))

fun verifyLiveOcrAsset(file: java.io.File, bytes: Int, sha: String) {
    check(file.isFile && file.length() == bytes.toLong()) {
        "LIVE_OCR_ASSET_MISSING_OR_LENGTH: ${file.name}. Required local cache is absent/invalid; no download is performed."
    }
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                check(n > 0)
                count += n
                check(count <= bytes) { "LIVE_OCR_ASSET_LENGTH" }
                digest.update(buffer, 0, n)
            }
        } finally { buffer.fill(0) }
    }
    check(count == bytes.toLong() && digest.digest().joinToString("") { "%02x".format(it) } == sha) {
        "LIVE_OCR_ASSET_HASH: ${file.name}"
    }
}

val stageLiveOcrAssets by tasks.registering(Sync::class) {
    liveOcrAssets.forEach { spec ->
        from(rootProject.file(spec.source)) {
            into(spec.path.substringBeforeLast('/'))
            rename { spec.path.substringAfterLast('/') }
        }
    }
    into(layout.buildDirectory.dir("liveocr-assets"))
    includeEmptyDirs = false
    duplicatesStrategy = DuplicatesStrategy.FAIL
    inputs.property("assetIdentity", liveOcrAssets.toString())
    doFirst {
        liveOcrAssets.forEach { verifyLiveOcrAsset(rootProject.file(it.source), it.bytes, it.sha) }
    }
    doLast {
        val stage = layout.buildDirectory.dir("liveocr-assets").get().asFile
        check(stage.walkTopDown().filter { it.isFile }.map { it.relativeTo(stage).invariantSeparatorsPath }.toSet() ==
            liveOcrAssets.map { it.path }.toSet()) { "LIVE_OCR_ASSET_SET" }
        // Reverify staged bytes to reject source changes during Sync as well.
        liveOcrAssets.forEach { verifyLiveOcrAsset(stage.resolve(it.path), it.bytes, it.sha) }
    }
}

// Source bytes must continue to match the pre-existing patched vendor, not just its package name.
val clipperIdentity = mapOf(
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
val verifyLiveOcrVendor by tasks.registering {
    val source = file("src/main/java/de/lighti/clipper")
    inputs.dir(source)
    inputs.property("vendorIdentity", clipperIdentity.toString())
    doLast {
        check(source.walkTopDown().filter { it.isFile }.map { it.relativeTo(source).invariantSeparatorsPath }.toSet() == clipperIdentity.keys)
        clipperIdentity.forEach { (name, identity) -> verifyLiveOcrAsset(source.resolve(name), identity.first, identity.second) }
    }
}

tasks.named("preBuild") { dependsOn(stageLiveOcrAssets, verifyLiveOcrVendor) }
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        it.name.startsWith("generate") && it.name.endsWith("LintModel") ||
        it.name.startsWith("lintAnalyze")
}.configureEach { dependsOn(stageLiveOcrAssets, verifyLiveOcrVendor) }
