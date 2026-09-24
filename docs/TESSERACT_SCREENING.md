# Tesseract 本机候选筛选 · 2026-09-24

结论：完成候选筛选，但不接入当前四语模型组合。更大模型改善部分样本，仍出现“不含税”→拉丁字母、简繁混淆，以及小字混排错误。没有手机端性能或兼容结论。

## 范围与来源

使用Mac已有Tesseract5.5.2（未安装/修改全局程序），先跑18张固定PNG × 页面分割6/11，共36项。随后同18张PNG × 原图/精确双线性2倍 × 两种固定语言顺序 × 现有/官方best两套模型，共144项。每个模型/顺序处理全部图片；期望文字和语言标签不参与推理选择，没有逐图挑选最优答案或修字。

两种统一顺序是eng+fra+chi_sim+chi_tra、chi_sim+chi_tra+eng+fra。OEM1、PSM6、单线程固定。2倍图全部18个像素指纹与Android平滑实验一致；不是用不同图片比较引擎。仍使用NFC与Unicode空白折叠的原评分规则。

官方tessdata_best固定提交e12c65a915945e4c28e237a9b52bc4a8f39a0cec，4个模型合计45,436,644 bytes，仅存临时目录；下载文件逐个核对Git blob和SHA256。模型仓库声明Apache2.0，后续若分发必须保留许可及相关声明，本次未将模型纳入项目或APK。[上游README](https://github.com/tesseract-ocr/tessdata_best/blob/e12c65a915945e4c28e237a9b52bc4a8f39a0cec/README.md)、[上游许可](https://github.com/tesseract-ocr/tessdata_best/blob/e12c65a915945e4c28e237a9b52bc4a8f39a0cec/LICENSE)。现有Homebrew模型逐个记大小/指纹，不依据目录名推定与上游某版本一致。

## 实际结果

| 模型 | 全局语言顺序 | 原图严格匹配 | 2倍严格匹配 |
| --- | --- | --- | --- |
| Mac原有 | 英法优先 | 5/17 | 4/17 |
| Mac原有 | 简繁优先 | 5/17 | 3/17 |
| 官方best | 英法优先 | 7/17 | 6/17 |
| 官方best | 简繁优先 | 5/17 | 3/17 |

上表不含每组均正确的1张空白负例。难例集包含空格/标点差异，不能把这些比例称为一般准确率。PSM6/11初筛均为5/17文字图严格匹配；两种分割模式并未消除关键错误。

best英文/法文个别样本改善，但繁体小字和混排仍出现“不含税/不含稅”→FER/TER；一些简体结果被读作繁体。不能把损失中文信息的输出交给翻译，也不能用期待原句补回。模型规模、模型顺序或处理倍率均没有成为可靠统一修复。

下一项：继续比较PaddleOCR系的本机推理候选，先做相同固定输入的质量筛选；选定后仍需增加未用于筛选的新语料，防止只适配这18张图。正式华为接入、整屏提取、混排语言判断和翻译保留未完成状态。

## 复现与证据

[筛选脚本](../scripts/screen-tesseract-fixtures.py)只消费固定语料；需要Python、Pillow、NumPy、Tesseract及4个模型。`--repo`指定KanDong根目录，`--models`为已有模型目录，`--output`为独立临时结果目录，`--android-report`使用[Android原始平滑报告](evidence/ocr-smoothing/2026-09-24/huawei/full-report.json.gz)，支持gzip。脚本不自动下载依赖或模型。

[全部分组结论](evidence/tesseract/2026-09-24/summary.json)、[原有模型144矩阵中的72项](evidence/tesseract/2026-09-24/existing-models.json)、[best对应72项](evidence/tesseract/2026-09-24/best-models.json)、[初始分割36项](evidence/tesseract/2026-09-24/initial-layout-probe.json)、[模型版本与下载指纹](evidence/tesseract/2026-09-24/best-model-provenance.json)。elapsedMs含宿主机进程启动/加载，仅用于排错，不能推算手机帧率、耗电或用户等待时间。
