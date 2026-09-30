> 🏛️ **Software Architect** | 步骤 1/1 | 225.5s

---

建议下一项采用**显式启用的 grouped 模式**，保留 v1 录制与 marker 默认回归路径。`SemanticGroup.PHRASE` 足够承载双行目标；主要缺口在分组资格、录制身份和 UI 单来源假设。

已确认 HEAD 为 `37eb9a7`，工作区干净。此次仅阅读文件和解析归档；未写入、构建、测试、联网或操作模拟器。

**1. 实际几何发现**

已核对 `docs/evidence/ocr-association-handoff/2026-09-30/emulator-final/` 下两份 latin 归档，SHA 与宿主固定值一致。以下为 `pageQuad` 外包矩形：

| fr-seam 位置 | 退款前半句候选／范围 | confirmation 候选／范围 | 判断 |
|---|---|---|---|
| 顶部 | `s0/c3-r3-f3`：`[67,274,488,312]` | `s0/c2-r2-f2`：`[70,323,258,357]` | 间隔11px，左缘差3px；可作为唯一相邻关系候选 |
| 中部 | `s1/c1-r1-f1`：`[68,1164,488,1203]`；`s2/c1-r1-f1`：`[67,1163,490,1203]` | `s1/c0-r0-f0`：`[69,1214,257,1247]`；`s2/c0-r0-f0`：`[69,1215,258,1249]` | 两个前半句、两个尾部重叠；不是四个独立句子 |
| 底部 | `s3/c1-r1-f1`：`[67,2223,488,2262]` | `s3/c0-r0-f0`：`[67,2271,260,2310]` | 间隔9px，左缘一致；可作为唯一相邻关系候选 |

关键限制：

- 三处前半句全部被关联器标为 `UNCERTAIN / NON_AXIS_ALIGNED`，适配后为 `AMBIGUOUS`。仅添加语义组仍会被现有引擎拒绝请求。
- 中部前半句之间和尾部之间分别存在 `STRONG` 边；前者仍为几何不确定，后者为 `UNAMBIGUOUS_PAIR`。不能据此挑最高分或 `ownsCoreCenter` 候选。
- 中部价格还包含 `DIFFERENT_RAW / POSSIBLE_CLIP` 冲突及乱码，必须继续保留。
- en-normal 没有退款跨行句，适合作为阅读顺序和“不误合并”对照。当前首段顺序确实是“取消→金额→早餐→入住”，与坐标阅读顺序相反。

**2. 最小决策与取舍**

| 方案 | 收益 | 代价 |
|---|---|---|
| 严格沿用现有 `KNOWN` 资格 | 最小风险 | 当前归档中零个完整退款组可翻译，只能完成拒绝展示 |
| grouped 模式增加独立、保守的轻微倾斜资格判定 | 可验证顶部、底部完整句 | 需冻结几何边界与反例；不代表 OCR 质量通过 |

推荐第二种，但**只在已确认的双行组内部使用新资格判定**，不放宽所有单行候选，不修改原关联器、原始坐标或诊断。

建议冻结的初始实验规则：

- 仅支持 EN/FR、近水平、同列、唯一相邻双行；以前半句语法未完成、下一行补全 `confirmation` 为条件。
- 左缘差不超过较小行高的 `0.5`，垂直间隔为 `0…0.75×较小行高`，行高比不超过 `1.5`；倾斜边界另以四边形验证，不能只看外包矩形。
- 只允许原诊断为空，或**唯一诊断为 `NON_AXIS_ALIGNED` 且通过冻结的近水平判定**。冲突、裁剪、多匹配、同段重叠继续拒绝。
- 中部四候选组成显式 `ambiguous=true` 的关系组，保留全部成员，整组不调用、不展示候选译文。相同文字在不同页面位置保持独立。
- 排序建立独立的派生 `order`；所有原始候选与原关联结果保持逐字段不变。重叠行的稳定排序只是确定性序列，不能充当语义消歧。

这些阈值是待反例验证的实验规则，不是已验收的通用阅读顺序算法。

**3. 最小接线**

现有 [ContextEngine](/Users/haoyuzuo/Projects/KanDong/ocrlab/src/contextShared/java/com/kandong/ocrlab/context/ContextEngine.kt:84) 已支持命中任一成员后选择完整 PHRASE、按组缓存和单卡展示，无需修改 `ocrlab`。

建议文件范围：

| 文件 | 改动 |
|---|---|
| 新增 `modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageSemanticLayout.kt` | 纯计算阅读顺序、双行组、歧义原因和规则版本 |
| `…/FullPageTranslationAdapter.kt` | 默认 individual；grouped 时派生 `order/groupId/groups`，保留完整 `originalEvidence/sourceMap` |
| `…/FullPageTranslationProbe.kt`、`…/RegionVisualTranslationController.kt` | 传递不可变模式；提供目标→全部来源成员查询，保持现有时钟、收据和请求所有权 |
| 新增 `…/RecordedGroupedFullPageTranslation.kt` | 独立录制身份与按组绑定；不修改 v1 匹配规则 |
| `modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualOcrBackend.kt`、`RegionVisualDemoReplies.kt`、`RegionVisualLabSurface.kt` | opt-in 模式、独立 asset 调度、显示一组多个来源编号 |
| 新增 `scripts/translation_full_page_grouped.py`、`scripts/translation_grouped_checks.py` | 派生整页输入、完整句请求、有限条件检查及独立导出 |
| 新增 grouped fixtures、录制 asset、证据目录 | 与 v1 分开冻结；同步 `TASKS.md`、`WORKLOG.md`、`docs/HANDOFF.md` |

当前 [UI 第254行](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/RegionVisualLabSurface.kt:254) 用 `sourceMap[card.targetId]`，组 ID 会导致卡片被直接跳过，必须改为查询全部成员。不要把组 ID 映射成一个“代表候选”。

新录制身份应绑定：

- 完整 v1 原始证据指纹；
- 分组规则版本、阅读顺序、全部组成员及歧义状态；
- 实际发送的完整整页 context；
- 每个目标有序来源键、原文拼接方式及响应身份。

供应商只返回完整句译文，不提供位置。任一整页或派生结构错配，拒绝整页回放，不降级为 marker。模式切换清理旧任务，等待新点击；保持原取得时间和60秒 TTL。

**4. 条件检查必须先冻结**

[现有检查](/Users/haoyuzuo/Projects/KanDong/scripts/translation_critical_checks.py:156) 对退款否定只支持少量条件；不支持 confirmation，正向 refundable 也没有完整时间关系核验。

新检查只识别有限的完整语法，比较 **事件＝confirmation、关系＝before/after、极性＝可退/不可退**，不生成或修补答案。至少冻结：

- EN/FR × BEFORE/AFTER × 正向/负向，共8个源条件；
- 对每项提供正确、关系反转、极性反转、条件遗漏、事件替换的译文反例；
- `only/unless/except`、额外分句、断尾、双重否定、矛盾中文均拒绝，除非另有明确支持与测试。

为保留 v1 可重导出性，建议不修改旧宿主检查模块：其源码 SHA 已进入旧运行身份。新模块只能在完整语法及目标条件均验证成功时，明确替代旧的 confirmation 条件“不支持”结果；不能普遍删除拒绝原因。

星期截止仍没有通用语义门禁，本阶段不能将它宣称为已解决。

**5. 必要行为测试**

复用现有 JUnit／unittest／instrumentation，不增加基础设施：

- 新 `FullPageSemanticLayoutTest.kt`：真实归档顺序、顶部/底部配对、中部四成员歧义；大间隔、跨列、竞争邻居、裁剪、重叠、倾斜边界和 ID 改名反例。
- 新 `RecordedGroupedFullPageTranslationTest.kt`：Python/Kotlin 派生字段一致；修改框外文字、坐标、分数、成员、顺序或规则版本即拒绝；错组响应不能串位。
- 新 `test_translation_full_page_grouped.py`、`test_translation_grouped_checks.py`：上述条件矩阵、完整句 text＋整页 context、源错误拒绝导出、原稿不能填 OCR、首错停止及零重试。
- 新 `GroupedRegionVisualUiTest.kt`：只框住尾部仍显示完整组卡；中部重复组保留原文；原始全部候选仍可核对；移框/缩放不重读；取消、菜单、后台、过期和迟到回调清理。
- 保留现有 v1/marker、中文零调度、整页匹配和原始 TTL 回归。对最终录制包断言实际审核结果，不预设供应商一定通过。

**6. 根任务的有界调用计划**

先冻结输入、反例、几何规则、评分表、源码 SHA 和确切预算，再执行：

- 最多8次独立合成条件矩阵调用；
- 若几何测试确认资格，再加顶部、底部两个归档 OCR 完整组，各1次；
- 最多10次翻译＋前后各1次 usage，建议源字符上限1000；中文和中部歧义组零调用；
- 沿用现有客户端限制、2秒间隔、首错停止、无重试／续跑；旧逐行结果直接离线比较；
- 逐项审核完整条件及源质量后导出，继续标记 `semanticVerified=false`、`qualityAccepted=false`。

预算仍为**审计→一次有界实施→一次独立审查**。若轻微倾斜资格不能通过反例验证，应交付明确拒绝结果，不能把仅原稿调用成功算成归档 OCR 分组展示完成。