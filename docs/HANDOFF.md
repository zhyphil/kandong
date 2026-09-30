# KanDong交接记录

更新于2026-10-01，起点701e2b9。本轮实现独立条件范围保守守卫并完成本机构建/回归；说明见[原文保护](CONDITION_GUARD.md)，[证据汇总](evidence/condition-guard/2026-10-01/summary.json)。拦住已知半句，但整页拒译会误拦，未默认采用，也未通过完整翻译质量验收。

**最新用户纠偏（3f524d0之后）：用户质疑旁支实验偏离核心业务，询问能否直接真机测试。接下来优先交付真实页面翻译的完整可安装流程，暂停继续扩大条件/段落规则实验。** 已核对正式compat仍无翻译功能；实验界面是固定PNG的实际OCR＋录制译文回放，不是实时手机页面翻译。

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

1. 从当前Git、本轮summary与TASKS恢复。旧实验无需重复供应商调用或安装；新开发版完成后按实际需要安装验收。本轮可用保存的JVM观察复核数量；不要把源分组正确当作译文正确。
2. 优先贯通可在手机测试的真实流程：镜面点击翻译→当前整屏可见上下文采集/本机识别→用户选择的实际翻译路线→选区对应内容显示。当前缺的主要是这些已有组件的产品接线，不能再用孤立测试数量代表完成度。沿用核心手势；本机为默认，不静默上云。开发版可以明确标实验/未核对，不冒称质量已验收。
3. 必要权限披露、敏感过滤、取消清理和联网供应商/文字范围/留存/会话同意随接线实施；真实屏幕上传必须等用户明确同意，密钥不进入仓库或聊天。默认采集授权不能替代OCR/翻译同意。旧规则/录制资产及失败记录保留，不以段落或重复组实验作为真机接线的通用前置门槛。

## 环境与构件

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。维持现有依赖及离线构建。最终两包哈希见本轮summary，未安装设备；主实验入口com.kandong.modelprobe/.RegionVisualLabActivity需同签名主/测试包。

专用AVD KanDong_OCR_API37_16K，项目.local/avd，emulator-5582，API37/ARM64/16KiB。本轮未启动/查询模拟器。最近真机历史目标nova 9/NAM-LX9/API31；恢复前重新核对身份，系统安装/共享由用户确认，不能继承本机JVM结论为设备验收。

最新只读设备核对：2026-10-01，2AS0221B09001601在线，NAM-LX9/API31；仅检测到com.kandong.modelprobe及com.kandong.modelprobe.test，未安装compat。未打开App、安装、截图、采集或联网；没有把这次连接核验写成业务测试通过。
