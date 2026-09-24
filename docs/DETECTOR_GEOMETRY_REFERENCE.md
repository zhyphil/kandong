# 检测框与透视裁剪：固定宿主参考

2026-09-24已完成独立准备：16组、13张裁剪图、2组polygon unclip微型用例，共78个参考文件/622,505字节。两次独立生成全部声明文件逐字节相同。此项不运行新模型，不证明Android后处理或三语识别质量通过。

本项可独立于[原图探针的模拟器兼容待办](ORIGINAL_IMAGE_PROBE.md)推进。数据仅供后续移植核对，不接入正式放大镜、不加入当前测试APK。

## 来源与规则

输入为原detector-v1固定的10组模型概率图和本项目合成原图；父manifest SHA-256仍为74aa1e39c8d3187ee2388ad07bb228e3c876c0486ccd02c47208942d26f2c80a。另外增加6张手工概率图，明确标为几何测试，不伪装成神经模型输出。

执行实际RapidOCR3.9.2函数，固定OpenCV5.0.0.93、NumPy2.5.3、Pyclipper1.4.0、Shapely2.1.2、Pillow12.3.0；源文件指纹和脚本指纹随manifest保留。

- 分割严格大于float32(0.3)；2×2默认anchor膨胀；RETR_LIST / CHAIN_APPROX_SIMPLE轮廓，最多处理1000个候选。
- fast框得分阈值0.5，最小边与最终过滤保持上游规则。Unclip采用polygon面积×1.6/周长作为offset，圆角连接、闭合polygon，不用放大矩形代替。
- 保留后处理原始框/分数序列；几何阅读顺序从稳定y排序、相邻y差10的行分组、同行x排序产生。框、原索引、固定ID、分数和裁剪作为整体对应，修正上游入口只排序框的关联风险。ID仅用于此固定样本，不能当作真实页面跨帧身份。
- 透视裁剪尺寸截断相对边长度的最大值；INTER_CUBIC、BORDER_REPLICATE。裁剪前高宽比≥1.5时再逆时针旋转90°。同时记录原图四角、旋转前尺寸、透视矩阵和旋转标记，避免后续丢失坐标关系。

[上游后处理](https://github.com/RapidAI/RapidOCR/blob/v3.9.2/python/rapidocr/ch_ppocr_det/utils.py)、[上游排序](https://github.com/RapidAI/RapidOCR/blob/v3.9.2/python/rapidocr/ch_ppocr_det/main.py)、[上游裁剪](https://github.com/RapidAI/RapidOCR/blob/v3.9.2/python/rapidocr/utils/process_img.py)。脚本调用已安装的固定版本，没有复制整个项目；配对排序根据其规则实现，Apache-2.0归属见[来源记录](ATTRIBUTION.md)。

## 覆盖与结果

| 输入组 | 最终框数 | 核验内容 |
|---|---:|---|
| 英文、法文 | 各1 | 固定实际检测结果，原分数随框排序 |
| 简体、繁体中文 | 各2 | 两行裁剪及位置 |
| 三语混排 | 3 | 同页三个块的原始索引与阅读顺序 |
| 空白、颜色控制、3张宽度控制 | 各0 | 保留负例，不人为制造文字框 |
| 等于分割阈值 | 0 | 使用严格大于，非大于等于 |
| 高于分割但低于得分阈值 | 0 | 分割通过不代表框通过 |
| 单点噪声 | 0 | 最小尺寸过滤 |
| 两块、分数不同 | 2 | 验证框排序后分数随原索引对应 |
| 贴边内容 | 1 | 原图坐标裁切与边缘裁剪 |
| 竖向内容 | 1 | 旋转90°及尺寸交换 |

另外2组unclip微型用例保存轴对齐矩形和分数坐标旋转四边形的实际expanded polygon、最小外接框和短边。裁剪图像与中间掩码均保存哈希；不是只保存“通过”结论。抽查英文和简体中文裁剪图可见预期合成句，但这不是OCR识别准确率测试。

## 复现与校验

[生成器](../scripts/export-detector-geometry-fixtures.py)只接受仓库外的新空目录，不下载模型，不读手机内容，写入前认证父manifest及输入文件。图片尺寸、概率元素数、文件字节和裁剪尺寸均设上界；输出文件名只允许受限字符。

    /path/to/pinned/python scripts/export-detector-geometry-fixtures.py --output /private/tmp/kandong-geometry-new-run

重新生成后按manifest声明的77个文件加manifest本身核对，不把依赖产生的临时运行状态当作参考输入。当前宿主依赖在输出工作目录额外创建了:memory:.ses；该临时文件没有进入仓库或样本，也没有作为可重复性结果。导入ORT时关闭遥测不等于已经审计其导入期行为或网络行为。

参考manifest SHA-256：1aa14f2efb50e62c5a2d95b45b7d5b5044d4ec90afa47a976e8dea50bbe466a6。

[固定数据](fixtures/detector-geometry-v1/manifest.json) · [两轮验证](evidence/detector-geometry/2026-09-24/verification.json)。原detector-v1以及手机已安装的测试产物未改变。

## Android移植仍需完成

1. 先核对适用于Android的polygon offset实现、许可证、整数缩放、圆角精度和多路径返回语义，不直接把Clipper版本不同当作几何等价。
2. 在分配前拒绝非有限坐标、零面积/零周长、奇异透视矩阵、超出预算的候选和裁剪。新增超过1000候选的压力用例，明确返回不完整状态；本次尚未覆盖该压力场景。
3. 分阶段比较掩码、膨胀、轮廓/框、分数、unclip、裁剪及旋转坐标关系。实现前固定容差，失败保留差异，不按结果改参考。
4. 目标华为运行并检查异常清理；第二设备兼容单独解决。接识别器后再评估EN/FR/简繁ZH/混排及关键金额、单位、否定，不能把13张参考裁剪冒充识别验收。
