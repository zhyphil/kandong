# 原图缩放与检测数值对照

2026-09-24：目标华为 LIO-AN00/API31 两轮各7项通过。OpenCV 4.14.0 路线在10张冻结合成原图上，缩放像素和模型输入逐位相同，检测概率满足此前固定容差，阈值掩码零翻转。API36专用模拟器仍在加载该库时 SIGILL，不能标为双设备验收或正式接入完成。

本项承接[同像素检测探针](DETECTOR_PROBE.md)，首次在 Android 端解码原始 PNG 并缩放；仍不产生文字框、透视裁剪、识别原文或译文。正式放大镜和其他模块保持不变。

## 固定输入与实现

- 原有 detector-v1 的10组/62文件全部保持原样；manifest SHA-256 为 74aa1e39c8d3187ee2388ad07bb228e3c876c0486ccd02c47208942d26f2c80a。
- 先验证 PNG 文件哈希和尺寸上界，再以 BitmapFactory 解码为软件 ARGB_8888、sRGB、不随屏幕密度缩放。解码后的不透明 BGR 像素必须匹配原有 manifest 的哈希。
- 对照A：Bitmap.createScaledBitmap，filter=true。
- 对照B：OpenCV 4.14.0，CV_8UC3/BGR、INTER_LINEAR、单线程。所有图片走同一规则；不按文字答案、语言或某个失败样例选择路径。
- 两路均使用已核验的 DetectorPacking 和同一检测模型/ORT CPU配置，逐张处理；每组记录缩放像素差、输入位差、概率误差、阈值变化与资源计数。参考数据、颜色顺序和数值门槛未更改。
- 从旧设备测试提取共享的张量所有权、数值报告和原子写入辅助方法；旧4项检测测试在最终包重新运行且结果与已验收版本完全一致。

## 最终产物与目标手机结果

| 检查 | 结果 |
|---|---|
| JVM单元测试 | 28项通过 |
| 构建 / Lint | 主包、测试包通过；0错误、7提示（含已知新版依赖提示） |
| 主APK | SHA 7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea，字节不变，无OpenCV |
| 测试APK | SHA 539c0ad292ff260467be8db34550217cd65213e97079751f1dc76a52f12a7b33；186,877,783字节 |
| 权限与组件 | 无权限、无测试界面/服务/接收器/提供者；仅instrumentation |
| 包内资产 | 70个检测资产＋2个OpenCV许可说明＋44个既有缩放资产字节核验通过 |
| 原有文件 | 219个受保护文件哈希不变 |
| 签名/对齐 | 签名、ZIP 16KiB对齐通过；新增64位库ELF LOAD段均16KiB对齐，未声称32位库支持16KiB |
| 华为测试 | 两轮各7项通过；原图主对照3335 / 3295 ms |
| OpenCV原图链路 | 10/10缩放像素、2,644,992输入float逐位一致；881,664概率满足冻结门槛；最大绝对误差1.4663e-5，联合绝对/相对容差通过；0掩码翻转 |
| 原生Bitmap对照 | 共1,120,572颜色通道不同；五张文字图共68个阈值翻转，不选择此路径来复现参考检测 |
| 重跑 | 除耗时外所有逐组结果一致；旧4项同像素检测结果与历史华为报告一致 |
| 每轮资源 | 20 Bitmap回收、20 Mat像素缓冲释放、42 ORT所有资源关闭；异常释放/非法输入测试通过 |

Mat.release 释放像素存储，但 OpenCV 的 native header 仍由其 finalizer 删除。因此资源计数不等于长期内存稳定性验收。测试进程已经停止；耗时是诊断链路耗时，不能当正式实时性能或耗电结论。

## 保留的失败证据和选择理由

1. OpenCV 5.0.0.1：专用API36模拟器加载时在原生库 rdvl 指令 SIGILL，未进入缩放测试。这是依赖/运行环境问题，不计为TDD红灯。没有将此版本装到华为。
2. OpenCV 4.13.0：模拟器可以加载，颜色测试先因未实现缩放而失败，实现后颜色及异常清理通过。原图对照9/10逐位一致；wide-2001有159,893个颜色通道不同，最大差2，严格原图门槛失败。所有差异保留。
3. 关闭CPU优化、增加零填充通道绕过特定后端均未改善4.13的差异，已撤除，不留在最终实现。进一步在宿主OpenCV5做同图通用路径对照，其宽图像素哈希恰与Android4.13相同，说明差异来自后端选择；初始Carotene分支猜测没有得到验证，不作为根因结论。
4. 选择4.14.0继续验证：实际构件和宿主均记录KleidiCV 26.03，目标华为最终原图链路10/10通过。模拟器仍在加载时 SIGILL；其内核报告SVE2而未报告SVE，华为不报告这些扩展。虚拟CPU与库分派不一致是有证据的解释，尚未修复或获得上游确认，不能声称所有Android设备兼容。
5. 加载崩溃会早于测试删除旧报告，首次辅助脚本曾读到上次遗留报告。该文件明确排除于4.14证据；随后运行器每轮先删除两个自有诊断报告，并校验新的运行ID/版本。华为两轮报告均为新的独立结果。

失败记录、原始最终报告、安装哈希、源文件哈希和构建结果均在[evidence](evidence/original-image-probe/2026-09-24/summary.json)。不因后续成功删除失败历史，也不将4.13的模拟器运行拼接成4.14双设备验收。

## 依赖来源与局限

只有 modelprobe 的 androidTestImplementation 引入 org.opencv:opencv:4.14.0。Gradle在测试构建前核对实际AAR为123,380,341字节、SHA-256为6d11b40f6a54113dafe8540b1237b637193cb21e83deb11bc53d6757d35d494d，主应用不依赖该库。

[官方Android说明](https://docs.opencv.org/4.x/d5/df8/tutorial_dev_with_OCV_on_Android.html)、[实际Maven构件](https://repo.maven.apache.org/maven2/org/opencv/opencv/4.14.0/)、[4.14许可](https://github.com/opencv/opencv/blob/4.14.0/LICENSE)。三次候选的实际AAR均匹配仓库单独发布的SHA256，但各自.module记录的AAR大小/哈希不一致，已保存原始元数据；这不视为已完成供应链审计。

测试包附OpenCV Apache-2.0许可与来源说明。预编译包同时包含其他原生组件，完整第三方notice、体积裁剪和产品发行审核尚未完成。当前四种ABI的实验包约178.2 MiB，不能直接据此选择正式产品依赖。

## 复现

先依照DETECTOR_PROBE.md准备既有本地模型，首次解析固定OpenCV依赖需要联网；之后可离线构建。

    ./gradlew :modelprobe:testDebugUnitTest :modelprobe:assembleDebug :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug --offline

仅在授权的目标华为安装该主/测试包，已相同哈希的主包无需重复安装。测试只读取随包合成PNG，不需要屏幕共享：

    adb -s <目标序列号> shell run-as com.kandong.modelprobe rm -f files/original-image-probe-report.json files/detector-probe-report.json
    adb -s <目标序列号> shell am instrument -w -r -e class com.kandong.modelprobe.AndroidDetectorProbeTest,com.kandong.modelprobe.OriginalImageProbeTest com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner
    adb -s <目标序列号> shell run-as com.kandong.modelprobe cat files/original-image-probe-report.json
    adb -s <目标序列号> shell am force-stop com.kandong.modelprobe

每轮核对新runId、实际安装APK、openCvArtifact/runtime、10组数量、数值通过标志和资源计数。执行两轮比较时仅排除计时字段，不忽略像素/输出哈希差异。API36当前模拟器配置已知无法完成此版本加载；不要把旧报告当作此次运行结果。

## 后续门槛

- 修复或替换专用模拟器的兼容环境，补同一最终构件的第二设备验证；保留失败，不篡改CPU探测或悄悄换算法让测试过关。
- 独立冻结检测概率→膨胀/轮廓→四角框/分数→unclip→透视裁剪的宿主参考，明确边缘/退化几何和框分数关联，再移植目标华为。
- 完整原图→框→裁剪→识别后重跑旧及新增三语语料；当前没有证明英语/法语/简繁中文或混排质量通过。
- 正式接入前另验真实页面、隐私/停止、内存、性能和许可证；本实验不扩大屏幕共享权限。
