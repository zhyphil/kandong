package com.kandong.modelprobe

import ai.onnxruntime.OrtSession
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.ocrlab.context.ClearReason
import com.kandong.ocrlab.context.ContextRect
import com.kandong.ocrlab.context.MirrorTransform
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.io.File
import java.util.UUID

/** Fixed authenticated synthetic Images only. No screenshots, device content or translation.
 * ch is pinned for both pages; expected text is inspected only AFTER real inference.
 * Each page closes its two sessions and Image scope and restores threads before claiming.
 */
@RunWith(AndroidJUnit4::class)
internal class FullPageRegionProbeTest : DetectorProbeTestSupport() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val geometry = GeometryCleanup()
    private val ort = Cleanup()
    private fun ortJson() = JSONObject().put("opened",ort.opened).put("closeAttempts",ort.closeAttempts)
        .put("closed",ort.closed).put("uncertain",ort.uncertain)
    private fun reason(e: Exception) = if(e is ProbeFailure) e.code else
        e.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: e.javaClass.simpleName
    private fun fields(c: FullPageOcrContract.Candidate): List<Any> {
        val p=c.provenance
        return listOf(p.version,p.pageFixtureId,p.stripIndex,p.read,p.core,p.contourIndex,p.rawBoxIndex,
            p.finalBoxIndex,p.stripReadingOrder,p.localQuad,p.pageQuad,p.detectorScore.toRawBits(),
            p.ownsCoreCenter,p.id,c.modelId,c.rawText,c.recognitionWidth,c.recognitionTime)
    }
    private fun rect(r: ContextRect)=JSONArray(listOf(r.left,r.top,r.right,r.bottom))
    private fun frameJson(f: FullPageRegionController.Frame)=JSONObject()
        .put("roi",rect(f.roi)).put("scale",f.transform.scale)
        .put("panSourcePixels",JSONArray(listOf(f.transform.panX,f.transform.panY)))
        .put("mirrorSize",JSONArray(listOf(f.transform.width,f.transform.height)))
        .put("selectedIds",JSONArray(f.selectedIds)).put("contextIds",JSONArray(f.contextIds))
        .put("unsupportedIds",JSONArray(f.unsupportedIds))
        .put("anchorsAreBoxEnvelopes",true)
        .put("mirror",JSONArray(f.visible.map { p -> JSONObject().put("id",p.candidateId)
            .put("groupId",p.groupId).put("sourceEnvelope",rect(p.sourceEnvelope))
            .put("sourceIntersection",rect(p.sourceIntersection)).put("mirrorRect",rect(p.mirrorRect))
            .put("conservative",p.conservative) }))

    private fun runPage(runId: String, session: Long, index: Int, page: JSONObject,
        fixture: FullPageOcrFixtures, inputs: ProbeInputs, model: ProbeModel,
        detectorInputs: DetectorProbeInputs, engine: OrtProbeEngine): JSONObject {
        val id=page.getString("id")
        val version=CaptureVersion(session,(index+1).toLong(),index.toLong(),1,0,0)
        val controller=FullPageRegionController { CaptureCheckpoint(version,true,SystemClock.elapsedRealtime()) }
        controller.observePage(version)
        val report=JSONObject().put("pageFixtureId",id).put("passed",false).put("fixtureSha256",FullPageOcrFixtures.MANIFEST_SHA)
            .put("modelId",model.id).put("modelSha256",model.sha).put("dictionarySha256",model.dictionarySha)
            .put("sourceWidth",page.getInt("width")).put("sourceHeight",page.getInt("height"))
        var staged: FullPageOcrPipeline.StagedPage?=null
        var permit: FullPageOcrPublication.RegionPermit?=null
        var ticket: FullPageRegionController.Ticket?=null
        var priorThreads: Int?=null
        var restored=false
        var scopeSucceeded=false
        var sessionsOpened=0; var sessionsClosed=0; var live=0; var peak=0
        val beforeTransport=fixture.transport.toMap()
        val beforeOrt=listOf(ort.opened,ort.closeAttempts,ort.closed)
        val started=SystemClock.elapsedRealtime()
        fun <T> withSession(bytes: ByteArray, block: (OrtSession) -> T): T {
            val outstanding=ort.opened-ort.closed
            var entered=false
            try {
                return engine.withModel(bytes) { s ->
                    bytes.fill(0); entered=true; sessionsOpened++; live++; peak=maxOf(peak,live)
                    check(live<=2) { "SESSION_BUDGET" }
                    block(s)
                }
            } finally {
                bytes.fill(0)
                if(entered) {
                    live--
                    if(!ort.uncertain && ort.opened-ort.closed==outstanding) sessionsClosed++
                }
            }
        }
        try {
            val dictionary=inputs.dictionary(model)
            priorThreads=Core.getNumThreads()
            try {
                Core.setNumThreads(1); check(Core.getNumThreads()==1) { "THREAD_SETUP" }
                withSession(detectorInputs.model()) { detector ->
                    withSession(inputs.model(model)) { recognizer ->
                        val pipeline=FullPageOcrPipeline(engine,detector,recognizer,model,dictionary,geometry,ort)
                        val clicked=checkNotNull(controller.click()) { "CLICK_REJECTED" }; ticket=clicked
                        val meta=RgbaFrameMetadata(page.getInt("width"),page.getInt("height"),version,
                            SystemClock.elapsedRealtime(),60_000)
                        fixture.withFrame(page,meta) { image ->
                            pipeline.runStaged(runId,id,image,meta) { controller.checkpoint(clicked) }.also { staged=it }
                        }
                    }
                }
                scopeSucceeded=true // All native callbacks AND their resource closes have returned.
            } finally {
                Core.setNumThreads(checkNotNull(priorThreads))
                restored=Core.getNumThreads()==priorThreads
            }
            val original=checkNotNull(staged) { "NOT_STAGED" }
            val actual=original.page
            val meta=original.metadata
            report.put("sourceVersion",JSONObject().put("session",version.session).put("snapshot",version.snapshot)
                .put("page",version.page).put("revision",version.revision).put("window",version.window).put("display",version.display))
                .put("acquiredAtMillis",meta.acquiredAtMillis).put("ttlMillis",meta.ttlMillis)
                .put("sourceComplete",actual.complete).put("sourceReason",actual.reason ?: JSONObject.NULL)
                .put("sourceCandidateCount",actual.candidates.size).put("detectorInvocations",actual.detectorInvocations)
                .put("recognitionInvocations",actual.recognitionInvocations)
                .put("frameCloseAttempts",actual.frameCloseAttempts).put("frameClosed",actual.frameClosed)
            val resourcesClosed=restored && live==0 && sessionsOpened==2 && sessionsClosed==2 && peak==2 &&
                !ort.uncertain && ort.opened==ort.closed && ort.closeAttempts==ort.closed &&
                geometry.balanced && fixture.balanced
            permit=original.claimForRegion(scopeSucceeded,resourcesClosed)
            val clicked=checkNotNull(ticket)
            check(controller.accept(clicked,permit)) { "REGION_REJECTED" }
            permit?.close() // Transferred aliases are inert.
            original.discard() // Pending cleanup cannot revoke the transferred guard.
            val strips=FullPageStripPlanner.plan(meta.width,meta.height).strips.size
            check(actual.complete && actual.reason==null && actual.transportRejection==null &&
                actual.detectorInvocations==strips && actual.recognitionInvocations==actual.candidates.size &&
                actual.frameCloseAttempts==1 && actual.frameClosed==1) { "SOURCE_INCOMPLETE" }
            val whole=checkNotNull(controller.render(ContextRect(0.0,0.0,meta.width.toDouble(),meta.height.toDouble()),
                MirrorTransform(1.0,meta.width.toDouble(),meta.height.toDouble()))) { "WHOLE_PAGE_REJECTED" }
            check(whole.metadata===meta) { "METADATA_REPLACED" }
            val identity=checkNotNull(whole.page.identity)
            check(identity==FullPageOcrAssociation.PageIdentity(runId,version,id,
                FullPageOcrContract.Model(model.id,model.sha,model.vocabulary),DetectorProbeInputs.MODEL_SHA,
                model.dictionarySha)) { "IDENTITY_CHANGED" }
            val originals=actual.candidates.associate { it.provenance.id to fields(it) }
            check(originals.size==actual.candidates.size && whole.page.rawCandidates.size==originals.size &&
                originals==whole.page.rawCandidates.associate { it.provenance.id to fields(it) }) { "EVIDENCE_CHANGED" }
            val members=whole.page.groups.flatMap { it.memberIds }
            check(members.size==originals.size && members.toSet()==originals.keys) { "SPATIAL_MEMBERS_CHANGED" }
            val inferenceCounts=listOf(actual.detectorInvocations,actual.recognitionInvocations)
            val nativeCounters=listOf(ort.opened,ort.closed,ort.closeAttempts,geometry.findContoursCalls,geometry.matOpened)
            val transportCounters=fixture.transport.toMap()
            val roi=ContextRect(60.0,610.0,320.0,665.0)
            val selected=checkNotNull(controller.render(roi,MirrorTransform(2.0,520.0,110.0))) { "ROI_REJECTED" }
            val panned=checkNotNull(controller.render(roi,MirrorTransform(3.0,180.0,90.0,40.0,10.0))) { "PAN_REJECTED" }
            val moved=checkNotNull(controller.render(ContextRect(0.0,0.0,600.0,200.0),MirrorTransform(1.0,600.0,200.0))) { "MOVE_REJECTED" }
            check(listOf(selected,panned,moved).all { it.page===whole.page && it.metadata===meta } &&
                selected.selectedIds==panned.selectedIds) { "PROJECTION_CHANGED_EVIDENCE" }
            check(inferenceCounts==listOf(actual.detectorInvocations,actual.recognitionInvocations) &&
                nativeCounters==listOf(ort.opened,ort.closed,ort.closeAttempts,geometry.findContoursCalls,geometry.matOpened) &&
                transportCounters==fixture.transport) { "PROJECTION_RERAN_NATIVE_WORK" }
            report.put("frameCandidateCount",whole.page.rawCandidates.size)
                .put("sourceStripCount",strips).put("edgeCount",whole.page.edges.size).put("groupCount",whole.page.groups.size)
                .put("emptyRawCount",whole.page.rawCandidates.count { it.rawText.isEmpty() })
                .put("noncoreCount",whole.page.rawCandidates.count { !it.provenance.ownsCoreCenter })
                .put("allProvenanceFieldsPreserved",true).put("spatialMembersComplete",true).put("noProjectionInference",true)
                .put("projection",frameJson(selected)).put("pannedProjection",frameJson(panned)).put("movedProjection",frameJson(moved))
            if(id=="hant-seam") {
                val ids=listOf("/s0/c0-r0-f0","/s1/c2-r2-f2").map { suffix ->
                    actual.candidates.single { it.provenance.id.endsWith(suffix) }.provenance.id
                }
                val conflict=whole.page.groups.single { it.memberIds.containsAll(ids) }
                check(conflict.text==FullPageOcrAssociation.Text.DIFFERENT_RAW && conflict.agreedRaw==null &&
                    whole.page.rawCandidates.filter { it.provenance.id in ids }.map { it.rawText }.toSet()==
                    setOf("含早餐，不含城市稅。","含早餐，不含城市税。")) { "RAW_CONFLICT_CHANGED" }
                check(selected.selectedIds.containsAll(ids) && selected.contextIds.containsAll(conflict.memberIds)) { "ROI_CONFLICT_MISSING" }
                check(whole.page.rawCandidates.any { !it.provenance.ownsCoreCenter }) { "NONCORE_MISSING" }
                controller.clear(ClearReason.PAUSE)
                check(controller.render(roi,MirrorTransform(2.0,520.0,110.0))==null && !controller.accept(clicked,null)) { "CLEAR_DID_NOT_REVOKE" }
                controller.observePage(version)
                check(controller.render(roi,MirrorTransform(2.0,520.0,110.0))==null) { "OBSERVATION_REACQUIRED" }
                report.put("conflictIds",JSONArray(ids)).put("rawTaxConflictRetained",true).put("clearRevoked",true)
            } else {
                check(id=="blank" && whole.page.rawCandidates.isEmpty() && whole.selectedIds.isEmpty() &&
                    whole.visible.isEmpty() && whole.contextIds.isEmpty() && whole.unsupportedIds.isEmpty()) { "BLANK_NOT_COMPLETE" }
                // Real elapsed time, original acquisition and exact 60-second TTL; never renew metadata.
                val deadline=meta.acquiredAtMillis+meta.ttlMillis
                while(SystemClock.elapsedRealtime()<deadline) {
                    SystemClock.sleep(minOf(250L,deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                }
                check(controller.render(roi,MirrorTransform(2.0,520.0,110.0))==null) { "EXPIRY_NOT_REVOKED" }
                controller.observePage(version)
                check(controller.render(roi,MirrorTransform(2.0,520.0,110.0))==null) { "EXPIRED_PAGE_REVIVED" }
                report.put("blankComplete",true).put("expiryRevoked",true).put("expiredAtCheckMillis",SystemClock.elapsedRealtime())
            }
            report.put("passed",true)
        } catch(e: Exception) {
            report.put("passed",false).put("error",reason(e))
        } finally {
            controller.clear(ClearReason.STOP); permit?.close(); staged?.discard()
            report.put("scopeSucceeded",scopeSucceeded).put("threadsBefore",priorThreads ?: JSONObject.NULL)
                .put("threadsRestored",restored).put("sessionsOpened",sessionsOpened).put("sessionsClosed",sessionsClosed)
                .put("peakSimultaneousSessions",peak).put("liveSessions",live)
                .put("ortResourcesOpened",ort.opened-beforeOrt[0]).put("ortCloseAttempts",ort.closeAttempts-beforeOrt[1])
                .put("ortResourcesClosed",ort.closed-beforeOrt[2]).put("ortCleanup",ortJson())
                .put("geometryCleanup",geometry.json()).put("transportCleanup",JSONObject(fixture.transport.mapValues { (k,v) -> v-beforeTransport.getValue(k) }))
                .put("cleanupBalanced",geometry.balanced && fixture.balanced && !ort.uncertain && ort.opened==ort.closed && ort.closeAttempts==ort.closed)
                .put("elapsedMillis",SystemClock.elapsedRealtime()-started)
        }
        return report
    }

    @Test fun fixedHantSeamAndBlankImageEvidenceProjectsWithoutReinference() {
        val runId=UUID.randomUUID().toString(); val start=SystemClock.elapsedRealtime()
        val pages=JSONArray(); var fixture: FullPageOcrFixtures?=null
        val summary=JSONObject().put("schema",1).put("runId",runId).put("fixtureSha256",FullPageOcrFixtures.MANIFEST_SHA)
            .put("passed",false).put("device",Build.MODEL).put("api",Build.VERSION.SDK_INT).put("pages",pages)
            .put("scope","Authenticated fixed synthetic Image -> actual detector/crop/ch recognizer -> association -> source-pixel box-envelope projection. No real capture, privacy, OCR quality or translation acceptance.")
            .put("snapshotRetention","Clear drops controller references; callers may retain immutable evidence. No heap erasure claim.")
        try {
            check(OpenCVLoader.initLocal() && Core.VERSION=="5.0.0") { "OPENCV_RUNTIME" }
            val fixtures=FullPageOcrFixtures(instrumentation.context.assets,geometry); fixture=fixtures
            val inputs=ProbeInputs { instrumentation.targetContext.assets.open(it) }
            val model=inputs.manifest().models.single { it.id=="ch" }
            val detectorInputs=DetectorProbeInputs { instrumentation.context.assets.open(it) }
            val engine=OrtProbeEngine(ort); check(engine.runtime=="1.30.0") { "ORT_RUNTIME" }
            summary.put("opencv",Core.VERSION).put("ort",engine.runtime).put("detectorSha256",DetectorProbeInputs.MODEL_SHA)
            for((index,id) in listOf("hant-seam","blank").withIndex()) {
                val report=runPage(runId,start,index,fixtures.pages.single { it.getString("id")==id },fixtures,inputs,model,detectorInputs,engine)
                pages.put(report)
                check(report.getBoolean("cleanupBalanced") && report.getBoolean("threadsRestored")) { "PAGE_CLEANUP_FAILED" }
            }
            summary.put("passed",pages.length()==2 && (0 until pages.length()).all { pages.getJSONObject(it).getBoolean("passed") })
        } catch(e: Exception) { summary.put("error",reason(e)).put("passed",false) }
        finally {
            summary.put("geometryCleanup",geometry.json()).put("ortCleanup",ortJson())
                .put("transportCleanup",JSONObject(fixture?.transport?.toMap().orEmpty()))
                .put("elapsedMillis",SystemClock.elapsedRealtime()-start)
            save(AtomicFile(File(instrumentation.targetContext.filesDir,"full-page-region-probe.json")),summary)
        }
        assertTrue("Inspect full-page-region-probe.json; technical region evidence only",summary.getBoolean("passed"))
    }
}
