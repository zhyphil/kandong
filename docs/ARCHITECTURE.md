# 架构：智能放大镜优先

```text
KanDong 智能放大镜
  ├─ 原文放大：自由取景、实际倍率、自动避让（Phase0A）
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

运行期间存在短暂整屏系统缓冲区；不建立整屏 Bitmap、不保存或发送帧。每帧最终关闭；局部 Bitmap 替换后释放。已有画面时按120ms节流；几何变化或清空后的第一帧立即刷新，避免静止页面无后续帧而持续空白。这不是拖动期间的帧率上限，也不是性能或耗电结论。停止、投屏回调、锁屏、配置变化释放投屏/缓冲区/窗口，进程不会自动恢复投屏。

源框和显示窗分开。显示窗、悬浮按钮及菜单设置FLAG_SECURE，选区与受保护显示窗重叠时清空旧帧。红框本身是透明的拖动窗口，只绘制裁剪区外的边框；内部触摸移动选区，不再穿透原App。默认显示窗为源区相反的上/下方位置，自动切换不改变源框。

`SourcePlacement`中的`MagnifierLayout`使用屏幕像素进行矩形与避让计算。移动目标就是源框；右上角触摸区48dp，边缘处约束到屏内，视觉角标24dp/箭头16dp。角标窗口透明且只包含固定自有图形，不含采集内容，不使用FLAG_SECURE，避免与源区重叠的透明触摸区将文字遮成黑块。普通位置角标在源区右上方外侧；屏幕角落可能拍到自有角标，这是局部遮挡限制，不通过补造像素抹除。显示内容及第三方FLAG_SECURE保护保持不变。

`SourceGesture`以按下时屏幕坐标/指针ID/选区为基准。移动连续约束到屏幕四边，不再依赖独立柄和松手吸附。右上角缩放固定左下角锚点，宽高独立，下限48dp/32dp，上限受屏幕和显示窗可避让空间限制；多指或取消冻结本次选区手势，整串事件由自有窗口消费。

`MagnifierViewport`独立维护倍率（1～5，初始2）、镜面尺寸及偏移。选区变化重置平移并保留倍率；滑杆以镜面中心、双指以两指焦点保持同一源像素，边缘约束平移。显示使用无密度Bitmap与ImageView MATRIX；SeekBar的0～400对应1.00～5.00倍，由真实倍率反向更新progress，程序更新不触发用户缩放回调。

镜面使用官方ScaleGestureDetector接收完整DOWN至UP/CANCEL事件流，禁用双击拖动及笔按钮缩放。单指平移与双指缩放互斥；抬起一指后以剩余指针重设位置，防止跳动。收起、菜单、结束丢弃检测器及有效手势状态；新显示窗使用新实例。镜面事件不转发原App。小于镜面的内容按准确倍率居中留白，大于镜面时允许平移至边缘。[ScaleGestureDetector](https://developer.android.com/reference/android/view/ScaleGestureDetector)、[ImageView MATRIX](https://developer.android.com/reference/android/widget/ImageView.ScaleType#MATRIX)、[SeekBar](https://developer.android.com/reference/android/widget/SeekBar)。

自动放置仍使用中线±24dp缓冲，检查源框/角柄与显示窗碰撞；不安全时清空并说明。

## 兼容版收起、菜单与会话状态

`SessionUiState`在主线程管理展开、收起、菜单（记录返回状态）和终止。`SessionBridge`只发布不含授权token的同进程不可变快照，首页按可见生命周期订阅/移除。控制命令携带本次会话ID；新会话仍须新的系统共享同意，旧命令不能恢复或结束后来会话。

收起/菜单先关闭处理入口，取消手势与延时任务，将同一个VirtualDisplay的surface设为null，清除局部Bitmap和队列图像，移除镜面/源框/手柄窗口。只留下56dp可拖动按钮或安全菜单。恢复复用原VirtualDisplay与ImageReader.surface，保留选区、倍率、镜面偏移及显示侧，等待新帧，不重复创建虚拟显示。此时系统共享token仍有效，界面和通知明确区分“暂停处理”与“结束共享”。

`BubbleGesture`负责触点、点击/拖动阈值、四侧安全区和左右贴边；拖出阈值再回原位、多指、取消或长按均不触发点击恢复。菜单使用覆盖默认显示屏的FLAG_SECURE窗口，背景铺满，内容按safeArea留出系统栏/刘海距离。窗口接收菜单空白处的触摸，不穿透到底层App。首页使用非浮动主题的全屏Dialog及实际WindowInsets；两处共享固定顶部导航和滚动正文。主菜单提供“关闭放大镜”，帮助/隐私二级页不显示会话退出按钮，关闭菜单返回进入前状态；未提供的翻译、账号和订阅没有实际操作入口。`CompatUi`统一本地线条图标、颜色、圆角与至少48dp触摸目标。

工具栏48dp+倍率栏48dp共同计入`MagnifierLayout`可避让空间，图像区据此重算，不能增加控制条后继续沿用旧图像高度。结束、锁屏、撤销、配置/显示旋转通过同一终止路径移除全部窗口，先设置终止状态再释放监听器、投屏及前台服务；重入清理安全。

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

几何变化后清空旧显示图并丢弃ImageReader已排队帧，再用新矩形裁剪后续帧；不比较图片生产者时间戳与系统时钟。窗口事务与屏幕采集不是原子操作，快速移动仍可能出现短暂空白/帧延迟，需实际设备观察；不把这条异步显示链路直接当作未来翻译的同步快照。调试APK的标准Service dump只输出区域/窗口几何、倍率与帧计数，不含页面文字、像素或包名；发布版不输出这些诊断。
