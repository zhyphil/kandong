# KanDong交接记录

更新于2026-09-30，起点783ffde。本轮已实现实际整页OCR到空间关联器的交接，本机178项、专用模拟器72项通过；新nova 9完整亮屏复验尚未完成。没有后台任务或定时运行。

## 当前边界

唯一目录/Users/haoyuzuo/Projects/KanDong，先读AGENTS.md、TASKS.md及[本轮说明](OCR_ASSOCIATION_HANDOFF.md)。本地开发／提交已授权；不推送、部署或发布。不读取其他项目或真实手机页面，不接DeepL／密钥／联网。app/compat/graphics未修改，v0.1.0手势保持。

## 已完成及待验证

- 本轮5份测试源码：FullPageOcrPublication及13同源行为测试、FullPageOcrPipeline.runStaged、整页与原生生命周期运行器。真实收据及模型身份、同一持续守卫、正常退出与清理成功后单次发布；拒绝清空原文／质量／关联。不是正式采集或翻译。
- 有效红灯10项6失败，原断言保留；178 JVM全部通过，构建成功、Lint0错误4既有警告。独立只读复核无必须修复问题；随后仅加fullPageModel分批参数并重建／根任务核查。
- 专用API37／16KiB模拟器72项全部通过64.666秒，含20组整页实际推理47.711秒、9生命周期情形。236候选／194组／42关系，空串和冲突全部保留；与历史原字段完全相同，严格整页精确仍5/20（含两空白）。
- 新机nova 9已实际运行模型：初轮72项中71通过，20页等整批会话结束时超过有效期，早期6页正确拒绝；分批ch的10页也有4页过期，79.236秒，检查时手机Dozing。已询问用户解锁；未收到就绪答复，不擅自解锁。不能称新机完整验收通过。
- 两轮真机失败、模拟器成功、源文件／APK哈希、有效红灯及审查均保存在[evidence/ocr-association-handoff/2026-09-30](evidence/ocr-association-handoff/2026-09-30)。summary.json为入口。旧历史证据不改写。

## 下一步直接执行

1. 用户确认解锁后核对设备NAM-LX9、序列2AS0221B09001601、API31／4096字节页，以及两安装包哈希。保持自有模型实验页前台，两批分别运行fullPageModel=ch及latin、各10固定页；既有脚本run-model-batch.py接受--serial、--test-sha、--model、--output，全新输出目录。当前测试包已安装，哈希一致时无需重复安装；系统确认如出现仍由用户操作。
2. 若亮屏仍因慢批次过期，缩短诊断保留批次；不放宽60秒TTL、不改取得时间／模型／答案。保存失败，再验收。运行后用audit.py检查整页身份、所有候选／组成员和历史对照；两批都通过才补勾新机20页。
3. 随后接整页上下文和选区显示：已有CandidateContext依赖双模型共框、人工语义组，不能直接把跨条带空间组当语义组。保留整页证据／原版本／取得时间，点击才启动，同页移框只改变展示，倍率不重新读取，失效／收起／菜单／停止撤下。仍为独立合成实验。

真实第三方树／窗口归属、敏感过滤、当前帧见证、复杂翻译质量、后端凭据和正式镜面接入仍需各自验收。

## 设备和构建

当前测试机为用户新换华为nova 9／NAM-LX9，Android12/API31、EMUI13、ARM64／4KiB、1080×2340／480dpi；正式compat未安装，真实共享和手势未验收。旧华为LIO-AN00/API31保留历史兼容要求与旧证据，不能沿用其设备序列。详见[设备记录](TEST_DEVICES.md)。

主实验APK SHA：7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea。最终测试APK SHA：8eb4858bc13b035f1009d60238c8c62323a11b17d27ac0618bb706e0e3c10706，已安装新手机及专用模拟器。主包不是正式放大镜包。

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。设置JAVA_HOME后运行./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:assembleDebug :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug。本轮JVM证据覆盖未变的纯测试源码，不因仅换消息重复全套。

专用AVD KanDong_OCR_API37_16K位于项目忽略目录.local/avd，emulator-5582、API37／16KiB；[启动说明](OCR_EMULATOR.md)。不得使用其他项目模拟器。旧冻结入口有旧APK／旧设备限制，不跳过身份检查；历史audit-reports.py会写输入目录，复查先复制，不修改冻结证据。
