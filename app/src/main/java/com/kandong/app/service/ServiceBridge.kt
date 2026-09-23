package com.kandong.app.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Same-process, per-session state. No page content, persistence or exported test endpoints. */
internal object ServiceBridge {
    var service: KanDongAccessibilityService? = null
    private var consentState by mutableStateOf(false)
    var consent: Boolean
        get() = consentState
        set(value) {
            if (consentState == value) return
            consentState = value
            if (!value) service?.stopSession()
        }
    fun clearConsent() { consentState = false }
    var status by mutableStateOf(ServiceStatus())
}

internal data class ServiceStatus(
    val connected: Boolean = false,
    val supported: Boolean = false,
    val running: Boolean = false,
    val active: Boolean = false,
    val scale: Float = 2f,
    val message: String = "请在系统设置中手动开启看懂服务。",
)
