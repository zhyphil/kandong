package com.kandong.modelprobe

import com.kandong.modelprobe.FullPageOcrAssociation.Result
import com.kandong.modelprobe.FullPageOcrAssociation.Text
import com.kandong.modelprobe.FullPageOcrAssociation.Geometry
import com.kandong.modelprobe.FullPageOcrAssociation.Reason
import com.kandong.ocrlab.context.*
import java.security.MessageDigest
import java.util.Collections

/** Fixed-page structural adapter. No source/resource authority or language detection is minted here.
 * Coordinates are original page pixels; declaredLanguage is an explicit fixture declaration.
 * Spatial diagnostics reuse the original association rules, without new receipts,
 * lifecycle callbacks or publication permits. The original owner still authenticates delivery.
 */
internal object FullPageTranslationAdapter {
    enum class Rejection { EMPTY, INVALID, BUDGET }
    sealed interface Outcome
    data class Rejected(val reason: Rejection) : Outcome
    data class Evidence(val metadata: RgbaFrameMetadata, val association: Result)
    data class AdaptedPage(val snapshot: ScreenSnapshot, val originalEvidence: Evidence,
        val sourceMap: Map<String, FullPageOcrContract.Candidate>) : Outcome
    private const val DETECTOR_SHA = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
    private val dictionaries = mapOf("ch" to "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af",
        "latin" to "d80d308e9759b3ea3aaf6e57fa51b39ef0a2eeb3145a9b23df819f0f0279af44")
    private fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    /** Length-framed UTF-8 fields, including a domain tag; delimiters inside identities are harmless. */
    private fun digest(fields: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun adapt(metadata: RgbaFrameMetadata, association: Result, declaredLanguage: String): Outcome {
        fun invalid() = Rejected(Rejection.INVALID)
        val id = association.identity ?: return invalid()
        if (!association.published || association.rejection != null || id.version != metadata.version ||
            id.sourceBatch.isEmpty() || id.sourceBatch.length > 160 ||
            !id.pageFixtureId.matches(Regex("[a-z0-9-]{1,64}")) || id.model !in FullPageOcrContract.models ||
            id.detectorSha != DETECTOR_SHA || dictionaries[id.model.id] != id.dictionarySha) return invalid()
        val route = when (declaredLanguage) { "en", "fr" -> "latin"; "zh-Hans", "zh-Hant" -> "ch"; else -> return invalid() }
        val v = metadata.version
        if (id.model.id != route || listOf(v.session,v.snapshot,v.page,v.revision,v.window.toLong(),v.display.toLong()).any { it < 0 } ||
            metadata.acquiredAtMillis < 0 || metadata.ttlMillis !in 1..ContextEngine.MAX_TTL ||
            metadata.acquiredAtMillis > Long.MAX_VALUE - ContextEngine.MAX_TTL) return invalid()
        val input = association.rawCandidates
        if (input.size > 128 || input.sumOf { it.rawText.length.toLong() } > 8192 || input.any { it.rawText.length > 2000 } ||
            input.groupingBy { it.provenance.stripIndex }.eachCount().values.any { it > 64 }) return Rejected(Rejection.BUDGET)
        val expected = FullPageOcrAssociation.validatedCopy(association,metadata.width,metadata.height) ?: return invalid()
        val raw = expected.rawCandidates
        if (raw.any { val q = it.provenance.pageQuad; !ContextRect(q.minOf { p -> p.x },q.minOf { p -> p.y },
                q.maxOf { p -> p.x },q.maxOf { p -> p.y }).valid() }) return invalid()
        if (raw.isEmpty()) return Rejected(Rejection.EMPTY)
        val identityFields = listOf(id.sourceBatch,v.session.toString(),v.snapshot.toString(),v.page.toString(),
            v.revision.toString(),v.window.toString(),v.display.toString(),id.pageFixtureId,id.model.id,
            id.model.sha,id.model.vocabulary.toString(),id.detectorSha,id.dictionarySha,
            metadata.width.toString(),metadata.height.toString(),metadata.acquiredAtMillis.toString(),metadata.ttlMillis.toString())
        val pageId = digest(listOf("fixed-page-snapshot-v1") + identityFields)
        val byCandidate = expected.groups.flatMap { g -> g.memberIds.map { it to g } }.toMap()
        val map = linkedMapOf<String,FullPageOcrContract.Candidate>()
        val blocks = raw.mapIndexed { index,c ->
            val blockId = digest(listOf("fixed-page-block-v1",pageId,c.provenance.id))
            if (blockId == pageId || map.put(blockId,c) != null) return invalid() // Never silently merge hash collisions.
            val group = byCandidate.getValue(c.provenance.id)
            val state = when {
                c.rawText.isBlank() -> BlockState.UNKNOWN
                group.text == Text.DIFFERENT_RAW -> BlockState.CONFLICT
                Reason.POSSIBLE_CLIP in group.reasons -> BlockState.TRUNCATED
                group.geometry == Geometry.UNCERTAIN -> BlockState.AMBIGUOUS
                else -> BlockState.KNOWN
            }
            val q = c.provenance.pageQuad
            val rect = ContextRect(q.minOf { it.x },q.minOf { it.y },q.maxOf { it.x },q.maxOf { it.y })
            ContextBlock(blockId,c.rawText,declaredLanguage,"unclassified",rect,rect,index,
                contextIds=frozen(emptyList()),state=state,source=SourceKind.PACKAGED_FULL_PAGE_SINGLE_MODEL_OCR,
                ocr=OcrEvidence(pageId,frozen(q.map { ContextPoint(it.x,it.y) }),frozen(listOf(OcrCandidate(c.modelId,c.rawText))),
                    frozen(if(state == BlockState.KNOWN) listOf(c.modelId) else emptyList())))
        }
        val screen = ScreenSnapshot(ScreenIdentity(v.session,pageId,v.page,v.revision,v.window.toString(),v.display.toString()),
            metadata.width.toDouble(),metadata.height.toDouble(),metadata.acquiredAtMillis,metadata.ttlMillis,
            frozen(blocks),frozen(emptyList()),frozen(listOf("OCR_COVERAGE_UNVERIFIED",
                "FIXED_FIXTURE_LANGUAGE_DECLARED", "SPATIAL_GROUPS_ARE_NOT_SEMANTIC")))
        return AdaptedPage(screen,Evidence(metadata.copy(version=v.copy()),expected),Collections.unmodifiableMap(map))
    }
}
