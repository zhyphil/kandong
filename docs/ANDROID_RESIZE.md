# Android图片缩放对照

2026-09-24。当前状态：构建/22单测通过（Lint 0错误/6提示），专用API36两轮各3项设备测试通过；**目标华为等待系统安装确认，本阶段尚未双设备验收**。只扩展独立modelprobe的androidTest和构建配置，不改变应用运行器、已有输入或正式放大镜。

## 检查范围

沿用仓库已有AndroidJUnitRunner1.6.2/ext-junit1.2.1，不另建测试框架。测试APK使用[固定预处理语料](RECOGNITION_PREP.md)的全部44文件，打包后逐字节核对；主APK与上一阶段的7f9ff255…相同。测试包SHA 299344075cb6348f6a717ca56ca80410c2b2131a2d7a48d8ae63696651ed018a，无权限/无Activity等应用组件，保留Android正常测试库声明；只通过调试设备测试入口启动，不接收图片参数、不读取屏幕或外部文件。

实际设备用BitmapFactory解码PNG，关闭密度缩放、指定软件ARGB8888/sRGB；所有12块源图和12块参考缩放图的解码BGR哈希必须与固定数据相同。再用Bitmap.createScaledBitmap(filter=true)将原裁剪图缩放到计划尺寸，经过已验证的RecognitionPacking，比较像素、float32和最终文字。每个识别模型都处理同样5组文字：先原固定张量，再Android缩放张量，共20次推理/轮。颜色校准组只比较像素，不计入识别质量。

参考[Android Bitmap](https://developer.android.com/reference/android/graphics/Bitmap#createScaledBitmap(android.graphics.Bitmap,int,int,boolean))、[解码密度设置](https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inScaled)及[官方设备测试方式](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)。API名称或文档不能代替设备像素比较。

## 模拟器结果

- 两轮各3项设备测试通过，固定PNG解码哈希全部正确；12块图的缩放像素、6组输入张量和10个模型结果重跑完全一致。
- 10/10最终原文与原张量参考逐字一致，7/10完整argmax序列一致；差异在Chinese的英语、法语、混排三个任务，解码文本未改变。**不是像素一致，也不代表新增语料或真实页面质量通过**。
- 5组文字图的RGB单通道最大差为8～9，彩色校准最大26（0～255）；保留全部差异统计，不强行把Android缩放视作OpenCV完全替代。详见[数值摘要](evidence/android-resize/2026-09-24/summary.json)。
- 每轮主对照36个Bitmap、44项模型资源创建/释放计数相等；另测试可变/不可变图片各4种极端尺寸，颜色保持、对象按身份释放。
- 初次测试错误假设“同尺寸必定返回同一对象”，在可变图上失败；改为检验像素与尺寸，并同时处理复制/复用，重新构建后两轮通过。失败日志和当时已完成的识别对照保留，不把整个初次测试列为通过。实际运行代码原已按对象身份处理，未发现正式产品回归。

## 真机与下一项

华为停在系统安装界面（InstallStaging），首次等待90秒后安装命令超时。已请用户亲自确认，未替用户处理系统安全提示；没有当前版本真机结果。安装完成后继续相同两轮及安装包哈希核对，再决定是否进入检测/裁剪技术验证。

模拟器命令使用安装后的 `com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner`，仅运行 `com.kandong.modelprobe.AndroidResizeProbeTest`。结果保存在目标应用私有 `files/resize-probe-report.json`，仅含合成数据；每次运行先删除本测试旧报告，不把旧结果当新结果。取消/后台的产品会话规则仍由原模型探针验证，本测试没有新增会话UI。真机验收后还需新增语料、真实检测框、透视裁剪及坐标映射，三语质量与翻译继续保留待验收。
