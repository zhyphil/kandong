# 安全与隐私：从 Phase0A 开始

## 两个版本的数据边界

| 版本 | 实际接触的数据 | 权限 | 保存/网络 |
| --- | --- | --- | --- |
| app 原生窗口放大 | 系统放大参数与源区域几何；不读节点/像素 | 用户手动开启无障碍，canControlMagnification | 不保存页面、无INTERNET |
| compat 华为验证版 | 经每次系统授权取得屏幕帧；内存中裁剪选定区域 | 悬浮窗、前台媒体投屏服务；无无障碍权限 | 不录像、不落盘、不上传、无INTERNET |

不能把原生版“不截图”的说明套用到兼容版，也不能把“只复制局部”说成系统只采集局部。compat 的系统缓冲区可能含整屏内容，必须准确告知；每帧使用后关闭，替换局部图像时释放旧Bitmap。内存释放不等于密码学擦除。

系统原生镜面不是敏感节点过滤器，它可能显示用户眼前的敏感文字；不会宣称密码已过滤。旧节点过滤只属于被冻结的引导实验，当前没有运行。

## 用户控制与生命周期

不替用户开启无障碍、悬浮窗或投屏授权；用户点击并同意本次使用后才开始。系统授权退出/取消不启动。停止、撤回同意、锁屏、旋转、服务退出应清理自有窗口与会话；compat必须结束MediaProjection/前台服务，重新开始要重新授权。默认不恢复进程前的会话。

原生版不接管用户已有系统放大。仅对有依据属于本次请求的窗口尝试关闭；关闭未确认时不能假装成功。Android公共API无owner ID，其他服务同时操控同一模式的竞态列为已知限制。

兼容版显示窗、移动柄和缩放柄用FLAG_SECURE，取景框与任一可见受保护控件重叠时停止显示旧帧。自动避让只移动自有显示窗，不操作底层App。单次手势只跟踪一个指针，多指或系统取消不能转成底层点击，也不触发边缘吸附。受保护页面可能黑屏；不绕过FLAG_SECURE/DRM/系统授权，也不从隐藏窗口补采。先用合成页或非敏感普通网页验证；本轮没有银行、密码、OTP或支付页面采集。

## 人亲自操作

所有最终动作由用户完成。生产代码没有自动点击、输入、购买、支付、确认、提交，也没有无障碍performAction/performGlobalAction/dispatchGesture。测试触摸只在合成或明确指定的普通测试页面执行，不作为产品能力。

兼容服务不导出；原生服务由BIND_ACCESSIBILITY_SERVICE保护；没有导出的屏幕数据IPC。AndroidX自身signature动态接收器权限不是网络或截图权限。禁用备份，不存历史。两条路线均无录音、账号、分析埋点或云服务。

## 后续阶段

0B文本采集优先区域内树节点，密码/editable/标记敏感先过滤；OCR不是绕过敏感保护的理由。静态个人信息可能未正确标注，不能声称过滤完整。原文/译文只留本次短时上下文，移动/页面变化/停止失效。翻译与AI必须分别说明用途，兼容版屏幕共享同意不自动涵盖云端上传。

基础隐私、授权、停止和保守失败现在实施；Phase3加强识别、长辈UX、TTS及性能。商店发布仍需真实用途声明和审查。`isAccessibilityTool=true` 仅适用于帮助残障用户，不能当审核保证。[Google Play政策](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)、[MediaProjection官方授权规则](https://developer.android.com/media/grow/media-projection)

兼容版取景边框虽然内部透明，Android12按整个窗口alpha判断跨UID触摸。已将边框window alpha设为不高于系统`maximumObscuringOpacityForTouch`的值，未关闭系统触摸保护；模拟器实际穿透回归通过。手机及拒绝遮挡触摸的App仍需逐项验证。[Android12官方触摸规则](https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events)
