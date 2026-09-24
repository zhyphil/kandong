# 自有合成页 MediaProjection 实验

`capturelab` 是独立 APK：`com.kandong.capturelab`，名称「看懂采集实验」，minSdk 31 / compile、target 36。不依赖 `app`、`compat`、`graphics`、`ocrlab` 或 `modelprobe`。它不改变正式产品和现有 OCR 实验的权限承诺。

## 人工运行

1. 相同 APK 已在 `KanDong_OCR_API37_16K`（API37/16KiB）及目标华为 `LIO-AN00/HWLIO`（API31/Android12）完成七阶段实测。安装指纹均匹配，结果只覆盖固定自有色块页，不推广到第三方页面。新设备仍必须先核对身份。
2. 打开实验，阅读整屏缓冲区披露。点「授权悬浮窗」，在系统设置中手动授权并返回。
3. 点「开始采集验证」，手动确认本次系统屏幕共享。API 34+ 请求默认整屏，API 31–33 使用平台标准授权入口。平台若仍允许改选单应用，本实验会在尺寸或标记不符时停止／超时，不假定已获得整屏证据。
4. 保持实验页在前台并有焦点，序列最多 40 秒。停止按钮在色块外；前台服务通知也提供停止操作。API 33+ 若通知权限未获准，系统可能不在通知抽屉显示它；本实验不自动申请额外通知权限，页面停止和系统前台服务管理仍可用。
5. 查看中文结果与计数。重新运行必须再次主动点击并确认屏幕共享；不重用 token、不自动恢复。

未授权、取消、授权超时或授权期间离页会清空待启动状态。系统授权页若在某 OEM 触发本页 `onStop`，也会保守丢弃该次授权，不在后台启动。会话中失焦、后台、锁屏、撤销、离页、尺寸或方向变化均结束采集。

## 固定序列与证据

| 阶段 | 同一 Image 中要求的证据 |
| --- | --- |
| 公开基线 | 本阶段两个颜色标记 + 固定底色色块 |
| 普通悬浮遮挡 | 新阶段标记 + `TYPE_APPLICATION_OVERLAY` 的遮挡色 |
| 安全悬浮遮挡 | 新阶段标记 + 安全悬浮层区域的实际分类 |
| 隐藏悬浮层 | 新阶段标记 + 原底色恢复 |
| 恢复悬浮遮挡 | 新阶段标记 + 遮挡色恢复 |
| 本页 `FLAG_SECURE` | 独立非安全悬浮窗口的新标记 + 本页两个标记与底色区域均为黑色；可见内容另记失败 |
| 清除本页安全标志 | 新阶段标记 + 原底色恢复 |

每阶段排空旧图像，再改变阶段标记；标记在同一会话内不重复，带有会话随机色分量。每项观察要求连续 3 帧一致，单阶段限时 4.5 秒。小型色带定时重绘促成新帧，不参与保护判定。每次只创建一个 VirtualDisplay，先注册 projection callback；不为尺寸变化重建显示。

安全整页阶段用独立 `TYPE_APPLICATION_OVERLAY` 窗口提供当前帧标记，因为受保护 Activity 自己的标记不能同时承担新鲜度证明。若 OEM 将该悬浮标记也屏蔽，则证据不足／超时，不能把全黑当作通过。所有阶段都核对真实 View 屏幕位置和真实悬浮窗口布局。

`Image.timestamp` 仅用于同一来源的递增诊断、拒绝重复／逆序帧；绝不与 `elapsedRealtime` 或 `nanoTime` 比较以证明新鲜度。主机单调时钟只管理阶段和总超时。

## 结果含义与边界

- `CURRENT_CONFIRMED`：公开或普通遮挡阶段的当前标记与目标色块同帧匹配。
- `BLACK_OBSERVED`：满足当前标记条件后，在预定采样点观察到黑色遮蔽；只描述本次合成页。
- `UNDERLYING_OBSERVED`：安全悬浮层未出现在采集结果中，预定位置显示已知底色；单独记录，不等同于黑色遮蔽或通用隐私验收。
- `CONTENT_VISIBLE`：安全阶段仍能采集到对应合成内容。
- `TIMEOUT` / `CANCELLED`：证据不足或取消，不能算通过。普通不匹配不会提升为保护成功。

`COMPLETED` 仅表示七个阶段结束，不表示所有阶段成功。最终另列 `coverControls` / `activityControls`：安全悬浮层必须有 BASELINE、COVER、HIDE、RESTORE 成功对照；安全 Activity 必须有 BASELINE、RESTORE、PUBLIC_RETURN 成功对照。缺失、失败或重复阶段均为 INSUFFICIENT，保留原始观察但不提升为完整证据。实验不检测任意窗口是否受保护，不验证第三方页面、所有像素、所有 App 或所有 OEM。确认仅覆盖五点采样的固定合成色块及指定会话。模拟器结果不能替代华为专项验收。

## 数据与生命周期

MediaProjection 提供的是临时**整屏**缓冲区；即使只检查本页色块，授权范围也不是局部裁剪。系统切换到其他界面与收到停止回调之间仍可能有在途整屏帧，代码只读预定色块坐标，不读取文字，不声称缓冲区永不含其他界面。

RGBA plane 在读取前核对尺寸、格式、crop、row/pixel stride、buffer position/limit 和坐标范围，直接采样后在 `finally` 关闭 Image。无 Bitmap、图像文件、OCR、无障碍服务、网络权限、翻译、密钥访问或任意 Intent 文本导入。结果只保留枚举和有界计数于内存，固定 `KDCaptureLab` logcat tag 输出 `PHASE` / `END` JSON 元数据；无 RGB、文本、截图或异常载荷。日志包含会话号、API、尺寸、方向、结果、计数，不记录实际图像时间戳。

服务不导出、`START_NOT_STICKY`、`stopWithTask`；必须一次性认领当前已恢复且有焦点的自有 Activity。停止会释放窗口、ImageReader、VirtualDisplay、projection、广播／显示监听与全部 handler 回调，并清理本页安全标志。进程结束后无后台恢复入口。实验窗口不能获取焦点，也不覆盖页面停止控件。

## 离线验证

沿用项目 AGP 8.13.0、Kotlin 2.2.20、JDK 17 与现有 JUnit / AndroidX runner 依赖，没有新下载或测试设施。

```sh
JAVA_HOME=/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home \
  ./gradlew --offline --no-daemon :capturelab:assembleDebug \
  :capturelab:assembleDebugAndroidTest :capturelab:testDebugUnitTest :capturelab:lintDebug
```

JVM 测试覆盖同帧匹配、来源时间基、版本、重复时间戳、取消／迟到帧、阶段／总超时、保护观察分类及缓冲区边界。Android instrumentation 的前5项测自有 View 位置和 Activity 生命周期取消，不需要投屏／悬浮窗权限。新增第6项必须已有用户亲自授予的悬浮窗权限，建立自有固定色块窗口，用仅测试包的 UiAutomation 开发截图在内存核对实际合成色，随后回收；没有屏幕共享，不保存截图、不读取第三方页面，不构成 MediaProjection 验收。所有测试都不申请或授予系统权限。

2026-09-24 至 25 日实际验证：

- 最终 22 项 JVM 测试通过；主／测试 APK 构建成功；lint 0 错误、10 警告。警告为实验 UI 国际化、图标、目标 API、可调整窗口、工具构造器及物理左坐标提醒；没有加入依赖或掩盖警告。
- 专用 API37 / 16KiB 模拟器，最终安装指纹与本机构建一致；5 项 instrumentation 全部通过（75.857 秒），含 4 项原有布局／取消与新增状态区布局回归。运行时间是整组测试，不是采集延迟。
- 首版真实授权后在 BASELINE 因坐标变化取消：Image 取得／关闭均 1，服务和投屏已停止；保留 3 次旧包相同取消的固定标签日志。新增不需要授权的布局测试先失败，复现状态文字从单行变成两行导致首个色块 top 1040→1047；固定状态区域高度后七阶段坐标回归通过，没有放宽几何一致性门槛。
- 独立只读审查 2 项 P2 已修正：无效旧启动按 `startId` 拒绝，不将整个实例标记 closing；安全窗口观察增加前后控制阶段评估及 5 项回归。排队启动 A/B 的实际 Android 交错尚未单独复现，此项依代码路径／平台 stopSelfResult 语义检查，不冒称设备覆盖。
- AO 实现步骤写入源码后到达 600 秒时限，未成功结束；主任务独立完成实际构建、修复与验证。只读审查完成且项目指纹未改变。没有把委派状态当作代码验收。
- 首次布局修正版及仅换 TRANSLUCENT 的对照版均经用户确认实际投屏：7阶段结束，但 COVER／RESTORE／SECURE_ACTIVITY 超时，保护前后对照均 INSUFFICIENT；分别取得／关闭177/177与179/179帧。不能把 COMPLETED 当通过。格式对照无效，已恢复 OPAQUE。
- 已定位为系统对 FLAG_NOT_TOUCHABLE 应用悬浮层的 alpha 0.8 限制：自有色块实际RGB191/90/83恰为指定色228/82/53与底色41/124/201按0.8/0.2混合；WindowManager 明确记录该包降透明度。开发截图只用于定位自有窗口绘制，不冒充投屏验收。
- 修复只移除固定实验窗口的 FLAG_NOT_TOUCHABLE，保留不可聚焦，窗口仍局限于色块、不覆盖停止按钮、无点击动作；未改系统安全设置和正式放大镜。实际合成回归先失败（6项中此1项失败），修正后6项12.894秒全部通过；22 JVM及构建、Lint0错误10警告通过，安装指纹一致。采集色容差、同帧新鲜度和安全判断均未放宽。
- 透明度修正版经新一次用户确认，在专用模拟器完成实际投屏七阶段，全部满足同帧／连续3帧门槛。普通遮挡／隐藏／恢复成功，安全悬浮层和安全Activity均BLACK_OBSERVED且两个前后对照CONTROLLED_OBSERVATION；取得／关闭38/38、显示1次，结束后服务／投屏／固定悬浮窗均无残留。仅这一合成页／会话通过，华为与按需OCR未完成。成功日志及审计见[七阶段实测](evidence/projection-capture/2026-09-25/opacity-projection-pass/summary.json)。失败及修复证据见[透明度回归](evidence/projection-capture/2026-09-25/opacity-fix/summary.json)。系统策略依据[AOSP透明度限制实现](https://android.googlesource.com/platform/frameworks/base/+/a451d5bae2e2fb1e75152c596a6f476d6f7ac1ec%5E!/)。

构件／原始失败／测试／只读审查记录见 [证据](evidence/projection-capture/2026-09-25/summary.json)。

依据：[MediaProjection](https://developer.android.com/media/grow/media-projection)、[FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)、[Image 时间戳来源](https://developer.android.com/reference/android/media/Image#getTimestamp())（本轮已核实）。

## 2026-09-25 目标华为实测

- 重连后已确认先前安装实际成功，APK SHA256为`2a5d6f248f334c5abef9ea88910504436225fcab72802f84d2ebbf3e740a1e99`，与模拟器同包。没有重复安装或修改代码。
- 首次共享在创建VD后被华为自动出现的通知权限Activity打断：BASELINE取消、BACKGROUND、0/0帧。事件日志包含GrantPermissionsActivity及该包POST_NOTIFICATIONS记录；应用立即结束并未后台继续采集。权限对话结束后由用户主动重新确认共享。
- PID3582/session2、1176×2400，五个公开对照CURRENT_CONFIRMED、两个保护阶段BLACK_OBSERVED、每阶段连续3帧；保护前后对照均CONTROLLED_OBSERVATION。图像取得／关闭41/41、显示1次。
- 服务为空、MediaProjection null、活动窗口列表无固定overlay。华为保留的mLastDisplayFreezeDuration历史窗口名字不代表窗口仍存在；先前全字符串查找的误判与实际列表核对均保留。
- [原始结果与审计](evidence/projection-capture/2026-09-25/huawei-pass/summary.json)。没有真实第三方图像／文字、OCR、翻译或上传；单轮通过不等于长时间稳定性或通用安全检测。现有版本首次通知弹窗仍可能中断，需要用户重新开始；未用忽略后台事件来绕过。
