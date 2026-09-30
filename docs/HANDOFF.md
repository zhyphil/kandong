# KanDong交接记录

更新于2026-09-30，起点37eb9a7。本轮完成有限双行确认条件的分组、实际供应商合成记录和独立卡片回放；完整说明见[跨行分组](OCR_SEMANTIC_GROUPS.md)，汇总见[证据](evidence/semantic-groups/2026-09-30/summary.json)。

## 当前边界

唯一目录/Users/haoyuzuo/Projects/KanDong。先读AGENTS.md、TASKS.md。本轮仅独立modelprobe测试源集、宿主脚本和固定合成数据；正式app/compat/graphics/ocrlab、debug宿主及依赖未改，手机未查询或更新。Android无网络权限，Mac只执行已授权的固定合成DeepL调用；没有真实屏幕采集或上传。

用户要求每项任务相称验证后以Conventional Commits提交、推送当前分支，不需重复确认；核对origin与远端分歧，正常push后核对哈希，不强推或改写历史。此约定不包含部署/Release/上架。当前分支main，origin为git@github.com:zhyphil/kandong.git。

## 已核实结果

- 53原始候选及位置/分数/冲突全部保留。派生顺序是坐标排序且显式不确定；只支持有限EN/FR确认前后退款双行结构。法语顶/底各一个双行组合格，中间4成员仍歧义；原始NON_AXIS_ALIGNED未抹除，独立几何资格只作用于派生组。不支持的孤立尾行不请求翻译。
- 默认保留v1。点“启用双行分组实验”清除旧结果后，再点“译文回放”才跑固定整页OCR。尾行选区可显示完整条件；每卡展示全部来源编号。原取得时间60秒、取消/菜单/收起/旋转、原生资源独占与迟到回复规则保留。整页输入/来源/派生顺序/成员逐字段匹配，不允许错配回放。
- 10次实际DeepL：8原稿＋2归档OCR双行组；2次用量查询，384字符对账一致，无重试。原初版规则全部拒绝；实际完整意义经逐项开发复核。新v2规则观察后开发，仅对同一原始回复离线复核10条通过，其中2条OCR进入候选。原拒绝/源码/响应保留，无追加调用，不称盲测；qualityAccepted与semanticVerified始终false。
- 141宿主、279 JVM、离线构建和Lint0错误4既有警告。最终同包模拟器11专项80.966秒＋14回归124.836秒通过，实际60秒/四页OCR/尾行卡片/原手势均有证据。独立冷启动/旋转与原生清理另列；截图脚本的边缘手势、错误手写编号和半露卡片均保留记录并修正，不把未完成截图算完整显示通过。
- AO预算3步：只读方案与复核成功、实现600秒超时后由根任务完成。独立复核P2空态断言已修；审查发生在实际录制及v2之前，后者只有根任务复核和正反例/实际测试证据。

## 下一步

1. 从当前Git与本轮summary开始，不重跑已完成的供应商调用；验证历史导出用离线脚本即可。
2. 先冻结未见过的单语整页布局/完整条件反例，检查规则是否仍可靠；同时评估跨段重复行的组关系。保留所有原始来源、冲突和失败，不能简单选最高分/删除重复或按样例ID补译文。
3. 联网会话同意/后端凭据、真实采集/遮挡/敏感过滤、正式镜面及真机仍须分别验收，不把实验录制包说成手机实时翻译。

## 环境与构件

JDK17：/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home；SDK：/Users/haoyuzuo/Library/Android/sdk。维持现有依赖及离线构建。

主实验APK：214b503f34ef14aa020773bd5ead66e4e51e7909c1a5dea34003f8ef1b2b3ed4；测试APK：2cc737a634c650ba0abf8caefe7de7bc2d64bc6d2538b369f3ef1f1fd50981d5。分组资产：26c7246a2536fc82881af84955a4f4680e418ec891812b2e89837170b8799ea6；旧v1资产：3ca858be7e1b99e78b4f6903b6998ad50a9ee6366e357947ff8c8c49c81e369f不变。入口com.kandong.modelprobe/.RegionVisualLabActivity，需同签名主/测试包。

专用AVD KanDong_OCR_API37_16K，项目.local/avd，emulator-5582，API37/ARM64/16KiB。结束后的进程/模拟器停止状态见本轮summary。最近真机历史目标nova 9/NAM-LX9/API31；恢复前重新核对身份，系统安装/共享由用户确认，不继承本次模拟器结论。
