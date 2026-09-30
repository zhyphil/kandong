# KanDong交接记录

更新于2026-10-01，起点1199609。本轮完成新整页布局/完整条件边界评估，发现4页7个不完整请求目标，**扩大分组范围的门槛未通过**。说明见[边界评估](SEMANTIC_CONTEXT_EVALUATION.md)，最终观察/评分/指纹见[证据汇总](evidence/semantic-context-eval/2026-10-01/summary.json)。上一项有限分组与录制卡片见[历史实现](OCR_SEMANTIC_GROUPS.md)，不改写其证据。

## 当前边界

唯一目录/Users/haoyuzuo/Projects/KanDong。先读AGENTS.md、TASKS.md。本轮只新增modelprobe宿主JVM测试、评分脚本和固定候选语料；正式app/compat/graphics/ocrlab、独立实验UI、既有分组算法/录制资产及依赖都未改。没有手机/模拟器操作、新OCR推理、供应商调用、凭据读取或真实屏幕采集。评估结果不是DeepL实际误译或手机体验结果。

用户要求每项任务相称验证后以Conventional Commits提交、推送当前分支，不需重复确认；核对origin与远端分歧，正常push后核对哈希，不强推或改写历史。此约定不包含部署/Release/上架。当前分支main，origin为git@github.com:zhyphil/kandong.git。

## 本轮已核实

- 首次执行前冻结24张新编写单语布局、93候选及独立完整关系预期；开发者知道规则，不是外部盲测或新OCR质量集。生成Kotlin只含输入，不带oracle；实际运行现有空间关联→分组→适配→请求组件。原字段、完整上下文及反向输入排序稳定性通过。
- 10个源分组完整目标、5个完整组保守不请求；前置否定/前提、后置例外/限制4页产生7个不完整目标。评分工具返回2，明确semanticGatePassed=false。完整context没有丢失，但不保证独立目标/卡片能保留条件关系。本轮不调规则使语料通过。
- 历史fr-seam20候选另行回放：四成员歧义未消除，尾行同文配对、句头原非轴向诊断保留；未删除重复或生成新可用目标。
- 8宿主检查通过；内存移除“不完整目标”检查时2项按预期失败，证明评分会检出缺陷。281 JVM、Lint0错误/0致命/4既有警告通过。没有重新组装APK、设备测试或独立代理审查。
- 初次定向JUnit XML被完整回归覆盖而未归档；首次report仅为初次评分记录。最终observations.xml与final-evaluation/report.json哈希匹配，作为权威证据。输入、协议、旧算法/录制资产冻结哈希未变。

## 当前下一项

1. 从当前Git、TASKS与本轮summary恢复。不要重复已完成供应商调用；复核本轮评分可直接使用保存的observations.xml，退出2表示预期的质量门槛失败。
2. 优先设计独立完整条件守卫：遇到前提/否定/例外/限制，不能可靠绑定时整组及相关碎片保留原文；不得仅让两行主句通过。旧v1/录制证据保留，新路径的版本、来源指纹与回放匹配需要单独验证。
3. 本轮4个失败作回归，新增首次执行前固定的反例、不同位置/距离和无关邻句对照；分别报告错误放行、正确完整组及保守遗漏，不能靠零请求或按ID补答案制造通过。完成性可靠后再研究重复观察共同来源，不挑最高分赢家。
4. 联网会话同意/后端凭据、真实采集/遮挡/敏感过滤、正式镜面及真机仍分别验收。固定实验录制包不是手机实时翻译功能；一般阅读顺序、译文质量和长辈体验仍未通过。

## 环境与历史构件

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。维持现有依赖及离线构建。宿主复现命令见本轮说明。

上一轮主实验APK为214b503f34ef14aa020773bd5ead66e4e51e7909c1a5dea34003f8ef1b2b3ed4，测试APK为2cc737a634c650ba0abf8caefe7de7bc2d64bc6d2538b369f3ef1f1fd50981d5。本轮未组装或核验设备上的包，不声称仍安装这些版本。分组资产26c7246a2536fc82881af84955a4f4680e418ec891812b2e89837170b8799ea6本轮已核对不变。入口com.kandong.modelprobe/.RegionVisualLabActivity，需同签名主/测试包。

专用AVD KanDong_OCR_API37_16K，项目.local/avd，emulator-5582，API37/ARM64/16KiB。本轮未启动/查询模拟器；停止状态仅有上一轮历史记录。最近真机历史目标nova 9/NAM-LX9/API31；恢复前重新核对身份，系统安装/共享由用户确认，不能继承本轮宿主测试为设备结论。
