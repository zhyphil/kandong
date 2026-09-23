package com.kandong.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kandong.app.service.ServiceBridge

@Composable
internal fun MainScreen(openSettings: () -> Unit) {
    val state = ServiceBridge.status
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF075E54), background = Color(0xFFF5F7F3))) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("看懂", fontSize = 36.sp, color = MaterialTheme.colorScheme.primary)
            Text("看不清，打开放大镜", fontSize = 28.sp, lineHeight = 38.sp)
            Text("放大一小块，原来的页面布局不变。", fontSize = 22.sp, lineHeight = 32.sp)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("使用前，请先了解", fontSize = 24.sp)
                    Text("由手机系统放大屏幕内容。看懂不采集屏幕像素、不读取文字、不保存、不上传，也不替您点击或输入。", fontSize = 20.sp, lineHeight = 30.sp)
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).toggleable(
                        value = ServiceBridge.consent, role = Role.Checkbox,
                        onValueChange = { ServiceBridge.consent = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ServiceBridge.consent, onCheckedChange = null)
                        Text("我同意本次使用", fontSize = 22.sp)
                    }
                }
            }
            OutlinedButton(openSettings, Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("打开系统无障碍设置", fontSize = 22.sp)
            }
            Text("首次使用，请在系统中开启“看懂”，再回到这里。", fontSize = 20.sp, lineHeight = 30.sp)
            Text(state.message, fontSize = 22.sp, lineHeight = 32.sp)
            Button(onClick = { ServiceBridge.service?.startSession() },
                enabled = state.connected && state.supported && ServiceBridge.consent && !state.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("打开放大镜", fontSize = 24.sp)
            }
            OutlinedButton(onClick = { ServiceBridge.service?.stopSession() }, enabled = state.connected && state.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("关闭放大镜", fontSize = 24.sp)
            }
            Text("打开后可切换到其他应用。拖动系统镜框上的手柄查看别处；在小面板上选择2倍、3倍或4倍。面板挡住内容时，点“收起”或“移位”。", fontSize = 20.sp, lineHeight = 31.sp)
            Text("镜框大小由手机系统提供调整方式，不同手机可能不同。已有系统放大时，请先自行关闭。", fontSize = 19.sp, lineHeight = 29.sp)
            Text("下一阶段：把放大区域的文字翻译成中文。", fontSize = 20.sp, lineHeight = 30.sp)
        }
    }
}
