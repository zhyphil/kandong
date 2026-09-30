# KanDong交接记录

更新于2026-09-30，本轮起点5fa799a。独立“看懂选区实验”已完成，217本机检查、专用模拟器59项通过（含7界面检查与真实60秒过期），最终包独立冷启动、横屏／旋转清理和缺配套包提示通过。下一项是实际整页OCR接实验界面。没有后台任务或自动运行，专用模拟器已停止。

## 当前边界

唯一目录/Users/haoyuzuo/Projects/KanDong。先读AGENTS.md、TASKS.md及[可视化实验说明](OCR_REGION_VISUAL_LAB.md)。本地开发／提交已授权，不推送、部署或发布。不读其他项目或真实手机页面，不接DeepL／密钥／联网。正式app／compat／graphics／ocrlab及v0.1.0手势未改。

## 本轮已完成

- Java debug薄宿主搭配固定同签名test APK Surface，普通启动通过父加载器提供Kotlin运行时；在instrumentation中复用已有加载器。无需新模块／依赖／权限；缺包明确错误，不自动安装或切换。主实验APK改变源于debug入口，旧main源码不变。
- 固定繁体中文页／空白页，7合成候选，完整保留框外条件、稅／税冲突、空字符串。显式展示才生成合成上下文；原Controller／Publication／Guard不改，显示期限从合成会话开始，不冒充真实屏幕或模型来源。
- 概览直接拖红框、右上24dp箭头／48dp触摸区独立调宽高，贴边触摸区完整向内保留；1～5倍滑杆默认2、镜面双指同步、单指平移。镜面绘制固定原图及对应候选框，卡片列出全部原文且冲突优先，不选取正确答案。收起／菜单／换页／后台／停止／60秒过期清空结果，恢复保留几何但不自动装载。
- 9有效行为红灯→15新状态测试，最终217 JVM／两包构建／Lint0错误4既有警告；根任务边缘UI红灯复现整体移动后修正。首轮59项58通过，唯一失败为测试要求最小高度再缩小。独立复核确认，根任务先拉高再检验原断言，最终59全部通过70.856秒。
- 最终包在专用API37／16KiB模拟器独立冷启动和真实点击成功；竖屏／横屏／菜单截图检查、旋转不自动展示、缺包错误与重新安装同SHA通过。手势测试为自身View上的MotionEvent；不是人手真机或任意OEM验收。证据与原失败在[evidence/region-visual-lab/2026-09-30](evidence/region-visual-lab/2026-09-30/summary.json)。

## 下一步直接执行

1. 检查Git状态和当前源码，不重复已完成的模拟器环境、20页质量矩阵或可视化样例实现。
2. 将已经验收的实际整页OCR管线接入此独立实验界面，以固定单一EN／FR／ZH页面验证显式点击→整页实际推理→选区对应与框外上下文保留。先明确后台推理与主线程Controller所有权、取消／迟到结果边界，避免把同步模型调用塞进界面线程。
3. 先用专用模拟器。保留每个候选、来源、坐标和冲突，不能按预期答案修正；不重新为混排挑模型。真实翻译对应、实时采集／敏感过滤／当前帧见证、后端凭据与正式镜面分别验收。样例原图和合成收据不能冒充新OCR或翻译结果。

控制器清理自身引用，调用方丢弃旧Frame；不声称擦除全部快照或堆内存。当前纯空间组仍不是语义理解，历史识别／翻译质量问题仍保留。

## 构建与运行

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。设置JAVA_HOME后运行./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:assembleDebug :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug。

本轮主实验APK：55291acf66cc2bdb5d7b40ffd0bc368bcf441ce61f33f9ffa3b0f173e7b279ca；测试APK：7f3816273ada270057f8a58dfcab85622b98bb3733c9ff5bebecf63cce6affb7。专用模拟器已安装配套两包；打开“看懂选区实验”或显式组件com.kandong.modelprobe/.RegionVisualLabActivity。详细命令、截图与复验入口见本轮说明。旧冻结入口固定旧APK，不跳过身份限制。

AVD KanDong_OCR_API37_16K位于项目忽略目录.local/avd，emulator-5582、API37／ARM64／16KiB，[启动说明](OCR_EMULATOR.md)；不得使用其他项目模拟器。历史audit-reports.py会写输入目录，复查先复制，不改冻结证据。

## 手机边界

当前目标nova 9／NAM-LX9、Android12/API31，详见[设备记录](TEST_DEVICES.md)。本轮没有操作或更新手机；上次核验主包仍7f9ff255…、测试包8eb4858…，整页OCR两批各10页是前阶段技术证据。正式compat未安装，新机共享／手势／性能未验收；恢复操作先核对身份，系统安装／共享确认由用户完成。旧LIO-AN00兼容要求及历史记录保留，不沿用其序列或结论。
