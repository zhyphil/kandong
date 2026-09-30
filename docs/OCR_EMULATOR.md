# 本机 OCR 模拟器验证

2026-09-24：用户暂时无法提供妈妈手机，后续使用 KanDong 专用模拟器。当前完整 OCR 的同一最终 APK 已在新环境两轮各13项通过；没有重新构建或替换算法来改变测试结果。

## 环境与结论

- AVD：`KanDong_OCR_API37_16K`，仅本项目使用；ADB `emulator-5582`。2026-09-24的临时目录 `/private/tmp/kandong-avd` 后续已不存在；2026-09-30用本机同一revision 9镜像重建于 `/Users/haoyuzuo/Projects/KanDong/.local/avd`（Git忽略），不依赖其他项目或重新下载镜像。
- Emulator 37.1.11；本机已有镜像 `system-images;android-37.1;google_apis_playstore_ps16k;arm64-v8a` revision 9；运行时 API37/Android17，ARM64，16KiB 内存页，4核/3GiB。
- 新镜像正常报告 CPU 能力（本轮没有 SVE/SVE2）。原 API36 环境加载 OpenCV 时的 SIGILL 未被修复；这是经验证的替代环境，不宣称已修复底层根因。旧失败记录保留。
- OpenCV 5.0.0.1、ORT1.30、模型、固定参考和验收门槛均未变，未伪造 CPU 能力、修改库分派或降低标准。
- 主 APK SHA：`7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea`。
- 测试 APK SHA：`768ab0228275fce79c05d01f9c6ceffb908fcd351f89b0c4542a63715fd24599`。安装后实际文件指纹核对通过，和华为最终验收一致。

## 验收

固定入口实跑两轮，每轮13项：完整OCR2、原图/检测回归7、几何1、扩展1、完整框1、裁剪识别1。此前临时入口已做两轮环境初检；归档以下正式入口的两轮，不把初检当成额外覆盖。

完整流程每轮十原图/十检测、五空图零识别、九裁剪、两模型十次识别/18行位置绑定；116691裁剪颜色通道、570960缩放通道、631728输入浮点逐位一致。九个评分整数区域一致；215 Mat缓冲、41 Bitmap、46 ORT关闭计数闭合。

七类报告两轮仅排除运行ID和计时后完全相同。与已验收华为对比时另仅排除顶层设备元数据及恢复后的OpenCV默认线程数（模拟器4/华为8）；实际计算均1线程，全部内容/概率/像素/文字/绑定与该基线相同。完整流程测试4.413/4.393秒包括断言与写报告，不是产品响应时间。

[验收摘要](evidence/ocr-emulator/2026-09-24/acceptance-summary.json)和同目录原始报告、仪器输出、环境及检查脚本可核查。此次不改应用源码，沿用最终包已通过的62项JVM/构建证据，没有重复宣称一次新的构建。

## 本机运行

工作目录 `/Users/haoyuzuo/Projects/KanDong`。只启动此专用AVD；不能使用其他项目的设备。现有AVD已创建，不需要重复创建或安装系统镜像。

```sh
ANDROID_AVD_HOME=/Users/haoyuzuo/Projects/KanDong/.local/avd /Users/haoyuzuo/Library/Android/sdk/emulator/emulator -avd KanDong_OCR_API37_16K -port 5582 -no-snapshot -no-boot-anim -no-audio -no-window -gpu swiftshader -memory 3072
```

这是持续运行的进程，可另开终端。需要可视窗口时去掉 `-no-window`。等待启动完成后，必要时安装上述同一基线包：

```sh
/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb -s emulator-5582 shell getprop sys.boot_completed
/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb -s emulator-5582 install -r modelprobe/build/outputs/apk/debug/modelprobe-debug.apk
/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb -s emulator-5582 install -r modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk
python3 -B scripts/run-ocr-emulator.py --output /private/tmp/kandong-ocr-emulator-new-run
```

入口拒绝非本专用AVD、不同安装指纹、非API37/16KiB环境或已存在输出目录；每轮先删除该实验的旧报告，核对新运行ID，结束时只停止实验进程。不采集用户屏幕，不使用手机。新版本需更新对应的验收身份，不能把此冻结入口静默当成任意包通过。

停止模拟器：

```sh
/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb -s emulator-5582 emu kill
```

## 后续范围

- 本机继续合成 EN/FR/中文、共同检测框的双模型候选、上下文/位置映射等实验。
- 手机专项暂缓：华为系统权限和后台行为、真实手势体验、发热/耗电；不以模拟器补勾这些项目。
- 本轮只补当前OCR数值链路的第二设备证据，未完成三语质量、真实屏幕授权、翻译或正式产品接入。


## 后续实验包

候选关联v2已完成两轮每轮14项模拟器验收，使用独立入口 `scripts/run-candidate-emulator.py`；详情及新构件身份见[候选v2](CANDIDATE_SELECTION_V2.md)。上文冻结旧包入口保持原身份检查。

2026-09-30：重建的专用AVD已核对API37、arm64-v8a、16384字节页和两安装包哈希，20项跨段关联同源测试通过；[本轮记录](FULL_PAGE_OCR_ASSOCIATION.md)。主实验APK未变，测试APK已更新为 `6ed22110a0b4dd21e338287a5f7309822cae1a2269bfeda559f18241304fe51f`；上方9月24日冻结入口及旧APK身份是历史证据，不适用于新包。没有重新运行模型或采集屏幕。

2026-09-30后续实际OCR→关联接线：最终测试包8eb4858bc13b035f1009d60238c8c62323a11b17d27ac0618bb706e0e3c10706，实际安装哈希一致；72项通过64.666秒，20页技术通过、9生命周期情形及原回归完成。详见[接线记录](OCR_ASSOCIATION_HANDOFF.md)。主实验包未变；以上历史冻结入口不能直接验证该新包。


2026-09-30整页证据→选区投影：当前模拟器测试APK为8b76c71d36e55bf885eaeaa6d90c581637cea53706644ead86f39f4687d78298，主实验包未变。202 JVM／构建／Lint通过后，模拟器94项通过76.218秒，含固定两页真实Image／OCR、16候选无损投影及原60秒TTL实际失效；[本轮说明](OCR_REGION_CONTEXT.md)。使用新证据目录run-emulator.py及此测试SHA、94项目标数，不复用历史冻结入口。没有读取屏幕或更新手机；实验和模拟器已停止。


2026-09-30可视化实验完成：modelprobe新增debug薄宿主，主实验APK更新为55291acf66cc2bdb5d7b40ffd0bc368bcf441ce61f33f9ffa3b0f173e7b279ca，测试包7f3816273ada270057f8a58dfcab85622b98bb3733c9ff5bebecf63cce6affb7，两包均已核对安装哈希。217 JVM／构建／Lint，专用模拟器59项全过（7UI、真实60秒清理），独立冷启动／横屏菜单／旋转清空／缺包提示通过；旧58/59失败和修正保留。见[运行及截图](OCR_REGION_VISUAL_LAB.md)。专用模拟器已停止；手机未更新，不复用历史冻结runner到新包。


2026-09-30后续：固定整页实际OCR接入独立可视化实验，最终配套包主214b503f…、测试b6f66c56…，本机236项、模拟器7专项＋79回归两批及独立冷启动四页／旋转再次推理通过。缺包验证后已恢复同SHA；结束停止模拟器。详见[本轮说明](OCR_REGION_REAL_LAB.md)及[证据](evidence/region-real-ocr/2026-09-30/summary.json)。
