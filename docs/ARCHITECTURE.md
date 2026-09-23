# 架构

## 技术基线

Kotlin2.2.20、Jetpack Compose（BOM2025.05.01）、AGP8.13.0、Gradle8.13、JDK17；minSdk33、compile/target36、Build Tools35.0.0。主界面用 Compose；小型无障碍覆盖窗口使用 Android Views/Canvas，便于明确窗口生命周期和触摸边界。

## 数据流

```text
用户主动读取
  → WindowSelector：默认显示器、唯一完整活动应用窗口、遮挡检查
  → AndroidNodeReader + BoundedNodeReader：后台单任务、有界且先过滤敏感子树
  → 不可变 NodeSnapshot / Snapshot
  → Session：generation、15秒有效期、候选身份
  → CandidateSelector：固定原标签说明
  → OverlayController / HighlightView
  → 用户亲自点击原应用
```

事件处理只撤销结果，不主动遍历节点。没有 AI provider、网络 API、截图、OCR 或输入执行器。

## 文件职责

| 目录 | 职责 |
| --- | --- |
| app/.../domain | 整数几何、节点政策、有界读取、不可变快照、候选及会话 |
| app/.../capture | Android节点适配、窗口选择及高亮前目标复核 |
| app/.../service | 系统事件、单线程后台采集、主线程状态/覆盖窗口、清理 |
| app/.../overlay | 小型可触摸面板和独立不可触摸高亮 |
| app/.../ui | 中文披露、权限入口、会话开关和开发状态 |
| fixture | 独立包名的本地合成 App，不是产品依赖 |
| app/src/test、androidTest | 纯逻辑回归与 Android跨应用验证 |

## 读取与时效

每次遍历最多300节点、24层、字段160字符、250ms协作式预算；节点数/深度/预算超限拒绝发布部分树，长标签截短并标记。单次 Binder 调用不能被这个预算硬中断，因此用后台单线程，取消 token 阻止迟到结果发布。快照保存普通字符串与整数，不保存 AccessibilityNodeInfo、Rect、事件对象或历史。

密码、editable、API34+敏感子树的标签读取前即跳过；其祖先可能聚合文本，也抑制祖先标签，正常兄弟节点保留。隐藏父节点仍检查可见子节点。只发布可见、enabled、有标签且有有效屏幕边界的节点；无标签控件不猜测。

候选按 clickable 优先、top、left、节点序号排序。同名标签不合并。窗口、旋转、bounds、路径、类型、viewId等用于复核。generation 拒绝过期异步结果；切换候选不续期。

## 覆盖层

TYPE_ACCESSIBILITY_OVERLAY 依附已连接的服务，无需 SYSTEM_ALERT_WINDOW。控制面板只占其自身尺寸，可触摸但不抢焦点；高亮层为透明背景边框，FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE。

`overlayLocalBounds = nodeScreenBounds - highlightView.getLocationOnScreen()`，单位为实际像素，裁剪到绘制区，不硬编码状态栏高度。候选与控制面板相交则排除；移动面板后必须重读。自身窗口移除事件保留有限身份记录，外部/未知事件保守失效。

## 故障与边界

服务重连默认停止并重新取得同意。屏幕关闭、停止、中断、解绑、销毁清理内存、回调与覆盖层。无法确认窗口、锁屏、键盘、其他遮挡、系统页面、分屏/PiP或放大时拒绝捕获；不退回读取底层旧窗口。
Android 无障碍树与事件不是跨 App 原子事务；事件缺失/延迟、动画和自绘内容仍有风险。真机验收不能由单元测试替代。

API依据见 [ATTRIBUTION](ATTRIBUTION.md)。
