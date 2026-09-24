> 🤖 **AI Engineer** | 步骤 1/1 | 218.6s

---

建议新增独立的测试整页推理路径，保留旧冻结契约。`0c218e8` 工作区干净；本轮仅只读检查，未运行构建、测试或设备操作。

1. **最小文件范围与执行路径**

   新增：
   - `testShared/.../FullPageOcrContract.kt`：预算、输出形状、原屏坐标和候选身份。
   - `androidTest/.../FullPageOcrPipeline.kt`：逐条带检测、逐行识别。
   - `androidTest/.../FullPageOcrFixtures.kt`：固定 PNG 拼页及 ImageWriter/ImageReader 输入。
   - `androidTest/.../FullPageOcrProbeTest.kt`、`contextTest/.../FullPageOcrContractTest.kt`。

   路径为：

   `固定 PNG 拼成完整 RGBA Image → AndroidRgbaFrameLease → SingleFrameStripInput.process → 检测 → BoxPipelineOpenCv.run → 单行裁切/识别 → 原屏候选列表`

   整个输入路径不接受 ROI 参数；选区只用于推理结束后的结果对应检查。

2. **现有 API 的复用边界**

   | API | 结论 |
   |---|---|
   | `EndToEndOcrProbeTest.resize` | 是 `private`；在新测试路径复用其算法，保留 BGR、`INTER_LINEAR` 和旧舍入，不改原参考测试。 |
   | `BoxPipelineOpenCv.run` / `BoxPipelineContract.cropRow` | 可直接复用；保留非 `COMPLETE` 时无可用部分框的规则。 |
   | `GeometryOpenCvProbe.crop` | 可直接复用，支持索引 `0..999`；`GeometryImage` 包装当前条带数组即可，无需再复制源 BGR。 |
   | `CropRecognitionPipeline.run` | 不可直接用于整页：明确限制最多 3 框。 |
   | `CropRecognitionPipeline.resize` | 仍调用旧 `validateRow`，真实 `.box-3` 以上会失败。可以给**临时单行计算副本**设置合法 `.box-0`、两个索引均为 0；真实身份始终保存在外层候选，不能从临时行恢复身份。 |
   | `RecognitionPacking.plan/pack` | 可以安全按 batch=1 复用；无需旧 `validateRows/bind`。 |
   | `DetectorProbeTestSupport.infer` | 可复用检测输出所有权管理。其 `tensor()` 仅适合检测形状，不能用于高度 48 的识别输入。 |
   | `OrtProbeEngine.withModel/infer` | 可以复用，但新路径必须自行提供经验证的识别输出形状。 |

   三框限制及编号限制见 [CropRecognitionPipeline.kt](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/CropRecognitionPipeline.kt:18)、[CropRecognitionContract.kt](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/CropRecognitionContract.kt:8)。

3. **会话、形状与明确预算**

   推荐最多同时持有 **1 个 detector＋1 个 recognizer session**，同一工作线程依次执行检测和识别，整组页面复用会话。先跑固定 ch 诊断组，再跑 latin 诊断组，分别报告，不按预期文字挑选输出。

   两个会话同时存活是新验收项，不能沿用旧“仅一个会话存活”的内存结论。这样可以在消费者回调内完成整条带，满足[禁止跨回调保留像素](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/SingleFrameStripInput.kt:33)的契约；不积攒整页 crops 或 prepared tensors。

   建议首轮新路径使用以下额外上限，均在分配/推理前检查：

   - 保留现有整帧 ≤4,194,304 像素、≤16 条带、plane ≤32 MiB、单条带 BGR ≤3 MiB。
   - 检测输入最多 3,145,728 floats；复用一块 ≤12 MiB direct storage，上一轮 tensor/result 关闭后才能覆写。
   - 每次只保留一个 crop；裁切继续满足旧 ≤1,048,576 像素限制。
   - 识别 batch=1，`320≤W≤1024`；输入 ≤147,456 floats，direct storage ≤589,824 bytes。超宽明确报不完整，不压缩或截断文字来凑预算。
   - 首轮最多 64 个识别框/条带、128 个/页，累计原文 ≤8192 字符；超过预算整页标记不完整，不返回“完整上下文”。
   - 每页独立报告沿用 `save()` 的 256 KiB 上限；不保存概率图、像素数组或整页全部张量。

   **输出形状存在可解决的前置缺口。** 旧 E2E 从冻结参考读取 `outputShape`。本轮读取两个已固定 ONNX 的结构，其宽度路径为两次向上取整的 stride-2 卷积，再一次宽度 2、stride-2 的无填充池化，可推导：

   `T = floor((W + 3) / 8)`，输出为 `[1,T,vocabulary]`。

   它与现有记录一致，包括 `W=452→T=56`、`605→76`，不能直接使用 `ceil(W/8)`。在新增契约中绑定现有模型哈希，补宽度边界测试，并由实际 `OrtProbeEngine.infer` 再核对输出。`W≤1024` 时 ch 输出最多 2,353,280 floats，低于现有 4,000,000 上限。

   这些是受控缓冲区预算，不是已经验证的进程 RSS 或 ORT 内部峰值。

4. **身份、坐标与接缝**

   每个原始识别候选保存：

   `CaptureVersion、pageFixtureId、strip.index、read/core、contourIndex、rawBoxIndex、finalBoxIndex、stripReadingOrder、localQuad、pageQuad、detectorScore、modelId、rawText`。

   - `BoxPipelineOpenCv` 已把框映射到条带源像素；原屏坐标只加 `read.left/top`，不能再经过 `sourceX/sourceY` 缩放一次。
   - 候选 ID 由页面版本、条带和原始框索引组成；排序不重编号。
   - `ownsSourceCenter` 仅记录几何归属。全部识别候选，包括空串、重复和残片，都保留；首轮不做拼接、去重或文字纠错。
   - 回调只返回文字及有界几何数据。版本失效、过期、取消或关闭失败时，不发布部分上下文；模型调用之间补检查，承认单次 native inference 不能据此保证即时中断。

5. **固定页面与验收**

   使用已有 PNG 原像素，不重新渲染字体。每种语言各制作普通页和接缝页，主尺寸 **1176×2400**；补 **1080×2400** 检查不同分段边界。

   | 页面 | 已有源图 |
   |---|---|
   | EN | `detector-v1/en-quality-16-source.png`；holdout 的 `en-checkin-24.png`、`en-tickets-32.png` |
   | FR | `detector-v1/fr-nonrefundable-16-source.png`；`fr-departure-24.png`、`fr-price-32.png` |
   | Hans | `detector-v1/zh-hans-quality-16-source.png`；`hans-order-24.png`、`hans-luggage-32.png` |
   | Hant | `detector-v1/zh-hant-quality-16-source.png`；`hant-order-24.png`、`hant-luggage-32.png` |

   Holdout 来源为 `docs/fixtures/trilingual-holdout-v1/`，将这 8 张按白名单放入新测试资产命名空间，保留原哈希。拼页直接逐行写入 ImageWriter，每次只解码一张小 PNG，避免额外完整页面 Bitmap。

   页面覆盖顶部、中部、底部，并安排一条带超过 3 行。接缝页分别让**实际字形**跨核心边界及读取边界 `s±64`，避免只让 PNG 白边跨缝；另加空白页。记录固定放置坐标、图像哈希和逐行真值，真值只进入推理后的评分。

   验收分开记录：

   - **运输/数值正确性**：原图身份、整页覆盖、坐标、稳定 ID、预算拒绝、关闭及取消无部分提交；旧冻结数值回归保持。
   - **实际 OCR 质量**：分 EN/FR/Hans/Hant 报告完整行、遗漏、重复、残片及金额、单位、否定、重音和简繁错误。接缝关联仅作为诊断标注，不能把“某候选命中”冒充整页无错。
   - **上下文理解/翻译**：本阶段仍未验收。真实整页 OCR 输出完成后，才能进入页面上下文理解和 ROI 对应译文实验。

三个模型文件当前均存在，读取所得哈希与固定记录相符，未发现必须下载、升级或增加依赖的前置阻塞。真正需要补齐的是上述独立单行适配、输出形状契约及双会话生命周期验证。实施后同步 `FULL_PAGE_OCR_INPUT.md`、`TASKS.md`、`WORKLOG.md`；138 JVM 和双设备各 28 项历史通过仍仅证明既有几何、字节及 Image 运输能力。
