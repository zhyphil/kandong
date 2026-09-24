# DeepL 整屏上下文翻译候选（独立宿主实验）

用户已注册DeepL开发者账号，选择它作为第一项实际联网候选。本机翻译路线保留。当前正式放大镜尚未增加翻译、网络或权限；不得把本实验凭据内置到APK。

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

本轮适配器新增7项测试＋前47项，共54项宿主检查通过；本地预演192请求/4092字符完成。包含完整上下文、中文不发、目标绑定、配额拒绝、官方域名、重定向拒绝、错误不回显凭据、不重试、私有文件权限检查；HTTP用替身，没有实际API成功证据。Android未再次变化，沿用上一阶段158 JVM/20模拟器及构建证据，未更新真机。

下一步：配置凭据后先验证账号/额度并运行合成比较；按原评分规则核对真实结果，保留失败。当前没有模型质量通过结论，也没有真实手机页面传输。

## 官方依据（2026-09-24核对）

- [认证/域名/密钥](https://developers.deepl.com/docs/getting-started/auth)
- [翻译请求与context](https://developers.deepl.com/api-reference/translate/request-translation)：context不另翻译；上下文字符不计费；数组各text不彼此共享上下文。
- [额度查询](https://developers.deepl.com/api-reference/usage-and-quota/check-usage-and-limits)与[字符计数/请求上限](https://developers.deepl.com/docs/resources/usage-limits)
- [账号密钥入口](https://support.deepl.com/hc/en-us/articles/360020695820-API-key-for-DeepL-API)与[安全后端指导](https://support.deepl.com/hc/en-us/articles/9773914250012-About-DeepL-API)
- [套餐](https://support.deepl.com/hc/en-us/articles/360021200939-DeepL-API-plans)：Developer总量1,000,000字符且不重置；旧Free月免500,000不接受新购。不是对当前账号额度的实测。
