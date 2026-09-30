# KanDong交接记录

更新于2026-09-30，本轮起点bb63d2f。独立固定页面实验已增加“翻译演示”入口和可取消的等待／失败／原文对照／卡片显示。265＋76本机检查、初轮65回归、最终7项强化UI与独立冷启动／横屏通过，分批证据保留。自有进程和专用模拟器已停止。

## 当前边界

唯一目录/Users/haoyuzuo/Projects/KanDong。先读AGENTS.md、TASKS.md及[本轮说明](OCR_TRANSLATION_VISUAL_LAB.md)。已授权本地开发与提交；不推送、部署、发布，不读取无关项目。

本轮仅modelprobe测试源集：纯展示协调器、异步演示回复、原后端／界面／会话的小幅接线和测试。正式app／compat／graphics、旧main／debug宿主、ocrlab及依赖均未改。实验界面明确标注“本地绑定演示，非真实翻译”；没有真实屏幕采集、真实翻译API、密钥读取或手机更新。

## 完成与验证

- 一次点击预留版本→点击票据→实际固定图像／整页OCR→原资源清理和桥接→原收据交付→本机异步绑定回复。保留原validatedAt／acquiredAt／TTL，不重复推理或重新计时。
- 全部候选和框外上下文保留，卡片与源／镜面按原候选编号关联；不推断语义组，不消除冲突或选择答案。EN／FR为明显占位标记；简繁中文不调度回复、保留原文。
- 移框／倍率／平移／原文对照仅改变显示。原文对照是实验比较视图，不替代产品“结束翻译”清理语义。取消／菜单／收起／暂停／换页／销毁清除结果与排队回复；恢复不自动读取。旧回调先验对象身份，不改变新时钟或请求。
- 原生槽位在取消后仍等真实清理；不持有Activity。等待、错误、过期有独立状态，原始60秒期限实际测试，一次显式点击可重试。
- AO预算3步骤全部完成，两只读指纹不变。独立审查P2是测试搜索了不存在的括号标记；已加强为实际编号、原文和候选View检查。后续只改比较按钮文案／宽度及强化测试，没有重跑付费编排。
- modelprobe265＋ocrlab76 JVM，离线构建成功；Lint0错误，4／30个既有警告。初轮65项195.532秒含旧手势与契约；最终同包7项UI100.177秒，包括真实父级触摸、新采集与延迟／失败／取消隔离。两批不可合称一次72项。
- 最终包独立冷启动四页、旋转清空、同进程再次OCR、横屏滚动与资源计数通过，截图已检查。第一轮横屏脚本边缘起手离开App，前台保护停止，无崩溃输出；修正脚本起点后复验通过，失败保留。
- [证据](evidence/translation-visual/2026-09-30/summary.json)含8源码SHA、两包、审查、首次红灯、分批结果与清理。不是OCR质量、真实译文、长辈体验或多机型通过；大字体未验。

## 下一步直接执行

1. 核对Git、TASKS与当前代码，不重新做环境、整页OCR、契约或这次界面。
2. 推进可替换供应商的实际返回和语义验收，先读DEEPL_TRANSLATION.md、TRANSLATION_PROTECTED_FACTS.md、TRANSLATION_CRITICAL_CHECKS.md及现有合成调用工具；保留旧失败与有限本地日期规则边界。
3. 沿用纯原文全页context和逐元素绑定；先分清正确原文语义质量与OCR误差，再映射到独立卡片。核对既有合成调用授权、配额与发送范围，不把旧响应回放／本地绑定标记算作新调用或质量通过；不重开混排语言筛选。
4. 联网会话同意／披露／后端凭据、真实采集／遮挡／敏感过滤和正式镜面分别验收。禁止将开发密钥打进APK或自动上传真实屏幕。

## 环境与复跑

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。保持离线构建和现有依赖。

主APK214b503f34ef14aa020773bd5ead66e4e51e7909c1a5dea34003f8ef1b2b3ed4；测试44ab3d4e884a90e5812dbc9520c2b166eb9999568219714c3adaa5073c3d4907。两包已配对安装在当前停止的专用模拟器；入口com.kandong.modelprobe/.RegionVisualLabActivity。

专用AVD KanDong_OCR_API37_16K，项目.local/avd，emulator-5582，API37／ARM64／16KiB；不得使用其他项目模拟器。复跑脚本在docs/evidence/translation-visual/2026-09-30，run-checks.py按build／install／tests使用新输出目录；check-standalone.py核对独立启动。不可直接套用历史包的验收声明。

最近真机历史目标为nova 9／NAM-LX9/API31；本轮未查询其连接状态或操作手机，恢复前必须核对身份。系统安装／共享确认由用户完成；旧LIO兼容要求保留，见TEST_DEVICES.md。
