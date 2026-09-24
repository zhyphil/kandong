# 本机文字识别实验 · 2026-09-24

状态：独立ocrlab实现、构建、单元测试和专用API36模拟器验证完成。它为区域翻译准备文字识别候选，**还没有翻译能力，也没有接入放大镜或读取真实页面**。华为上的OCR仍待验证；同日完成的[资源优化华为观察](HUAWEI_RESOURCE_VALIDATION.md)属于另一应用路径。

## 输入与边界

新应用“看懂文字实验”，包名com.kandong.ocrlab，仅依赖随包Latin OCR：com.google.mlkit:text-recognition:16.0.1。沿用项目工具链和原生View实验界面，min29、compile/target36。生产app/compat/graphics/qualitylab源码与版本未改。

用户点开始才运行12句项目自写英法原文，每句绘制为16/24/32像素字号，加一张空白负例，共37项。固定640px宽、32px留白、黑字白底、sans-serif普通字形，自动换行，无截断、自动缩字或文字标签。传入Intent文字、URI、ClipData等不被读取；没有屏幕、相机、照片、文件导入或模型下载入口。

输入快照与docs/fixtures/translation-cases.json逐字节一致，SHA256为1f638fe9922fc979db040cb1d12cbee17dc66d950e62ec7b2872fe5544cf6553。原语料中的not_run是准备阶段快照，实际OCR结果存于独立报告；不能把OCR运行当作翻译语料已经通过。

只进行NFC与Unicode空白折叠/首尾清理。大小写、数字、重音、标点、否定词都参与严格比较；不用翻译语义字段criticalValues或expectedMeaning代替OCR原文。空白结果、失败、取消均如实报告，没有准确率通过门槛。

## 构建与依赖核查

- debug构建及7项JUnit测试通过；Lint为0错误、6项提示。首次发现断行常量类型不符，改用API29的等值LineBreaker常量后复查通过，没有压制该检查。
- APK约49.8MiB，包含四种架构原生库与21个模型资源；该大小是实验包实测值，不是未来产品承诺。签名和16KiB压缩包对齐检查通过；不等于所有设备页大小的完整兼容验收。
- 最终APK无Android系统权限，仅声明自身签名级动态接收器权限；没有INTERNET、ACCESS_NETWORK_STATE或屏幕/相机/存储等权限。SDK初始化provider、服务、接收器及GoogleApiActivity均未导出，只有实验launcher导出。
- 依赖仍包含Google客户端、ML Kit公共组件、Firebase组件和数据传输代码。不能将“模型随包”或移除网络权限说成SDK没有指标代码/本地队列；正式采用前仍须核对许可、披露与完整依赖行为。
- 构建、已安装模拟器包与独立只读审查按13文件源码汇总SHA256绑定：408dab4eb99f09ab5f264734f2125ca08a8328ccc60ea8bd75b9c27f6a1ad089。审查未发现P1/P2；没有声称执行过红绿回归循环。

[完整验证及APK校验](evidence/ocr/2026-09-24/verification.json)、[依赖树](evidence/ocr/2026-09-24/dependencies.txt)、[合并组件](evidence/ocr/2026-09-24/components.json)、[随包模型](evidence/ocr/2026-09-24/bundled-models.json)、[独立审查](evidence/ocr/2026-09-24/review.md)。

## 实测结果

| 场景 | 结果 |
| --- | --- |
| 初次普通模拟器运行 | 37项完成，36张文字图中33张严格匹配，空白图无文字 |
| 清空实验数据、网络关闭、Google服务停用 | 无活动默认网络；37项仍完成，结果相同。测试后网络和GMS设置恢复 |
| 取消/退后台/旋转 | 均在在途任务期间取消；取消时分别已完成0/18/0项，之后报告冻结，返回页面不自动运行 |
| 手动重开 | 新runId、37项完成、结果一致 |
| 外部启动参数 | 传入合成哨兵文本、URI与run参数，报告未变，未自动启动或识别外部内容 |

三轮的3处差异都来自同一金额句在不同字号下的冒号旁空格。原文“Total : 1 234,50 €”被识别为“Total :1 234,50 €”或“Total: 1 234,50 €”；数值和欧元符号保留。**保留这3项严格不匹配，不为提高成绩放宽规则**。这12句简单清晰图片不能代表真实界面、裁剪小字、模糊、复杂排版或一般识别准确率。

[普通运行](evidence/ocr/2026-09-24/online-emulator-report.json)、[无网络/GMS运行](evidence/ocr/2026-09-24/offline-gms-disabled-report.json)、[环境恢复](evidence/ocr/2026-09-24/offline-environment.json)、[生命周期](evidence/ocr/2026-09-24/lifecycle.json)、[重复运行](evidence/ocr/2026-09-24/repeat-report.json)。

## 取消与保存

全进程只允许一项异步识别在途。取消或onStop先撤销结果发布、解除Activity引用，SDK任务真正完成后再回收Bitmap和关闭recognizer；等待清理期间拒绝新运行。旧任务不能释放新任务占用，也不会写入迟到结果。

只有显式启动实验后才在应用私有目录生成ocr-report.json，内容限定随包合成文字。新运行先替换旧报告，原子写入后回读校验；报告失败不显示成功。取消报告的cleanupPendingAtPublication只是当时快照，后续手动重开验证清理已能继续，不能把字段当作持续实时状态。

## 运行方式与下一步

~~~sh
./gradlew :ocrlab:assembleDebug :ocrlab:testDebugUnitTest :ocrlab:lintDebug
adb -s emulator-5580 install -r ocrlab/build/outputs/apk/debug/ocrlab-debug.apk
adb -s emulator-5580 shell am start -n com.kandong.ocrlab/.OcrLabActivity
~~~

在“看懂文字实验”中手动点开始。调试包的固定合成报告可通过以下命令读取：

~~~sh
adb -s emulator-5580 shell run-as com.kandong.ocrlab cat files/ocr-report.json
~~~

这里只操作本项目专用AVD。原实验进程和模拟器已结束；网络/GMS/旋转设置均恢复。首次模拟器在安装前以137结束，原因未证实；以1536MiB/2核重新启动后完成验证，不将宿主环境退出归因为产品故障。

下一步是目标华为的独立合成OCR兼容验证，以及独立的EN/FR→中文翻译模型验证；之后才考虑按用户主动请求的区域流程，继续优先Accessibility Tree、按需OCR。当前无新的真机OCR结果、云翻译、发布或正式功能开关。

官方来源：[Android OCR API](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[TextRecognizer资源释放](https://developers.google.com/android/reference/com/google/mlkit/vision/text/TextRecognizer)、[ML Kit条款与隐私](https://developers.google.com/ml-kit/terms)。SDK及模型按其官方条款核对，不以示例代码的Apache许可证代替SDK/模型许可。
