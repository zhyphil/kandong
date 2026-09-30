# 冻结执行范围 · 2026-09-30

本轮唯一输入为已有单语固定PNG和完整归档OCR证据。沿用用户已选择DeepL、配置KanDong专用密钥及继续合成实验的授权；不读/发送真实屏幕、不修改套餐或购买额度、不把凭据放到APK。

- AUTHORED_SOURCE 9个独立目标、RECORDED_OCR_SOURCE 14个独立目标，各调用一次；总计23次翻译、正常完成前后各1次usage。每次均附对应轨道整页原文context，完整重复/残片保留。中文0次。
- 源字符保守预算700，包含金额XML；固定2秒间隔、首错停止、无自动重试/续跑。配额有效且足够、总限额不超过1000000才发送。
- `docs/fixtures/full-page-translation-v1/inputs.json` SHA256 `80d273e04e60784a3f154655821b5072eee6ac2ffddd0f02e4f4df20330942d8`。
- `rubric.json` SHA256 `e3980f866d37b46f91cefe71dc89195c131a04c6ee24705760c1e87095a43e46`。在新调用前冻结。语义复核不预填通过。
- 新工具实际使用的源码SHA、请求和预算保存在live/source-hashes.json、plan.json、preflight.json；完整已解析响应先保存再检查，哈希不是HTTP原始报文哈希。
- 导出要求运行完整、请求/响应/来源/复核绑定一致；仅OCR文字正确、开发语义复核通过且现有关键检查通过才显示候选。未核对/错误/规则拒绝保留原文。semanticVerified=false、qualityAccepted=false。
- OCR context按现有adapter候选ID顺序，并非已验收的语义阅读顺序；authored按placement/行顺序。两者不混用，不从原稿纠正OCR。没有本地日期新样本。
- 手机侧只回放本轮已录制结果，完整新OCR指纹一致才能一一绑定当前对象；不重建旧收据或延长60秒期限。UI显著说明录制、本次未联网。正式产品联网同意/凭据/采集仍另行验收。

独立只读复核未发现阻断实现问题；建议加强最终逐目标卡片检查和四页指纹比较，根任务执行。宿主117检查、本机271+76与离线构建已通过；这些不替代即将执行的真实API语义复核或最终包模拟器检查。

[DeepL翻译API](https://developers.deepl.com/api-reference/translate/request-translation)及[额度查询](https://developers.deepl.com/api-reference/usage-and-quota/check-usage-and-limits)本轮核对：每个text独立，需显式context；兼容响应tag_handling_version元数据。既有官方HTTPS/无重定向客户端复用。
