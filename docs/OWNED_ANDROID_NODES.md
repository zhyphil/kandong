# Android 自有页面的节点适配验证

2026-09-24。承接[元数据隐私契约](LOCAL_CAPTURE_PRIVACY.md)，本轮在专用模拟器上用 Android 实际创建的节点验证属性、坐标、延迟标签获取和页面失效。**范围是看懂内部固定的 View 测试页，不是第三方 App 的无障碍树采集，也不是翻译质量验收。**

## 实现与入口

- `OwnedNodeLabActivity`：英语、法语、简中、繁中四页分开；点击“读取本页”才启动本次检查。支持换页、改价、同窗口遮挡面板、滚动和清除。正文、按钮、图片描述、密码输入框、普通输入框、敏感节点、带汇总描述的容器及省略文字都来自随包固定数据。
- `OwnedNodeSnapshot`：只接受上述 Activity 注册的 12 个 View，调用 `View.createAccessibilityNodeInfo()`，保留实际 class、clickable、密码/可编辑/可见/敏感属性和屏幕坐标。只对通过过滤的节点调用本层标签 getter，之后释放节点引用；API 33 之后 `recycle()` 本身不再承担旧式对象池回收，清空引用仍必要。
- `CaptureOrigin.OWNED_ANDROID_FIXTURE`：实际节点测试与上一轮手写元数据区分，成功和拒绝路径都保留来源。结果仍是 `LocalCaptureInspection`，覆盖始终 `UNVERIFIED`，没有转换成模型/网络请求。

在“看懂文字实验”首页进入 **本地节点读取实验**。这不是正式“看懂”放大镜入口，不需要开启无障碍或屏幕共享。实验 Activity 不对外导出，不读取 Intent/extras/外部文字。下方结果区可单独滚动，只显示本次取得的原文，不生成译文。

## 哪些是实际观察，哪些是测试限定

| 证据 | 来源及限制 |
| --- | --- |
| class、clickable、password、editable、visible | Android 当前创建的 `AccessibilityNodeInfo` 属性，不能据此推断通用隐私安全 |
| sensitive | API 34+ 实际属性；API 31 分支返回 `UNAVAILABLE`，不调用不存在的方法、不改为 false。本轮只在 API 37 运行，31 分支是有界兼容逻辑测试，未冒充华为实测 |
| 节点屏幕 bounds | 保留 Android 原值，并与该 View 的屏幕位置和宽高对照；前四个可见节点在四页中一致 |
| 可见裁剪范围 | 自有 View 的 `getGlobalVisibleRect()`，明确从 root 坐标转到屏幕、再归一到 decor；不是第三方节点的通用坐标换算 |
| 遮挡 | 仅检查测试页内已知遮挡面板。实际证实节点仍可报告可见，但本层将重叠内容排除；没有测跨 App 悬浮窗或系统通知遮挡 |
| 截断 | 自有 TextView 的 Layout 省略信息；屏外裁剪另按几何处理。不能推广为其他 App/WebView 的完整文字可见性判定 |
| 保护窗口 | 可读取本 Activity 自己的 `FLAG_SECURE` 并拒绝标签获取；不是探测其他 App 安全标志的能力 |
| 父子、顺序、窗口列表、标签范围、普通页非敏感分类 | 固定 View 注册表和受控页面的声明。单窗口“完整”仅在该固定测试模型内成立，不是实际系统窗口枚举；节点枚举对整体屏幕仍记为 NO，中文实验控件不进入单语正文 |

本次直接创建的节点 `frameworkWindowId` 实测均为 **-1**。代码保留这个原值；过滤契约中的窗口键 `1` 只是本测试内部的标识，不能拿它冒充真实无障碍窗口 ID。因此本轮不能验收跨 App 窗口身份或 IPC 树遍历。

官方说明：节点屏幕 bounds 在系统放大下可能带缩放/偏移；敏感数据属性从 API 34 才有。[AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)。View 可见矩形采用 root 坐标，不能直接当屏幕矩形。[View.getGlobalVisibleRect](https://developer.android.com/reference/android/view/View#getGlobalVisibleRect(android.graphics.Rect))。本次没有修改或启用系统放大设置，也未验证多窗口、旋转后的跨窗口投影。

## 读取边界修正

**延迟调用标签 getter，不等于 Android 节点对象尚未包含文字。** 本机 SDK 官方 `TextView` 源码在初始化节点时调用 `info.setText(getTextForAccessibility())`；这发生在本层调用 `info.text` 之前。测试统计的 `labelReads` 只代表本层 getter 次数，不能当作系统未取得密码/屏外文字的证明。

本轮仅涉及随包合成内容。未来实际服务需要按平台真实行为披露和限制数据边界，在交给模型/日志/存储/网络前过滤；不能承诺通过“不调用 getter”就避免节点创建或 IPC 携带内容。当前也没有实际第三方窗口保护/语义隐私分类证据，不能因为普通节点属性为 false 就把它当可上传信息。

## 实际结果

- 来源回归先验证失败：2 项测试证明旧实现把自有 Android 来源误标为手写元数据；补齐传递后通过。
- 最终 JVM **192 项**（modelprobe 116，ocrlab 76）通过，无失败/错误/跳过。主/测试 APK 构建通过；Lint **0 错误、30 警告**，比上一轮新增 4 条 `UseKtx` 建议，未为消除建议引入新依赖。
- 首轮 Android 40 项中 39 通过，固定位置用例在 `fullScroll()` 的平滑动画期间点击读取，随后的滚动事件取消了任务，导致等待结果超时。保留失败记录；固定位置用例改为即时滚动，并新增“动画期间取消且停稳后不自动重读”用例，定向 2 项通过。
- 最终专用 `KanDong_OCR_API37_16K`（API37、16KiB）**42 项**通过，Android runner 34.671 秒：14 项自有实际节点测试、25 项旧隐私契约、2 项来源、1 项旧五页展示绑定。41 项通过后，审查补上“文字不变但节点重新布局时清除旧绑定”的直接回归，最终完整跑 42 项；不把首轮失败写成通过。
- 四页初始画面各生成/释放 12 个节点，标签 getter 仅访问 `1,2,3,4,5,10`；密码 6、输入框 7、敏感 8、汇总容器 9、省略 11 和屏外/部分可见的 12 未交给后续文字结果。文字标题/按钮/价格/条件和原坐标关联通过；没有调用翻译服务。
- 改价通过实际 TextWatcher 使旧结果失效；节点布局、滚动、换页、遮挡、清除、窗口失焦/后台、实际 15 秒过期均清理本次文字，不自动重读。队列内取消不创建新采集；同窗口安全标志使延迟 getter 全部不执行。
- 四张检查图仅由测试绘制本 Activity 的 decor，不是设备屏幕截图。人工查看了四页，单语原文与结果对应，源区的省略/滚动是预设测试条件；中文结果末行需滚动查看，不冒称完整文字同时可见或正式长辈 UI 验收。

证据：[本轮摘要](evidence/owned-android-nodes/2026-09-24/summary.json)，含失败/定向/最终记录、JVM XML、安装包与源码指纹、四页节点元数据和自有 View 检查图。最终安装包与本机 APK 指纹相同。只更新专用模拟器，未安装/操作华为；没有新权限、真实内容、凭据读取或网络调用，正式 `app/compat/graphics` 未改。

## 复现

使用项目配置的 JDK17 和已有 SDK/依赖：

```sh
./gradlew --offline :modelprobe:testDebugUnitTest :ocrlab:testDebugUnitTest \
  :ocrlab:assembleDebug :ocrlab:assembleDebugAndroidTest :ocrlab:lintDebug
```

核对 AVD 为 `KanDong_OCR_API37_16K` 后安装独立主包/测试包。新 Android 类：`com.kandong.ocrlab.context.capture.OwnedNodeCaptureInstrumentedTest`；来源类：`CaptureOriginTest`。测试只操作应用自己的 View；没有节点动作、手势注入、UiAutomation 第三方树读取或系统权限变更。

## 后续与验收缺口

本轮完成的是自有受控页面适配。第三方树遍历/虚拟节点、可靠页面版本、全局窗口列表、放大坐标、窗口/语义隐私分类、真实遮挡仍待验证；API31不能通过当前敏感证据门槛的问题保持开放，不填默认值绕过。

下一有界任务优先推进 **API31 的本地整屏采集兼容实验**：使用随包页面验证系统投屏对安全窗口的行为，以及隐藏/恢复看懂自有镜面后如何取得当前新帧；保持保护/来源/时效证据，区分树信息不可用与实际像素不可读，再评估按需本机 OCR。先在专用模拟器准备并验证，华为最终验收与系统共享确认另行进行。这个任务不等于授权真实文字上传，也不将旧版系统未知标记改为“已安全”。
