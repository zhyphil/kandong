> 📲 **Mobile App Builder** | 步骤 1/1 | 288.5s

---

已完成限定的 5 个文件实现，保留原有修改。

- `FullPageTranslationAdapter.adapt(metadata, association, declaredLanguage)` 返回 `AdaptedPage` 或明确的 `EMPTY/INVALID/BUDGET`。保留全部候选、来源及空间关联，深度冻结集合，使用长度分帧 SHA-256 标识，不生成语义分组。
- `FullPageTranslationProbe` 提供 `observe/click/captured/pending/select/transform/render/evidence/clear/accept`，另有身份绑定的 `failed` 重载和 `hasOriginal(now)`。校验完整六字段版本、原始 TTL、采集顺序及持续单调时间；中文本地保留原文，不暴露请求。
- `PreparedCapture` 暴露 `spec/request/version`；`prepare(spec)` 仅预留元数据，`run(prepared, ticket)` 单次消费并拒绝其他 runner 的对象。原重载保留。
- 单模型证据使用独立校验分支，旧双模型逻辑未改；新增 14 个 JVM 契约测试，未修改根任务的四项测试。

`captured` 的调用前提已写入代码：必须经 `RegionOcrBridge` 成功投递，且原 owner 的证据仍有效。协调器不认证资源清理，也不判断译文语义；绑定不变而文字互换仍可结构性通过。

`git diff --check` 无报错。按分工未运行编译、测试或仪器验证，需根任务执行并复核。