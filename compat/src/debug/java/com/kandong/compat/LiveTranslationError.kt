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
            // Only transport errors from the send path describe the internet service connection.
            // An OCR file/model IOException must keep its local processing diagnosis.
            val key=when {
                sending && e is SocketTimeoutException -> "SERVICE_TIMEOUT"
                sending && e is IOException -> "SERVICE_UNAVAILABLE"
                else -> e.message
            }
            return when(key) {
                "SENSITIVE_PAGE" -> LiveTranslationError("检测到可能的敏感信息，已清除本页，未发送。","sensitive_page")
                "CLOUD_NOT_CONFIGURED" -> LiveTranslationError("此开发版尚未配置联网翻译，请联系安装者完成配置。","cloud_not_configured")
                "SERVICE_UNAVAILABLE","AUTHORITY_UNAVAILABLE","SERVICE_DISABLED" -> LiveTranslationError("联网翻译暂不可用，请检查手机网络或联系安装者。不会自动重试。","service_unavailable")
                "SERVICE_TIMEOUT" -> LiveTranslationError("等待翻译超时，请检查手机网络后重新点翻译。不会自动重试。","service_timeout")
                "CREDENTIAL_EXPIRED","UNAUTHORIZED" -> LiveTranslationError("此手机的翻译凭据已过期或已撤销，请联系安装者更新配置。","credential_expired")
                "PROVIDER_FAILED_NO_RETRY" -> LiveTranslationError("翻译服务暂时未能完成处理，请稍后再点翻译。不会自动重试。","provider_failed")
                "FREE_QUOTA_EXCEEDED" -> LiveTranslationError("DeepL 免费翻译额度不足，本次未继续请求翻译。","free_quota_exceeded")
                "PILOT_QUOTA_EXCEEDED","METADATA_LIMIT" -> LiveTranslationError("试用翻译额度已用完，请稍后再用或联系安装者。重新打开不会重置额度。","pilot_quota_exceeded")
                "CANCELLED","DUPLICATE_NO_RETRY" -> LiveTranslationError("本次请求已结束，未重复发送。需要新结果时请重新点翻译。","request_ended")
                "CONSENT_REQUIRED" -> LiveTranslationError("翻译说明版本已更新，请联系安装者更新开发版。","consent_required")
                "PAGE_EXPIRED" -> LiveTranslationError("本次处理超时或剩余时间不足，请重新点翻译。","processing_timeout")
                "LANGUAGE_MISMATCH" -> LiveTranslationError("译文的源语言与所选语言不一致。请重新点翻译，核对英语或法语选项。","language_mismatch")
                "PAGE_TOO_LARGE" -> LiveTranslationError("本页文字超过开发版处理上限，请换文字较少的页面后再点翻译。","page_too_large")
                "INVALID_RESPONSE","RESPONSE_TOO_LARGE" -> LiveTranslationError("未收到可用的完整译文，请稍后再点翻译。不会自动重试。","response_invalid")
                "BUSY" -> LiveTranslationError("翻译连接正在处理上一项请求，请稍后再点翻译。不会自动重试。","service_busy")
                "RECOGNITION_WIDTH_BUDGET" -> LiveTranslationError("本页含过长的文字行，当前版本尚不能完整识别。","processing_failed")
                else -> LiveTranslationError(GENERIC,"processing_failed")
            }
        }

        // Only contract codes cross into the UI. Never display raw server/exception text.
        fun relayCode(value: String): String = if(value in allowedRelayCodes) value else "SERVICE_UNAVAILABLE"

        private val allowedRelayCodes=setOf(
            "SENSITIVE_PAGE","FREE_QUOTA_EXCEEDED","PAGE_TOO_LARGE","PILOT_QUOTA_EXCEEDED","METADATA_LIMIT",
            "LANGUAGE_MISMATCH","CREDENTIAL_EXPIRED","UNAUTHORIZED","PROVIDER_FAILED_NO_RETRY",
            "PAGE_EXPIRED","INVALID_RESPONSE","RESPONSE_TOO_LARGE","BUSY","CANCELLED","DUPLICATE_NO_RETRY",
            "CONSENT_REQUIRED","SERVICE_UNAVAILABLE","AUTHORITY_UNAVAILABLE","SERVICE_DISABLED"
        )
        private const val GENERIC="本次处理未完成，请重新点翻译。不会自动重试或再次发送。"
    }
}
