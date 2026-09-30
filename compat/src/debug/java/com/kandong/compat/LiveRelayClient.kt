package com.kandong.compat

import android.content.Context
import com.kandong.liveocr.OcrBlock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** No retries, redirect, arbitrary endpoint, disk text cache, API key or content logs. */
internal class LiveRelayClient(private val context: Context) {
    @Volatile private var connection: HttpURLConnection? = null
    private var pending: Pair<String,String>?=null
    @Synchronized fun cancel() {
        val request=pending; pending=null
        connection?.disconnect(); connection=null
        if(request != null) Thread({
            var c:HttpURLConnection?=null
            try {
                c=URL("http://127.0.0.1:18741/cancel").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                c.instanceFollowRedirects=false; c.connectTimeout=2000; c.readTimeout=2000
                c.requestMethod="POST"; c.doOutput=true; c.useCaches=false
                c.setRequestProperty("Content-Type","application/json")
                c.setRequestProperty("Authorization","Bearer ${request.second}")
                c.setRequestProperty("Connection","close")
                val body=JSONObject().put("requestId",request.first).toString().toByteArray()
                c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) }; body.fill(0)
                c.responseCode
            } catch(_:Exception) { /* No retry, no content; disconnect/lease also gate relay. */ }
            finally { c?.disconnect() }
        },"KanDong relay cancel").start()
    }
    fun translate(blocks: List<OcrBlock>, language: String, remainingMillis: () -> Long, current: () -> Boolean): Map<String,String> {
        check(current()) { "STALE" }
        require(language in listOf("EN","FR") && blocks.size in 1..50)
        val file = java.io.File(context.filesDir,"relay-token")
        require(file.isFile && file.length() == 64L && file.canonicalFile.parentFile == context.filesDir.canonicalFile) { "RELAY_NOT_CONFIGURED" }
        val token = file.inputStream().use { String(it.readBytes(),Charsets.US_ASCII) }
        require(token.matches(Regex("[a-f0-9]{64}"))) { "RELAY_NOT_CONFIGURED" }
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("requestId",id).put("language",language)
            .put("disclosure","deepl-free-public-v1").put("publicPageConfirmed",true)
            .put("remainingMillis",remainingMillis())
            .put("blocks",JSONArray().apply { blocks.forEach { put(JSONObject().put("id",it.id).put("text",it.text)) } })
            .toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 32768)
        val c = URL("http://127.0.0.1:18741/translate").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        connection=c
        synchronized(this) { pending=id to token }
        try {
            c.instanceFollowRedirects=false; c.connectTimeout=4000; c.readTimeout=35000
            c.requestMethod="POST"; c.doOutput=true; c.useCaches=false
            c.setRequestProperty("Authorization","Bearer $token")
            c.setRequestProperty("Content-Type","application/json")
            c.setRequestProperty("Connection","close")
            c.setFixedLengthStreamingMode(body.size)
            check(current()) { "STALE" }
            c.outputStream.use { it.write(body) }
            val code=c.responseCode
            val raw=(if(code == 200) c.inputStream else c.errorStream)?.use { stream ->
                val out=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(4096)
                while(true) { val n=stream.read(buffer); if(n<0) break; require(out.size()+n<=32768); out.write(buffer,0,n) }
                out.toByteArray()
            } ?: error("RELAY_UNAVAILABLE")
            check(current()) { "STALE" }
            val json=try { JSONObject(String(raw,Charsets.UTF_8)) } finally { raw.fill(0) }
            if(code != 200) {
                val allowed=setOf("SENSITIVE_PAGE","FREE_QUOTA_EXCEEDED","PAGE_TOO_LARGE","SESSION_LIMIT","LANGUAGE_MISMATCH","RELAY_EXPIRED")
                val error=json.optString("error")
                throw IllegalStateException(if(error in allowed) error else "RELAY_UNAVAILABLE")
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
        } finally {
            body.fill(0); c.disconnect(); if(connection===c) connection=null
            synchronized(this) { if(pending?.first == id) pending=null }
        }
    }
}
