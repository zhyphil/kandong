# 本机语言候选与块级路由实验

2026-09-24；基线 de41932。结论：技术链路可运行、输出可复现；**v1 单串语言规则未通过混排质量验收，不能接入正式翻译。**

## 范围与先行冻结

只将 `com.google.mlkit:language-id:17.0.6` 加入 modelprobe 的 androidTest；app、compat、ocrlab 均未引入新依赖。本轮输入是38条项目自编字符串，无真实页面、截图、OCR调用或翻译。先在 de41932 提交[样本](fixtures/language-routing-v1.json)和[规则](LANGUAGE_ROUTING_PLAN.md)，再执行模型，未根据结果修改阈值、答案或添加短语例外。样本SHA256为 `733f883e4e974f110b201ceb9307d648f53bc14b2e6b46719a5a097eb33d6e7c`。

所有语言候选和原始分数完整保留；原文不改写。纯金额/时间/标点保留；短词、未知、弱证据、OCR冲突和汉字/Latin混合提示待核对；否则按预先固定的0.90最高分与0.20差距判断。EN/FR仅产生实验翻译路由意向，ZH保留原文，其他语言待核对。此规则不负责切分混合语言。

## 逐组结果

最终专用 API37 / ARM64 / 16KiB 模拟器两轮，各38条真实模型推理，所有候选分数和决策逐项相同（仅排除耗时）。

| 组 | 数量 | 符合预设行为 | 具体结果 |
|---|---:|---:|---|
| 英语完整句 | 6 | 6 | en，进入候选翻译 |
| 法语完整句 | 6 | 6 | fr，进入候选翻译 |
| 中文完整句 | 6 | 6 | zh，原文保留；简繁各3 |
| 短词 | 8 | 8 | 全部待核对 |
| 金额/时间/标点 | 4 | 4 | 原样保留 |
| 同串混排 | 4 | 2 | 汉英混排2条拒判；英法混排2条错误自动路由 |
| 其他语言 | 4 | 4 | 日/韩/俄/西语均待核对 |

合计36/38符合本组预设行为，其中14条是正确拒判，不能把36/38说成语言识别准确率。自动路由24条中22条符合预期，2条错误，**质量门槛失败**：

- `Annuler booking now`：en=0.9672821164，另有da=0.0110608330；错误当作单一英语。
- `No refund après départ`：fr=0.9932991266；错误当作单一法语。

高分并不能证明单串没有混排。官方接口提供整串语言候选，明确不在一串内识别多种语言；该结果与能力边界一致。[官方API说明](https://developers.google.com/ml-kit/language/identification/android)。不能靠提高阈值或固定本轮短语特例宣布问题解决。

## 工程检查与限制

- 新增6项路由边界单测，modelprobe共88项通过；加上本轮上一阶段未变的ocrlab49项，共137项。构建/Lint通过，modelprobe为0错误、6条已有实验界面提示。
- 最终两轮每轮15项技术/状态测试：路由6、主动点击控制器8、实际模型探针1。探针始终另存 `technicalPassed` 与 `qualityAccepted`；本轮后者为false。15项技术测试通过不是语言质量通过。
- 两轮客户端各创建/关闭1次，无运行错误；原始字符串和候选全部归档。计时仅为模拟器观察，不是手机性能验收。
- 主APK保持 `7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea`；292项既有资产/原生库逐文件不变。测试包新增固定样本、随包TFLite模型、4个ABI的语言引擎。最终测试SHA `833f4c4c02122ae5180f0179be0bfa34de0447f17bf463abbc2b7b32c1e5fcf9`，大小214388351字节；模型/引擎/SDK AAR与POM指纹详见证据。不把测试包体积当产品包体积。
- 签名和ZIP的16KiB对齐通过；新增64位原生库LOAD段对齐16384，32位库4096。实际仅ARM64/API37运行，不代表其他ABI、旧API36故障修复或华为验收。
- 最终合并Manifest无INTERNET、ACCESS_NETWORK_STATE、REORDER_TASKS，保留原测试入口移除规则。安装后再次核对测试/目标两包无网络权限。SDK本机推理与可能的运行指标收集分开描述；没有做全设备流量审计，也不据此宣称所有SDK行为均无遥测。[SDK条款](https://developers.google.com/ml-kit/terms)。只使用合成输入。
- 首轮初始化失败：instrumentation包context的applicationContext为null，SDK下游GoogleApi拒绝。保留失败报告后，仅为测试包添加稳定ContextWrapper应用上下文，并通过公开 `MlKit.initialize` 初始化；未修改模型或绕过许可/保护。它是测试环境适配，不是正式产品初始化方式。[公开初始化API](https://developers.google.com/android/reference/com/google/mlkit/common/MlKit)、[ContextWrapper](https://developer.android.com/reference/android/content/ContextWrapper)。
- 最终审查恢复了原Manifest的入口/权限限制并用最终包重新跑两轮；早期通过不替代最终证据。没有重跑完整图片OCR回归，旧模型/几何/资产未变，本轮仅针对语言与点击契约。

## 重跑

在已启动的 `KanDong_OCR_API37_16K`（emulator-5582）安装本记录指纹的modelprobe主/测试包后：

```sh
python3 -B scripts/run-language-emulator.py --out /private/tmp/kandong-language-new-run
python3 -B docs/evidence/language-routing/2026-09-24/audit.py
```

输出目录必须全新。入口拒绝其他设备、AVD、API、页大小或包指纹；不会寻找真机，不读取真实页面。运行器退出成功只说明技术验证结束，仍必须读 `qualityAccepted`。修改代码重新构建后须重新审查/固定包身份，不能关闭身份检查。

## 后续任务

语言任务保持开放。下一轮先设计混排/短词与整屏上下文证据如何共同表达不确定性，保留块级原文、其他卡片/同屏不同语言边界；不能把整页最高分强套到所有选区。先冻结新的独立混排/短标签样本，再比较候选方案；本轮38条只作已知回归，不能再次称新盲测。未通过前只保留候选证据，不把本实验的translate决策接入正式产品。

用户要求的“按翻译才处理当前整屏、仅在放大区显示对应译文”已在[独立交互实验](ON_DEMAND_TRANSLATION.md)验收；其中中文仍为人工演示。真实整屏采集/失效检测、自动分组、语言决策、实际翻译及正式镜面接入仍需各自验证。

原始报告、首次失败、单测XML、最终构建、权限/依赖/身份与独立审计：[证据目录](evidence/language-routing/2026-09-24/acceptance-summary.json)。无真机操作、推送或发布。
