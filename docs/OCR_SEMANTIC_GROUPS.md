# 完整跨行条件的分组实验

2026-09-30，起点 `37eb9a7`。本阶段解决固定合成页中 `confirmation.` 单独显示时丢失前一行退款条件的问题。仅在独立 `modelprobe` 实验页显式启用，正式放大镜和手机未更新。

## 当前行为

默认仍是原 v1 录制模式。点“启用双行分组实验”清除旧结果，再点“译文回放”才运行一次固定整页 OCR。分组后，选区只覆盖 `confirmation.` 尾行，也显示所属完整条件和组内全部源候选编号。移框、倍率及镜面平移不重新识别或翻译；菜单、收起、取消、后台及原取得时间后的60秒到期仍清空。

派生顺序按页面坐标排序，明确标为未验收的空间顺序，不声称通用语义阅读顺序。支持的组仅限英语／法语“确认前／后，可／不可退款”两行结构；使用文本结构、行距、列对齐、凸四边形及斜率检查，不按样例ID选择答案。存在跨列、插入行、重叠竞争、截断或不支持的几何即不放行。

四张固定单语页的53个原始候选全部保留，原始位置、文字、分数位、来源和冲突诊断未改。法语页顶部与底部各有一个合格双行组；中部两头两尾仍为四成员歧义组，不挑选胜者。原来斜向诊断保留，额外的有限几何资格只作用于派生组；完整20行法语上下文仍含重复和乱码。无法单独表达完整条件的退款／confirmation孤立行不请求翻译。

## 实际供应商记录与离线复核

调用前冻结输入、完整条件评分规则、源码及请求哈希，限定10次翻译、2次用量查询，首错停止、无重试。8次是原稿构造的正反条件，2次是实际归档OCR双行组；各请求携带对应整页原文上下文，源两行保留LF，使用 `split_sentences=nonewlines`。中文不调用，原稿不补进OCR结果。参数依据 [DeepL 官方翻译接口](https://developers.deepl.com/api-reference/translate/request-translation)。

实际10次全部完成；usage从5708到6092，增加384字符，与返回计费合计384一致。保留完整未改写的解析JSON，不称HTTP原始字节。

初版严格规则拒绝了全部10条：响应保留了换行，另出现“恕不退款”“可办理退款”。逐项开发复核确认这些具体响应保留了确认事件、前／后关系和退款正／负含义。原 `live/check-*.json`、初版脚本及全拒绝导出包原样保留。

随后增加独立 `complete-confirmation-condition-v2` 离线规则：只支持事件与动作之间至多一次换行及上述退款表达；反向条件、事件缺失、例外、附加分句、双重否定及“可申请退款”等不同含义仍拒绝。先运行反例取得失败，再实现并复核。同10条已保存响应通过新规则，其中2条OCR组成为实验卡片候选；没有追加网络请求。响应文字与换行均不改写。

这是**观察结果后的开发扩展，不是未见样例盲测**。导出仍要求源正确、逐项开发复核通过、规则通过；保留 `qualityAccepted=false` 与 `semanticVerified=false`。完整输入、成员顺序、原始字段与派生布局参与Android绑定，整包SHA固定；原始拒绝、二次判断、逐项复核及源码哈希可相互核对。

## 复现与证据

- [汇总](evidence/semantic-groups/2026-09-30/summary.json)、[逐项复核](evidence/semantic-groups/2026-09-30/semantic-review.json)、[初轮导出](evidence/semantic-groups/2026-09-30/base-export.json)。
- [调用前约束](evidence/semantic-groups/2026-09-30/PROTOCOL.md)、[观察后扩展说明](evidence/semantic-groups/2026-09-30/RECHECK_PROTOCOL.md)、[调用状态](evidence/semantic-groups/2026-09-30/live/status.json)。
- [最终本机检查](evidence/semantic-groups/2026-09-30/offline-final.json)、[分组原生测试](evidence/semantic-groups/2026-09-30/native-grouped/summary.json)、[兼容回归](evidence/semantic-groups/2026-09-30/native-regression/summary.json)、[独立启动](evidence/semantic-groups/2026-09-30/standalone/summary.json)。

最终141项宿主检查、279项JVM测试、主／测试包离线构建和Lint通过（0错误，4项既有警告）。最终同包模拟器分两批完成：11项分组专项80.966秒、14项兼容回归124.836秒。独立冷启动的实际卡片全文、旋转清空和资源释放也已核对。截图脚本的边缘滚动、错误手写编号和半露卡片记录保留，修正脚本后补拍完整视图；应用代码及APK未改变。小页面概览仍有编号重叠，不作为长辈体验验收。

仅使用专用 `KanDong_OCR_API37_16K` 模拟器，入口 `com.kandong.modelprobe/.RegionVisualLabActivity`。安装主实验包和同签名测试包后选择分组模式；Android不联网，界面注明已录制回复。宿主再导出应使用 `scripts/translation_grouped_recheck.py --run …/live --review …/semantic-review.json --out <不存在的新文件>`；不重复执行供应商调用。原v1资产仍可独立重建，不复用为分组请求的答案。

## 后续边界

当前只解决有唯一几何关系的固定双行确认条件。下一项先冻结未见过的整页布局及条件反例，验证规则的适用范围；并评估跨段重复行能否在保留全部来源与冲突的前提下建立可靠的组关系。没有证据时继续保留原文，不删除重复候选来制造覆盖率提升。

真实屏幕采集／遮挡与敏感过滤、联网会话同意／后端凭据、正式镜面、长辈体验和新机兼容仍须各自验收。本轮不是上述功能发布，也不证明任意退改条款的语义正确。
