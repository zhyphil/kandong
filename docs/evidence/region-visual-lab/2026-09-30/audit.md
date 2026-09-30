> 🏛️ **Software Architect** | 步骤 1/1 | 271.1s

---

建议采用 **debug 专用薄宿主 + 测试 APK 中的合成视觉实验**。选择小型固定页面，暂不引入历史 OCR 回放。预算限定为四步：打包入口、合成会话、视图交互、验证。

已确认 `AO_ORCHESTRATED=1`、HEAD 为 `5fa799a`，工作区干净。本次仅阅读源码和现有 APK；未构建、操作设备或联网。

**打包决策**

| 方案 | 收益 | 代价／问题 |
|---|---|---|
| 在 androidTest manifest 注册独立 Activity | 主 APK 可保持不变 | 当前测试 APK 不具备独立运行所需的 Kotlin 运行时；不能直接采用 |
| **debug 薄宿主加载测试包界面，推荐** | 不移动控制器、不重复编译共享类；可脱离 instrumentation 启动 | 必须安装配套测试 APK；主 debug APK 哈希改变 |

实际解析现有两 APK 的 DEX 后发现：

- 主 APK `7f9ff255…` 定义了 1,056 个 Kotlin 类。
- 测试 APK `8b76c71d…` **没有 Kotlin 类**；`Intrinsics`、`Function0`、`Unit` 均缺失。
- 测试 APK 含 `FullPageRegionController`，但 `ProbeInputs`、`OrtProbeEngine`、`RecognitionPacking` 等只在主 APK。

因此，“测试 APK 添加 launcher 后直接运行”目前只是未经满足的打包设想。现有 [sourceSets 配置](/Users/haoyuzuo/Projects/KanDong/modelprobe/build.gradle.kts:26) 解释了这个边界。

推荐宿主实现：

- 新增 Java debug Activity，只负责加载界面、转发生命周期和显示加载错误。
- 固定查询 `com.kandong.modelprobe.test`，检查与宿主签名一致。
- 正常独立启动时，用安装包 `sourceDir` 创建 `PathClassLoader`，父加载器为宿主加载器；Kotlin 运行时由父加载器提供。
- 通过公开的小接口交接 `View`、前台／后台／销毁回调。界面使用平台控件，不依赖测试包资源。
- instrumentation 下优先使用已有加载器可见的同一个界面类，避免加载第二份类型。
- 缺失或不匹配测试包时明确报错，不自动安装或切换实验。

这一方案仍需根任务做真实启动验证，不能把上述设计视为已验证实现。不要通过复制 Kotlin 类、移动整个 `testShared` 到 debug、放宽 Publication，解决打包问题。

**允许的最小文件范围**

新增文件建议：

| 文件 | 职责 |
|---|---|
| `modelprobe/src/debug/AndroidManifest.xml` | 独立实验 launcher、明确主题、测试包查询声明 |
| `modelprobe/src/debug/java/com/kandong/modelprobe/RegionVisualLabActivity.java` | 薄宿主与公开界面接口 |
| `modelprobe/src/testShared/java/com/kandong/modelprobe/RegionVisualFixture.kt` | 固定页面、合成候选、来源坐标 |
| `modelprobe/src/testShared/java/com/kandong/modelprobe/RegionVisualSession.kt` | 主线程会话状态、控制器接线、失效处理 |
| `modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualLabSurface.kt` | 页面、ROI、镜面、菜单和手势 |
| `modelprobe/src/contextTest/java/com/kandong/modelprobe/RegionVisualSessionTest.kt` | 纯状态／绑定回归 |
| `modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualLabUiTest.kt` | 实际 UI、生命周期和截图验证 |

另更新 `TASKS.md`、`WORKLOG.md`、交接说明及新证据目录。现有 androidTest manifest 中“从不操作 UI”的注释需限定为原 OCR 测试。[原注释位置](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/AndroidManifest.xml:10)

此路线预计无需修改 build、main manifest、ocrlab 源码或已验收的控制器／Publication／Association。

**输入与状态设计**

采用两个固定页面：文字页和空白页。文字页包含正常文字、重叠冲突、空字符串候选及框外上下文；总量控制在约八个候选内。

- 页面绘制和候选四角使用**同一份源坐标定义**，不把拼接后的 TextView 行位置冒充原位置。
- 例如 `1200×800` 的短页面、较大字号，可避免旧长页面缩成不可读缩略图。跨段冲突的位置必须落在实际 planner 的相邻段重叠区；同段重叠不会被 Association 合成跨段冲突组。[对应规则](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageOcrAssociation.kt:209)
- 始终显示“固定合成页面；无新 OCR／翻译；不代表当前屏幕”。
- 使用独立 `visual-synthetic/<UUID>` 标识。metadata 的时间表示本次合成上下文开始时间，TTL 表示实验展示期限。
- 可以沿用现有控制器测试构造合成 Publication 输入的方式，但模拟 receipts、模型字段绝不能被报告为本轮实际推理证据。[现有合成接线](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/contextTest/java/com/kandong/modelprobe/FullPageRegionControllerTest.kt:31)

最小接线：

`显式点击 → 新合成版本 → click → stage → claimForRegion → accept → render`

全部同步运行在主线程。无需 executor、假延迟、模型加载或原生资源。

状态只需要：当前版本、前台状态、展开／收起／菜单／停止、ROI、倍率、pan、装载次数、当前 Frame、失效原因及期限回调。

- 移框、改宽高、倍率、平移只调用 `render`，保持同一 page、metadata 和期限。
- 页面变化更新版本并清理。
- 菜单、收起、停止、后台、TTL 清理控制器及界面持有的 Frame、候选文字和标注。
- 关闭菜单恢复原展开／收起状态；收起恢复保留几何参数，但结果必须等待再次点击。
- `onPause` 即清理，`onStop/onDestroy` 幂等兜底；恢复前台不装载。
- 期限回调绑定当前会话世代，禁止旧回调清除新会话。

Publication 的原 Guard、单次许可和 TTL 规则保持原样。[许可边界](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageOcrPublication.kt:61)

若以后改为历史回放，必须另存不可变历史来源与本次合成生命周期，不能给历史候选换一个“当前”Guard。当前合同没有真实采集来源认证能力，这是产品接入边界，不应通过降低校验解决。

**视图与交互**

采用一个避开系统栏／刘海的根容器，内含固定页面、镜面及控件；菜单覆盖完整内容区。

- 页面等比显示并留白，不拉伸；原文保持可读。必要时让候选详情列表滚动，不压缩页面字号。
- ROI 内部及边框直接拖动；右上角 24dp 标记、至少 48dp 命中区，宽高独立调整。
- 倍率连续 1～5，首次进入默认 2；滑杆和镜面双指缩放共享唯一倍率值。
- 镜面单指平移；缩放不改变 ROI。
- 页面与镜面使用明确的统一显示比例 `s`：ROI 拖动量除以 `s`，镜面平移量除以 `s×zoom`，避免 dp、源像素、镜面像素混用。
- 所有控件至少 48dp；镜面位置可按 ROI 上下位置自动避让，不增加手动换边。
- 镜面位置绘制来源框／编号，旁边用可读字号列出编号对应的全部候选。冲突成员即使在 ROI 外也通过 `contextIds` 保留并说明位置，不挑最高分或“正确答案”。

分别显示：未触发、有效空白页、选区无候选、空字符串候选、冲突、结果失效。不能把这些状态都画成空镜面。

**根任务验证顺序**

1. **先验证打包。** 安装配套两包后，在未运行 instrumentation 的情况下直接启动实验 launcher；确认无缺类、无需悬浮窗权限。记录主 APK 新哈希，保留旧哈希为历史。
2. **新增有意义的红灯回归。** 覆盖显式触发、变换不重新装载／续期、全部冲突绑定、空白与失效区别、清理后界面不残留、旧期限回调不影响新结果。
3. **测试实际交互。** debug 宿主属于 target package，因此可使用 `startActivitySync`；用现有 Instrumentation／UiAutomation 完成真实拖动、改宽高、平移和双指缩放，不能只用 `performClick` 宣称手势通过。
4. **专用模拟器视觉验收。** 核对 `KanDong_OCR_API37_16K`、API37／16KiB及安装哈希；检查初始、冲突、空白、拖动后、1／2／5倍、全屏菜单、收起恢复和真实 TTL 到期截图。后台返回与进程重启后必须没有自动结果。运行原相关回归，但无需重做完整二十页 OCR 矩阵。

现有 `ProtectedTranslationLabActivity` 可参考菜单和清理结构；其重排整页文字、预设区域切换及延迟交付不适合作为此次空间视图实现。