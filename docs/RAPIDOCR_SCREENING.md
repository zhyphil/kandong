# PaddleOCR 系本机候选筛选 · 2026-09-24

结论：PP-OCRv5 mobile 的中文识别器在这批中文及混排图片上明显优于前两轮候选，值得进入独立Android模型可运行性验证；**三语质量和正式翻译尚未验收**。法语重音、小字简繁混淆与混排间距仍有失败项，Latin模型不能单独覆盖中文。

## 固定方案

临时Python环境使用RapidOCR3.9.2、ONNX Runtime1.30.0，仅CPU、两个线程配置均为1；没有改Mac全局Python或Android依赖。明确指定PP-OCRv5 mobile检测、中文/Latin识别两套模型，关闭方向分类处理；库构造器仍加载小型分类模型，记录其大小而不隐瞒。配置中Det.limit_side_len为1280且limit_type为max，所有输入使用相同参数；后续核验发现该版本在max路线按输入尺寸改用960/1500/2000，因此1280不是实际硬上限，见[检测规则核验](DETECTOR_CONTRACT.md)。原始模型/输出保持原样。text_score为0，保留低置信输出；不按预期答案挑选、融合或修正结果。

先用原18张PNG × 原图/2倍 × 两识别器，72项。复用的2倍像素与此前Android平滑报告完全一致。随后在不修改模型、参数、比较规则的情况下，用10条新句的16/24/32px及空白，共31张图再跑124项；新manifest在运行前固定为3afed931fa6904864fe71f93f0be1de11a182f5a016fbc933fa529384cf165d9。新语料使用相同字体，仍不是多字体/真实页面或一般准确率验证。

[新语料定义](fixtures/ocr-trilingual-holdout-v1.json)和[固定图片manifest](fixtures/trilingual-holdout-v1/manifest.json)保留原文、像素和字体指纹；未将字体或模型二进制加入仓库。模型来源是RapidAI固定v3.9.2配置中的ModelScope文件，每个下载均匹配发布清单SHA256；不是把转换文件冒称Paddle官方原文件。[RapidOCR](https://github.com/RapidAI/RapidOCR/tree/v3.9.2)、[PaddleOCR多语言说明](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.md)。

## 结果与限制

| 语料 | 中文识别器原图/2倍 | Latin识别器原图/2倍 |
| --- | --- | --- |
| 原18张（17张文字） | 11/17、12/17 | 10/17、10/17 |
| 新31张（30张文字） | 22/30、22/30 | 12/30、12/30 |

所有空白负例正确。严格比较仍只做NFC和Unicode空白折叠，**不删除空格或更改标点来提高比例**。Latin在含中文输入上失败属于模型能力边界；不能按这些已知语言标签在正式流程里选择结果。

- 原中文简/繁4张图在中文识别器的两个倍率均逐字匹配，kg、金额、不可退款等此前关键错误消失；原法语n'est句在原图有bilet/éechangeable错字，2倍修复。
- 新图中的金额、时间、7公斤、含/不含、勿重复付款和禁止进入未观察到语义丢失；这只覆盖列出的自编样本。仍有à→a、稅→税，以及混排空格/全半角斜线差异，原小字混排也有简繁混淆。原金额图的千位空格丢失保留为格式不匹配。
- 输出含实际行框、原始文字和置信度；它们不是页面分组、可靠语言检测或上下文翻译。没有做模型结果自动融合。
- 构造器及单次耗时来自Mac，不能推算华为内存、耗电或交互等待时间。正式手机模型验证需单独证明相同输入张量的输出及取消/释放，再做Android图像预处理与检测对齐。

## 复现与后续

[scripts/screen-rapidocr-fixtures.py](../scripts/screen-rapidocr-fixtures.py)要求显式固定manifest SHA、模型目录与输出目录，仅接受自编语料scope；不自动下载文件。依赖版本在[requirements](../scripts/ocr-screening-requirements.txt)，下载来源/散列在[宿主机依赖记录](evidence/rapidocr/2026-09-24/host-dependencies.json)。脚本化后重新跑原72项，文字、位置、像素与首轮一致。固定模型及[原72项](evidence/rapidocr/2026-09-24/baseline72.json)、[新124项](evidence/rapidocr/2026-09-24/holdout124.json)、[摘要](evidence/rapidocr/2026-09-24/summary.json)均留档。

RapidOCR源码发行版标示Apache2.0；转换模型的上游为PaddleOCR。当前上游README列出的MODEL_LICENSES.md链接返回404，具体转换记录仍需补齐，不能当成已经完整审计；若分发模型，须保存实际对应许可和版权声明。当前仅临时宿主机评估，没有模型APK或正式分发。下一阶段只做有界独立模型探针，不把宿主机质量改善直接升级为产品通过。
