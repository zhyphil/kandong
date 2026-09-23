# 官方放大能力研究与 Phase 0A 决策

核验日期：2026-09-23。依据 Android 官方 SDK 文档、本机 API36 `android.jar` 的公开签名及实际设备测试；推断和待测项明确标注。

## 先澄清 A 与 B

A **Window Magnification** 是系统局部放大体验；B **AccessibilityService.MagnificationController** 是查询和控制系统放大的公开接口，两者不是可互换的独立渲染方案。Phase 0A 选择 **A + B**，不是自己绘制屏幕副本。

API33 引入 `MagnificationConfig` 的 WINDOW 模式以及 scale / center 控制。需设备声明 `FEATURE_WINDOW_MAGNIFICATION`、服务声明 `canControlMagnification` 并由用户开启服务。`setMagnificationConfig` 的返回值与回调/非空源区域一起确认状态；显示质量仍需肉眼和设备测试。旧 `setScale/setCenter/reset` 针对全屏模式，不能作为窗口模式的替代。[配置](https://developer.android.com/reference/android/accessibilityservice/MagnificationConfig)、[控制器](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.MagnificationController)

`getCurrentMagnificationRegion` / 新回调在 WINDOW 模式给出**被投影到放大窗口的屏幕源区域**，不是浮动窗口外框的目标矩形。不能把它直接当作翻译面板的摆放位置。`isActivated/setActivated` 需要 API34；API33需按模式区分：WINDOW模式非空region才表示激活；FULLSCREEN模式在服务有控制能力时region也可非空，1倍不能仅因region非空就判成已放大。不把记住的窗口倍率当正在显示。[回调](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.MagnificationController.OnMagnificationChangedListener)、[Builder](https://developer.android.com/reference/android/accessibilityservice/MagnificationConfig.Builder)

公开 Builder 没有设置镜框宽高、样式、独立目标矩形或向系统镜面插入内容的接口；本机 SDK36 签名已核对。Android14+ 官方帮助提供系统面板调整放大镜大小和倍率的操作，具体手柄/面板仍由 OEM 实现。窗口中心请求与手指拖动是否符合“现实放大镜”体验需真机验证。[系统操作说明](https://support.google.com/accessibility/android/answer/6006949?hl=en-GB)

## 方案比较

表中性能为工程推断，**没有实测耗电或延迟数字**。平台 API 存在不代表所有 OEM 行为一致。

| 维度 | A 系统 Window Magnification + B Controller | C Accessibility overlay + screenshot 自绘 | D1 MediaProjection + 自绘 overlay | D2 View Magnifier / PixelCopy |
| --- | --- | --- | --- | --- |
| 跨 App | 系统级能力；需 feature/版本支持，逐 App 实测 | 可采集允许的屏幕/窗口，再覆盖显示 | 整屏分享可跨 App；单 App 分享只限选中 App | 需要可访问的本 App View/Window/Surface，不是任意第三方屏幕 API |
| 底层 layout | 系统显示放大；不改字体/布局；实测排除 reflow | 不改底层布局，但显示的是采样副本 | 不改布局，显示媒体帧副本 | 本 App 内可无 reflow；不满足跨 App 核心 |
| 倍率 | WINDOW config scale，可请求2/3/4倍 | 自己绘制控制 | 自己绘制控制 | widget可设 zoom |
| 移动区域 | config中心 + 系统手势；实际限制待测 | 自己管理源/目标坐标和手势 | 同左，还需流尺寸/方向变更同步 | 局限自身内容 |
| 尺寸 | 系统UI调整，公共接口不可直接设镜框尺寸 | 可自定义尺寸 | 可自定义尺寸 | 可设尺寸，但只用于自身view |
| 翻译整合 | 可取得源 region，译文需另一个 overlay，不能注入系统镜面 | 裁剪/OCR与译文布局可统一；要防递归采集自己的overlay | 同左，流与窗口更复杂 | 不能覆盖跨 App 需求 |
| Android版本 | Controller24；本产品 WINDOW 控制最低33；activation34需分支 | screenshot30；window screenshot34；本工程最低33 | 21起；34+单App选择/授权约束 | Magnifier28，PixelCopy24起；窗口重载26 |
| OEM兼容 | feature检测不够；拖动、系统面板、保护窗口都要验证 | 截图权限/安全窗口/overlay差异；不保证更通用 | 投屏系统UI、前台服务、厂商后台限制 | 标准本App能力但范围不符 |
| 延迟 | 系统渲染链，预期较适合实时；待测 | screenshot有间隔限制/失败，非高帧率契约 | 可连续流；额外采集/复制/绘制链路 | 本App内较直接 |
| 电池 | 不做App持续截图，预期较低开销；未量化 | 持续采样/复制/OCR可能更耗电 | 持续投屏/GPU/缓冲区开销待测 | 不解决跨App需求 |
| 隐私 | KanDong仅控制参数、观察几何，无屏幕像素/文字 | 截图实际可能先整屏/整窗口进内存；须裁剪/释放、按需启用 | 必须逐次系统授权；流数据进入App，不默认使用 | 自身内容范围较小 |
| Play政策 | 需无障碍用途声明、准确披露及审核，不能保证上架 | 同左，额外截图用途及敏感数据处理披露 | 投屏/前台服务规则与用户数据政策；若用a11y overlay仍有a11y声明 | 无a11y不适用该项，但不满足任务 |
| 实现复杂度 | 中；系统窗口不可完全自定义，状态/生命周期是重点 | 高；采样速率、遮挡/递归、坐标、内存、受保护内容 | 高；授权、前台服务、虚拟显示器和帧生命周期 | 低到中，但选错适用范围 |

C 的截图能力是按 display 或 window 请求；不能因为业务只用一小块，就宣称系统只采集了这块。`takeScreenshotOfWindow` 可避免无障碍 overlay 覆盖目标内容，仍须验证 magnification 下的坐标，安全窗口拒绝不得绕过。[AccessibilityService 截图 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

D1 每个 MediaProjection 会话需用户授权，API34+ token 单次使用并有前台服务要求；单 App 分享不能自动成为所有 App 的放大镜。[MediaProjection](https://developer.android.com/media/grow/media-projection)

D2 官方 widget 从其关联 View/Surface 或本 App 主窗口取内容，透明 overlay 的 View 不等于拥有底层 App 的 Surface。[Magnifier](https://developer.android.com/reference/android/widget/Magnifier)、[PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)

全屏系统放大虽不会改变 App 内部 layout，但不是用户要求的局部镜面；不会把它作为无提示降级。字号/显示尺寸调整会影响排版，也不采用。

## 后续区域翻译的关键阻碍

`getBoundsInScreen` 在放大开启时可被缩放和偏移；API34 `getBoundsInWindow` 不受放大缩放。需独立验证窗口原点和源 region 的坐标转换，尤其 API33；旧节点高亮代码不能直接对 region 做交集。[AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getBoundsInWindow(android.graphics.Rect))

系统镜面不能插入我们的译文。0B 初版采用独立大字译文面板，原文镜面可继续核对；将来的“原文/中文”视觉切换需要专门设计。源区变化立即撤销旧译文，按需重新读取。先树后OCR，本地优先，模型未选型。

## 决策与停止条件

优先 A+B：第一阶段不用屏幕采集权限，就能检验最重要的看清体验。仅使用默认显示器；已有用户放大时不接管；不写系统字体/密度/安全设置；失败明确反馈。系统未提供公开owner ID，同窗口模式的其他服务接管无法可靠区分，不能承诺完全无冲突；共存需实测。

若真实手机不支持窗口模式、镜面移动/遮挡不可用或不能可靠取得源区，按机型记录 No-Go，再做 C/D1 的独立对照 spike，先复核权限与性能成本，不绕过平台保护。

Google Play 的 `isAccessibilityTool` 仅适用于帮助残障用户的核心用途。本产品以视觉辅助为核心，但设置标记不等于获得商店认可；公开发布前需真实用途、视频、声明及政策审查。本轮没有提交审核。[Google Play 官方政策](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)

## 真机发现与用户指定的兼容 spike

实际读取：HMA-L29 Android10/API29；随后用户换成目标 LIO-AN00 Android12/API31。两者 `pm has-feature android.software.window_magnification` 未声明能力，且均低于公开WINDOW控制API33。官方路线在这两台机上的可运行性为 No-Go；这不是已安装后的视觉测试结论。

用户明确要求支持华为后，新增独立 `compat`：MediaProjection每次由人授权，TYPE_APPLICATION_OVERLAY显示区域副本；取景框与镜面分开、FLAG_SECURE防止把镜面再次采入、重叠时清空提示。运行时是显式授权的持续屏幕帧，约8fps采样上限；不是默认后台截图，也不能宣称系统只采局部。它是对D1的兼容性实验，是否足够流畅/易用以真机结果为准，最终产品体验尚未定型。
