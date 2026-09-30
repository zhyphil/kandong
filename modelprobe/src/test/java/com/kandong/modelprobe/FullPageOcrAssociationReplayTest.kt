package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import kotlin.random.Random

/** Archived synthetic data only. Reference text/quality annotations never enter associate(). */
class FullPageOcrAssociationReplayTest {
    private val fixtureSha = "c492f2628a449b901041343a658a9cac9ffff2d31196fd51ec31854e7cd660f2"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun decode(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    private fun values(s: String) = s.split(',')
    private fun rect(s: String): FullPageStripPlanner.Rect = values(s).map(String::toInt).let {
        require(it.size == 4); FullPageStripPlanner.Rect(it[0], it[1], it[2], it[3])
    }
    private fun quad(s: String) = values(s).map(String::toDouble).also { require(it.size == 8) }
        .chunked(2).map { GeometryProbeContract.Point(it[0], it[1]) }
    // Explicit full-field value comparison, not Provenance reference equality or text alone.
    private fun fingerprint(c: FullPageOcrContract.Candidate): String {
        val p = c.provenance
        return listOf(p.version, p.pageFixtureId, p.stripIndex, p.read, p.core, p.contourIndex,
            p.rawBoxIndex, p.finalBoxIndex, p.stripReadingOrder, p.localQuad, p.pageQuad,
            p.detectorScore.toRawBits(), p.ownsCoreCenter, b64(p.id), c.modelId, b64(c.rawText),
            c.recognitionWidth, c.recognitionTime).joinToString("|")
    }
    private fun signature(r: FullPageOcrAssociation.Result): String = buildString {
        appendLine("published=${r.published};rejection=${r.rejection};identity=${r.identity}")
        r.rawCandidates.forEach { appendLine(fingerprint(it)) }
        r.edges.forEach { appendLine("${b64(it.leftId)}|${b64(it.rightId)}|${it.kind}") }
        r.groups.forEach { appendLine("${b64(it.id)}|${it.memberIds.map(::b64)}|${it.text}|${it.geometry}|${it.reasons}|${it.agreedRaw?.let(::b64)}") }
    }

    @Test fun archivedBothDevicesKeepEveryCandidateAndStableAssociations() {
        val bytes = checkNotNull(javaClass.classLoader!!.getResourceAsStream("archived.properties"))
            .use { it.readBytes() }
        assertTrue(bytes.size <= 512 * 1024); assertEquals(fixtureSha, sha(bytes))
        val props = Properties().apply { load(ByteArrayInputStream(bytes)) }
        assertEquals("1", props.getProperty("schema"))
        val cases = props.getProperty("cases").split(','); assertEquals(40, cases.size)
        var total = 0; var empty = 0; var noncore = 0; var chars = 0; var criticalConflicts = 0
        val report = arrayListOf("schema=1", "fixtureSha256=$fixtureSha", "cases=${cases.joinToString(",")}")
        for (case in cases) {
            fun p(key: String) = checkNotNull(props.getProperty("$case.$key"))
            val v = values(p("version")).map(String::toLong)
            val version = CaptureVersion(v[0], v[1], v[2], v[3], v[4].toInt(), v[5].toInt())
            val model = FullPageOcrContract.Model(p("model"), p("modelSha"), p("vocabulary").toInt())
            val identity = FullPageOcrAssociation.PageIdentity(p("sourceBatch"), version, p("page"), model,
                p("detectorSha"), p("dictionarySha"))
            val plan = FullPageStripPlanner.plan(p("width").toInt(), p("height").toInt())
            val receipts = List(p("stripCount").toInt()) { i ->
                FullPageOcrAssociation.StripReceipt(i, rect(p("strip.$i.read")), rect(p("strip.$i.core")),
                    p("strip.$i.complete").toBooleanStrict(), p("strip.$i.count").toInt())
            }
            val input = List(p("count").toInt()) { i ->
                val indices = values(p("$i.indices")).map(String::toInt)
                val recognition = values(p("$i.recognition")).map(String::toInt)
                val provenance = FullPageOcrContract.Provenance(version, p("page"), p("$i.strip").toInt(),
                    rect(p("$i.read")), rect(p("$i.core")), indices[0], indices[1], indices[2], indices[3],
                    quad(p("$i.local")), quad(p("$i.quad")), p("$i.score").toDouble(),
                    p("$i.owner").toBooleanStrict(), decode(p("$i.id")))
                FullPageOcrContract.Candidate(provenance, model.id, decode(p("$i.text")), recognition[0], recognition[1])
            }
            assertTrue(p("complete").toBooleanStrict())
            val result = FullPageOcrAssociation.associate(identity, plan, FullPageOcrAssociation.UpstreamState.COMPLETE,
                receipts, input) { true } // Fixed historical replay, not a live page's validity check.
            assertTrue("$case: ${result.rejection}", result.published)
            assertEquals(identity, result.identity)
            val original = input.associate { it.provenance.id to fingerprint(it) }
            assertEquals(input.size, original.size)
            assertEquals(original, result.rawCandidates.associate { it.provenance.id to fingerprint(it) })
            val members = result.groups.flatMap { it.memberIds }
            assertEquals(input.size, members.size); assertEquals(original.keys, members.toSet())
            for (order in listOf(input.reversed(), input.shuffled(Random(701)), input.shuffled(Random(702)))) {
                val repeat = FullPageOcrAssociation.associate(identity, plan, FullPageOcrAssociation.UpstreamState.COMPLETE,
                    receipts.reversed(), order) { true }
                assertEquals("Permutation changed $case", signature(result), signature(repeat))
            }
            for (group in result.groups) {
                val raw = group.memberIds.map { id -> input.single { it.provenance.id == id }.rawText }
                group.agreedRaw?.let { agreed ->
                    assertTrue(agreed.isNotEmpty()); assertEquals(2, raw.size)
                    assertTrue(raw.all { it == agreed }); assertEquals("UNAMBIGUOUS_PAIR", group.geometry.name)
                }
            }
            // Regression assertion AFTER association; these labels never influence grouping.
            if (p("page") == "hant-seam" && model.id == "ch") {
                val ids = listOf("/s0/c0-r0-f0", "/s1/c2-r2-f2").map { suffix ->
                    input.single { it.provenance.id.endsWith(suffix) }.provenance.id
                }
                val group = result.groups.single { it.memberIds.containsAll(ids) }
                assertEquals("DIFFERENT_RAW", group.text.name); assertNull(group.agreedRaw)
                assertEquals(setOf("含早餐，不含城市稅。", "含早餐，不含城市税。"),
                    input.filter { it.provenance.id in ids }.map { it.rawText }.toSet())
                criticalConflicts++
            }
            total += input.size; empty += input.count { it.rawText.isEmpty() }
            noncore += input.count { !it.provenance.ownsCoreCenter }; chars += input.sumOf { it.rawText.length }
            fun save(key: String, value: Any) { report += "$case.$key=$value" }
            save("candidateCount", input.size); save("groupCount", result.groups.size); save("edgeCount", result.edges.size)
            save("signatureSha256", sha(signature(result).toByteArray(Charsets.UTF_8)))
            save("versionSource", p("versionSource")); save("fieldPreservation", true); save("permutationsStable", true)
            result.groups.forEachIndexed { i, g ->
                save("group.$i.id", b64(g.id)); save("group.$i.members", g.memberIds.joinToString(",", transform = ::b64))
                save("group.$i.text", g.text.name); save("group.$i.geometry", g.geometry.name)
                save("group.$i.reasons", g.reasons.joinToString(",")); save("group.$i.agreed", g.agreedRaw?.let(::b64) ?: "-")
            }
            result.edges.forEachIndexed { i, e ->
                save("edge.$i", "${b64(e.leftId)},${b64(e.rightId)},${e.kind}")
            }
        }
        assertEquals(472, total); assertEquals(40, empty); assertEquals(92, noncore); assertEquals(7880, chars)
        assertEquals(2, criticalConflicts)
        report += listOf("candidateCount=$total", "emptyCount=$empty", "noncoreCount=$noncore", "utf16Units=$chars",
            "criticalConflicts=$criticalConflicts", "passed=true")
        val output = File("build/reports/full-page-association/replay.properties")
        val directory = checkNotNull(output.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        output.writeText(report.joinToString("\n", postfix = "\n"), Charsets.US_ASCII)
    }
}
