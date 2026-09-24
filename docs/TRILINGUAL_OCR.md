# 三语 OCR 对照实验

首批范围为英语、法语、中文，中文简体/繁体分别记录，另有同屏混排。输入仅为项目自编合成文字，不读取屏幕、照片或外部文件。实验不是翻译功能，中文原文也不是人工预置译文。

## 固定输入与历史基线

[独立语料](fixtures/ocr-trilingual-v1.json)包含原华为7个失败输入（含fr-total的3种字号）及英、法、简体、繁体、混排各16/32px，另加空白，共18张参考图。原12条英法语料、37项旧实验与报告保持原样；复制的历史句子逐字核对，包括窄空格。

参考图位于ocrlab/src/main/assets/trilingual-v1；同一PNG随包交给两个设备，不在设备上重新绘制参考图。生成工具为Pillow12.1.1，字体Arial Unicode MS Regular，字体文件SHA256为876af2cd4854644e7f3e7feb2f688997fdb3343c6df6693611209c9dfb47ccec；只保存自编文字的栅格图，不分发字体文件。宽640px、边距32px、黑字白底；字号及实际行由manifest记录。参考图不是旧Android原生字体的复制品。

生成器：[generate-ocr-fixtures.py](../scripts/generate-ocr-fixtures.py)。复现需相同字体与工具版本；生成后校验PNG文件、尺寸和逐行RGBA8888像素SHA256。manifest SHA256为2341dff63bd10d41867351ddff0e4145daa2d9d3493bc8cdc88ea289c1a076a8。人工查看了中文、繁体、混排与旧失败样本，未发现缺字或截断。

## 候选核查 · 2026-09-24

官方Android接口列出随包Latin与Chinese两条依赖，版本均为16.0.1；中文选项为ChineseTextRecognizerOptions。随包路线在构建时携带模型，无需首次通过Play Services获取模型。官方语言表列出English、French与中文Hans/Hant，但这不是该华为的运行或准确率证据。[Android接口](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[语言范围](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)。

实际下载并检查官方Chinese16.0.1 AAR：2,036,186 bytes，SHA256 cb81f8036a6410e036cff88350ee8548e5258ff6f9e2ffbc7455defa7f672dbe，包含Hani与Latn模型资产；该AAR自身manifest无uses-permission，最终依赖合并与APK仍需单独检查，不能用单个AAR推断整个应用权限。包体积也不是最终APK增量。POM使用ML Kit Terms of Service；不把示例的Apache许可套用到SDK或模型。[官方构件](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/text-recognition-chinese-16.0.1.pom)、[条款与隐私](https://developers.google.com/ml-kit/terms)。SDK说明存在指标代码与联网用途；独立实验继续移除联网权限，最终以APK核查及实测为准。

## 执行与判定规则

18个输入 × 设备原生绘制/固定PNG × 原图/2倍最近邻像素复制 × Latin/Chinese，共144次识别。两种识别器处理所有输入，语言标签只用于分组报告，不根据预期答案挑选或合并结果。原图与放大图均保存实际像素指纹；坐标按实际处理倍率还原。

识别原文与SDK块/行/元素、位置和语言证据分开保留。未给出可靠语言时标未知；Latin不能自动当作英语。严格比较仍为NFC与Unicode空白折叠，不删标点、修单位或用答案补字。取消/离开后禁止迟到结果写入，SDK在途结束后才释放图片。

依次验收：候选与固定语料核查 → 实验实现/构建/生命周期 → 两设备同像素与三语质量。固定输入、实验实现和两设备运行/生命周期已完成；识别质量未通过，继续质量改进任务。不能用144项运行完成代替质量通过。

## 任务2实际验收

- 新增5个源码与7项JUnit，实际全部37项测试通过；debug构建通过，Lint0错误/13提示。StaticFieldLeak所指字段只保存applicationContext，独立审查未发现Activity泄漏；未发现可确认P1/P2。
- 最终APK53,188,277 bytes，SHA256 a08fde25c2d53a20b0a06c4da27a7405e5a27885a19bbc2696b0c4ad29eec244；新增9个模型资产，原21个模型资产逐字节不变。仅有自身签名权限，无Android联网/采集权限；新Activity不导出，签名/16KiB对齐通过。源码/模型指纹及检查见[验证记录](evidence/trilingual/2026-09-24/verification.json)，[独立审查](evidence/trilingual/2026-09-24/review.md)。
- 华为LIO-AN00/API31与专用API36各完成144项及清理后重开144项。两设备均验证取消、后台返回不自动运行、跨实验取消及清理后启动另一实验；模拟器另外验证旋转并恢复设置。华为取消命中在途任务、后台取消发生于任务间隙，分别如实记录。两设备原37项raw结果与此前各自基线完全一致（华为29/36，模拟器33/36），没有用新增模型改写旧结果。
- 华为安装包SHA256已复核一致，未安装GMS；未改手机网络/安全设置，不宣称已做断网测试。结束后实验应用已停止。

证据：[华为](evidence/trilingual/2026-09-24/huawei/analysis.json)、[华为生命周期](evidence/trilingual/2026-09-24/huawei/lifecycle.json)、[API36](evidence/trilingual/2026-09-24/api36/analysis.json)、[API36生命周期](evidence/trilingual/2026-09-24/api36/lifecycle.json)。raw结果在各目录full-report.json与restart-report.json，只有固定合成文字。

## 任务3发现与下一轮改进

[跨设备配对](evidence/trilingual/2026-09-24/cross-device.json)显示：全部72个固定PNG处理任务在两设备上像素及原始输出一致；68个非空原生绘制任务像素不同。这支持排查渲染输入差异，但不能单凭此确定此前每个错误的唯一根因。

华为原生fr-luggage/32两识别器在原图均丢失kg中的k，两倍复制后正确；sans错误也有改善，但n'est撇号仍丢失。中文出现行李→行季、退款→退歉，混排出现不含税→不合税；全角/半角标点和间距差异另行保留，不把全部不匹配都解释成语义错，也不放宽比较以掩盖错字。SDK对部分短块给出其他语言/未知，不能直接用该字段保证三语判定正确。

本轮双输入×双引擎各组严格匹配仅1～6/17（含大量排版差异），每组空白均为空；这些数值是难例集诊断，不是一般准确率。当前不得接入正式区域翻译。下一步在同一固定语料上配对验证平滑重采样，分别检查收益与新增错误；预期答案仍只用于评分，不能决定推理或纠错。
