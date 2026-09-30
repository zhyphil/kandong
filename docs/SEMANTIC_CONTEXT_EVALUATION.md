# 整页条件与重复来源边界评估

2026-10-01，基点 `1199609`。本轮评估完成，**分组扩大范围的门槛未通过**：24张新编写的单语候选布局中，4页产生7个不完整条件目标。完整页面文字虽然保留在上下文中，目标本身仍可能漏掉前提或例外。没有执行供应商调用，不能据此声称已经观察到错误译文。

## 输入与方法

先冻结[语料与独立预期](fixtures/semantic-context-eval-v1/corpus.json)和[评估协议](fixtures/semantic-context-eval-v1/PROTOCOL.md)，再执行现有Kotlin组件：空间关联 → 派生分组 → 整页适配 → 按需请求。24页、93候选覆盖单一英语、法语、简体中文或繁体中文；包含不同位置/列、重复观察、冲突/裁剪、行距/缩进、前提/例外及无关邻句。未增加混排门槛。

这些是开发者了解现有规则后编写、首次执行前固定的输入，不是外部盲测或新OCR输出。四段来源、模型标识和分数为结构契约的构造值；未生成或识别新图片。预期来自原句关系及编写的布局，只交给宿主评分器，不传入被测Kotlin组件。分类依据是来源成员是否覆盖预先标注的完整条件，不能靠删除重复候选改善结果。

原语料、预期、既有算法和录制资产在本轮均未调整；[冻结指纹](evidence/semantic-context-eval/2026-10-01/frozen.json)再次核对一致。协议中的“零网络调用”指实验的模型/翻译/采集调用；按用户授权进行的Git远端核对和推送独立执行。

## 结果

| 观察 | 数量与意义 |
| --- | --- |
| 原始候选/整页上下文保留 | 93/93；JVM逐字段核对，评分器另核原文/位置/分数与上下文成员 |
| 正确完整条件目标 | 10；只是源分组正确，不是译文质量通过 |
| 保守保留、不产生条件目标 | 5个完整组：3个重复来源例、宽行距、缩进 |
| 不完整条件目标 | 4页、7目标；即使全页context齐全，也未达到完整来源关系门槛 |
| 简体/繁体 | 各1页，原文保留、0翻译请求 |

四个失败页面如下。“单独”指另一个请求目标，不是原候选被删除。

| 页面 | 必须共同保留的条件关系 | 实际请求目标 | 风险 |
| --- | --- | --- | --- |
| `en-trailing-exception` | `No refunds after` / `confirmation.` / `Except when the service is cancelled.` | 前两行成组，例外单独请求，共2个不完整目标 | 主结论缺少取消服务的例外 |
| `fr-trailing-restriction` | `Remboursement possible avant` / `confirmation.` / `Uniquement avec votre reçu.` | 前两行成组，限制单独请求，共2个不完整目标 | 可退款结论缺少凭收据的限制 |
| `en-leading-negated-claim` | `The following is NOT our refund policy:` / `No refunds after` / `confirmation.` | 仅后两行成组，1个不完整目标 | 被否定的政策可能被单独呈现为有效结论 |
| `fr-leading-prerequisite` | `Si le service est annulé :` / `Remboursement possible après` / `confirmation.` | 前提单独请求，后两行成组，共2个不完整目标 | 可退款结论缺少取消服务的前提 |

根因在现有v1派生规则的范围：只连接有限退款句头与 `confirmation` 尾行；额外候选仅在重叠或夹于两行之间时阻断。位于组前/组后的修饰文字没有成为完整性约束，部分又作为普通单行目标。全页context并不能替代目标成员关系；是否产生实际误译仍需后续固定输入的真实供应商验证。

冲突文字、同段重叠副本、跨列碎片、插入标签、裁剪冲突均保留原始来源，不据此扩大可用范围。邻接客服/配送句与条件无关的对照仍允许独立目标，避免把所有邻句都算成同一条件。

## 历史重复来源研究

另回放历史 `fr-seam` 的20候选，没有运行新OCR。中间四成员组仍因 `NON_UNIQUE_ADJACENCY` 不可用：

- 尾行 `s1/c0-r0-f0`、`s2/c0-r0-f0`：同文、无歧义配对，原一致文本为 `confirmation.`。
- 句头 `s1/c1-r1-f1`、`s2/c1-r1-f1`：同文，但原几何仍是 `UNCERTAIN / NON_AXIS_ALIGNED`，`agreedRaw` 为null。

可以继续研究“同一行的多份观察共同作为来源”的派生组，不能直接选最高分或删掉重复项。这个发现没有生成新可用组或译文；应先解决前提/例外完整性，再考虑扩大重复来源覆盖。

## 验证与证据

- 8项宿主评分器测试通过。临时仅在内存移除“不完整目标”检查后，其中2项按预期失败；原评分器文件未改。属于反向验证，不宣称完整TDD流程。
- 281项modelprobe JVM测试通过，其中新增2项执行24页及历史拓扑；0失败/错误/跳过。技术测试验证保真、顺序稳定及请求观察，不断言语义门槛通过。
- `lintDebug`：0错误、0致命、4既有警告。仅运行本机JVM编译/测试与Lint，没有重新组装APK、安装手机或运行模拟器。
- 独立评分首次与最终均返回 **2**，结果同为4页/7个不完整目标。0表示评估无此类错误，1表示输入/证据/工具完整性异常，2表示评估正常完成但语义门槛失败。
- 首次定向测试的XML被后续完整回归覆盖，未归档，不补造。首次报告只作初次评分记录；最终[原始观察](evidence/semantic-context-eval/2026-10-01/observations.xml)与[评分报告](evidence/semantic-context-eval/2026-10-01/final-evaluation/report.json)有匹配SHA，作为本轮权威证据。

汇总见[summary.json](evidence/semantic-context-eval/2026-10-01/summary.json)。正式放大镜、独立实验UI、旧分组规则及资产都未修改；`qualityAccepted`、`semanticVerified` 均为false。

## 本机复现

在项目根目录，用现有JDK17与缓存依赖：

```sh
/opt/homebrew/bin/python3 -B -m unittest discover -s scripts -p test_semantic_context_eval.py
JAVA_HOME=/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home ./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:lintDebug
/opt/homebrew/bin/python3 -B scripts/semantic_context_eval.py evaluate --junit modelprobe/build/test-results/testDebugUnitTest/TEST-com.kandong.modelprobe.SemanticContextEvaluationTest.xml --out /private/tmp/kandong-semantic-eval-replay
```

输出目录必须不存在。最后一步预期退出码2，不要将其改成成功。只复核已保存观察时，把 `--junit` 换成本报告的 `observations.xml`，无需重新构建。输入生成器只写JVM候选输入、不含预期；评分会校验现有生成文件与冻结语料一致。

## 下一项验收要求

先在独立实验路径设计完整条件守卫：识别到前提/否定/例外/限制但不能可靠绑定时，整组及相关碎片保留原文，不发送不完整目标；不改原候选或按样例ID补答案。将本轮失败作为明确回归，同时增加首次执行前固定的新反例和无关邻句对照；分别报告错误放行与保守遗漏，不只追求零请求。旧v1和失败证据继续保留，新规则的版本/指纹/卡片绑定另行验证。尚未授权由本轮质量失败自动触发新的供应商调用或真实屏幕上传。
