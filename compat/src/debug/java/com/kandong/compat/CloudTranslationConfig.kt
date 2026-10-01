package com.kandong.compat

import java.net.URI

/** Deliberately not a data class: toString/copy must not disclose the credential. */
internal class CloudTranslationConfig private constructor(val origin: String, val token: String, val expiresAt: Long) {
    companion object {
        private val originPattern=Regex("https://kandong-translation-pilot\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.workers\\.dev(?::443)?")
        fun create(origin: String, token: String, expiresAt: Long, now: Long=System.currentTimeMillis()): CloudTranslationConfig {
            require(origin.length<=160 && originPattern.matches(origin)) { "CLOUD_NOT_CONFIGURED" }
            val uri=URI(origin)
            require(uri.scheme=="https" && uri.rawUserInfo==null && uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery==null && uri.rawFragment==null && uri.port in listOf(-1,443)) { "CLOUD_NOT_CONFIGURED" }
            require(token.matches(Regex("[a-f0-9]{64}"))) { "CLOUD_NOT_CONFIGURED" }
            require(expiresAt>now) { "CREDENTIAL_EXPIRED" }
            require(expiresAt-now<=90L*24*60*60*1000) { "CLOUD_NOT_CONFIGURED" }
            return CloudTranslationConfig(origin.removeSuffix(":443"),token,expiresAt)
        }
    }
}
