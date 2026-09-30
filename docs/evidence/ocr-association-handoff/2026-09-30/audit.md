> 👁️ **Code Reviewer** | 步骤 1/1 | 178.8s

---

已核对基点 `783ffde`，工作区干净。建议保留现有 `run()` 和固定页 runner，增加测试专用适配层与延迟发布。现有候选保留、关联快照和资源清理机制可以复用。

本阶段设计控制在以下四项：

1. **从真实完成结果生成不可变收据。**  
   [Pipeline:60](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageOcrPipeline.kt:60) 的 `status=COMPLETE` 仅代表检测框处理完成，识别尚未结束。适配器必须先检查最终 `page.complete`，再核验：

   - 实际诊断覆盖计划全部条带，索引唯一，`read/core/detector` 与计划一致。
   - 每段 `boxes` 等于实际候选直方图；识别调用数等于候选总数；检测调用数等于条带数。
   - Image 关闭成功，运输与清理没有拒绝。

   随后才把实际诊断转换为不可变 `StripReceipt`。**不能遍历计划补造成功收据。** 空白页同样需要全部实际零候选条带记录。不完整结果传入非 `COMPLETE` 上游状态，并保留原始原因；现有 `CANCELLED_OR_STALE` 无法可靠拆分，不必猜测。

2. **身份绑定同一次验证与推理调用。**  
   新适配入口接收当前调用的页面 ID、`meta` 和已验证模型来源，不接受独立可选的 `PageIdentity` 封套。模型与字典 SHA 应来自 [ProbeInputs:29](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/main/java/com/kandong/modelprobe/ProbeInputs.kt:29) 验证清单所得、且实际用于加载字典与创建 session 的同一个 `ProbeModel`；检测器 SHA 对应实际验证的检测器字节。

   `ProbeModel` 本身是可构造数据类，不能仅凭类型视作认证。保持这条窄调用链即可，无需改生产模型接口。页面版本直接取同次 `meta.version`，空白页也不能从候选或邻页推导。

3. **一个生命周期守卫贯穿读取、推理、关联和发布。**  
   [Current:64](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/testShared/java/com/kandong/modelprobe/FullPageOcrContract.kt:64) 当前在 `run()` 内创建，不能在关联阶段重新创建后声称时间连续。

   最小方案是在 `testShared` 增加每页独立的守卫：保存原始 metadata、最后观测时间和永久失效状态；提供检查并返回 checkpoint 的方法。把同一方法传给现有 `run()`，关联与最终发布也使用这个守卫。取消、任一版本字段变化、TTL 到界、时间倒退或回调异常后，永不恢复有效。这样无需改变旧 `run()` 签名，也覆盖 `SingleFrameStripInput` 的检查。原生调用仍同步完成，只在边界拒绝结果。

4. **关联结果暂存，作用域正常退出后才发布。**  
   🔴 **不能只检查资源平衡。** [Runner:187](/Users/haoyuzuo/Projects/KanDong/modelprobe/src/androidTest/java/com/kandong/modelprobe/FullPageOcrProbeTest.kt:187) 的 session 清理判断可能在晚到回调抛错后仍为真；现有异常记录也不必然清空所有已完成页面。

   新协调器应同时要求：页面成功、所属 session 作用域正常返回、最终资源清理成功、同一守卫仍有效。任一条件失败，丢弃暂存的候选、关系和组；不要只把 `published` 改成 false 却保留数据。正常退出标志配合 `finally`，也能覆盖断言等异常退出而不吞掉异常。延迟发布期间消耗的 TTL 必须计入，不能重置时间来让测试通过。

避免复制 runner 的最佳方式是**直接扩展现有固定页测试**：同一次推理继续生成原字段，并暂存新增关联结果；最终清理后填入关联报告。提取一个统一的丢弃函数，供页面异常、session 异常和最终清理失败共用。保留旧报告字段含义，新增关联字段即可。

必须补齐的回归用例：

| 范围 | 必须验证 |
|---|---|
| 真实固定推理 | 复用现有两模型 × 十页面矩阵，覆盖 EN/FR/Hans/Hant、跨段页和 blank；组成员完整覆盖实际候选 ID，各候选全部字段原样保留，包括空串、非核心与冲突。 |
| 完成边界 | 检测诊断已 `COMPLETE`、随后识别失败；已有成功条带后缺段/失败。关联输出全部为空。 |
| 空白页认证 | 全部实际零候选收据成功；缺失、重复、失败或计数错误收据均拒绝；版本来自本页 metadata。 |
| 连续守卫 | OCR 最后时间为 150，关联首次为 149；TTL 恰好到界；六个版本字段分别变化；失效后恢复原 checkpoint 仍拒绝。 |
| 晚到异常 | 关联计算中、关联返回后、最终发布前回调失败，均无候选、关系或组外泄。 |
| 外层清理 | Image/运输清理失败、session close 不确定、线程恢复失败，以及**清理平衡但 session 回调失败**，均撤销暂存结果。 |
| 身份与快照 | 错误模型/字典身份、页面或候选版本不匹配、计数不符均拒绝；修改输入集合不能改变已取得的快照。 |
| 可复用性 | 一页拒绝后，清理正常的同一 session 可以处理下一页；新页使用新的独立守卫。 |

新增协调器测试放入现有共享测试目录，真实原生边界测试继续扩展 `FullPageOcrLifecycleProbeTest`。不需要新增测试基础设施或调整关联阈值。

本次仅完成源码审查，未运行构建、测试或设备操作。