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

## 自由取景与自动避让

取景框几何、手势和避让逻辑为KanDong自身实现；未复制其他项目代码。等比居中显示参考[ImageView.ScaleType.FIT_CENTER](https://developer.android.com/reference/android/widget/ImageView.ScaleType#FIT_CENTER)，原始屏幕指针坐标参考[MotionEvent](https://developer.android.com/reference/android/view/MotionEvent#getRawY(int))。实际倍率按显示像素与裁剪像素计算，须注意[Bitmap密度](https://developer.android.com/reference/android/graphics/Bitmap#setDensity(int))可能引入的绘制缩放。

独立倍率滑杆与镜面滚动基于官方[SeekBar](https://developer.android.com/reference/android/widget/SeekBar)和[ImageView.ScaleType.MATRIX](https://developer.android.com/reference/android/widget/ImageView.ScaleType#MATRIX)；比例与滚动约束代码独立实现。此前FIT_CENTER引用为历史设计，当前不以填满镜面决定倍率。

## 收起与日常界面

收起/恢复参考官方[VirtualDisplay.setSurface](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#setSurface(android.view.Surface))的null断开语义，恢复复用同一次虚拟显示；遵守[MediaProjection每次授权规则](https://developer.android.com/media/grow/media-projection#user-consent)。图标由本项目线条绘制，未复制外部图标库；点击区域及标签参考[Android无障碍Views建议](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views)。

API29安全区使用官方[WindowInsets stable insets与DisplayCutout](https://developer.android.com/reference/android/view/WindowInsets)合并系统栏和刘海边界，由本次授权Activity传入私有服务，未知时不按零处理。

菜单二级页返回使用[Dialog的OnBackInvokedDispatcher](https://developer.android.com/reference/android/app/Dialog#getOnBackInvokedDispatcher())处理API33+系统返回，旧版本保留返回键处理；关闭对话框注销回调，迟到回调不重建页面。

## 红框直接操作与双指缩放

双指识别使用官方[ScaleGestureDetector](https://developer.android.com/reference/android/view/ScaleGestureDetector)，传入完整事件序列，以focusX/focusY作为局部缩放焦点；指针切换遵循[MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)的pointerId/actionIndex。几何、焦点保持、滑杆同步和线条图标均独立实现，无新增第三方依赖。

## 本机画质对照实验

独立qualitylab使用Android官方[Paint位图过滤](https://developer.android.com/reference/android/graphics/Paint#FILTER_BITMAP_FLAG)作基准。精细插值独立实现自Mitchell与Netravali的[1988年原论文](https://www.cs.utexas.edu/~fussell/courses/cs384g-fall2013/lectures/mitchell/Mitchell.pdf)公式(8)，B=C=1/3；未复制论文图像或第三方源码。轻度锐化、合成输入、图标和实验界面在本项目编写，无新运行时依赖。范围、数据与限制见[QUALITY_LAB](QUALITY_LAB.md)。

## 启动后返回桌面

成功启动后由前台Activity发出Android官方[ACTION_MAIN / CATEGORY_HOME](https://developer.android.com/reference/android/content/Intent#CATEGORY_HOME)导航，使用[ResultReceiver](https://developer.android.com/reference/android/os/ResultReceiver)接收本服务就绪结果。没有模拟点击或无障碍全局动作；LaunchHandoff的一次性/过期结果控制为本项目实现。

## GPU C独立实验

使用Android官方[EGL14](https://developer.android.com/reference/android/opengl/EGL14)、[GLES30](https://developer.android.com/reference/android/opengl/GLES30)和Khronos[precise规则](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_gpu_shader5.txt)。着色器、CPU Float系数和测试均为项目内实现，数学公式沿用既有Mitchell归属；无第三方代码/模型/新运行时依赖，见[QUALITY_GPU](QUALITY_GPU.md)。


## 独立OCR实验依赖

ocrlab使用Google ML Kit随包Latin OCR 16.0.1及其传递依赖，模型和原生引擎通过Google官方Maven依赖构建，不把SDK/模型重新标成MIT或Apache开源项目。适用[ML Kit条款与隐私](https://developers.google.com/ml-kit/terms)；具体依赖清单和模型文件校验见[OCR实验记录](OCR_LAB.md)。实验输入是本项目自行编写的英法合成句，UI/运行器独立编写，API用法参考官方文档，未复制完整上游项目。正式选择、分发或新增模型前仍需逐项核对对应条款和归属。


## 独立本机模型探针

modelprobe使用Microsoft ONNX Runtime Android1.30.0（MIT及其ThirdPartyNotices）。PaddleOCR识别权重由RapidAI转换为ONNX，固定为ModelScope RapidAI/RapidOCR v3.9.2发布的两文件；模型卡声明Apache License 2.0。PaddleOCR、RapidOCR许可、模型卡、实际文件SHA与来源随包保存在modelprobe/src/main/assets/legal；未复制整个上游项目，CTC及Android生命周期代码独立实现。转换流程本身尚未独立审计，具体限制见[模型探针](MODEL_PROBE.md)。

模型二进制不提交Git，只由明确运行的准备脚本下载并验证；编译不自动联网下载模型。所有输入来自本项目自编图片的真实宿主机检测/预处理张量，字典保持模型的原始索引，没有分发字体软件或用户页面。

## 原图缩放与检测几何参考

独立modelprobe instrumentation包现使用[OpenCV官方Android构件](https://repo.maven.apache.org/maven2/org/opencv/opencv/5.0.0.1/)5.0.0.1候选（先前4.14.0结果按原版本保留），Apache-2.0许可和来源随测试包保存；未加入主应用。候选加载/数值差异、元数据不一致、体积及尚未完成的第三方notice审核见[原图探针](ORIGINAL_IMAGE_PROBE.md)。

[几何参考生成器](../scripts/export-detector-geometry-fixtures.py)调用固定RapidOCR3.9.2的DBPostProcess与get_rotate_crop_image，并依据其排序规则将框/索引/分数一起排序。该上游检测入口保留Copyright (c) 2020 PaddlePaddle Authors、Apache-2.0归属；许可文本已有modelprobe/src/main/assets/legal/RapidOCR-LICENSE.txt和PaddleOCR-LICENSE.txt。新输入和裁剪均来自本项目合成数据，没有复制用户屏幕或字体文件，也未复制整个上游项目。Pyclipper与Shapely仅用于既有宿主参考环境，尚未选择或引入Android polygon实现。

- Android几何基础探针：`GeometryProbeContract`及`GeometryOpenCvProbe`按前述RapidOCR3.9.2规则实现阈值、裁剪尺寸、透视与旋转；仅测试源集，关联原ID/分数。本项目的预算、认证与异常验证为新增代码。原Apache-2.0许可/版权记录仍随测试资产保留。`lightbringer/clipper-java`仅宿主候选初筛，未加入Android依赖，来源及Boost许可记录见`docs/POLYGON_OFFSET_INTAKE.md`。

- 几何测试的OpenCV5版本对齐原因、官方源码与候选构件证据见[Android几何探针](DETECTOR_GEOMETRY_PROBE.md)。只调用官方API，没有移植上游插值实现或修改固定参考。
