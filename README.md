# KanDong · 看懂

**跨 App 局部屏幕放大镜 + 放大区域翻译。** 面向老年人及视觉、语言或数字使用困难人群。产品顺序固定为：**看清 → 看懂 → 理解 → 会操作**。

当前先做 **Phase 0A 放大镜技术验证**。不改变原 App 布局、字号或按钮位置；2× / 3× / 4× 放大原内容。翻译是下一阶段，AI 解释和下一步高亮随后推进；没有自动点击、输入、确认、提交、购买或支付。

## 两条明确区分的验证路线

| 模块 | 用途 | 系统要求/权限 | 当前目标 |
| --- | --- | --- | --- |
| `app` | 官方 Window Magnification + MagnificationController | Android13/API33+，设备支持窗口放大；用户开启无障碍服务 | 专用 API36 模拟器；真机兼容性逐机验证 |
| `compat` | 用户明确要求的旧华为兼容 spike；屏幕共享 → 局部裁剪 → 放大显示 | Android10/API29+；悬浮窗 + 每次屏幕共享授权；无需无障碍 | 当前目标华为 LIO-AN00，Android12/API31 |
| `fixture` | 独立合成页，网格/小字/点击计数/自身布局指纹 | 本地测试应用，无真实个人资料 | 验证倍率、原布局与手动点击 |

已连接的两台华为分别为 HMA-L29（Android10/API29）和 LIO-AN00（Android12/API31）；都不支持当前官方窗口控制路线。**目标机已切换为 LIO-AN00**。不能把 EMUI 版本当 Android API 版本，不能只降低 minSdk 就声称官方路线可运行。

兼容版的取景框与放大显示窗分开，避免屏幕共享把自身窗口再次采入。它是一个明确标识的技术实验，尚不是最终“原地镜面”体验。当前实际构建和设备证据以 [VALIDATION](docs/VALIDATION.md) 为准。

唯一根目录 `/Users/haoyuzuo/Projects/KanDong`，origin `git@github.com:zhyphil/kandong.git`。独立项目，本轮只做本地提交，不推送/发布。

## 构建

需要 JDK17、Android SDK Platform36 / Build Tools35.0.0。Android Studio 打开本目录，Gradle JDK选17。`local.properties` 配置 SDK 路径（不提交）。

```sh
cd /Users/haoyuzuo/Projects/KanDong
./scripts/check.sh
```

脚本只为本次构建选择 JDK17，不改变系统默认 Java。输出：

- 官方版：`app/build/outputs/apk/debug/app-debug.apk`
- 华为兼容验证版：`compat/build/outputs/apk/debug/compat-debug.apk`
- 合成测试页：`fixture/build/outputs/apk/debug/fixture-debug.apk`

使用 Android Studio 选择相应 module 和目标设备运行，或明确指定设备序列号安装。不要在 Android12 手机上安装最低13的 `app`。

## 目标华为手机的使用方法

1. 安装并打开“看懂兼容验证”，阅读屏幕共享说明，勾选本次同意。
2. 点“允许悬浮窗”，在手机系统页面手动允许，返回看懂。
3. 点“打开放大镜”，在系统屏幕共享提示中亲自确认开始。
4. 切换到“看懂测试页”、Chrome 普通网页或其他非敏感 App。
5. 拖动“移动取景框”，查看红框区域的放大原文；用2×/3×/4×调整。若取景框与显示窗重叠，移开或点“换边”。
6. 点“停止”结束屏幕共享和所有悬浮窗口。**锁屏后再解锁，放大窗消失是正常行为**；需要时回到看懂再点“打开放大镜”，重新确认本次屏幕共享。旋转后同样停止。

运行时，Android 把屏幕帧交给本机进程，应用只复制取景区域显示；**不是只采集一个区域的系统 API**。不记录、不落盘、不上传。受保护页面可能黑屏，不绕过保护；测试先用合成页。

华为若提示“纯净模式增强防护下，仅支持安装经过华为应用市场安全检测的应用”：用户需自行在 设置 → 系统和更新 → 纯净模式 暂时关闭增强防护，再继续安装并按系统提示验证。这个全局开关会放宽外部安装限制，测试后恢复；KanDong不会修改它。[华为官方安装说明](https://consumer.huawei.com/cn/support/content/zh-cn01089223/)

## Android13+ 官方版使用方法

打开“看懂” → 系统无障碍设置 → 看懂放大镜 → 手动开启 → 返回并同意本次使用 → 打开放大镜。系统镜框的手柄用于移动，控制条选择2/3/4倍、收起和停止。已有系统放大时先自行关闭，再开始看懂；不会自动接管。

官方版本不读取页面文字、不采集截图、不联网。窗口尺寸使用系统提供的设置，KanDong 没有通用镜框尺寸 setter。侧载受限设置参见 [Android 官方帮助](https://support.google.com/android/answer/12623953?hl=zh-Hans)。

## 路线与验证

Phase0A 放大 → Phase0B 区域法语/英语译中文 → Phase1 帮我看懂 → Phase2 下一步怎么办 → Phase3 长辈体验/TTS/性能。基础安全贯穿全程。

- [产品定义](docs/PRODUCT.md)、[架构](docs/ARCHITECTURE.md)、[MVP路线](docs/MVP.md)
- [官方方案比较](docs/MAGNIFICATION.md)、[安全与数据](docs/SAFETY.md)、[实际验证记录](docs/VALIDATION.md)
- [环境](docs/ENVIRONMENT.md)、[来源](docs/ATTRIBUTION.md)、[任务板](TASKS.md)、[工作记录](WORKLOG.md)
- [历史引导实验](experiments/guidance/README.md)：旧代码保留，已退出当前产品入口；旧高亮图不是放大镜证据。
