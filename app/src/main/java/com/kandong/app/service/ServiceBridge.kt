package com.kandong.app.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Same-process UI state only. No IPC, node labels, history, or saved state. */
internal object ServiceBridge {
    var service: KanDongAccessibilityService? = null
    var consent by mutableStateOf(false)
    var status by mutableStateOf(ServiceStatus())
}

internal data class ServiceStatus(
    val connected: Boolean = false,
    val running: Boolean = false,
    val capturing: Boolean = false,
    val message: String = "服务未连接，请在系统设置中手动开启。",
    val candidateCount: Int = 0,
    val truncated: Boolean = false,
)
