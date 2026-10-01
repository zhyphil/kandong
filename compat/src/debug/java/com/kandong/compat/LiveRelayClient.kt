package com.kandong.compat

import com.kandong.liveocr.OcrBlock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** No retries, redirect, arbitrary endpoint, disk text cache, API key or content logs. */
internal class LiveRelayClient {
    @Volatile private var connection: HttpURLConnection? = null
    private class Pending(val id:String,val config:CloudTranslationConfig)
    private var pending: Pending?=null
    @Synchronized fun cancel() {
        val request=pending; pending=null
        connection?.disconnect(); connection=null
        if(request != null) Thread({
            var c:HttpURLConnection?=null
            try {
                c=URL(request.config.origin+"/cancel").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                c.instanceFollowRedirects=false; c.connectTimeout=2000; c.readTimeout=2000
                c.requestMethod="POST"; c.doOutput=true; c.useCaches=false
                c.setRequestProperty("Content-Type","application/json")
                c.setRequestProperty("Authorization","Bearer ${request.config.token}")
                c.setRequestProperty("Connection","close")
                val body=JSONObject().put("requestId",request.id).toString().toByteArray()
                c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) }; body.fill(0)
                c.responseCode
            } catch(_:Exception) { /* No retry, no content; disconnect/lease also gate relay. */ }
            finally { c?.disconnect() }
        },"KanDong cloud cancel").start()
    }
    fun translate(config: CloudTranslationConfig, blocks: List<OcrBlock>, language: String, remainingMillis: () -> Long, current: () -> Boolean): Map<String,String> {
        check(current()) { "STALE" }
        require(language in listOf("EN","FR") && blocks.size in 1..50)
        require(config.expiresAt>System.currentTimeMillis()) { "CREDENTIAL_EXPIRED" }
        val remaining=remainingMillis()
        require(remaining in 5001L..60000L) { "PAGE_EXPIRED" }
        require(blocks.all { it.text.isNotBlank() && it.text.codePointCount(0,it.text.length)<=1500 } &&
            blocks.sumOf { it.text.codePointCount(0,it.text.length) }<=6000 && blocks.map { it.id }.distinct().size==blocks.size) { "PAGE_TOO_LARGE" }
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("requestId",id).put("language",language)
            .put("disclosure",TranslationSetupView.DISCLOSURE_VERSION).put("publicPageConfirmed",true)
            .put("remainingMillis",remaining)
            .put("blocks",JSONArray().apply { blocks.forEach { put(JSONObject().put("id",it.id).put("text",it.text)) } })
            .toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 32768) { "PAGE_TOO_LARGE" }
        val c = URL(config.origin+"/translate").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        synchronized(this) {
            check(current()) { "STALE" }; connection=c; pending=Pending(id,config)
        }
        try {
            c.instanceFollowRedirects=false; c.connectTimeout=4000; c.readTimeout=35000
            c.requestMethod="POST"; c.doOutput=true; c.useCaches=false
            c.setRequestProperty("Authorization","Bearer ${config.token}")
            c.setRequestProperty("Content-Type","application/json")
            c.setRequestProperty("Connection","close")
            c.setFixedLengthStreamingMode(body.size)
            check(current()) { "STALE" }
            c.outputStream.use { it.write(body) }
            val code=c.responseCode
            check(code !in 300..399) { "SERVICE_UNAVAILABLE" }
            val raw=(if(code == 200) c.inputStream else c.errorStream)?.use { stream ->
                val out=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(4096)
                while(true) { val n=stream.read(buffer); if(n<0) break; require(out.size()+n<=32768) { "RESPONSE_TOO_LARGE" }; out.write(buffer,0,n) }
                out.toByteArray()
            } ?: error("SERVICE_UNAVAILABLE")
            check(current()) { "STALE" }
            val json=try { JSONObject(String(raw,Charsets.UTF_8)) } finally { raw.fill(0) }
            if(code != 200) {
                throw IllegalStateException(LiveTranslationError.relayCode(json.optString("error")))
            }
            require(json.getString("requestId") == id)
            val rows=json.getJSONArray("translations")
            require(rows.length() == blocks.size)
            return linkedMapOf<String,String>().apply {
                blocks.forEachIndexed { index,b ->
                    val row=rows.getJSONObject(index)
                    require(row.getString("id") == b.id)
                    val text=row.getString("text"); require(text.isNotBlank() && text.length<=6000)
                    put(b.id,text)
                }
            }
        } catch(e:Exception) {
            cancel(); throw e
        } finally {
            body.fill(0); c.disconnect(); if(connection===c) connection=null
            synchronized(this) { if(pending?.id == id) pending=null }
        }
    }
}
