package com.kandong.compat

import com.kandong.liveocr.LiveOcrException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

/** Content-free user guidance; does not retry or change snapshot lifetime. */
internal data class LiveTranslationError(val message: String, val code: String) {
    companion object {
        fun from(e: Exception, sending: Boolean = false): LiveTranslationError {
            if(e is LiveOcrException) return LiveTranslationError(
                if(e.code == "RECOGNITION_WIDTH_BUDGET") "本页含过长的文字行，当前版本尚不能完整识别。" else GENERIC,
                "ocr_"+e.code.lowercase(Locale.ROOT)
            )
            // Only transport errors from the send path describe the USB/Mac connection.
            // An OCR file/model IOException must keep its local processing diagnosis.
            val key=when {
                sending && e is SocketTimeoutException -> "RELAY_TIMEOUT"
                sending && e is IOException -> "RELAY_UNAVAILABLE"
                else -> e.message
            }
            return when(key) {
                "SENSITIVE_PAGE" -> LiveTranslationError("检测到可能的敏感信息，已清除本页，未发送。","sensitive_page")
                "RELAY_NOT_CONFIGURED" -> LiveTranslationError("尚未配置 Mac 翻译连接。请在 Mac 完成配置，再点翻译。","relay_not_configured")
                "RELAY_UNAVAILABLE" -> LiveTranslationError("翻译连接不可用。请连接手机与 Mac 并启动翻译服务。不会自动重试。","relay_unavailable")
                "RELAY_TIMEOUT" -> LiveTranslationError("等待翻译超时。请检查 Mac 网络后重新点翻译。不会自动重试。","relay_timeout")
                "RELAY_EXPIRED","UNAUTHORIZED" -> LiveTranslationError("Mac 翻译连接已失效。请在 Mac 重新配置连接，再点翻译。","relay_expired")
                "PROVIDER_FAILED_NO_RETRY" -> LiveTranslationError("翻译服务暂时未能完成处理，请稍后再点翻译。不会自动重试。","provider_failed")
                "FREE_QUOTA_EXCEEDED" -> LiveTranslationError("DeepL 免费翻译额度不足，请在 Mac 检查额度。本次未继续请求翻译。","free_quota_exceeded")
                "SESSION_LIMIT" -> LiveTranslationError("本次连接的翻译上限已用完，请在 Mac 重新启动并配置翻译连接。","session_limit")
                "PAGE_EXPIRED" -> LiveTranslationError("本次快照已过期或剩余时间不足，请重新点翻译。","expired")
                "LANGUAGE_MISMATCH" -> LiveTranslationError("译文的源语言与所选语言不一致。请重新点翻译，核对英语或法语选项。","language_mismatch")
                "PAGE_TOO_LARGE" -> LiveTranslationError("本页文字超过开发版处理上限，请换文字较少的页面后再点翻译。","page_too_large")
                "INVALID_RESPONSE","RESPONSE_TOO_LARGE" -> LiveTranslationError("未收到可用的完整译文，请稍后再点翻译。不会自动重试。","response_invalid")
                "BUSY" -> LiveTranslationError("翻译连接正在处理上一项请求，请稍后再点翻译。不会自动重试。","relay_busy")
                "RECOGNITION_WIDTH_BUDGET" -> LiveTranslationError("本页含过长的文字行，当前版本尚不能完整识别。","processing_failed")
                else -> LiveTranslationError(GENERIC,"processing_failed")
            }
        }

        // Only contract codes cross into the UI. Never display raw server/exception text.
        fun relayCode(value: String): String = if(value in allowedRelayCodes) value else "RELAY_UNAVAILABLE"

        private val allowedRelayCodes=setOf(
            "SENSITIVE_PAGE","FREE_QUOTA_EXCEEDED","PAGE_TOO_LARGE","SESSION_LIMIT",
            "LANGUAGE_MISMATCH","RELAY_EXPIRED","UNAUTHORIZED","PROVIDER_FAILED_NO_RETRY",
            "PAGE_EXPIRED","INVALID_RESPONSE","RESPONSE_TOO_LARGE","BUSY"
        )
        private const val GENERIC="本次处理未完成，请重新点翻译。不会自动重试或联网。"
    }
}
