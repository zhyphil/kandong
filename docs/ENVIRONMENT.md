# 开发环境检查（2026-09-23）

项目唯一根目录：`/Users/haoyuzuo/Projects/KanDong`。检查时目录为空，没有 Git 历史或用户文件。已初始化 `main`，origin 为 `git@github.com:zhyphil/kandong.git`；只读 `git ls-remote origin` 成功、无 refs，远端为空。未推送。

| 项目 | 本机检查结果 | 本项目使用 |
| --- | --- | --- |
| 系统 | macOS Darwin 25.6.0 / arm64 | 本机开发 |
| Android Studio | AI-261.26222.65.2614.16204760（2026.1.4） | 可直接打开根目录 |
| 默认 Java | Oracle JDK 25.0.2 | 不用于 Gradle 8.13 |
| 可用 JDK 17 | Temurin 17.0.20.1 | Gradle 与 JVM 编译目标 17 |
| Android SDK | `~/Library/Android/sdk` | local.properties 指向此目录，不提交 |
| SDK platforms | 35、36、36.1、37.2 | compile/target 36 |
| Build Tools | 35.0.0、36.0.0 | 由项目固定 |
| Gradle | PATH 无全局命令，有官方本机缓存 | 官方 Wrapper 8.13 |
| Kotlin | PATH 无独立 kotlinc | Gradle 插件负责 Kotlin 编译 |
| ADB | 37.0.1 | 显式调用 SDK platform-tools |
| 设备 | 起始检查无手机或运行中的模拟器 | 新建 KanDong_Phase0_API36 |

专用测试模拟器使用已有 `system-images;android-36;google_apis;arm64-v8a`，数据在 `/private/tmp/kandong-avd`，序列号 `emulator-5580`。未复用其他项目的模拟器或文件。初始请求1080×2400，设备测试时实际查询为1080×1920 / 420dpi；以下运行证据以实际参数为准。临时目录可能被系统清理；日后可在 Android Studio 建立自己的 KanDong AVD。

Gradle Wrapper 由本机官方 Gradle 8.13 的 wrapper task 生成，临时生成任务已成功。分发包 SHA-256：`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`，取自 [Gradle 官方校验文件](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256)，写入 wrapper properties。项目实际编译及运行结果另见最终验证记录。

初始沙箱网络访问不能解析 github.com；通过主机授权网络重新执行后 SSH 远端读取成功。这不是仓库权限失败。依赖下载和本机构建需要相应缓存写入/网络权限。

版本固定以可复现为目的，不声称采用最新版本。AGP 8.13 与 Gradle 8.13 / JDK 17 的兼容性参见 [Android 官方说明](https://developer.android.com/build/releases/agp-8-13-0-release-notes)。
