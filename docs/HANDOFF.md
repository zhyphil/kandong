# KanDong交接记录

更新于2026-09-30，本轮起点9aecfd8。整页OCR证据→选区／镜面投影实验已完成，202项本机测试、专用模拟器94项通过，独立复核无必须修复问题。下一项是独立可视化实验页。没有后台任务或定时运行，专用模拟器已停止。

## 当前边界

唯一目录/Users/haoyuzuo/Projects/KanDong，先读AGENTS.md、TASKS.md及[本轮说明](OCR_REGION_CONTEXT.md)。本地开发／提交已授权；不推送、部署或发布。不读取其他项目或真实手机页面，不接DeepL／密钥／联网。app/compat/graphics未修改，v0.1.0手势保持。

## 本轮已完成

- 仅6份modelprobe测试源码：Publication单次许可、原管线转交方法、控制器、投影器、24项共享行为测试和1项两页原生测试。许可沿用原Guard、metadata、取得时间和TTL，资源关闭后才能交出；旧回调不能撤销新页，失效不能重新激活。
- 整页候选的原文／来源／两套四角／分数／模型／空间组无损保留；选区只选择ID并投影镜面位置，保留框外上下文及冲突。移框、倍率和平移不重新读取或推理。斜框／乱序四角明确是保守包围矩形，无面积的斜线也保留为不支持定位。
- 原9项有效行为红灯全部保留，根任务补充斜线1项红灯后修复；最终202 JVM全部通过，离线构建成功，Lint0错误4既有警告。独立只读复核最终6份源码与两APK哈希，无必须修复问题。
- 专用API37／16KiB模拟器94项通过76.218秒。固定hant-seam与blank实际Image及模型新链路通过，16候选（含稅／税冲突）逐字段保留，8检测／16识别、2帧／4会话／56ORT关闭，线程恢复。原取得时间后60005ms实际撤下空白页；原生计数确认变换不重复推理。旧发布／关联／图像／原生生命周期回归包含在94项中。
- [证据入口](evidence/ocr-region-context/2026-09-30/summary.json)包含有效红灯、最终JUnit／构建／Lint、模拟器结果、独立几何核查、审查及构件身份。先前整页交接的nova 9两批各10页已通过，旧过期失败保持历史记录；本轮没有手机安装或测试，不能把旧真机结果算作新路径验收。

## 下一步直接执行

1. 先检查当前Git状态和任务板；不要重复新机接入、20页矩阵或已完成的投影底层。新nova 9目前保留上一阶段测试包，正式compat未安装。
2. 实施独立可视化实验页：固定整页、可自由调整红框、镜面对应位置及原文／冲突提示。接本轮控制器验证显式点击、同页移框／倍率／平移复用及页面／收起／菜单／停止／过期撤下；先使用专用模拟器。开始前明确实验入口和测试源集边界，不直接接正式放大镜。
3. 本轮输入仍是空间组，不是语义组；旧CandidateContext／validOcr契约不放宽。之后再推进真实语义与翻译对应、真实采集及正式镜面，各有独立验收。原文或预置译文演示不等于真实翻译。

真实内容变化必须由后续观察层更新版本或显式清理，同版本observe幂等。控制器只丢弃自己持有的引用，调用方须丢弃旧Frame；不能宣称擦除所有快照或堆内存。第三方树／窗口归属、敏感过滤、当前帧见证、复杂翻译质量、后端凭据和正式镜面接入仍待验收。

## 设备和构建

当前测试机为用户新换华为nova 9／NAM-LX9，Android12/API31、EMUI13、ARM64／4KiB、1080×2340／480dpi；新机真实共享和正式手势未验收。旧华为LIO-AN00/API31保留历史兼容要求与旧证据，不能沿用其设备序列。详见[设备记录](TEST_DEVICES.md)。

主实验APK SHA：7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea，未变。当前本机构建和专用模拟器测试APK：8b76c71d36e55bf885eaeaa6d90c581637cea53706644ead86f39f4687d78298。手机最后核验测试APK仍为8eb4858bc13b035f1009d60238c8c62323a11b17d27ac0618bb706e0e3c10706；本轮未再查手机状态。主包不是正式放大镜包。

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。设置JAVA_HOME后运行./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:assembleDebug :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug。

专用AVD KanDong_OCR_API37_16K位于项目忽略目录.local/avd，emulator-5582、API37／16KiB；[启动说明](OCR_EMULATOR.md)。本轮入口见证据目录run-emulator.py，核对新测试SHA并指定94项和全新输出目录；原生过期测试需真实等待60秒。不得使用其他项目模拟器，不跳过旧冻结入口的APK／设备限制。历史audit-reports.py会写输入目录，复查先复制，不修改冻结证据。
