# 实际几何裁剪到识别的固定参考

2026-09-24。Android完整框流程目标华为验收后，继续NORMAL准备项：一个离线导出脚本、固定参考和记录，零AO/子代理，不改Android或旧数据。**此处只有宿主参考完成，不是三语质量或完整OCR通过。**

## 范围与来源

直接认证并使用此前已验收几何流程产生的13张裁剪：5组文字图9行，以及two-blocks/edge-touching/vertical三组图形4行；另8个无框输入明确不生成张量、不推理、不补文字。保留框ID、检测原索引、阅读序号、四角、分数、旋转和原图→裁剪矩阵。

[导出器](../scripts/export-crop-recognition-fixtures.py)只调用固定RapidOCR3.9.2的resize_norm_img与CTC解码，OpenCV5.0.0.93/ORT1.30.0。处理固定为高度48、批宽取整、宽高比排序、BGR/NCHW浮点归一化与右侧正零，独立重建数值核对上游输出。此小语料的实际排序与稳定排序相同，不推断所有NumPy并列排序都稳定。

两个已缓存Chinese/Latin模型均处理全部8组非空输入，不按语言答案挑模型；模型及完整索引字典沿用既有指纹。CPU串行单线程、ALL_OPT、关闭CPU arena，固定库和源码哈希；无需下载。输出保存每行原始文本、完整argmax、张量行→阅读序号→框ID回映，不做空白、重音或错字修正。

## 实际验收

两次独立导出51个声明文件、682245字节逐字节相同：13张缩放PNG/13份ARGB、8份张量、16份完整argmax和manifest。张量总816048个float32；16次推理26行。压缩/原始长度及SHA、旧963项文件保护核对通过。

manifest为50281字节，SHA256 `bbbfd058777e51e4fb7769764d2b49dac7618c784b8e0ef652af84d973f64f6a`。参考在[固定清单](fixtures/crop-recognition-v1/manifest.json)，两轮输出及[核对摘要](evidence/crop-recognition-reference/2026-09-24/verification.json)保留。依赖生成的`:memory:.ses`不属于声明参考；启动仍出现telemetry ID落盘失败提示，不把关闭运行时统计当作网络审计。

结果仍包含错误：Chinese法语出现“Ce bilet … éechangeable”，繁体行出现简体“李”，混排末行出现“不含税/不含税”；Latin中文丢失。无文字图形也产生“7”“A7”等猜测。全部保留为技术对照，**不能将同样输出判为阅读正确，也不能将这些图形当作识别正确率样本**。置信度/无文字拒绝及真实语言选择仍待质量阶段验证。

## 下一项

独立Android测试用实际BoxPipeline生成框、GeometryOpenCv裁剪，再做OpenCV线性缩放与RecognitionPacking，两个现有模型均推理；期望结果只在实际计算后比较。预先固定13裁剪/缩放像素、8张量逐位一致，16模型运行26行原文与argmax、框身份回映，8空案例无推理，以及错误输入与清理检查。数值一致后，再串联实际检测概率并运行新增三语语料。正式读屏和翻译仍未接入。
