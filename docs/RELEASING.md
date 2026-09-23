# 兼容版发布

发布产品为 `compat`（包名 `com.kandong.compat`），`app`平台实验和`fixture`不作为下载资产。

## 本地签名身份

首次发布已创建项目专用发布密钥，保存在被Git忽略的 `.signing/`；目录权限700、密钥及配置600。它与Android调试密钥不同。**请由项目所有者将整个目录保存到安全的离线或加密备份，不能上传仓库、Release或粘贴到聊天。丢失后无法沿用同一签名升级已有安装。** 不自动生成替代密钥。

`.signing/release.properties`只在本机提供以下字段（示例无实际密码）：

```properties
storeFile=.signing/kandong-release.p12
storePassword=<private>
keyAlias=kandong-release
keyPassword=<private>
```

新工作环境应恢复同一密钥及配置。缺配置时普通 `assembleRelease` 只能得到未签名产物；`scripts/release.sh`会拒绝生成可分发版本，绝不回退使用调试签名。签名参考[Android官方文档](https://developer.android.com/studio/publish/app-signing)。

## 制作下一版

1. 只在用户授权发布时执行。更新`compat/build.gradle.kts`中的versionName，versionCode必须递增；编写对应`docs/releases/v版本.md`。
2. 使用JDK17/项目Gradle Wrapper/Android Build Tools35.0.0，运行 `./scripts/release.sh`。可用`KANDONG_JAVA_HOME`、`KANDONG_ANDROID_SDK`指定安装路径。
3. 脚本构建release、运行release单测及Lint，验证APK签名和对齐、非debuggable和无INTERNET，输出`dist/v版本/`中的APK、校验和、签名信息、元数据与说明。核对签名指纹与前一正式版一致。`dist/`不提交Git。
4. 对实际发布APK作安装/启动/授权/停止检查；调试版签名不同，不能覆盖安装，不能未经同意卸载用户手机版本。
5. 提交版本记录，创建注释标签，推送指定分支/标签；创建GitHub Release并上传上述公开产物。发布前检查目标仓库、版本和资产，发布后核对远端标签提交及下载SHA256。

正式版签名与开发版隔离；签名变更的Android规则见[应用签名](https://developer.android.com/studio/publish/app-signing)，校验工具见[apksigner](https://developer.android.com/tools/apksigner)。不把侧载Release当作已通过应用市场审核。
