# 整屏OCR跨段关联实验

2026-09-30，从d56a87c继续。关联与冲突保留的技术验收通过；仅在modelprobe独立测试中，尚未接入实际Image→OCR输出、实时采集、翻译或正式放大镜。

## 规则与边界

上轮核心中心过滤会丢掉正确的繁体“稅”、留下错误的“税”。新增FullPageOcrAssociation按空间建立关系，完整保留原始文字、双坐标、分数、模型与来源。不同位置的同名标签不因文字相同而合并；冲突、空串、可能截断的文字不猜选、不拼接或补字。

- 输入是一页、一个版本、一个模型的完整结果；空条带也须有完成记录。缺段、取消、过期、异常、身份或预算错误整页拒绝。
- 相邻条带在共同读取区域内，交集宽/较大宽及高/较大高均达到80%时强关联；可能截断另用50%较小框高度与内部读取边缘2px门槛。见实现前[冻结计划](plans/2026-09-30-seam-association.md)。
- 保留直接边与全部组成员，文本和几何不确定性分别记录；一对多、同段多成员、传递链、非轴对齐四边形和边缘接触均标原因。核心归属和分数不挑选。
- agreedRaw仅指无几何歧义的两个强关联候选逐字相同，不证明文字正确或完整。沿用每页128候选、每段64候选、8192个UTF-16单位预算。
- 坐标列表做不可变快照，当前性检查覆盖开始、计算和最终发布。回调/模型封套只证明一致性，不证明真实屏幕来源、隐私、TTL或裸候选实际模型来源。

## 验证

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| JVM | 165项，0失败/错误/跳过 | 144既有＋20反例＋1历史回放 |
| 主/测试APK、Lint | 构建成功，0错误4既有警告 | 离线，无依赖升级 |
| 历史实际OCR输出 | 40组472候选全部字段保留 | 两设备各20组旧合成页，无本轮推理 |
| 重复性 | 输入重排不变，两轮报告字节相同 | 不按数组顺序选代表 |
| Android模拟器 | 20项同源测试通过，测试器0.117秒 | API37/ARM64/16KiB，固定候选 |
| 独立只读审查 | 无必须修复问题，源码指纹未变 | 实现、冻结规则、实际证据 |

472候选（含40空串、92非核心）形成388组、84关系、24逐字一致无歧义组。两设备历史20组关系相同；“含早餐，不含城市稅。”和“含早餐，不含城市税。”仍同组冲突，不选成唯一译文输入。一些中文残片仍孤立，这些数字不是OCR质量改善或完整去重证明。

输入排除答案、字形和质量标签，40源文件哈希见[清单](fixtures/full-page-association-v1/manifest.json)。4空白页无候选直接版本，明确按旧测试公式与同轮邻页会话派生，不冒充直接观测；20类反例意图在实现前冻结。

主实验APK保持7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea；新测试APK为6ed22110a0b4dd21e338287a5f7309822cae1a2269bfeda559f18241304fe51f，模拟器安装指纹一致。历史资源只供JVM，不入APK。正式app/compat/graphics未改；手机本轮未连接／未安装，不算新华为验收。

计划先跑桩的红灯，但工作者受Gradle缓存权限阻止；根任务补跑与测试文件修改重叠、指纹不稳定。均不计有效红灯，原记录保留。最终165项及构建在稳定源码下运行。旧2项GradleDependency提示本次离线未报，不宣称修复。

完整JUnit、Lint、两轮回放、审查、源码/APK哈希见[摘要](evidence/full-page-association/2026-09-30/summary.json)。

后续设备接入补充：同日更换的nova 9／NAM-LX9/API31已用同一测试APK通过20项关联反例，另6项Image色块检查通过；见[新机记录](TEST_DEVICES.md)。这是新增固定数据设备证据，不改变上述历史40组回放的来源，也不代表新机OCR推理或屏幕共享通过。

## 复验及下一项

项目根目录使用现有JDK17，无需联网、密钥、屏幕或模型调用：

    JAVA_HOME=/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home ./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:assembleDebug :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug

回放输出modelprobe/build/reports/full-page-association/replay.properties。单项任务 :modelprobe:testDebugUnitTest --tests '*FullPageOcrAssociationReplayTest' 可重复；会替换工作目录测试汇总，不能当165项证据。完整XML已另存。

输入导出工具 docs/evidence/full-page-association/2026-09-30/prepare-inputs.py --out <新目录> 只读并校验源归档；重建与冻结输入逐字节相同。

[专用模拟器](OCR_EMULATOR.md)核对名称/API/页大小/安装哈希后运行com.kandong.modelprobe.FullPageOcrAssociationTest；[精确命令](evidence/full-page-association/2026-09-30/emulator/summary.json)。临时AVD消失，本轮用本机同一镜像重建于项目忽略目录.local/avd。

下一项在独立实验中将实际整屏Image→OCR完成记录、全部候选、模型身份和版本守卫接关联器；验证取消、异常、缺段、迟到结果不发布半页关联，冲突全部保留。随后衔接整屏上下文与选区显示，同页移框只更新显示，倍率只改呈现。

真实页面见证、第三方来源/敏感过滤、联网同意、后端密钥及翻译质量仍各自待验收，不能据此开启正式镜面翻译或上传真实屏幕。
