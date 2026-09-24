# DeepL 整屏上下文翻译候选（独立宿主实验）

用户已注册DeepL开发者账号并完成专用密钥配置，真实API已连通。本轮94次成功合成调用，但日期/货币等关键语义仍有失败，未通过产品接入门槛。本机翻译路线保留。正式放大镜尚未增加翻译、网络或权限；不得把本实验凭据内置到APK。

## 实验设计（发送前固定）

- 只读已固定的24张手写单语页面，其中22张EN/FR页、48个外语目标；另外2张中文页/6个目标保留原文，不发送给服务商。全部是开发回归，后续还需新的独立单语页。
- 每次只请求一个原元素的`text`，固定`source_lang=EN/FR`、目标`ZH-HANS`；整屏模式的`context`包含完整可见页的白名单文本块、角色、组/阅读顺序、位置和本次目标ID。另一模式不提供context，作为孤立词对照。没有预期译文、语义答案、case标签或真实屏幕数据。
- 本机以本次请求的目标ID回绑唯一返回文字，源位置/卡片关系来自固定原文；不依赖服务商生成坐标，也不把整页译文切片。DeepL只声明context可影响译文，不承诺理解KanDong的JSON结构；效果必须由实验决定。
- 两轮共192次请求、4,092个源字符；context不计翻译字符数（官方说明），但仍受128KiB请求上限。本工具额外限制32KiB请求/响应、30秒单次超时、最多10,000源字符、固定官方域名，无代理、无重定向、无自动重试。失败即停止，不切另一个服务商或开通收费。
- 使用服务端默认模型，不把不可固定的云模型冒称固定权重；如响应提供`model_type_used`则记录。严格检查唯一译文、源语言及原元素绑定。结构通过后仍要逐项检查Book/Vol/Note、日期范围、最大/最小、单位/货币与否定条件。
- 先读`/v2/usage`，确认配额足够。超大/无界的账号额度拒绝执行，待确认账号方案；usage本身不能证明账号计费方案。当前依用户所述Developer方案试验，不购买/升级或调整限额。

## 安全配置

在Mac终端运行以下命令，再粘贴密钥并回车（输入不显示）：

```sh
/opt/homebrew/bin/python3 /Users/haoyuzuo/Projects/KanDong/scripts/configure-deepl-key.py
```

从DeepL账号“API Keys & Limits”复制密钥，不放到聊天。配置工具仅把密钥写到本项目`.local/translation/deepl-api-key`，目录700/文件600并被Git忽略；不联网、不打印、不写入APK。读取仅限此路径，不扫描其他项目、浏览器或钥匙串。可在DeepL账号撤销/更换密钥；本地文件是开发配置，不是生产密钥管理服务。

本次通过系统打开`.command`未能让用户看到窗口，电脑控制工具又禁止查看Terminal；因此改为用户手动运行上面的明确命令。不能把启动返回成功当作用户已经看到窗口。

项目出现用户新建`.env`，只检查是否有已知DeepL字段（无值输出），并加入Git忽略；不覆盖文件、不将其中任意内容擅自视为凭据。当前读取器仍只接受上面的专用文件。

官方认证说明以`:fx`区分旧Free密钥，使用`api-free.deepl.com`；其他密钥使用`api.deepl.com`候选地址，Developer实际成功与否以首次usage响应为准。失败不自动换地址发送。凭据只出现在官方HTTPS请求的Authorization头，不进入URL、命令行或实验报告。

## 运行与验收

默认只准备计划，不读密钥、不联网：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-translation-deepl.py --out /tmp/kandong-deepl-dry-run
```

专用密钥已由用户配置后，已获授权的合成实验入口：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-translation-deepl.py --execute-synthetic --out /tmp/kandong-deepl-run
```

输出目录须全新，响应、耗时、逐目标绑定、配额与代码SHA可复核。错误只记录固定类型/HTTP状态，不输出服务端错误正文或密钥。usage可能延迟，成功后字符差值不能单独证明每条翻译都成功。

初始准备阶段适配器新增7项测试＋前47项，共54项宿主检查通过；本地预演192请求/4092字符完成。包含完整上下文、中文不发、目标绑定、配额拒绝、官方域名、重定向拒绝、错误不回显凭据、不重试、私有文件权限检查；HTTP用替身，没有实际API成功证据。Android未再次变化，沿用上一阶段158 JVM/20模拟器及构建证据，未更新真机。

凭据配置与首次API比较现已完成，实际结果见下；上述54项是发送前的工具检查，不能代替真实语义结果。

## 官方依据（2026-09-24核对）

- [认证/域名/密钥](https://developers.deepl.com/docs/getting-started/auth)
- [翻译请求与context](https://developers.deepl.com/api-reference/translate/request-translation)：context不另翻译；上下文字符不计费；数组各text不彼此共享上下文。
- [额度查询](https://developers.deepl.com/api-reference/usage-and-quota/check-usage-and-limits)与[字符计数/请求上限](https://developers.deepl.com/docs/resources/usage-limits)
- [账号密钥入口](https://support.deepl.com/hc/en-us/articles/360020695820-API-key-for-DeepL-API)与[安全后端指导](https://support.deepl.com/hc/en-us/articles/9773914250012-About-DeepL-API)
- [套餐](https://support.deepl.com/hc/en-us/articles/360021200939-DeepL-API-plans)：Developer总量1,000,000字符且不重置；旧Free月免500,000不接受新购。不是对当前账号额度的实测。

## 实际结果（2026-09-24）

专用密钥由用户在终端确认保存后，usage成功返回`character_count=0 / character_limit=1000000`。本次仅发送原有手写合成页，未读取/发送手机屏幕、未写密钥到证据或APK、未升级套餐。最终usage为`2615 / 1000000`，与94个成功响应的`billed_characters`合计一致；另有1个HTTP429失败。usage可能延迟，数值一致不等于翻译质量合格。

| 协议 | 实际完成 | 结论 |
| --- | --- | --- |
| 单目标＋整页JSON/孤立词，两轮原计划192次 | 74次；第75次HTTP429，立即停止，未重试 | 未完成第一轮；37个整页目标审查为24通过、11失败、2待复核 |
| 同一目标＋整页纯原文context | 14次，91字符，一轮 | 12个歧义短标签正确；单日有效与$币种仍失败 |
| 整页连续正文一次翻译 | 3页×两轮＝6次，1070字符 | 两轮日期/货币错误都保留；没有元素绑定，不能拿来回填镜面 |

纯原文context保留当前整页所有文字、阅读顺序与组间空行，仅去掉JSON元数据。目标ID、角色、原位置仍在本地保留；没有加入“正确答案”或推测场景句。这与[官方context说明](https://developers.deepl.com/docs/learning-how-tos/examples-and-guides/how-to-use-context-parameter)中的文档内容用法一致，但不证明所有JSON一定失败，也不证明纯文本适合任何页面。云默认模型不能固定权重版本，以上是本次观测。

选定诊断例子（均为看过第一轮后选择的开发回归，不是盲测）：

| 原文及页含义 | JSON context | 纯原文context |
| --- | --- | --- |
| Book，酒店/餐位预订 | 书籍 | 预订 |
| Book，书店/图书馆 | 图书 | 图书 |
| Vol，航班 | 航班／飞行 | 航班 |
| Vol，盗窃报警/保险 | 航班／飞行 | 盗窃 |
| Return，退货／返回 | 都为返回 | 退货／返回 |
| Note，账单／评分 | 都为注 | 账单／评分 |
| Valable le 5 décembre 2026 | 有效期至2026年12月5日 | 有效期至2026年12月5日（仍错误） |
| $120.00 per night | 每晚120.00（丢币种） | 每晚120.00美元（原文未明确USD） |

整页正文的p106两轮也都把“某日有效”译为截止日；p304真正的“截至某日（含）”则正确。由此不能把前者当中文措辞等价。p003整页正文仍将部分$解释为美元。$规则沿用[既有保守校验](TRANSLATION_NEXT_CANDIDATE.md)，不因新结果改宽标准。部分票务“不可换票”变成“不可兑换”，列为待复核，不算通过。

后两轮每次新请求前间隔2秒，没有再次遇429；这只是小样例限速措施，不是服务速率保证。当前工具失败即停、不重试；如后续做生产重试，需按[官方错误处理](https://developers.deepl.com/docs/best-practices/error-handling)单独设计有界退避与取消，不把超时当作未扣额度。

### 检查与证据

- 58项宿主检查通过（原54＋纯原文4），验证原文完整、表示之外参数相同、配额拒绝、失败保留证据且不重试。旧HTTPError替身在Python3.14有ResourceWarning，已保留日志；没有真实密钥输出，也不将警告说成零警告。
- 三次运行均核对冻结计划、源码指纹及已完成响应；逐目标方案源ID绑定未改变。94成功/1失败与各轮配额记录见[实测汇总](evidence/deepl-translation/2026-09-24/live-summary.json)。
- [原JSON部分结果](evidence/deepl-translation/2026-09-24/json-context-run1/semantic-review.json)、[纯原文14项对照](evidence/deepl-translation/2026-09-24/plain-context-run1/semantic-review.json)、[整页正文诊断](evidence/deepl-translation/2026-09-24/whole-page-run1/semantic-review.json)；各目录保留压缩原始响应、usage、计划、hash和status。判定由工程代理完成，不是外部译者或妈妈的人工验收。
- Android模块没有追加更改，沿用此前158 JVM/20模拟器证据，不重复无关构建；没有真机安装、真实屏幕采集、推送或发布。

### 下一项

保留纯原文整屏上下文路线，先处理日期关系、币种/单位和票务条件的通用一致性检查及明确失败结果；不按case ID替换预置译文、不把被拒译项算成成功。随后做完整48目标两轮回归与新的独立单语样例，记录可用覆盖和剩余误译。现有数字/货币字面检查仅能发现一部分遗漏，不能承诺语义安全。只有质量、覆盖与真实采集/隐私门槛都通过后，再接服务端凭据管理和镜面；不会把开发密钥打进手机包。

## 后续关键事实检查已执行

见[完整检查与回归](TRANSLATION_CRITICAL_CHECKS.md)：新增112次成功合成调用/2244字符，两轮各56目标；原始译文各46通过、9失败、1待复核，最终展示各45候选/11保留原文。86宿主测试通过；校验不是翻译修复，正式手机仍未接入。之前94次与其失败证据不变。
