# Android 译文展示与清理实验

2026-09-24。本轮把已保存的合成结果接入 `ocrlab` 独立 Android 页面，完成译文候选、本地日期、保留原文三类结果的展示与失效验证。没有把翻译接入正式放大镜，也没有进行新 DeepL 调用或真实屏幕读取。

## 已实现的行为

- 明确点击“翻译”才建立本次整页快照与请求。整页上下文保留全部块，选区只决定展示哪些结果。
- 结果携带明确种类和来源；不一致的组合整批拒绝。例如，保留原文的条目必须没有译文，不能夹带之前被拒绝的内容；本地日期不能冒充云译文。
- 译文卡片和源块 ID、源位置、语义组保持本地绑定。移动选区复用同一次结果，不重新请求；旧几何/缩放测试继续通过。实验界面用“下一处”切换固定区域，不宣称已实现跨 App 译文覆盖。
- 无法核对时展示原文并提示“这段翻译暂时无法核对，请先看原文。”；中文原文保留，不显示错误警告。
- 收起、菜单、页面/窗口变化、停止、供应商变化和过期都清除活跃快照、请求与结果缓存；迟到回调无效。恢复或关闭菜单不自动处理，必须再次点击。
- 菜单返回保持进入前的展开/收起状态；页面切换和后台返回不会带回上一页译文。

`ContextContracts`/`ContextEngine` 只核对结构与来源标签，不重新判断翻译语义。`LOCAL_DATE` 在本实验中来自上轮本地规则的冻结结果，手机没有新运行日期解析器或翻译引擎。不能把本轮 Android 结构通过说成新增翻译质量通过。

## 随包数据

从已审计记录离线导出 5 张单语页面：p303 英语、p106/p304 法语、p007 简体中文、p008 繁体中文。共 25 个上下文块、17 个可选区域。

25 条展示记录中，6 条为此前 DeepL 候选、3 条为此前本地日期、16 条保留原文（10 条中文、4 个未评估标题、2 个尚未核实的退改条款）。中文与未评估标题不是失败译文。未知字段、真实文字或外部 Intent 不进入该实验。

`protected-translation-display.json` 为 15640 字节，SHA-256：
`aa3c331e08272c9b1134a53736c2715c9dfdff2064030b02238ebe726f997c1f`。

导出脚本验证上轮审计文件指纹及源对象一致性；第二次导出逐字节相同。Android 加载有字节上限并核对固定指纹，APK 内资源与源文件一致。收起时清除的是活跃处理状态；随包合成原文是固定测试资源，不把它的存在当作真实用户文字生命周期验证。

## 验证结果

- 核心 6 项行为测试先失败，实现后通过；再补 1 项展示反例。`modelprobe` 116 项、`ocrlab` 49 项，**共 165 项 JVM 测试通过**，没有跳过。
- 独立主 APK / instrumentation APK 构建通过。Lint 最初发现 `readNBytes` 需要 API33，已换成兼容最低 API29 的限长读取；最终 **0 错误、26 警告**，包括实验页中文拼接的国际化提醒及现有实验警告，未冒称零警告。
- 只在 `KanDong_OCR_API37_16K` / `emulator-5582` / API37 / 16KiB 环境安装；安装后主包、测试包 SHA 与本地包一致。未检测到真机，本轮没有手机安装/验收。
- **6 项模拟器 instrumentation 测试全部通过，71.034 秒**：五页全部上下文/结果绑定；真实界面金额/日期/原文提示；菜单与收起恢复；待返回时清理；进入本实验启动页再返回；等待真实 60 秒到期自动撤下结果。
- 三张实际模拟器截图经检查：金额、日期、长原文及警告无重叠或截断，按钮可见。仅为当前专用模拟器尺寸，不代表各设备最终 UX 验收。
- APK 仅有 AndroidX 内部接收器权限，无 INTERNET / ACCESS_NETWORK_STATE，无开发密钥或 `.env` 资源。`app`、`compat`、`graphics`、正式手势和屏幕权限未变。

[构建、测试、截图与指纹证据](evidence/translation-display/2026-09-24/summary.json)。[金额截图](evidence/translation-display/2026-09-24/money.png)、[日期截图](evidence/translation-display/2026-09-24/date.png)、[保留原文截图](evidence/translation-display/2026-09-24/original-warning.png)。旧失败构建也保留，未覆盖。

## 如何运行

在 KanDong 根目录构建：

```sh
JAVA_HOME=/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home \
  ./gradlew --offline :modelprobe:testDebugUnitTest :ocrlab:testDebugUnitTest \
  :ocrlab:assembleDebug :ocrlab:assembleDebugAndroidTest :ocrlab:lintDebug
```

本轮已经安装到专用模拟器。打开“看懂文字实验”→“译文展示与清理实验”；点击翻译，使用“下一处”查看价格、日期和原文提示。收起或进入菜单后恢复，应回到原文；再点击翻译才重新显示。这里的收起/菜单都是当前 Activity 内的模拟，尚非正式系统悬浮窗。

复跑界面测试前用 `adb -s emulator-5582 emu avd name` 确认专用 AVD 名称，并确保安装的是本次主包/测试包（指纹见证据）。随后：

```sh
/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb -s emulator-5582 shell am instrument -w -r \
  -e class com.kandong.ocrlab.context.ProtectedDisplayInstrumentedTest \
  com.kandong.ocrlab.test/androidx.test.runner.AndroidJUnitRunner
```

测试会打开本实验界面并等候约一分钟的失效期限，不操作其他 App。系统 APK 入口不可从外部直接启动新增内部 Activity；使用实验首页按钮。

离线复核随包来源（输出文件必须不存在）：

```sh
PYTHONDONTWRITEBYTECODE=1 /opt/homebrew/bin/python3 scripts/prepare-translation-display-fixture.py \
  --out /private/tmp/kandong-display-fixture-check.json
```

## 下一项

进入可见页面采集前的本地隐私与来源契约：保留全文上下文/位置关系，同时标明密码、敏感窗口、自有悬浮层、不可见/截断内容和缺失证据；未知不能伪装成已过滤。先用合成节点验证，不启用真实用户页面上传，也不把 MediaProjection 的放大授权解释成 OCR/联网同意。

完整真实采集适配、OCR 质量、后端凭据/会话同意与正式镜面接入仍分别待验收；复杂退改条款继续保留原文。
