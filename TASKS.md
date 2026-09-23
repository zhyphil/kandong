# KanDong 任务板

产品顺序：看清 → 看懂 → 理解 → 会操作。当前目标真机是用户指定的 HUAWEI LIO-AN00 / Android12 / API31。

- [x] 环境核验，独立Git main与SSH origin正确，无推送
- [x] 逐项检查既有文件，修正 README/PRODUCT/ARCHITECTURE/MVP/SAFETY
- [x] 官方Window Magnification / Controller / screenshot overlay / MediaProjection / View方案比较
- [x] 保存旧引导原型和历史失败记录，退出当前主流程
- [x] Phase0A官方原生窗口实现：2/3/4倍、系统镜面、控制条、同意/停止、已有放大保护
- [x] 33项单元测试、app/compat/fixture构建、Lint、两版本release构建
- [x] API36专用模拟器3项集成测试与实际放大截图
- [x] 真机版本核验：两台华为均不支持原生API33路线
- [x] 按用户要求建立独立compat屏幕共享/区域裁剪验证版
- [x] 独立审查与两项修复；模拟器兼容路径倍率、拖动、触摸穿透、停止释放验证
- [ ] 目标华为：完成安装、悬浮窗、本次屏幕共享授权
- [ ] 目标华为：合成页、Chrome与第三方App的2/3/4倍、移动、布局、触摸、停止验收
- [ ] Phase0A真机Go/No-Go及“源框/镜面分开”UX判断
- [ ] 原生最低API33、其他OEM、镜框手指移动/尺寸、共存测试
- [ ] Phase0B：FR/EN区域翻译（先树，按需OCR；未实施）
- [ ] Phase1：帮我看懂（未实施）
- [ ] Phase2：下一步怎么办、节点高亮、人手操作（旧实验未来重验）
- [ ] Phase3：TTS/长辈UX/隐私增强/性能电池测量

下一步先完成当前目标机的Phase0A；不把旧高亮测试或模拟器成功当作真机验收。没有云端上传、自动操作、上架或远端推送。
