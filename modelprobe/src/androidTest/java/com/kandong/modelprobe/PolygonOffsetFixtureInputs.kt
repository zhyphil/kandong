package com.kandong.modelprobe

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class PolygonOffsetCase(val id: String, val category: String,
    val paths: List<List<PolygonOffsetKernel.Point>>, val distance: Double,
    val expected: List<List<PolygonOffsetKernel.IntPoint>>)

/** Frozen synthetic assets only. Every load re-reads/authenticates BOTH byte streams before
 * parsing either. Returned values own immutable copies, so later consumption needs no open asset.
 * Direct parser methods exist only for malformed-schema tests, never as a load fallback. */
internal class PolygonOffsetFixtureInputs(private val open: (String) -> InputStream,
    private val list: (String) -> List<String>) {
    fun load(): List<PolygonOffsetCase> {
        val names = list(NAMESPACE)
        require(names.size == 2 && names.toSet() == IDENTITIES.keys) { "ASSET_SET" }
        val manifest = authenticated("manifest.json")
        val cases = authenticated("cases.json")
        parseManifest(manifest)
        return parseCases(cases)
    }

    private fun authenticated(name: String): ByteArray {
        val identity = IDENTITIES.getValue(name)
        return open("$NAMESPACE/$name").use { authenticate(it, identity.first, identity.second,
            if (name == "manifest.json") MANIFEST_CAP else CASES_CAP) }
    }

    fun verifyLegal(): Map<String, String> {
        val names = list(LEGAL_NAMESPACE)
        require(names.size == LEGAL_IDENTITIES.size && names.toSet() == LEGAL_IDENTITIES.keys) { "LEGAL_SET" }
        LEGAL_IDENTITIES.forEach { (name, identity) ->
            open("$LEGAL_NAMESPACE/$name").use { authenticate(it, identity.first, identity.second, MANIFEST_CAP) }
        }
        return LEGAL_IDENTITIES.mapValues { it.value.second }
    }

    companion object {
        const val NAMESPACE = "polygon-offset-v1"
        const val LEGAL_NAMESPACE = "polygon-offset-legal"
        const val MANIFEST_CAP = 128 * 1024
        const val CASES_CAP = 2 * 1024 * 1024
        const val MANIFEST_SHA = "e4415247a09d954501e0c5f87a2e5a6e35cdeef9ffc5ff5349aaf0a1a0f9e8ea"
        const val CASES_SHA = "972e5c809dfaea5744caa05ceb7920383a99dca1d923e2bf69c950a707c09576"
        const val COMMIT = "5ef8c0a467023c495e44e582e9cbd8ca7308a590"
        const val PATCH_SHA = "8c913466b38a9d7d2dc44a95bcc84d04088dc3d7c9ecc0559c2bcdf7dd5613cb"
        val IDENTITIES = mapOf("manifest.json" to (1086 to MANIFEST_SHA), "cases.json" to (1225517 to CASES_SHA))
        val LEGAL_IDENTITIES = mapOf(
            "LICENSE.txt" to (1338 to "c9bff75738922193e67fa726fa225535870d2aa1059f91452c411736284ad566"),
            "NOTICE.txt" to (1241 to "6912a835b4057a4ad3f39daa0e6feff0a1a38d8474e99e95353f4825a65e1c2c"),
            "provenance.json" to (6431 to "b555bf04f526ef853787ff306e0e814999106c5360d02ae5767a0092f285ee5d"))
        val CATEGORIES = mapOf("frozen-micro" to 2, "frozen-final-quad-not-pre-unclip" to 13,
            "half-tie-squares" to 128, "seeded-rotated-rectangles" to 512,
            "small-or-thin" to 28, "general-multipath-offset" to 6)
        val IDS: List<String> = PolygonOffsetKernel.immutable(buildList {
            addAll(listOf("axis-aligned", "fractional-rotated", "en-quality-16.box-0", "fr-nonrefundable-16.box-0",
                "zh-hans-quality-16.box-1", "zh-hans-quality-16.box-0", "zh-hant-quality-16.box-1", "zh-hant-quality-16.box-0",
                "mixed-quality-16.box-2", "mixed-quality-16.box-1", "mixed-quality-16.box-0", "two-blocks.box-1",
                "two-blocks.box-0", "edge-touching.box-0", "vertical.box-0"))
            for (size in 1..16) for (position in 0..3) for (winding in 0..1) add("half-$size-$position-$winding")
            for (i in 0..511) add("rotated-$i")
            for (i in 0..6) for (j in 0..3) add("small-$i-$j")
            for (i in 0..5) add("multi-$i")
        })
        fun category(index: Int): String = when (index) {
            in 0..1 -> "frozen-micro"
            in 2..14 -> "frozen-final-quad-not-pre-unclip"
            in 15..142 -> "half-tie-squares"
            in 143..654 -> "seeded-rotated-rectangles"
            in 655..682 -> "small-or-thin"
            in 683..688 -> "general-multipath-offset"
            else -> throw IllegalArgumentException("CASE_INDEX")
        }
        fun sha(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        internal fun authenticate(input: InputStream, bytes: Int, expectedSha: String, cap: Int): ByteArray {
            require(cap in 1..CASES_CAP && bytes in 1..cap && expectedSha.matches(Regex("[0-9a-f]{64}"))) { "ASSET_METADATA" }
            val out = ByteArrayOutputStream(minOf(bytes, 8192))
            val buffer = ByteArray(8192)
            var count = 0
            while (true) {
                // Read at most one byte beyond the expected size; reject before writing it.
                val n = input.read(buffer, 0, minOf(buffer.size, bytes - count + 1))
                if (n < 0) break
                require(n > 0) { "ASSET_STALLED" }
                count += n
                require(count <= bytes && count <= cap) { "ASSET_LENGTH" }
                out.write(buffer, 0, n)
            }
            require(count == bytes) { "ASSET_TRUNCATED" }
            return out.toByteArray().also { require(sha(it) == expectedSha) { "ASSET_HASH" } }
        }

        private fun <T> document(raw: ByteArray, cap: Int, parse: (JsonReader) -> T): T {
            require(raw.isNotEmpty() && raw.size <= cap) { "JSON_BYTE_CAP" }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            JsonReader(InputStreamReader(ByteArrayInputStream(raw), decoder)).use { reader ->
                reader.isLenient = false
                val value = parse(reader)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "JSON_TRAILING" }
                return value
            }
        }
        private fun obj(r: JsonReader, keys: Set<String>, field: (String) -> Unit) {
            require(r.peek() == JsonToken.BEGIN_OBJECT) { "JSON_OBJECT_TYPE" }
            r.beginObject()
            val seen = HashSet<String>()
            while (r.hasNext()) {
                val key = r.nextName()
                require(key in keys && seen.add(key)) { "JSON_UNKNOWN_OR_DUPLICATE_KEY" }
                field(key)
            }
            r.endObject()
            require(seen == keys) { "JSON_MISSING_KEY" }
        }
        private fun <T> array(r: JsonReader, min: Int, max: Int, element: () -> T): List<T> {
            require(r.peek() == JsonToken.BEGIN_ARRAY) { "JSON_ARRAY_TYPE" }
            r.beginArray()
            val result = ArrayList<T>()
            while (r.hasNext()) {
                require(result.size < max) { "JSON_ARRAY_CAP" }
                result.add(element())
            }
            r.endArray()
            require(result.size >= min) { "JSON_ARRAY_SHORT" }
            return PolygonOffsetKernel.immutable(result)
        }
        private fun string(r: JsonReader): String {
            require(r.peek() == JsonToken.STRING) { "JSON_STRING_TYPE" }
            return r.nextString().also { require(it.length <= 512) { "JSON_STRING_CAP" } }
        }
        private fun number(r: JsonReader): Double {
            require(r.peek() == JsonToken.NUMBER) { "JSON_NUMBER_TYPE" }
            return r.nextDouble().also { require(it.isFinite()) { "JSON_FINITE" } }
        }
        private fun integer(r: JsonReader): Long {
            require(r.peek() == JsonToken.NUMBER) { "JSON_INTEGER_TYPE" }
            val token = r.nextString()
            require(token.matches(Regex("-?(0|[1-9][0-9]*)"))) { "JSON_INTEGER" }
            return token.toLong()
        }

        internal fun parseManifest(raw: ByteArray) = document(raw, MANIFEST_CAP) { r ->
            obj(r, setOf("schema", "scope", "sourceCommit", "sourceProvenance", "referenceVersions", "seed", "caseCount", "config", "files", "categories")) { key ->
                when (key) {
                    "schema" -> require(integer(r) == 1L)
                    "scope" -> require(string(r) == "synthetic positive round closed-polygon offset kernels; NOT full DB detector or OCR")
                    "sourceCommit" -> require(string(r) == COMMIT)
                    "sourceProvenance" -> require(string(r) == "docs/evidence/polygon-offset/2026-09-24/provenance.json")
                    "referenceVersions" -> {
                        val versions = mapOf("numpy" to "2.5.3", "pyclipper" to "1.4.0", "shapely" to "2.1.2")
                        obj(r, versions.keys) { require(string(r) == versions.getValue(it)) }
                    }
                    "seed" -> require(integer(r) == 20260924L)
                    "caseCount" -> require(integer(r) == 689L)
                    "config" -> obj(r, setOf("join", "end", "miterLimit", "arcTolerance", "unclipRatioSingleQuad", "inputCast", "comparison")) { k ->
                        when (k) {
                            "join" -> require(string(r) == "ROUND")
                            "end" -> require(string(r) == "CLOSED_POLYGON")
                            "miterLimit" -> require(number(r) == 2.0)
                            "arcTolerance" -> require(number(r) == 0.25)
                            "unclipRatioSingleQuad" -> require(number(r) == 1.6)
                            "inputCast" -> require(string(r) == "float32 then truncate to integer toward zero")
                            "comparison" -> require(string(r) == "all polygon integer vertices exact modulo cyclic start, winding and polygon order")
                        }
                    }
                    "files" -> obj(r, setOf("cases.json")) {
                        obj(r, setOf("bytes", "sha256")) { k ->
                            when (k) {
                                "bytes" -> require(integer(r) == 1225517L)
                                "sha256" -> require(string(r) == CASES_SHA)
                            }
                        }
                    }
                    "categories" -> obj(r, CATEGORIES.keys) { require(integer(r) == CATEGORIES.getValue(it).toLong()) }
                }
            }
        }

        internal fun parseCases(raw: ByteArray): List<PolygonOffsetCase> = document(raw, CASES_CAP) { r ->
            var index = 0
            val cases = array(r, 689, 689) {
                var id: String? = null; var rowCategory: String? = null; var distance: Double? = null
                var paths: List<List<PolygonOffsetKernel.Point>>? = null
                var expected: List<List<PolygonOffsetKernel.IntPoint>>? = null
                obj(r, setOf("id", "category", "paths", "distance", "expected")) { key ->
                    when (key) {
                        "id" -> id = string(r)
                        "category" -> rowCategory = string(r)
                        "distance" -> distance = number(r).also { PolygonOffsetKernel.validateDistance(it) }
                        "paths" -> paths = array(r, 1, 2) {
                            array(r, 4, 4) {
                                val xy = array(r, 2, 2) { number(r) }
                                PolygonOffsetKernel.Point(xy[0], xy[1])
                            }
                        }
                        "expected" -> {
                            var vertices = 0
                            expected = array(r, 1, PolygonOffsetKernel.MAX_OUTPUT_PATHS) {
                                array(r, 3, PolygonOffsetKernel.MAX_OUTPUT_VERTICES) {
                                    require(++vertices <= PolygonOffsetKernel.MAX_OUTPUT_VERTICES) { "EXPECTED_VERTEX_CAP" }
                                    val xy = array(r, 2, 2) { integer(r).also { require(it in -8321L..8321L) { "EXPECTED_COORDINATE" } } }
                                    PolygonOffsetKernel.IntPoint(xy[0], xy[1])
                                }
                            }
                        }
                    }
                }
                require(id == IDS[index] && rowCategory == category(index)) { "FROZEN_CASE_ID_OR_CATEGORY" }
                val input = checkNotNull(paths)
                require(input.size == if (index < 683) 1 else 2) { "FROZEN_PATH_COUNT" }
                PolygonOffsetKernel.validateInput(input)
                PolygonOffsetCase(checkNotNull(id), checkNotNull(rowCategory), PolygonOffsetKernel.immutablePaths(input),
                    checkNotNull(distance), PolygonOffsetKernel.immutablePaths(checkNotNull(expected))).also { index++ }
            }
            require(cases.map { it.id } == IDS && cases.groupingBy { it.category }.eachCount() == CATEGORIES)
            cases
        }
    }
}
