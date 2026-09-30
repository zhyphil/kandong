# KanDong交接记录

更新于2026-10-01，起点701e2b9。本轮实现独立条件范围保守守卫并完成本机构建/回归；说明见[原文保护](CONDITION_GUARD.md)，[证据汇总](evidence/condition-guard/2026-10-01/summary.json)。拦住已知半句，但整页拒译会误拦，未默认采用，也未通过完整翻译质量验收。

## 当前边界

唯一目录/Users/haoyuzuo/Projects/KanDong。先读AGENTS.md、TASKS.md。本轮只改modelprobe测试链路、宿主JVM测试和固定候选语料/证据。正式app/compat/graphics/ocrlab、独立实验UI、旧v1分组算法/录制资产及依赖未改。无设备查询/操作、新OCR/供应商调用、凭据读取或真实屏幕采集。两实验包已构建，但未安装。

用户要求每项任务相称验证后以Conventional Commits提交、推送当前分支，无需重复确认；核对origin与远端分歧，正常push后核对哈希，不强推/改写历史或自动创建Release。当前main，origin为git@github.com:zhyphil/kandong.git。

## 本轮已核实

- 新 `FullPageConditionGuard` 是有限词汇风险检测，不是通用语义判断。EN/FR退款页若有额外条件提示，整页原文保留，避免拆行修饰碎片泄漏。默认 `conditionGuard=false`；仅显式与grouped模式一起启用。中文原文保留。没有把开关加入实验UI或正式产品。
- 旧24页/93候选回归：7不完整目标→0、10完整目标保留，保守完整组5→9，附带5普通目标被阻断。新18页/72候选：0不完整、4完整、14保守，附带16普通目标被阻断；其中2客服句与退款无关，是明确误拦。
- 历史fr-seam列车否定句也导致整页保留原文，原2合格双行组→0。20候选/context仍保留，但可用性退步。因此qualityAccepted、semanticVerified、defaultAdoption均false。
- 新模式的页面/块/组身份及布局版本独立。旧有效录制在新模式被拒绝；没有分组时页面身份仍隔离。原字段、诊断、全部上下文、原取得时间/60秒、取消/菜单/收起/页面变化和迟到回复隔离经检查保留。
- 6新行为测试先4项失败，首修后5/6，修正“历史页无风险”的测试前提后6/6；再补请求身份红灯/修复和历史误拦检查。最终288 JVM全过、两APK构建成功、Lint0错误/0致命/4既有警告；Receiver参数名既有编译警告保留。最终7项守卫XML为权威观察，分批失败不合并为一次全绿。
- 冻结的新JSON/预期、旧v1规则/语料/资产SHA未变。`scripts/condition_guard_fixtures.py` 默认只核验两个生成文件，显式--write才重建。原v1评分仍4页7不完整目标、返回2，失败记录保留。

## 当前下一项

1. 从当前Git、本轮summary与TASKS恢复。无需重复供应商调用或安装包。本轮可用保存的JVM观察复核数量；不要把源分组正确当作译文正确。
2. 先保留段落/修饰范围与多来源关系，缩小整页保护的误拦。新对照先冻结有关和无关修饰句，要求旧7失败保持阻断、普通内容恢复、未知关系继续原文保留。不能直接删除否定词以消除误拦，不能按ID修结果或靠零请求声称质量通过。
3. 旧v1及录制资产保留；新模式尚未接录制/界面。减少误拦后再验证新版本/来源指纹/卡片绑定，之后研究重复观察共同来源。联网同意/后端凭据、真实采集/遮挡/敏感过滤、正式镜面及真机继续独立验收。

## 环境与构件

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。维持现有依赖及离线构建。最终两包哈希见本轮summary，未安装设备；主实验入口com.kandong.modelprobe/.RegionVisualLabActivity需同签名主/测试包。

专用AVD KanDong_OCR_API37_16K，项目.local/avd，emulator-5582，API37/ARM64/16KiB。本轮未启动/查询模拟器。最近真机历史目标nova 9/NAM-LX9/API31；恢复前重新核对身份，系统安装/共享由用户确认，不能继承本机JVM结论为设备验收。
