# Phase 0B：本机区域翻译验证计划

核对日期：2026-09-24。状态：方案与合成验收材料已准备；没有接入OCR/翻译SDK，没有下载模型、申请权限或读取真实页面。目标仍为用户指定的华为LIO-AN00/API31。只读研究属于NORMAL小范围准备，不新增AO步骤。

## 已核实的候选与待验证点

| 环节/候选 | 官方资料支持的事实 | 对KanDong的判断 |
| --- | --- | --- |
| ML Kit Latin OCR随包版 | Android支持随包模型与Play Services动态模型两条路径；随包依赖为`com.google.mlkit:text-recognition`，模型安装后可直接使用 | 可作为FR/EN合成OCR候选；不要选择依赖Play Services首次取模型的路线来假定华为可用。具体SDK依赖、遥测和机型实测仍需核对 |
| ML Kit翻译 | 支持英语、法语、中文；翻译模型按需下载，不是官方支持的随APK打包路径 | 有首次准备/失败/删除模型的产品流程；不能称“装好立即全离线”。动态下载与Play Services管理模型不是同一概念，但也不能据此保证这台华为可用 |
| ML Kit翻译质量 | 本机翻译适合简单内容；非英语语言之间以英语作中间语言 | FR→中文必须单独检查否定、金额、日期和限制条件，不能从EN→中文成绩推断 |
| Tesseract OCR | 官方提供本机C/C++引擎，仓库代码Apache-2.0，依赖和语言数据需分别核查 | 作为可控制依赖的备选；Android封装、法英模型和小字识别尚未选定或测速 |
| 自打包模型+ONNX Runtime Android | 官方支持Android本机推理，模型需适配格式、存储和内存 | 只是运行时路线，尚未选定有合适许可/大小/质量的翻译模型，不能把运行时可用当作翻译能力已实现 |

来源：[ML Kit OCR Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[模型安装路径](https://developers.google.com/ml-kit/tips/installation-paths)、[支持语言](https://developers.google.com/ml-kit/language/translation/translation-language-support)、[翻译能力与限制](https://developers.google.com/ml-kit/language/translation)、[Tesseract官方仓库](https://github.com/tesseract-ocr/tesseract)、[ONNX Runtime移动端](https://onnxruntime.ai/docs/tutorials/mobile/)。以上是候选排序与推断，不是目标机兼容结论；本轮没有检查目标机GMS安装状态或实际下载连通性。

## 影响现有产品承诺的差异

ML Kit说明输入和结果在设备上处理、不发给Google服务器，但SDK可能联网获取模型等信息并发送性能/使用指标。因而“文本本机翻译”不等于“应用完全不联网”；接入前必须核对实际依赖与合并Manifest，并重新准确描述这一点，不能直接沿用看懂当前“无网络”的表述。[官方隐私说明](https://developers.google.com/ml-kit/terms)

翻译SDK文档要求使用前确认模型可用、管理下载及关闭Translator；文档给出的模型量级约30MB，实际法英中组合的下载/存储量要实测，不先写死总大小。翻译界面还有Google attribution要求，正式设计要核对，不能把示例代码页脚的Apache许可误当作全部模型/服务许可。[Android翻译接入](https://developers.google.com/ml-kit/language/translation/android)、[翻译使用指南](https://developers.google.com/ml-kit/language/translation/translation-terms)

因此本轮不把任一SDK直接加入compat。优先准备独立合成实验；若候选要求改变联网/遥测承诺，应先给出实际依赖与产品披露方案，再决定正式接入。不会以云翻译作为静默失败替代。

## 下一轮可执行工作

1. **只用合成文本/图片的实验。** 不申请Accessibility、悬浮窗或MediaProjection。先分别验证翻译文本质量与OCR识别质量，避免把两类错误混在一起。模型未就绪时显示真实状态，不返回伪译文。
2. **固定验收输入。** 从[合成语料](fixtures/translation-cases.json)起步；金额、小数点、时间、否定词、条件与编号逐项人工核对。每条都保留原文和评判要求；当前12条是起始材料，不代表覆盖率或通过率。
3. **华为依赖/离线门槛。** 在实际目标机核对依赖可用性、首次准备、下载失败/取消、已准备后的实际离线执行、删除/重新准备。记录SDK/模型版本、磁盘占用、冷/热耗时和PSS，不把模拟器成功替代真机；不修改手机安全设置来强行兼容。
4. **再接区域流程。** 生产仍按既定顺序优先Accessibility Tree，文本不足时用户主动触发裁剪OCR；明确新增文本读取授权。请求携带会话/选区/页面代数，移动、滚动、收起、锁屏、结束使旧结果失效。镜面始终能切回原文，不替用户点击任何按钮。

初始成功条件是关键数字/否定/条件无遗漏或反转、失败可取消、迟到结果不覆盖新区域、手机离线状态与界面承诺一致。普通措辞可允许等义译法，不要求逐字匹配样例中文。若关键项失败，标记该模型/场景未通过，不靠调低总体平均门槛掩盖。

此计划不解除Phase0A候选版的真机复验，也不启动登录、订阅、AI解释或自动操作。
