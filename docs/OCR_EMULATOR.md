# 本机 OCR 模拟器验证

2026-09-24：用户暂时无法提供妈妈手机，后续使用 KanDong 专用模拟器。当前完整 OCR 的同一最终 APK 已在新环境两轮各13项通过；没有重新构建或替换算法来改变测试结果。

## 环境与结论

- AVD：`KanDong_OCR_API37_16K`，存储于 `/private/tmp/kandong-avd`，仅本项目使用；ADB `emulator-5582`。
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
ANDROID_AVD_HOME=/private/tmp/kandong-avd /Users/haoyuzuo/Library/Android/sdk/emulator/emulator -avd KanDong_OCR_API37_16K -port 5582 -no-snapshot -no-boot-anim -no-audio -no-window -gpu swiftshader -memory 3072
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
