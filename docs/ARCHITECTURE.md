# 架构：智能放大镜优先

```text
KanDong 智能放大镜
  ├─ 原文放大 2× / 3× / 4× （Phase0A）
  │   ├─ app：系统 Window Magnification + MagnificationController
  │   └─ compat：显式 MediaProjection → 区域裁剪 → 独立放大窗
  └─ 中文翻译（Phase0B）
      源区域 → UI Tree 优先 → 不足时按需截图/OCR → 语言识别 → 中文
          ↓
      帮我看懂：简单中文解释（Phase1）
          ↓
      下一步怎么办 → 节点定位 → 高亮 → 用户亲自点击（Phase2）
```

Kotlin2.2.20、AGP8.13.0、Gradle8.13、JDK17、compile/target36、Build Tools35.0.0。主产品 `app` 优先 Jetpack Compose；跨 App 小型控制窗用 Android Views。`compat` 使用原生 Views 做有界技术实验，避免为验证引入额外框架。没有 AI provider 或翻译实现。

## 官方路径 app

`ui/MainScreen` 披露与授权入口 → `service/ServiceBridge` 同进程状态 → `KanDongAccessibilityService` 生命周期 → `magnification` 状态/系统适配 → Android 原生局部镜面；`MagnifierControls` 仅提供控制条。

服务只请求控制放大能力，不请求节点读取/截图/手势。不处理事件文字或 source。系统负责原内容的放大渲染，应用仅观察 mode、scale 和源区几何。源区不是镜框目标矩形，不用于猜测面板位置。倍率调整保留用户移动后的中心。

已有放大或未知状态不启动。请求返回值、配置回读/回调与源区共同确认；拒绝和超时必须反馈。系统手柄可改变源区；用户切到其他模式时不抢回。停止/断开/锁屏/旋转清理本次控制；API没有公开owner身份，不能绝对保证和另一个同时控制同模式的服务无竞态。

## 华为兼容路径 compat

独立 package `com.kandong.compat`，最低API29。`MainActivity` 展示实际屏幕共享披露 → 用户允许 `SYSTEM_ALERT_WINDOW` → 每次由 `MediaProjectionManager` 取得系统同意 → 不导出的前台 `ProjectionMagnifierService` → VirtualDisplay / ImageReader → 仅复制取景框像素 → ImageView 放大。

运行期间存在短暂整屏系统缓冲区；不建立整屏 Bitmap、不保存或发送帧。每帧最终关闭；局部 Bitmap 替换后释放。采样节流约每120ms一次只是试验上限，不是性能或耗电结论。停止、投屏回调、锁屏、配置变化释放投屏/缓冲区/窗口，进程不会自动恢复投屏。

源框和显示窗分开。显示窗/拖动柄设置 FLAG_SECURE，以避免被再次采集出递归画面；取景框一旦和显示窗重叠就清空图像并提示移动，不把黑屏副本当有效放大。边框绘制在裁剪区外，仅拖动柄和控制条接触触摸，源区仍可由用户操作。镜面换边时重新选择可见源区。

这是为目标 LIO-AN00/API31 做的替代 spike。系统是否排除安全悬浮窗、画面刷新和触摸行为都需实际设备确认。它的显示方式和原生镜面不同，不声称两者UX等价。当前仅默认内置屏，旋转停止，译文尚未实现。

## 已有代码审计与保留

| 文件/模块 | 处理 | 后续用途/限制 |
| --- | --- | --- |
| Gradle/Kotlin/Compose/Wrapper | 保留，不升级依赖 | 两条放大路径共享工具链 |
| app/domain 节点、几何、Session + 16单测 | 保留编译与测试，主流程不实例化 | 0B节点过滤/取消；2高亮会话 |
| app/capture | 保留但无产品调用、无节点权限 | WindowSelector拒绝放大；必须重新处理坐标后才可用 |
| old OverlayController/HighlightView | 保留但未启用 | Phase2真实节点高亮 |
| 旧服务/桥接/首页/config/instrumentation | 保存到 experiments/guidance，退出构建 | 历史参考，不能恢复为产品核心 |
| fixture | 新原文/网格/布局指纹；旧Activity保留 | 不重排证据来自测试App内部，而非被放大的a11y坐标 |
| scripts/device-check | 当前仅专用模拟器自动化 | 真机权限由人开启；不在真实手机静默写安全设置 |

## 0B 的边界契约（设计，未实现）

`RegionContext` 应绑定 display、rotation、package/window、源区域精确形状、时间和generation。`RegionText` 要注明来源 tree/OCR、语言、缺失/截断、原文片段。`Translation` 保留原文与译文、来源版本和失效条件。金额/日期/否定不能无证据补全。

区域变化、页面变化、停止使结果失效；用户点中文才触发采集，不连续全屏OCR。API34可用不受放大缩放的window bounds，API33必须另验证映射；截图fallback可能先整屏/整窗口入内存，应按实际过程披露。兼容版已有帧也不代表自动授权未来OCR/AI处理。

云模型若以后需要，另做数据最小化、单次发送同意、供应商/留存披露。保持无输入执行器；后续 Explain → Highlight → Human taps 是附加层。
