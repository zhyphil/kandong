package com.kandong.compat

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class LiveTranslationErrorTest {
    @Test fun disconnectedUsbExplainsHowToReconnect() {
        val result=LiveTranslationError.from(ConnectException("private endpoint"),sending=true)
        assertEquals("relay_unavailable",result.code)
        assertTrue(result.message.contains("连接手机与 Mac"))
    }

    @Test fun timeoutIsDistinctAndDoesNotClaimNothingWasSent() {
        val result=LiveTranslationError.from(SocketTimeoutException("private payload"),sending=true)
        assertEquals("relay_timeout",result.code)
        assertTrue(result.message.contains("超时"))
        assertTrue(result.message.contains("不会自动重试"))
        assertFalse(result.message.contains("未发送"))
    }

    @Test fun expiredOrStaleConnectionRequiresMacReconfiguration() {
        listOf("RELAY_EXPIRED","UNAUTHORIZED").forEach {
            val result=LiveTranslationError.from(IllegalStateException(LiveTranslationError.relayCode(it)),true)
            assertEquals("relay_expired",result.code)
            assertTrue(result.message.contains("重新配置连接"))
        }
    }

    @Test fun providerFailureIsNotReportedAsAUsbFault() {
        val result=LiveTranslationError.from(IllegalStateException(LiveTranslationError.relayCode("PROVIDER_FAILED_NO_RETRY")),true)
        assertEquals("provider_failed",result.code)
        assertTrue(result.message.contains("翻译服务"))
        assertFalse(result.message.contains("USB"))
        assertFalse(result.message.contains("未发送"))
    }

    @Test fun expiredSnapshotAndWrongLanguageHaveSpecificRecovery() {
        val expired=LiveTranslationError.from(IllegalStateException(LiveTranslationError.relayCode("PAGE_EXPIRED")),true)
        assertEquals("expired",expired.code)
        assertTrue(expired.message.contains("重新点翻译"))
        val language=LiveTranslationError.from(IllegalStateException(LiveTranslationError.relayCode("LANGUAGE_MISMATCH")),true)
        assertEquals("language_mismatch",language.code)
        assertTrue(language.message.contains("源语言"))
    }

    @Test fun connectionBudgetIsNotClaimedToBeTheDeeplFreeQuota() {
        val result=LiveTranslationError.from(IllegalStateException("SESSION_LIMIT"),true)
        assertEquals("session_limit",result.code)
        assertFalse(result.message.contains("免费"))
        assertTrue(result.message.contains("重新启动"))
    }

    @Test fun localIoAndUnknownDetailsDoNotLeakOrMasqueradeAsUsbFaults() {
        val local=LiveTranslationError.from(IOException("private OCR text"))
        assertEquals("processing_failed",local.code)
        assertFalse(local.message.contains("USB"))
        assertFalse(local.message.contains("private"))
        val code=LiveTranslationError.relayCode("private response text")
        assertEquals("RELAY_UNAVAILABLE",code)
        assertFalse(LiveTranslationError.from(IllegalStateException(code),true).message.contains("private"))
    }
}
