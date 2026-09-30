# KanDong交接记录

更新于2026-09-30，本轮起点559dad4。实际整页OCR已接上独立按需翻译契约；256＋76本机测试、专用模拟器最终同包81项通过。测试进程与专用模拟器已停止，没有后台自动任务。

## 当前边界

唯一目录/Users/haoyuzuo/Projects/KanDong。先读AGENTS.md、TASKS.md及[本轮说明](OCR_TRANSLATION_BINDING.md)。本地开发和清晰提交已授权，不推送、部署或发布。不读无关项目、真实手机页面或密钥，不自动调用翻译服务。

正式app／compat／graphics、v0.1.0手势、modelprobe旧main源码及依赖均未改。ocrlab共享契约新增单模型证据来源，旧双模型规则保持；其余新适配／协调器仅在modelprobe测试源集。独立“看懂选区实验”当前仍是实际OCR与原图，没有新增翻译按钮，也没有真实译文。

## 本轮完成

- 原页面身份先预留，再创建显式点击票据，之后才获取图片／推理。PreparedCapture单次消费且绑定原runner，旧run(spec)接口保持可用。原资源清理、Guard和桥接取消路径保留。
- 全部原候选、重复／冲突／空白、原始四角／分数／模型／字典和空间关系不可变保留；整页上下文与ROI目标分开。语义组保持为空，覆盖状态未验证；斜向证据不确定，空页／退化／超预算明确拒绝，不截断上下文。
- 复用OnDemandTranslation请求与回复契约，英语／法语只验证显式绑定标记，中文在本机保留，不暴露供应商请求。页面来自已认证固定素材，语言按素材声明，不是新增自动识别。
- 六字段版本、原取得时间／TTL和持续单调时钟受检查；移框／倍率只改显示，菜单／暂停／停止／换页／过期丢弃当前文字和迟到结果。旧回调先核对请求对象，不污染新时钟。结构正确不能证明译文含义正确。
- AO三步骤完成；根任务把重复空间算法改为复用原规则，并新增实际四页接线测试。单模型3红灯后通过；独立审查P2过期首次点击吞掉，补测红灯后修复。两只读阶段指纹未变，根任务验证最终修复。
- 最终256 modelprobe＋76 ocrlab JVM、离线构建通过；Lint0错误，分别4／30警告。专用API37／ARM64／16KiB同包81项37.382秒通过，包含实际四页OCR与原界面回归；早期2项、修复前80项单独保留。详见[汇总](evidence/ocr-translation-binding/2026-09-30/summary.json)。

## 下一步直接执行

1. 核对Git状态与当前源码，不重做环境初始化、四页OCR可视化或契约接线。
2. 在独立固定页面实验加入显式翻译入口、等待／失败／切回原文状态及源元素对应卡片，接上本轮协调器。一次点击先新建整页OCR请求，不能把上一次OCR截图重新计时；恢复、移动红框或倍率变化不自动读图。保留现有手势和取消路径。
3. 先用本项目专用模拟器完成可取消的界面接线；测试占位结果必须显式标注。然后单独推进供应商实际响应与译文质量，不能把绑定测试标记或原图作为真实翻译。已有DeepL合成质量失败证据继续保留，不重新打开混排模型筛选。
4. 实时页面采集／当前帧见证／敏感过滤、联网会话同意／供应商披露／后端凭据、正式镜面接入分别验收。不把密钥放进APK，不自动上传真实屏幕。

## 构建与检查

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。设置JAVA_HOME后执行./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:assembleDebug :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug :ocrlab:testDebugUnitTest :ocrlab:assembleDebug :ocrlab:lintDebug。

主实验APK214b503f34ef14aa020773bd5ead66e4e51e7909c1a5dea34003f8ef1b2b3ed4；最终测试APK004d296600b6e53dff91ed32f645be04f1cc5d706392590e13a666bfbcc6b449。已安装到当前停止的专用模拟器，两包安装哈希一致。可视化入口仍为com.kandong.modelprobe/.RegionVisualLabActivity，显示固定页面OCR；此次契约通过instrumentation接线，没有新翻译界面。

本轮可重跑入口为docs/evidence/ocr-translation-binding/2026-09-30/run-checks.py，按build／install／tests顺序，使用新的输出目录；设备测试类名单见emulator/summary.json。严格校验专用AVD／API37／ARM64／16KiB和当前安装包SHA，不覆盖历史证据或复用旧包验收声明。

AVD KanDong_OCR_API37_16K位于项目忽略目录.local/avd，emulator-5582，详见[模拟器说明](OCR_EMULATOR.md)。只使用本项目模拟器。旧可视化独立冷启动与四页截图见[上一阶段](OCR_REGION_REAL_LAB.md)，不是本轮新增冷启动测试。

## 手机边界

最近验证目标为nova 9／NAM-LX9、Android12/API31，详见[设备记录](TEST_DEVICES.md)。本轮没有查询、操作或更新手机；当前连接／安装状态未知，不把旧记录写成当前观察。恢复手机测试前核对身份，系统安装／共享确认由用户完成；旧LIO-AN00兼容要求与历史证据保留。
