# 来源与归属

2026-09-23 核验。KanDong 独立实现，没有 fork、复制或改名其他完整项目，没有复用 SpotAva/Parknow 文件。

## Android / Kotlin / Gradle

- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)：服务绑定、窗口读取、系统事件与覆盖层能力。
- [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)：可见性、文本/描述、可点击属性、敏感标记及 getBoundsInScreen。
- [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)：TYPE_ACCESSIBILITY_OVERLAY、FLAG_NOT_TOUCHABLE 和 FLAG_NOT_FOCUSABLE。
- [Compose 编译器配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)：Kotlin Compose compiler 插件。
- [AGP8.13 兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)：Gradle/JDK/SDK版本范围。
- [UiAutomation](https://developer.android.com/reference/android/app/UiAutomation)：设备测试使用 FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES，避免测试框架压制被测服务。

Gradle Wrapper由官方 Gradle8.13 wrapper task生成；Gradle、Kotlin及AndroidX遵循其各自上游许可证。运行时依赖没有从其他本地项目复制。

## ScreenSaathi

上游：[NITISH-R-G/ScreenSaathi](https://github.com/NITISH-R-G/ScreenSaathi)，仓库标示 MIT。
定位：仅供未来 Phase2“下一步怎么办”技术参考，不决定放大镜核心架构。概念参考：只读 Accessibility Tree → 真实节点位置 → 可视提示 → 用户亲自点击。本次只阅读其公开项目说明并记录思路，没有引入其源码、模型服务、音频流程或资源，因此没有复制代码所需的版权头。
若将来实际复用具体 MIT 文件，必须先核实该文件对应提交/许可证，并在分发中保留相应版权与许可声明。上游状态不能当作 KanDong 的实测证据。

放大镜能力的官方API比较见 [MAGNIFICATION](MAGNIFICATION.md)。新增原生/兼容放大实现独立编写，未复制ScreenSaathi或AOSP源码。
