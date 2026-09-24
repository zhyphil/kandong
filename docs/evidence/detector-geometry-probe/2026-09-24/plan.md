> 📲 **Android Geometry Planner** | 步骤 1/1 | 140.8s

---

建议下一阶段限定为**测试专用的掩码／轮廓计数和冻结四角裁剪探针**。预算：最多 6 个新 Kotlin 文件、`modelprobe/build.gradle.kts` 及父代理维护的文档／任务记录；不新增依赖。

只读检查显示工作区干净，OpenCV 4.14.0 已是 [androidTest 专用依赖](/Users/haoyuzuo/Projects/KanDong/modelprobe/build.gradle.kts:25)。[参考生成器](/Users/haoyuzuo/Projects/KanDong/scripts/export-detector-geometry-fixtures.py:88)保存了可独立比较的中间结果。

1. **文件划分**

   以下路径均在 `modelprobe/src`，包路径为 `java/com/kandong/modelprobe/`：

   | 源集／新文件 | 职责 |
   |---|---|
   | `testShared/GeometryProbeContract.kt` | 数据关联、尺寸预算、四角及矩阵前置校验 |
   | `testShared/GeometryFixtureBytes.kt` | 有界读取、哈希、GZIP 和限定格式 PNG 解码 |
   | `androidTest/GeometryFixtureInputs.kt` | 认证 manifest、完整资产集合和引用关系 |
   | `androidTest/GeometryOpenCvProbe.kt` | 膨胀、轮廓计数、透视裁剪、旋转及资源所有权 |
   | `test/GeometryProbeGuardTest.kt` | 现有 JUnit 下的字节、数值、几何防护测试 |
   | `androidTest/DetectorGeometryProbeTest.kt` | 现有 AndroidJUnitRunner 下的参考对照、压力及异常清理 |

2. **输入认证先于图像原生分配**

   固定 manifest SHA：
   `1aa14f2efb50e62c5a2d95b45b7d5b5044d4ec90afa47a976e8dea50bbe466a6`。

   先限量读取并认证，再解析；要求 16 cases、13 crops、77 声明文件加 manifest，拒绝路径越界、遗漏、重复及额外文件。预检逐文件进行，消费时重新认证实际字节。

   预设上限：manifest 128 KiB、单文件 2 MiB、单边 4096、单图 1,048,576 像素；乘法使用受检 `Long`。概率解压长度严格为 `4×H×W`，掩码为 `H×W`，同时校验压缩／解压哈希。

   为满足解码哈希先于图像原生分配，PNG 使用测试专用 RGB8、非交错字节解码：校验签名、IHDR、chunk 边界／CRC、IDAT 解压长度、过滤器及结束位置；恢复 BGR 后核验冻结哈希，之后才创建 OpenCV Mat。拒绝其他格式，不引入 Bitmap 或新解码依赖。

3. **两条独立计算路径**

   - 概率必须有限且位于 `[0,1]`；以 `> 0.3f` 生成 0/1 掩码，使用全 1 的 2×2 kernel、默认 anchor、一次 dilation；轮廓输入乘 255，采用 `RETR_LIST / CHAIN_APPROX_SIMPLE`。
   - 裁剪只接收 **source PNG＋冻结 reference quad**。保留 `id/originalIndex/readingOrder/detectorScore` 与矩阵、旋转、crop 的整体关联，分数不重算。目标四角为 `(0,0),(W,0),(W,H),(0,H)`；尺寸截断相对边长最大值，`INTER_CUBIC + BORDER_REPLICATE`，高宽比 ≥1.5 时逆时针旋转。
   - 接受前校验有限、源图范围内、四点唯一、严格凸且非退化；归一化矩阵求解遇到 ≤`1e-12` 的相对 pivot／投影分母即拒绝。保存正逆变换；旋转像素映射采用 `(x,y)→(y,W−1−x)`。

4. **运行前冻结验收门槛**

   - 16 组 binary mask、dilation：逐字节完全一致。
   - `contourCount`：精确一致。
   - 13 张 crop：尺寸、旋转及关联精确一致；BGR 字节初始门槛为零差异。
   - 归一化矩阵：`|actual−reference| ≤ 1e-9 + 1e-9×|reference|`。
   - 四角正逆往返：绝对容差 `1e-6` 像素、相对容差 `1e-9`。
   - 差异先保存，再使测试失败；不修改参考或根据结果放宽门槛。

5. **防护、压力及清理**

   JUnit 覆盖损坏／超限／截断输入、PNG 过滤器、NaN/Inf、越界、重复点、凹形、自交、共线及奇异矩阵。设备测试使用内存合成的 32×32 分离块，预期 1024 contours；返回 `INCOMPLETE_CANDIDATE_LIMIT`、实际计数和 1000 上限，不静默截断为成功。

   在轮廓创建、矩阵创建、warp、rotation 后分别注入失败；逐项释放所有已取得的 Mat，包括 contour、hierarchy、kernel 和临时矩阵。计数仅证明释放尝试／调用完成；native headers 仍由 finalizer 管理。

6. **集成与父代理验收**

   androidTest assets 从 `docs/fixtures` 直接引用，仅纳入 `detector-geometry-v1/**`，保留既有资产；不复制二进制。报告仅含运行标识、合成 ID、差异汇总、状态及资源计数。

   父代理执行构建、包内容／哈希检查，并在用户确认后完成华为两轮测试，清除旧报告、验证新 runId。要求主 APK 保持既有 `7f9ff255…5a6aea` 完整哈希，`app/compat/ocrlab/qualitylab` 源码不变；同步 WORKLOG/TASKS。

本轮仅完成只读计划，没有运行测试。既有 28 JVM／华为 7 项记录不是新增证据。Polygon unclip、检测框与分数计算、OCR 和端到端对齐均留待后续；API36 SIGILL 保持 OPEN。