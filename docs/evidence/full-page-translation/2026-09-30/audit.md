> 🏛️ **Software Architect** | 步骤 1/1 | 268.6s

---

建议交付一个完整但受限的里程碑：**固定四页的真实宿主供应商响应、逐项质量门禁，以及当前独立 UI 的明确 RECORDED 回放**。预计 **23 次翻译调用，7 个实现文件**；无需修改正式 `app/compat/graphics`、OCR 管线或共享契约。

本次仅只读审计。已核实 `AO_ORCHESTRATED=1`、干净 `main@64a367d6a100c1056633f320bf39dd05aca69b0d`；未联网、读取凭据、操作设备、构建、运行测试或修改文件。预算为一个专职审计，无委派。

**1. 当前接缝与选择**

现有能力足以承接此交付：

- [FullPageTranslationProbe.kt](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageTranslationProbe.kt:11) 已保留当前请求、整页证据、原取得时间和失效守卫，但供应商固定为 `BINDING_TEST_ONLY_NO_TRANSLATION_MODEL`。
- [RegionVisualDemoReplies.kt](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualDemoReplies.kt:31) 已有可注入、可取消的异步回复接口。
- 共享契约已有 `RECORDED_DEEPL / LOCAL_DATE_RULE / SOURCE` 和完整响应绑定检查，可以直接复用。
- [prepare-translation-display-fixture.py](/Users/haoyuzuo/Projects/KanDong/scripts/prepare-translation-display-fixture.py:10) 固定导出旧 `p303/p106/p304/p007/p008`，**不能直接用于本轮 OCR 页面**。

| 选择 | 收益 | 代价 |
|---|---|---|
| **推荐：宿主实测＋质量门禁＋独立 UI 回放** | 用户能检查真实供应商候选与当前 OCR 卡片对应，完成此次推进 | 需要严格的跨运行证据匹配，预计七个实现文件 |
| 有界后备：仅完成宿主实测与可验证回放包 | 能独立交付真实响应、质量结果和不可误用的输入身份 | Android 集成明确未完成；下一项就是同一回放包的匹配器与 UI 接线 |

如果实现发现必须修改第八个实现文件或共享生命周期契约，采用后备里程碑，不降低身份检查来凑文件数。

**2. 精确输入与冻结证据**

页面来自 [FullPageVisualOcrRunner.PAGES](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageVisualOcrRunner.kt:30)，全部为 `1176×2400`：

| 页面 | 识别模型 | authored 行数 | 归档 OCR 候选数 | 按当前守卫静态核算的可请求数 |
|---|---|---:|---:|---:|
| `en-normal` | latin | 8 | 8 | 4 |
| `fr-seam` | latin | 15 | 20 | 10 |
| `hans-normal` | ch | 8 | 9 | 7，仅保留原文 |
| `hant-seam` | ch | 12 | 16 | 7，仅保留原文 |

这里的“可请求”是代码规则核算，不是本次执行测试结果，也不代表 OCR 正确。

原始页面定义为：

`docs/fixtures/full-page-ocr-v1/manifest.json`

SHA-256：

`e95fa1bac48e0a32272a65cf4b85679705597b2bbe3ca69997f038fdf26345f1`

实际 PNG 位于 `docs/fixtures/trilingual-holdout-v1/`：

- `en-checkin-24.png`、`en-tickets-32.png`
- `fr-departure-24.png`、`fr-price-32.png`
- `hans-order-24.png`、`hans-luggage-32.png`
- `hant-order-24.png`、`hant-luggage-32.png`

逐 PNG 字节数、文件 SHA、解码 RGBA SHA、放置坐标均已有 manifest 声明和 [加载验证](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageOcrFixtures.kt:24)，保持不变。

建议选择以下归档作为唯一 OCR 输入基准，目录为：

`docs/evidence/ocr-association-handoff/2026-09-30/emulator-final/`

| 文件 | 本次读取计算的 SHA-256 |
|---|---|
| `full-page-ocr-latin-en-normal.json` | `8e1cb6dec61edb7777344b6c10f345ebca95600cb5dabca091392f0c4e1e03c4` |
| `full-page-ocr-latin-fr-seam.json` | `ed265e5a3926265908a7776e105803d8f38ec9d06abb9cd7b676da219218a724` |
| `full-page-ocr-ch-hans-normal.json` | `79d7e4f9bebac6af68e6f63ad8325e8510ea995ca749400e2fa161a1b6a81c79` |
| `full-page-ocr-ch-hant-seam.json` | `81e98f2a0778574ec6b846a0e9515c9a2c7384fdbec1805bcce77e0262262b2b` |

对应 nova 9 归档位于 `docs/evidence/ocr-association-handoff/2026-09-30-nova9-awake/{latin,ch}/`。本次读取比较发现，上述四页候选除历史 `id/version` 外字段相同。**这不证明未来新运行相同**；Android 每次仍须核对完整实际输出。

当前 `translation-visual/2026-09-30/standalone/*-counters.json` 是资源计数证据，不可替代完整候选文件。

**3. 两条输入轨道与调用预算**

先冻结输入清单和独立 rubric，再允许根任务调用。

- `AUTHORED_SOURCE`：从 manifest 的原文行、placement 和 glyph 坐标派生完整页面。坐标明确标为 authored glyph bounds。保留所有重复位置。
- `RECORDED_OCR_SOURCE`：逐字段保留上述归档的全部 53 个候选及关联证据。不给 OCR 原文纠错、拼句、去重，也不从 authored 轨道补 context。
- 两轨道的请求、返回、评分和分母分开；authored 返回永不回填 OCR 卡片。

建议计划 **23 个独立目标，每个一次调用**：

| 轨道 | 目标选择 | 调用数 |
|---|---|---:|
| authored EN | `p0` 两行、`p1` 两行 | 4 |
| authored FR | `p0` 两行、`p1` 三行 | 5 |
| OCR EN | 原始候选序号 `1,2,3,6` | 4 |
| OCR FR | 原始候选序号 `1,3,5,6,8,12,15,16,18,20` | 10 |
| Hans/Hant | 保留精确 OCR 原文 | 0 |

序号仅便于审核；实际清单保存完整原始 ID。相同文字的不同候选仍各有独立响应，不共享或扩展译文。authored 未选位置仍进入全文 context，明确属于未抽测目标。

每次请求：

- 单个目标 `text`；显式 `EN/FR → ZH-HANS`。
- `context` 为该轨道**整页全部原文**，包括 ROI 外内容。
- authored 使用固定 placement/行顺序；OCR 使用现有 adapter 的候选顺序，不冒称语义阅读顺序。
- context 不包含译文、rubric、预期答案、纠错文字或推测场景。金额保护只包装目标 `text`，context 保持纯原文。

复用当前 `DeepLClient`、额度检查、32 KiB 上限、30 秒超时、官方端点和失败即停逻辑。计划最多 23 次翻译＋前后两次 usage，即正常完成 **25 个 HTTP 请求**；不自动重试、续跑或增补第二轮。调用前冻结精确源字符上限，包含 XML 包装，额度不足则零翻译调用。

原始成功响应须在绑定检查之前保存；失败、超时和可能已消耗额度分别记录。密钥读取只由根任务在显式执行入口触发，默认预演及导出均不读取凭据。

**4. 身份匹配与失败关闭**

归档响应不能直接使用旧 Android block ID：[当前 ID](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageTranslationAdapter.kt:57) 包含会话、版本和取得时间，每次运行会改变。

建议保留两层身份：

1. **不可变录制身份**：输入文件 SHA、完整历史候选 ID、模型/字典/检测器身份、完整请求 SHA、原始响应 SHA、检查版本、rubric/review SHA、供应商及录制时间。
2. **当前请求身份**：当前真实收据、六维版本、原 `acquiredAt/validatedAt/TTL`、当前 `ProbeRequest` 对象和当前目标 binding。

只允许显式排除跨运行必变的历史运行 ID、版本、批次和计时字段；其余内容严格匹配，包括：

- 页面、语言、尺寸、模型 SHA、字典 SHA、词表；
- **所有候选**的数量、顺序、原文字节、strip/read/core、框索引、local/page quad、score、recognition shape、归属标记；
- 归一为明确候选键后的全部空间关联、冲突原因；
- 全页 context 及预定目标集合。

不能只比较当前 ROI、目标文字、序号或页面名。重复文字不得通过 `associateBy(text)` 合并。

匹配完成后，以当前 `sourceMap` 建立历史候选到当前 binding 的一一映射，创建当前 `FixtureResponse`；不从历史记录重建“新鲜”截图或延长 TTL。任一框外候选发生变化，也拒绝使用该页录制译文。

缺文件、未知 schema/provider、重复 ID、额外或缺失结果、哈希不符、字段变化均明确报错并撤下译文；不回退到旧手写译文或绑定标记。

**5. 内容风险与质量门禁**

已确认两个需要保留的例子：

- FR 候选 13 为 `Pnx totar": oz,oo e.`，与候选 10 的金额行构成冲突；它仍留在完整 OCR context 中，两者不作为当前可请求目标。
- Hans 有 `□，，，成` 残片；Hant 有 `税/稅` 差异及跨缝错误。中文保留原文不等于中文识别正确。

区分以下状态：

| 状态 | 处理 |
|---|---|
| 结构非法、身份未知或证据不匹配 | 不发送／不加载；整页失败关闭 |
| 已知 OCR 冲突、截断、空串或几何不确定 | 保留原候选和全文上下文；沿用现有目标禁用规则 |
| context 含这些问题 | 页面显示“识别上下文存在未核对内容”；不宣称整页理解通过 |
| 事实检查拒绝 | `KEEP_ORIGINAL / CHECK_UNVERIFIED` |
| 检查通过但语义未复核、失败或待定 | 保留原文，不导出可展示候选 |
| 精确输入的开发复核通过 | 可导出 `CANDIDATE / RECORDED_DEEPL`，仍保持 `semanticVerified=false` |

复用 [金额与日期处理](/Users/haoyuzuo/Projects/KanDong/scripts/translation_protected_facts.py:103) 和 [关键检查](/Users/haoyuzuo/Projects/KanDong/scripts/translation_critical_checks.py:127)。本轮四页没有完整月份日期标签，因此**不会新增本地日期转换实测证据**；日期规则仅跑既有离线回归。

rubric 必须分别记录：源文本质量、context 缺陷、供应商语义、规则结果、最终可展示状态。最低检查项为：

- `16:30`、`07:45`、`07:15`、站台 `6` 不混淆；
- 早餐“不包含”、禁止乘坐对应列车的否定关系不反转；
- 两张票的总价 `€27.40` 不变成人均价格；`82,60 €` 原始金额保留；
- `before Monday` 的截止关系不反转；
- `Aucun remboursement après` 与框外 `confirmation.` 的条件不被丢弃或改为无条件规则；
- 无无据补充、删漏或自动修复 OCR。

现有规则没有完整覆盖早餐否定、星期截止及此处分行退款条件；其中退款条件还会被保守拒绝。正确但被规则拒绝的结果单列“可用性缺口”，不按 case 放行。

报告分别给出供应商完成数、源正确/错误数、语义通过/失败/待定数、规则拒绝数和 UI 可展示覆盖。开发复核不是外部盲测；HTTP 成功和事实检查通过均不能替代语义质量。

**6. 七个实现文件的所有权计划**

路径缩写：`TS = modelprobe/src/testShared/java/com/kandong/modelprobe/`；`AT = modelprobe/src/androidTest/java/com/kandong/modelprobe/`。

| 所有者 | 文件 | 限定职责 |
|---|---|---|
| 宿主实现 | 新增 `scripts/translation_full_page_recorded.py` | 固定输入预演、有界执行、响应绑定、已有检查调用、独立 review 验证、离线导出；复用现有 HTTP 客户端 |
| Android 契约实现 | `TS/FullPageTranslationProbe.kt` | 注入 `TranslationProviderChoice`，保留原默认值和所有生命周期逻辑 |
| Android 契约实现 | `TS/RegionVisualTranslationController.kt` | 传递供应商身份；不改状态机语义 |
| Android 契约实现 | 新增 `TS/RecordedFullPageTranslation.kt` | 纯数据验证、完整证据匹配、一一映射、生成既有 `FixtureResponse` |
| Android 接线 | `AT/RegionVisualDemoReplies.kt` | 有界读取固定 asset、解析录制包、复用可取消异步调度；保留 marker 测试替身 |
| Android 接线 | `AT/RegionVisualOcrBackend.kt` | 安装录制适配器及一致 provider/version；保持当前原生槽位和迟到回复隔离 |
| Android 接线 | `AT/RegionVisualLabSurface.kt` | 显示 RECORDED 来源、候选/拒译/context 警示及准确等待/失败文案 |

录制执行使用 `TranslationMode.LOCAL`，因为 Android 只读取本地记录；model 标识采用明确的 `RECORDED_DEEPL_FULL_PAGE_V1`，version 绑定完整回放包 SHA。不将其标成当前云调用或本地翻译模型。

host 通过小型请求/响应适配接口隔离 DeepL，Android 继续消费 `FixtureResponse`。无需服务器、新服务或新共享架构。旧导出脚本保持不变，仅复用其 outcome 约定。

数据、测试及记录另计，不能隐含在七文件预算内：

- `docs/fixtures/full-page-translation-v1/{inputs,rubric}.json`
- 新证据目录 `docs/evidence/full-page-translation/2026-09-30/`，保存冻结计划、哈希、响应、review、审计及失败记录。
- `modelprobe/src/androidTest/assets/full-page-translation-recorded-v1.json`，只装载 OCR 轨道及来源元数据。
- 同步 `TASKS.md`、`WORKLOG.md`、交接及本次结果说明。

**7. 验证与完成标准**

沿用 unittest/JUnit/现有仪器测试，不新增基础设施。

- **宿主**：验证两轨道全文不串用、框外内容完整、23 次上限、中文零调用、重复目标不合并；额度不足和首个错误零重试；金额 XML 异常拒绝；未审查或证据错配无法导出。
- **纯契约**：真实四页归档匹配；修改任一框外文字、geometry、score、model、关联或 context 即拒绝。只改变合法当前会话身份可重新绑定，但旧回调不能影响新请求。
- **生命周期**：沿用实际取得时间的 60 秒期限；移框/倍率/平移不读取或请求；取消、换页、菜单、收起、后台、旋转清理；成功和失败迟到回复均隔离；供应商身份变化撤销旧工作。
- **UI**：EN/FR 实际录制候选及精确原文对应；缺失/拒绝结果有明确原因；Hans/Hant 无回复调度；显著显示“已录制 DeepL 响应，本次未联网”；不能只断言 placeholder 消失。
- **根任务验证**：相称 Python/JVM 回归、离线构建/Lint、APK 无新增网络权限或凭据、专用模拟器重点 UI 与原手势回归。缺依赖属于环境阻塞。

此里程碑成立的条件是：真实响应可追溯、质量结论如实记录、错误不能进入录制卡片、当前 OCR 与录制输入严格相符。若没有候选通过门禁，应交付失败分析和保留原文结果；不能靠占位译文宣称完成真实翻译接入。
