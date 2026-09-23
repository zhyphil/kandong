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
import com.kandong.app.BuildConfig
import com.kandong.app.service.ServiceBridge

@Composable
internal fun MainScreen(openSettings: () -> Unit) {
    val state = ServiceBridge.status
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF075E54), background = Color(0xFFF5F7F3))) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("看懂", fontSize = 36.sp, color = MaterialTheme.colorScheme.primary)
            Text("看清标签，再自己点击", fontSize = 25.sp)
            Text("不改变原来的页面。您选择要解释的内容，看懂用边框指出位置。最后由您亲自操作。", fontSize = 20.sp, lineHeight = 30.sp)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("使用前，请先了解", fontSize = 24.sp)
                    Text("• 只有您点“读取页面”时，才读取一次当前应用的可见标签。\n• 页面文字只留在内存中，最多15秒；变化或停止就清除。\n• 不自动点击、不输入、不截图、不联网、不保存页面。\n• 输入框、密码和已标记的敏感区域会跳过；静态隐私文字未必能识别，请勿在隐私或支付页面使用。", fontSize = 19.sp, lineHeight = 29.sp)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                        value = ServiceBridge.consent, role = Role.Checkbox,
                        onValueChange = {
                            ServiceBridge.consent = it
                            if (!it) ServiceBridge.service?.stopSession("已撤回同意，辅助已停止。")
                        }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ServiceBridge.consent, onCheckedChange = null)
                        Text("我已了解，并同意本次使用", fontSize = 20.sp)
                    }
                }
            }
            OutlinedButton(openSettings, Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                Text("打开系统无障碍设置", fontSize = 21.sp)
            }
            Text("系统开关需要您手动开启。返回此页后，再点击“开始辅助”。", fontSize = 18.sp, lineHeight = 27.sp)
            Text(if (state.connected) "服务已连接" else "服务未连接", fontSize = 24.sp)
            Text(state.message, fontSize = 20.sp, lineHeight = 30.sp)
            Button(onClick = { ServiceBridge.service?.startSession() },
                enabled = state.connected && ServiceBridge.consent && !state.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                Text("开始辅助", fontSize = 23.sp)
            }
            OutlinedButton(onClick = { ServiceBridge.service?.stopSession() }, enabled = state.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                Text("停止辅助并清除", fontSize = 23.sp)
            }
            Text("开始后：切换到其他应用 → 点悬浮面板“读取页面” → 用上一项/下一项选择 → 核对边框后自己点击。面板挡住内容时，可移动到另一侧后重读。", fontSize = 19.sp, lineHeight = 29.sp)
            Text("Phase 0：目前按原标签进行固定说明，不使用 AI，也不判断点击后的结果。", fontSize = 17.sp, lineHeight = 25.sp)
            if (BuildConfig.DEBUG) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("开发验收信息", fontSize = 18.sp)
                        Text("本区仅用于 Phase 0 验证，不是未来长辈界面。", fontSize = 16.sp)
                        Text("会话：${if (state.running) "运行" else "停止"}；读取：${if (state.capturing) "进行中" else "空闲"}\n候选数：${state.candidateCount}；标签截短：${state.truncated}", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
