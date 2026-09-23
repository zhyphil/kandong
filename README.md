# KanDong · 看懂

面向老年人的 Android 屏幕理解辅助层：**Explain → Highlight → Human taps**。
不改变原 App 排版，不代替用户点击、输入或购买。当前是 Phase 0 技术原型，不是 AI 理解产品。

唯一根目录：`/Users/haoyuzuo/Projects/KanDong`。远端：`git@github.com:zhyphil/kandong.git`。
本项目完全独立，不读取、修改或复用 SpotAva/Parknow 的代码、配置或数据。

## 当前能力

- 用户主动点“读取页面”，获取其他 App 当前窗口的可见、有标签、可用节点；读取 text、contentDescription、class、clickable、enabled、bounds、窗口及节点身份。
- 跳过输入、密码、系统标记敏感的子树；按可点击性和页面位置列出候选，显示原标签的固定说明。
- 按实际 node bounds 绘制黑黄边框。高亮层不接收触摸，用户亲自点原 App。
- 页面变化、15秒到期、停止、锁屏或服务断开会撤销结果。没有截图、OCR、翻译或 AI。

## 构建与运行

需要 Android Studio、JDK17、SDK Platform36 / Build Tools35.0.0。最低 Android13/API33。
本机 SDK 已在不提交的 `local.properties` 配好；其他机器可由 Android Studio 创建该文件。

```sh
cd /Users/haoyuzuo/Projects/KanDong
./scripts/check.sh
```

脚本在 macOS 自动为本次构建选择 JDK17，不修改系统默认 Java；其他平台设置 `JAVA_HOME` 或 `KANDONG_JAVA_HOME`。
也可用 Android Studio 打开此根目录，Gradle JDK 选17，运行 `app`。

APK：`app/build/outputs/apk/debug/app-debug.apk`。安装到自己选择的测试设备：

```sh
~/Library/Android/sdk/platform-tools/adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

`DEVICE_SERIAL` 替换为 `adb devices` 中的目标；不要未经确认安装到其他人的设备。

## 手机端开启与使用

1. 打开“看懂”，阅读说明，点“打开系统无障碍设置”。
2. 在“已安装的应用/下载的应用/服务”中找到“看懂 · 只解释，不代操作”，阅读系统提示后手动开启。
3. 返回看懂，确认“服务已连接”，勾选“我已了解，并同意本次使用”，点“开始辅助”。重连后需重新同意。
4. 切换到一个普通、非敏感的 App 页面，点悬浮面板“读取页面”。用上一项/下一项选择目标，核对框线和原标签，再亲自点击原控件。
5. 面板挡住内容时点“移到下方/上方”再重读。点“停止”移除面板并清除快照；完全关闭权限请再到系统无障碍设置关闭服务。

若侧载 APK 被系统提示“受限设置”，部分 Android13+ 设备需在 设置 → 应用 → 看懂 → 右上角菜单 → 允许受限设置 后再开启。仅在确认 APK 来源后操作；厂商路径可能不同，参见 [Android 官方帮助](https://support.google.com/android/answer/12623953?hl=zh-Hans)。

## 验证与文档

先在独立合成测试页验证，不用支付/银行或真实隐私页面：

```sh
~/Library/Android/sdk/platform-tools/adb -s DEVICE_SERIAL install -r fixture/build/outputs/apk/debug/fixture-debug.apk
```

打开“看懂测试页”，读取后应框住“测试按钮”；计数保持0。亲自点该按钮才变为1，旧框随变化消失。再测候选切换、滚动、弹窗、键盘、移动面板、15秒过期和停止。自动设备测试使用专用模拟器，先在其系统设置中手动开启看懂服务，再运行：

```sh
./scripts/device-check.sh emulator-5580
```

替换为自己的专用模拟器序列号。脚本拒绝实体手机，检查实际 JUnit 结果而不是仅看 adb 退出码。测试重启目标进程后，仅在显式测试参数下重绑已开启的服务；不会将此逻辑加入产品。`connectedDebugAndroidTest` 的安装/清理和 instrumentation 重启会影响绑定状态，优先用该脚本。真实手机按前述步骤人工验收。

- [产品方向](docs/PRODUCT.md)、[架构](docs/ARCHITECTURE.md)、[安全边界](docs/SAFETY.md)
- [MVP与下一阶段](docs/MVP.md)、[实际验证记录](docs/VALIDATION.md)、[环境检查](docs/ENVIRONMENT.md)
- [来源与归属](docs/ATTRIBUTION.md)、[工作记录](WORKLOG.md)、[任务板](TASKS.md)

当前不支持系统页面、键盘显示、弹窗/分屏/PiP、系统放大、外接显示器和无法确认的遮挡。Canvas/WebView 可能没有足够的可访问标签。模拟器通过不代表各厂商真机通过；完整证据和未验项以 VALIDATION 为准。
