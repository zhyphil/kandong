# KanDong 交接记录

更新于2026-09-30。用户已恢复工作，本轮完成跨段关联技术验收；下一项为实际整屏OCR结果与关联器接线。没有安排后台定时运行。

设备切换补充：已识别当前测试机为华为nova 9（NAM-LX9），Android12/API31，序列2AS0221B09001601。两实验包安装哈希一致，20关联＋6实际Image合成色块检查通过。当前未安装正式放大镜，未验证新机OCR推理或共享／手势；旧华为仅保留兼容目标与历史证据。[设备详情](TEST_DEVICES.md)。

## 目录与边界

唯一目录 /Users/haoyuzuo/Projects/KanDong，远端 git@github.com:zhyphil/kandong.git。先读AGENTS.md和TASKS.md，不读取其他项目。仅本地开发／提交获授权，没有推送、发布、部署授权；华为安装／共享由用户确认。

v0.1.0交互固定，本轮未改app/compat/graphics。正式手机放大镜未接翻译；未读取真实屏幕、调用模型／DeepL、访问密钥或联网服务。

## 完成情况

- 9月25日整屏Image→分段OCR：144 JVM、模拟器39项；华为USB中断后恢复20页／生命周期／故障报告，另补36项短回归，无华为39项完整控制台汇总。[历史记录](FULL_PAGE_OCR_PROBE.md)。核心中心过滤会删正确繁体候选，已拒绝。
- 本轮测试关联器保留全部候选，按空间关联，文本与几何不确定性分开；取消／缺段／身份异常整页拒绝。20类冻结反例，165 JVM、离线构建、Lint0错误4既有警告、模拟器20项通过。
- 两设备历史40组／472候选全部字段保留，含40空串／92非核心；388组／84关系，24组仅代表无歧义逐字相同。两轮字节一致，“稅／税”冲突不选择；不是新增华为或OCR质量通过。
- 独立只读复核无必须修复问题。有效红灯未记录（缓存权限阻止，补跑与修改重叠），保留但不计验收；稳定源码下最终165项证据另存。

[本轮说明](FULL_PAGE_OCR_ASSOCIATION.md)；[证据／源码／APK哈希](evidence/full-page-association/2026-09-30/summary.json)。本地提交用git log核对，没有推送。

## 下一项

先查工作区，复用冻结规则与反例。将实际整屏OCR的条带完成记录、全部候选、模型身份与版本守卫接FullPageOcrAssociation。固定自有页验证成功、取消、过期、异常、缺段和发布前失效；不允许半页／迟到结果，不删冲突。之后才接整屏上下文与选区显示。

首版单一EN／FR／ZH，外语→中文，中文原文保留、简繁分记；点击翻译才获取当前可见整页，移框仅改同页显示。收起／菜单／停止丢弃文字与迟到结果。当前帧见证、第三方窗口／敏感过滤、联网同意／后端凭据、复杂语义质量、正式镜面仍需独立验收，不能按参考答案选OCR输出。

## 环境

- JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。
- 专用AVD KanDong_OCR_API37_16K，emulator-5582，API37／ARM64／16KiB。临时AVD消失，同一已有revision 9镜像重建到项目忽略目录.local/avd；[启动方法](OCR_EMULATOR.md)。不使用其他项目设备。
- 两APK已安装模拟器及新nova 9、哈希一致；主实验APK未变，测试APK为6ed22110a0b4dd21e338287a5f7309822cae1a2269bfeda559f18241304fe51f。新机API31／ARM64／4096字节页／1080×2340／480dpi，每次操作先核对序列和型号。旧华为LIO-AN00/API31、序列2KE0220109017133只作历史记录，冻结旧脚本可能限定其身份，不能跳过或冒用；新机本轮命令见设备证据。
- JAVA_HOME设上述JDK后，./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:assembleDebug :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug。同源Android类com.kandong.modelprobe.FullPageOcrAssociationTest。40组历史数据仅在JVM资源，不入APK。
- 旧冻结脚本可能锁旧APK哈希，不可静默当新包入口。历史audit-reports.py会写输入目录，复查先复制到新目录，不改冻结证据。
