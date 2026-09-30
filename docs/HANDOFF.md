# KanDong交接记录

更新于2026-09-30，本轮起点7e45a3c。固定整页实际OCR已接到独立“看懂选区实验”，236本机检查、专用模拟器7项专项及79项回归分两批通过；最终配套包独立冷启动、四页实际推理、旋转／同进程再次推理与缺包恢复完成。没有后台任务或自动运行，专用模拟器已停止。

## 当前边界

唯一目录/Users/haoyuzuo/Projects/KanDong。先读AGENTS.md、TASKS.md及[实际OCR接线说明](OCR_REGION_REAL_LAB.md)。本地开发和清晰提交已授权，不推送、部署或发布；不读无关项目、真实手机页面、密钥或调用翻译服务。正式app／compat／graphics／ocrlab、旧modelprobe main源码、v0.1.0手势及Gradle依赖均未改。

## 本轮完成

- 固定en-normal／fr-seam走latin，hans-normal／hant-seam走ch，按认证fixture声明语言固定模型；不是实际页面自动语言识别。点击后后台实际整页推理，完整原始候选、重复、冲突、空串及框外上下文保留。
- PreparedPage私有小图块只解码一次，PNG／RGBA验证后，同像素用于实际Image输入与概览／镜面。四页逐行RGBA对照通过；没有整页Bitmap或按参考答案重绘。
- 单原生工作线程／进程共享运行槽，最多一任务、零待运行OCR。原Controller／Guard／许可／资源留在后台，清理计数与线程恢复核实后才交出只读显示证据。主线程原时间／60秒TTL／请求身份再校验，几何不重新识别；清理不确定禁止同进程再运行。
- 菜单、收起、换页、后台、停止和销毁立即撤下结果／取消订阅；原生调用返回后清理，不硬中断JNI、不声称擦除所有堆副本。原手势模式明确作为合成回归，真实模式不失败回退。
- Java debug宿主固定同签名包、协议2、ARM64 ELF校验和缓存PathClassLoader，支持APK内原生库路径；Kotlin／ORT父加载器，OpenCV测试包加载器。测试包更换要求进程重启，缺包明确失败。独立路径不依赖InstrumentationRegistry。
- 根任务10桥接红灯，后增4边界、3会话、2候选比较测试，最终236 JVM通过，离线构建及Lint0错误4既有警告。首轮实际OCR误用Provenance对象身份比较而拒绝全部非空结果；独立复核P1与根任务定位一致。改为全字段／分数原始位比较，新增快照相等红灯后通过；原失败／主动停止记录保留。
- 最终模拟器专项7项31.306秒、回归79项150.332秒通过，不拼成一次86项。确定性测试在实际推理结束但Image／模型范围尚未关闭时重建Activity，验证运行槽保持、旧结果不恢复、清理后再次点击才成功。真实60秒过期与原手势保留。
- 最终配套包脱离instrumentation冷启动四页，分别8／20／9／16候选；同进程旋转后再次实际推理、全屏菜单、缺测试包提示与恢复原SHA通过。没有质量评分改进声明。证据见[evidence/region-real-ocr/2026-09-30](evidence/region-real-ocr/2026-09-30/summary.json)。AO实现步骤600秒超时，不是成功返回；根任务核对代码并完成剩余工作，两只读阶段指纹未变。

## 下一步直接执行

1. 检查Git状态和当前源码，不重复环境初始化、已完成20页矩阵或本轮可视化实现。
2. 将实际OCR整页证据接入既有按需翻译请求契约（ocrlab的OnDemandTranslation／TranslationProviderChoice）。完整上下文与选区目标分别绑定版本，明确不确定候选处理、源元素与返回译文对应、取消／过期／迟到结果；先验证供应商无关的本机请求／返回接线。
3. 先用专用模拟器。真实翻译调用、联网本次同意／供应商披露／后端凭据、实时采集／当前帧见证／敏感过滤和正式镜面分别验收；不要把预置译文、原图或固定页技术通过当产品能力，不重开混排模型筛选。当前空间组不是语义理解，旧OCR／翻译质量问题仍保留。

## 构建与运行

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。设置JAVA_HOME后执行./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:assembleDebug :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug。

主实验APK214b503f34ef14aa020773bd5ead66e4e51e7909c1a5dea34003f8ef1b2b3ed4，测试APKb6f66c56bcaa61258498f7ee32939dd1253db903a5e9d41c0723f1db2ade3ff8。专用模拟器已安装匹配两包；入口com.kandong.modelprobe/.RegionVisualLabActivity，点“整页 OCR”，换页后须重新点击；无需共享或悬浮权限。旧冻结脚本校验旧APK，不得跳过身份限制。本轮可重跑入口在证据目录run-checks.py／check-standalone.py，先核对源码与构件SHA；检查脚本创建新的输出目录，不能覆盖冻结证据。

AVD KanDong_OCR_API37_16K位于项目忽略目录.local/avd，emulator-5582、API37／ARM64／16KiB，见[启动说明](OCR_EMULATOR.md)。仅使用本项目模拟器。历史audit-reports.py会写输入目录，复查须先复制。

## 手机边界

当前目标nova 9／NAM-LX9、Android12/API31，详见[设备记录](TEST_DEVICES.md)。本轮未操作或更新手机；上次核验主包7f9ff255…、测试包8eb4858…是旧阶段证据，当前连接／安装状态未重新核实。正式compat未安装的旧记录不冒充当前观察，新机共享／手势／性能仍待验收。恢复操作先核对身份，系统安装／共享确认由用户完成；旧LIO-AN00兼容要求与历史记录保留。
