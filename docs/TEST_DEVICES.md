# KanDong测试设备

2026-09-30，用户已将当前真机换为华为nova 9。每次操作先核对实际连接身份，不对其他设备或App执行测试；新机结果与旧机历史分别记录。

## 当前真机

| 项目 | 设备报告 |
| --- | --- |
| 名称／型号 | HUAWEI nova 9／NAM-LX9 |
| 产品／设备 | NAM-L29／HWNAM |
| ADB序列 | 2AS0221B09001601 |
| 系统 | Android12／API31；EmotionUI_13.0.0 |
| ABI／内存页 | arm64-v8a（另支持32位ARM）；4096字节 |
| 屏幕／密度 | 1080×2340；480dpi，无手动修改 |

已安装com.kandong.modelprobe（看懂模型验证）与com.kandong.modelprobe.test（看懂缩放验证）。两者本地／实际安装SHA分别为7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea和6ed22110a0b4dd21e338287a5f7309822cae1a2269bfeda559f18241304fe51f，与553cc56已验收构建一致。复用现有构件，没有新建包或更改代码。

本轮新机26项通过：FullPageOcrAssociationTest的20项固定文字／几何反例，以及AndroidImageStripProbeTest的6项实际ImageWriter→ImageReader色块、异常关闭与槽位复用检查，测试器总耗时0.757秒。图像使用固定1176×2400、33×35、32×33、32×32尺寸，不是对新机实际屏幕分辨率的采集验收。

原始身份、安装记录、两包哈希、26项逐项日志及复验命令见[证据摘要](evidence/device-onboarding/2026-09-30-nova9/summary.json)。使用前核对型号、API、页大小和安装哈希；系统安装或屏幕共享的确认由用户完成。

正式compat未安装；新机OCR模型推理、屏幕共享／隐私、窗口遮挡、放大镜手势和性能尚未验收。本轮不采集屏幕、不申请共享、不联网。下一开发任务仍为独立整屏OCR→关联器接线。

## 保留的环境

- 旧华为LIO-AN00/API31，序列2KE0220109017133：兼容要求和既有证据保留，当前不默认操作；原39项整屏控制台因USB中断不完整，恢复报告和36短回归的边界不变。
- 专用模拟器KanDong_OCR_API37_16K：API37／ARM64／16KiB，启动方式见[OCR_EMULATOR.md](OCR_EMULATOR.md)，模拟器结果不代替真机。

历史证据脚本可能限定旧序列／型号或APK。新机使用明确记录的新命令，不修改冻结证据、不跳过身份检查、不把旧结果改标为新设备通过。
