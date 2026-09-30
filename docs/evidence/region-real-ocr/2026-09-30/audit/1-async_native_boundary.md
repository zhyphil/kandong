> 🏛️ **Software Architect** | 步骤 1/1 | 426.5s

---

建议采用：**单个后台线程拥有 OCR、Guard 和控制器；主线程只持有不可变显示证据，并独立检查失效状态。** 保留现有合成模式，新增默认的“固定页面真实 OCR”模式。

已完成预算内 **1 个只读审计步骤**。确认 `HEAD=7e45a3cc256945c914f3669dacb41323987b45d5`、工作区干净。未修改文件、构建、运行测试、访问设备或联网；实施与独立评审应分别开展。

**当前源码中需要跨越的边界**

| 发现 | 依据与影响 |
|---|---|
| 当前展示是同步合成输入 | [RegionVisualSession.kt:26](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/RegionVisualSession.kt:26) 调用 `RegionVisualFixture.stage()`，不能直接把按钮处理替换为同步 OCR。 |
| 原生执行和控制器具有线程所有权 | [FullPageOcrPipeline.kt:31](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageOcrPipeline.kt:31)、[FullPageRegionController.kt:29](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageRegionController.kt:29) 检查创建线程。Guard 没有同样的线程断言，但内部状态可变，也必须保持线程封闭。 |
| 当前许可不能直接跨线程交给 UI 控制器 | [FullPageOcrPublication.kt:61](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageOcrPublication.kt:61) 转交的是原 Guard；它仍调用生产者的 checkpoint。主线程消费会跨越原控制器线程边界。 |
| 已有正确的关闭后发布范例 | [FullPageRegionProbeTest.kt:93](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageRegionProbeTest.kt:93) 在 Image 范围退出、两个 session 关闭、OpenCV 线程数恢复后才 `claimForRegion()`。可以提取执行结构，不能实例化整个 instrumentation 测试类用于独立启动。 |
| 当前原图并非 OCR 输入像素 | [RegionVisualLabSurface.kt:185](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualLabSurface.kt:185) 用 `drawText` 绘制合成文字；布局和几何还硬编码 `1200×800`。实际 fixture 为 `1176×2400`，另有 `1080×2400`。 |

两种实现选择：

| 方案 | 收益 | 代价 |
|---|---|---|
| **推荐：完整权威链留在后台，向 UI 发送不可变证据** | 不改变原 Guard／许可契约；原生资源和关闭路径集中 | UI 必须增加独立的请求身份、时钟和生命周期检查 |
| 后台只执行模型，将发布／控制器搬到主线程 | 几何直接复用现有控制器调用 | 需要设计新的跨线程 Guard 转交协议；容易丢失原时钟历史或错误续期，超出本阶段必要范围 |

**最小线程桥与请求规则**

```text
主线程
显式点击 → 请求身份、立即清除旧显示
                    ↓ 有界提交
固定后台线程
observePage → click → 验证资产 → 获取固定整页
→ runStaged → 全部清理完成 → claimForRegion → accept → render
                    ↓ 不可变显示证据
主线程
检查请求/页面/生命周期/原期限 → 原图、候选、选区投影
```

采用最小的 **1 个运行请求、0 个待执行 OCR 请求**：

- 正在推理或取消清理时，新点击返回“上次处理尚未结束”；不排队、不自动补跑。
- 取消后立即撤下 UI，但运行槽直到后台完成清理才释放。之后必须再次明确点击。
- 后台已完成、旧结果仍在主线程消息队列中时，可以接受新点击；旧结果必须按请求身份丢弃。
- 进程内共用一个串行原生执行器，避免 Activity 重建后出现两个尚未结束的原生任务，同时修改 `Core.setNumThreads()`。
- 执行器不持有 Activity；每个 Surface 有独立订阅身份，`dispose()` 立即解除订阅。

跨线程只传递：

```text
RequestKey(surfaceEpoch, requestId, pageVersion)
PageSpec(id, language, width, height, pinnedModelId)
DisplayEvidence(
    requestKey,
    originalMetadata,
    immutableAssociation,
    authenticatedRaster,
    lastValidatedWorkerTime,
    actualDiagnosticCounts
)
```

`Ticket`、`Guard`、`Pending`、`RegionPermit`、Image、Mat、ORT session 均不传到主线程。

主线程维护自己的单调时间历史，接收时要求：

```text
请求身份仍匹配
且当前处于前台、展开、未 dispose
且页面版本匹配
且 now >= 上次 UI 时间及后台最后验证时间
且 now < original acquiredAtMillis + ttlMillis
```

旧请求先判身份，再处理时间／失败状态，防止旧回调撤销新结果。时间回退、过期或失效后不可复活；恢复、移框、缩放和回调送达均不得重建 metadata。

页面切换、收起、菜单、pause、stop、dispose 的主线程处理必须同步完成：撤销当前身份、清空 Frame／候选卡／镜面及待交付槽，然后通知后台。后台 checkpoint 读取原子取消状态，在当前同步 JNI 调用返回后的检查点拒绝后续工作。**立即撤下显示不等于立即中断 JNI，也不等于擦除所有堆内副本。**

几何无需再次发送原生任务。UI 可对已验证的不可变关联结果调用现有 [FullPageRegionProjection.kt:26](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageRegionProjection.kt:26)，每次投影检查上述显示资格；这只是派生显示，不产生新许可。ROI、pan 使用源像素，倍率保持独立的 `1..5`。

**原图与原生资源所有权**

建议在 `FullPageOcrFixtures` 增加经过认证的 `PreparedPage`：

- 沿用 manifest、PNG 文件 SHA、尺寸、sRGB／不透明像素及 `rgbaSha256` 检查。
- 每个页面只解码其唯一 PNG 一次；保存私有、只读的 ARGB 小图块及整数 placement。
- ImageWriter 填充、整页预览、镜面都读取这份像素，不再次解码、不按参考文字重绘。
- 解码 Bitmap 在产生 `PreparedPage` 前回收。UI 可直接绘制只读 ARGB 图块，避免保留整页 Bitmap。
- 四个建议页面各只有两个唯一图块；最大是法语页 `640×(138+208)×4 = 885,760` 字节，不必新增约 11 MiB 的整页 Bitmap。
- 不向 Runner 暴露参考文本、glyph 答案或评分规则。它只收到固定页面描述、像素和预先确定的模型。

保留现有 `withFrame(page, meta, consume)` 入口，提取共用的认证／解码逻辑，再增加接受 `PreparedPage` 的入口，避免破坏历史测试调用。

单次 Runner 必须按以下顺序完成：

1. 后台创建控制器票据；验证 fixture、模型、字典。
2. 为本次固定整页获取创建一次 metadata，随后始终沿用。
3. 执行 `runStaged()`。
4. 关闭 Image、ImageWriter／Reader、Mat、ORT tensor／result、两个 session／options；恢复 OpenCV 线程数。
5. 同时检查成功退出、所有关闭计数和 `Cleanup.uncertain`，再 claim、accept、生成显示证据。

`OnceResource.close()` 会记录关闭失败但吞掉异常，因此“函数正常返回”不能替代资源检查。清理不确定时本次零发布，并使共享原生执行器进入不可继续运行状态；Activity 重建不能清除这个状态。

**冷启动类加载必须单独处理**

当前宿主的 [RegionVisualLabActivity.java:35](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/debug/java/com/kandong/modelprobe/RegionVisualLabActivity.java:35) 已验证固定包名及与宿主同签名，但 fallback 只有：

```java
new PathClassLoader(test.sourceDir, getClassLoader())
```

本次只读核对现有构件得到：

- 主 APK：`55291acf…279ca`，包含 ORT 两个 ARM64 `.so`。
- 测试 APK：`7f381627…affb7`，包含 `libopencv_java5.so`、`libc++_shared.so`。
- 两个现有合并 manifest 都是 `extractNativeLibs=false`；这些 ARM64 库在 APK 内未压缩。

因此，仅增加 `test.nativeLibraryDir` 仍不充分。建议宿主：

- 继续先验证固定 `com.kandong.modelprobe.test` 和签名，再加载任何测试代码。
- instrumentation 已能解析 Surface 时复用其加载器；独立启动使用固定包的安装路径创建 loader。
- native 搜索路径包含 `test.nativeLibraryDir` 及匹配当前进程 ABI 的 `test.sourceDir!/lib/<abi>`；同时核对 OpenCV 与 `libc++_shared.so` 条目。路径不得来自 Intent。
- 保持 parent-first：Kotlin、`OrtProbeEngine`、`ai.onnxruntime.*` 及 ORT JNI 由宿主拥有；OpenCV Java／JNI 由测试包加载器拥有。
- 进程内缓存加载器，不缓存 Activity／Surface。重复创建 loader 可能导致相同 JNI 库被不同加载器重复打开。
- 检测已加载测试包被替换时要求重启实验进程，不在同进程切换 native loader。
- 添加固定 Surface 协议版本或新构造器签名；同签名不代表旧测试包具有兼容接口。
- 传入资源用途的测试包 Context：fixture／detector 从测试包 assets 读取，recognizer／dictionary 从宿主 assets 读取。独立执行路径不得依赖 `InstrumentationRegistry`。
- `OpenCVLoader.initLocal()`、ORT 初始化、模型加载全部在后台；捕获加载失败并显示明确失败，不能回退到合成候选。

这是基于源码及构件的故障分析，**本轮未实际验证冷启动 JNI 加载**。instrumentation、冷启动、同进程 Activity 重建需要分别验证。

**有界文件设计**

路径均相对项目根目录；“新增”为建议文件。

| 文件 | 本阶段职责 |
|---|---|
| `modelprobe/src/testShared/java/com/kandong/modelprobe/RegionOcrBridge.kt`（新增） | 纯 Kotlin 请求身份、原子取消、运行槽、不可变交付、后台控制器及可注入 Runner／dispatcher／clock 接口 |
| `modelprobe/src/testShared/java/com/kandong/modelprobe/RegionVisualSession.kt` | 保留主线程 UI 状态；参数化页面尺寸；接入 backend；立即失效、显示时钟检查、几何复用 |
| `modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageVisualOcrRunner.kt`（新增） | 从现有 Probe 提取每页原生执行／认证／清理后 claim；不包含报告评分或 instrumentation 初始化 |
| `modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageOcrFixtures.kt` | 共用认证解码、只读 `PreparedPage`、同像素 Image 填充 |
| `modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualLabSurface.kt` | 默认真实 OCR 模式；显式“合成手势回归”模式；像素绘制、动态尺寸、处理中／失败／过期状态 |
| `modelprobe/src/debug/java/com/kandong/modelprobe/RegionVisualLabActivity.java` | 安装包／签名／协议验证，资源 Context、缓存 loader、APK native 搜索路径 |
| `modelprobe/src/contextTest/java/com/kandong/modelprobe/RegionOcrBridgeTest.kt`（新增） | 确定性并发与交付回归 |
| `modelprobe/src/contextTest/java/com/kandong/modelprobe/RegionVisualSessionTest.kt` | 增加实际页面尺寸及异步 backend 场景；保留合成行为测试 |
| `modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageVisualOcrProbeTest.kt`（新增） | 新 Runner 的实际四页、像素一致、清理、原始候选无损验证 |
| `modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualOcrUiTest.kt`（新增） | 明确点击、后台响应、实际候选、几何和生命周期接线 |
| `modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualLabUiTest.kt` | 明确选择合成模式再执行原手势用例；保留历史证据含义 |
| `TASKS.md`、`WORKLOG.md`、`docs/OCR_REGION_VISUAL_LAB.md` | 分列新结果、合成回归和未验收边界 |

不需要改变核心 pipeline／publication／controller 算法、Gradle 依赖或产品模块。`RegionVisualFixture` 继续只服务显式合成模式；真实模式不可调用它。

**建议固定四页**

| page ID | 固定识别模型 | 覆盖目的 |
|---|---|---|
| `en-normal` | `latin` | 普通英语整页，顶部／中部／底部与框外上下文 |
| `fr-seam` | `latin` | 法语重音、金额以及条带接缝 |
| `hans-normal` | `ch` | 简体中文独立记录 |
| `hant-seam` | `ch` | 繁体中文、跨段候选和原有冲突场景 |

模型映射由认证 fixture 的声明语言预先固定，不根据 OCR 文本、预期答案或历史匹配率选择。每页只跑这一个识别器；不增加混排筛选或双模型择优。

实际空白输入继续由 `FullPageRegionProbeTest` 的 `blank` 覆盖；合成空字符串／冲突继续在独立合成模式验证。新四页若没有产生某类候选，不能人为补入。所有原始文本、空串、非核心候选、来源四角、分数、空间组及不确定原因均保留；空间组不称作语义理解。

**先写失败测试的切口**

采用现有 JUnit，注入可手动推进的 worker／UI dispatcher、假时钟、可暂停的 Runner。无需增加测试框架。

| 场景 | 必须证明 |
|---|---|
| 尚未点击；仅换页、恢复、移框、缩放 | 原生调用数为零 |
| A 结果已排队，B 已完成，再送达 A | A 不能显示，也不能清除 B |
| A 的失败／取消回调晚于 B | B 状态不变 |
| 推理停在检查点，逐一执行页面／收起／菜单／pause／stop／dispose | UI 当场清空；后台继续后结果被拒绝 |
| 忙碌状态连续点击 100 次 | 只有一个实际任务，零待执行 OCR |
| 取消后立即新点击，再在清理后点击 | 前者拒绝；后者才建立新请求 |
| acquisition=100、TTL=60,000；延迟交付 | 60,099 可显示，60,100 拒绝；不从交付时间计时 |
| 过期后时钟回退、恢复、旧 ticker 执行 | 旧结果不复活，旧 ticker 不撤销新请求 |
| 首次有效显示后 move／resize／zoom／pan／viewport | 同一 page／metadata，原生计数不增加 |
| zoom 与 pan 改变 | ROI 成员不变；mirror 可见项允许变化 |
| 无候选、空串候选、冲突组 | 三者状态不同，全部原始内容保留 |
| 模型创建、处理回调、Image 关闭、session 关闭、线程恢复失败 | 零发布；清理不确定时禁止后续原生请求 |
| Activity 重建时旧 JNI 尚未返回 | 无并发第二个原生任务、无 Activity 被后台持有 |
| PNG／像素／模型／字典被替换 | 验证失败，无合成回退 |
| 框位于新页面边角 | 24dp 角标、48dp 触摸区、独立宽高与倍率保持 |

原生测试应比较“本次 pipeline 输出 → 本次 UI evidence”的全部字段，而不是只断言识别文字等于参考答案。像素测试应比较 `PreparedPage` 与送入 Image 的实际 RGBA，包括白色间隔及 placement。

**验收命令与边界**

以下供后续实施阶段执行，本轮未运行。

先对新增行为取得真实红灯，再运行完整现有本机检查：

```sh
cd /Users/haoyuzuo/Projects/KanDong

JAVA_HOME=/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home \
  ./gradlew --offline :modelprobe:testDebugUnitTest \
  --tests com.kandong.modelprobe.RegionOcrBridgeTest \
  --tests com.kandong.modelprobe.RegionVisualSessionTest
```

```sh
JAVA_HOME=/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home \
  ./gradlew --offline :modelprobe:testDebugUnitTest \
  :modelprobe:assembleDebug :modelprobe:assembleDebugAndroidTest \
  :modelprobe:lintDebug

shasum -a 256 \
  modelprobe/build/outputs/apk/debug/modelprobe-debug.apk \
  modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk
```

仅由后续执行者核验专用模拟器身份：

```sh
adb -s emulator-5582 emu avd name
adb -s emulator-5582 shell getprop ro.build.version.sdk
adb -s emulator-5582 shell getprop ro.product.cpu.abi
adb -s emulator-5582 shell getconf PAGE_SIZE
```

要求分别为 `KanDong_OCR_API37_16K`、`37`、`arm64-v8a`、`16384`。安装两包后核对安装 APK SHA 与本次本地构件一致，再运行：

```sh
adb -s emulator-5582 shell am instrument -w -r \
  -e class com.kandong.modelprobe.RegionOcrBridgeTest,com.kandong.modelprobe.RegionVisualSessionTest,com.kandong.modelprobe.FullPageVisualOcrProbeTest,com.kandong.modelprobe.RegionVisualOcrUiTest,com.kandong.modelprobe.RegionVisualLabUiTest,com.kandong.modelprobe.FullPageRegionControllerTest,com.kandong.modelprobe.FullPageRegionProbeTest,com.kandong.modelprobe.FullPageOcrPublicationTest,com.kandong.modelprobe.FullPageOcrLifecycleProbeTest \
  com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner
```

另行验证无 instrumentation 的冷启动：

```sh
adb -s emulator-5582 shell am force-stop com.kandong.modelprobe
adb -s emulator-5582 shell am start -W \
  -n com.kandong.modelprobe/.RegionVisualLabActivity
```

随后在自有固定实验页明确点击 OCR，验证真实模型调用、四页结果、同进程重新打开／旋转及缺少测试包时的失败提示。仅 `am start` 成功不能算冷启动 OCR 通过，也不能用 instrumentation 预加载后的成功替代它。

交付证据应分开记录：构建／JVM、合成手势回归、实际固定页 OCR、独立冷启动。此阶段不构成识别质量、翻译、真实屏幕采集或手机兼容验收。