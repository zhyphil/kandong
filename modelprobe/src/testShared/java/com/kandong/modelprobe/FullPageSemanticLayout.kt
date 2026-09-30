package com.kandong.modelprobe

import com.kandong.modelprobe.FullPageOcrAssociation.Result
import com.kandong.modelprobe.FullPageOcrAssociation.Text
import com.kandong.modelprobe.FullPageOcrAssociation.Geometry
import com.kandong.modelprobe.FullPageOcrAssociation.Reason
import com.kandong.modelprobe.FullPageOcrContract.Candidate
import com.kandong.ocrlab.context.ContextRect
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import kotlin.math.abs

/** Fixed synthetic EN/FR experiment. Derived order is explicitly uncertain, never OCR quality.
 * No receipts, clocks, IO, association mutation, raw text normalization or overlap winner. */
internal object FullPageSemanticLayout {
    const val VERSION="full-page-semantic-layout-v1"
    const val QUALIFICATION="LIMITED_GROUP_GEOMETRY_QUALIFICATION"
    data class Phrase(val key:String,val memberKeys:List<String>,val eligible:Boolean,val ambiguous:Boolean,
        val reasons:List<String>,val qualification:List<String>)
    data class Target(val key:String,val memberKeys:List<String>,val sourceText:String)
    data class Layout(val orderedKeys:List<String>,val groups:List<Phrase>,val blockedKeys:List<String>,
        val targets:List<Target>,val context:String,val version:String=VERSION,val orderUncertain:Boolean=true)
    private fun <T> frozen(v:Collection<T>):List<T> = Collections.unmodifiableList(ArrayList(v))
    fun digest(fields:List<String>):String {
        val hash=MessageDigest.getInstance("SHA-256")
        fields.forEach { val bytes=it.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array());hash.update(bytes) }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun normalized(s:String)=Normalizer.normalize(s.lowercase(Locale.ROOT),Normalizer.Form.NFD).replace(Regex("\\p{M}"),"")
    fun head(s:String,language:String):Boolean=when(language) {
        "en" -> Regex("(no refunds|refunds are available) (before|after)").matches(normalized(s))
        "fr" -> Regex("(aucun remboursement|remboursement possible) (avant|apres)").matches(normalized(s))
        else -> false
    }
    fun tail(s:String)=Regex("confirmation\\.?").matches(normalized(s))
    fun bounds(c:Candidate)=c.provenance.pageQuad.let { q -> ContextRect(q.minOf { it.x },q.minOf { it.y },q.maxOf { it.x },q.maxOf { it.y }) }
    fun nearHorizontal(c:Candidate):Boolean {
        val q=c.provenance.pageQuad
        if(q.size!=4 || q.any { !it.x.isFinite() || !it.y.isFinite() })return false
        val (a,b,d,e)=q;val h=bounds(c).height
        if(h<=0 || !(a.x<b.x && e.x<d.x && a.y<e.y && b.y<d.y))return false
        for(i in 0..3) { val p=q[i];val r=q[(i+1)%4];val s=q[(i+2)%4]
            if((r.x-p.x)*(s.y-r.y)-(r.y-p.y)*(s.x-r.x)<=0)return false }
        return abs(b.y-a.y)<=.02*(b.x-a.x) && abs(d.y-e.y)<=.02*(d.x-e.x) &&
            abs(e.x-a.x)<=.05*h && abs(d.x-b.x)<=.05*h
    }
    private fun adjacent(a:Candidate,b:Candidate):Boolean {
        val x=bounds(a);val y=bounds(b);val h=minOf(x.height,y.height)
        return h>0 && abs(x.left-y.left)<=.5*h && y.top-x.bottom in 0.0..(.75*h) && maxOf(x.height,y.height)<=1.5*h
    }
    private fun overlap(a:Candidate,b:Candidate):Boolean {
        val x=bounds(a);val y=bounds(b)
        return maxOf(x.left,y.left)<minOf(x.right,y.right) && maxOf(x.top,y.top)<minOf(x.bottom,y.bottom)
    }
    private fun blocksPair(other:Candidate,a:Candidate,b:Candidate):Boolean {
        if(overlap(other,a)||overlap(other,b))return true
        val x=bounds(a);val y=bounds(b);val z=bounds(other)
        return maxOf(x.left,y.left,z.left)<minOf(x.right,y.right,z.right) && z.top<y.top && z.bottom>x.bottom
    }
    fun derive(a:Result,language:String):Layout {
        val raw=a.rawCandidates
        require(raw.size<=128 && raw.sumOf { it.rawText.length.toLong() }<=8192)
        require(raw.map { it.provenance.id }.distinct().size==raw.size)
        require(raw.map(RecordedFullPageTranslation::key).distinct().size==raw.size)
        val ordered=raw.sortedWith(compareBy<Candidate>({bounds(it).top},{bounds(it).left},{bounds(it).bottom},
            {bounds(it).right},{it.rawText},{RecordedFullPageTranslation.key(it)}))
        val original=a.groups.flatMap { g -> g.memberIds.map { it to g } }.toMap()
        require(original.keys==raw.map { it.provenance.id }.toSet())
        val graph=raw.associate { it.provenance.id to linkedSetOf<String>() }
        ordered.filter { head(it.rawText,language) }.forEach { x ->
            ordered.filter { it!==x && tail(it.rawText) && adjacent(x,it) }.forEach { y ->
                graph.getValue(x.provenance.id).add(y.provenance.id);graph.getValue(y.provenance.id).add(x.provenance.id) }
        }
        val groups=mutableListOf<Phrase>();val seen=mutableSetOf<String>()
        for(c in ordered) {
            val id=c.provenance.id
            if(id in seen || graph.getValue(id).isEmpty())continue
            val todo=java.util.ArrayDeque<String>();todo.add(id);val component=mutableSetOf<String>()
            while(todo.isNotEmpty()) { val i=todo.removeFirst();if(component.add(i))todo.addAll(graph.getValue(i).filter { it !in component }) }
            seen.addAll(component)
            val members=ordered.filter { it.provenance.id in component };val reasons=mutableListOf<String>()
            if(members.size!=2)reasons.add("NON_UNIQUE_ADJACENCY") else {
                if(raw.any { it.provenance.id !in component && blocksPair(it,members[0],members[1]) })
                    reasons.add("INTERVENING_OR_OVERLAP_COMPETITOR")
                members.forEach { x ->
                    val g=original.getValue(x.provenance.id)
                    if(g.text==Text.DIFFERENT_RAW || (g.reasons.isNotEmpty() && g.reasons!=listOf(Reason.NON_AXIS_ALIGNED)))
                        reasons.add("ORIGINAL_DIAGNOSTIC_UNSUPPORTED")
                    if(!nearHorizontal(x))reasons.add("GEOMETRY_UNQUALIFIED")
                }
            }
            val keys=frozen(members.map(RecordedFullPageTranslation::key))
            groups.add(Phrase("g:"+digest(listOf(VERSION)+keys),keys,reasons.isEmpty(),reasons.isNotEmpty(),
                frozen(reasons.distinct()),frozen(if(reasons.isEmpty())listOf(QUALIFICATION) else emptyList())))
        }
        val groupByKey=groups.flatMap { g -> g.memberKeys.map { it to g } }.toMap()
        val targets=mutableListOf<Target>();val blocked=mutableListOf<String>()
        for(c in ordered) {
            val key=RecordedFullPageTranslation.key(c);val g=groupByKey[key]
            if(g!=null) {
                if(g.eligible && targets.none { it.key==g.key })targets.add(Target(g.key,g.memberKeys,
                    g.memberKeys.joinToString("\n") { k -> ordered.single { RecordedFullPageTranslation.key(it)==k }.rawText }))
            } else if(language in setOf("en","fr") && (tail(c.rawText)||Regex("\\b(refunds?|remboursement)\\b").containsMatchIn(normalized(c.rawText)))) {
                blocked.add(key)
            } else {
                val diagnostic=original.getValue(c.provenance.id)
                if(c.rawText.isNotBlank() && diagnostic.text!=Text.DIFFERENT_RAW && Reason.POSSIBLE_CLIP !in diagnostic.reasons &&
                    diagnostic.geometry!=Geometry.UNCERTAIN)targets.add(Target(key,frozen(listOf(key)),c.rawText))
            }
        }
        return Layout(frozen(ordered.map(RecordedFullPageTranslation::key)),frozen(groups),frozen(blocked),frozen(targets),ordered.joinToString("\n") { it.rawText })
    }
    fun fields(layout:Layout):List<String> {
        val out=mutableListOf("grouped",layout.version,"orderUncertain",if(layout.orderUncertain)"1" else "0","order",layout.orderedKeys.size.toString())
        out.addAll(layout.orderedKeys)
        layout.groups.forEach { g -> out.addAll(listOf("phrase",g.key,g.memberKeys.size.toString())+g.memberKeys+
            listOf(if(g.eligible)"1" else "0",if(g.ambiguous)"1" else "0",g.reasons.size.toString())+g.reasons+
            listOf(g.qualification.size.toString())+g.qualification) }
        out.addAll(listOf("blocked",layout.blockedKeys.size.toString())+layout.blockedKeys)
        layout.targets.forEach { t -> out.addAll(listOf("target",t.key,t.memberKeys.size.toString())+t.memberKeys+t.sourceText) }
        out.addAll(listOf("ordered-context",layout.context));return frozen(out)
    }
}
