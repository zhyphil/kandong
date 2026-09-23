# 历史引导原型（冻结，不是当前 MVP）

2026-09-23 产品校正前的节点读取 → 原标签说明 → 目标高亮入口。原始完整基线保存在 Git 提交 `68f3bf9`。本目录保留旧服务、桥接状态、UI、服务能力配置和设备测试；**不在 settings.gradle.kts/sourceSets 中，不构建到 APK**。

现有 app 的 `domain/`、`capture/`、`overlay/OverlayController.kt`、`HighlightView.kt` 暂保留并编译，未被新的放大镜服务实例化；16个纯逻辑测试继续保护敏感子树、几何与会话行为。当前服务关闭 `canRetrieveWindowContent`，旧捕获链路没有产品入口。

- 0B 可复用节点过滤/有界采集思想，但需重新解决放大坐标、区域文本、窗口原点和内容授权；旧 WindowSelector 拒绝放大，不能删掉这项保护就直接启用。
- Phase2 可复用快照失效、高亮绘制和窗口生命周期；目标复核还需包含标签/内容一致性，不只几何。旧15秒过期不是当前放大镜会话时限。
- 旧 fixture Activity 保留为 `GuidanceFixtureActivity`，新 `FixtureActivity` 面向放大镜并用自身布局指纹验证无 reflow。
- 历史设备测试单项曾通过真实边框/点击穿透，但最后全套 **3项、1失败**（窗口时序保守拒绝）；未完成稳定性验收。旧测试不能算当前放大镜通过，也不再纳入当前设备脚本。
- `docs/screenshots/phase0-highlight.png` 仅是旧合成高亮实测图，不是放大镜截图。

此处不是独立 Gradle module。将来按新坐标/权限/产品契约逐项迁回并测试，不能直接恢复旧首页或原型定位。
