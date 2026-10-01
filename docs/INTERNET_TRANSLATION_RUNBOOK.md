# 手机独立联网翻译

2026-10-01：代码已准备，**尚未部署、配置真机或完成拔线验收**。最新证据见 `docs/HANDOFF.md`；不能把旧 USB 路线的人工验收当作本路线的验收。

## 运行方式

手机本机整屏 OCR → `kandong-translation-pilot` HTTPS Worker → DeepL API Free → 同次快照镜面。每页仍由用户点“开始翻译”，只选英语或法语；不新增识字预览/二次发送。原快照、选区、手势、60秒时限、敏感过滤、跳过不清楚文字和停止清理不变。

`compat` debug 只接受 `https://kandong-translation-pilot.<账号子域>.workers.dev`，无回环/HTTP/任意供应商/重定向/重试/离线队列。供应商密钥只在 Worker secret。安装时单独配置随机256位设备凭据，最长90天；应用将其导入 Android Keystore AES-GCM 加密的 no-backup 文件并删除明文暂存。APK/Git不含密钥或设备凭据。日常使用无需 Mac 或 USB；首次配置、到期续期和撤销仍由安装者管理，尚无公开注册/账户/订阅功能。release仍仅本机放大。

## 资源和额度

- 独立 Worker：`kandong-translation-pilot`；仅 `/translate`、`/cancel` 和 `/health`。`/health`只表示程序可响应，不检查鉴权/额度或证明DeepL可用。
- SQLite Durable Object：`QuotaAuthority`，固定对象名 `deepl-api-free-pilot-v1`。这是一个共享 DeepL 账户的额度协调器；升级/轮换不得换名或删库来重置额度。
- Secret：`DEEPL_API_KEY`，只接受现有 API Free `:fx` 密钥；`DEVICE_CREDENTIALS`，只存设备ID、凭据SHA256校验值和到期时间。至多16条；当前配置工具只管理这一台 nova 9。
- 全局：20,000字符/日、100,000字符/月、100请求/日、2,000请求/月；每设备10,000字符/日、60,000字符/月、60请求/日、1,200请求/月。均为 UTC 日历窗口，按申请时间计数；额度为本试用服务的上限，不等同于 DeepL 账户的实际剩余额度。
- 单页最多50块、每块1,500 Unicode码点、合计6,000码点；请求/响应各32KiB。整屏所有可读块组成 `context`，不先裁红框。DeepL `context`不计翻译字符，但请求大小仍计入限制；实际账户额度先通过usage核对。
- 请求先持久化扣额、再最多发送一次；超时、崩溃和不确定结果不退款、不重发。串行租约限制此账户的并行处理；取消后不能撤回已经到达供应商的文字。

只持久化设备/随机请求标识、到期/截止时间、状态、额度计数。没有文字、文字哈希、截图或译文缓存。请求记录保留至凭据到期+60秒后清理；额度保留本月和上月，按月清理。清理通过请求和Durable Object alarm执行，无需用户再次打开手机；平台调度可能延迟。每设备最多10,000条请求元数据，全局40,000条。取消先到也建立记录；服务实例重启后仍拒绝重复请求。关闭默认日志/调用日志/追踪，避免平台记录请求细节，代价是当前只有平台汇总指标与固定错误码可用于诊断。

披露合同为 `deepl-cloudflare-direct-v3`，旧v2请求拒绝。按钮旁明确文字经Cloudflare中转到DeepL，含红框外；图片不上传，仅公开页面。DeepL API Free的留存条款不能当作Pro零长期留存承诺。

## 本机验证（不部署）

使用Node24/npm，目录 `backend/translation`：

```sh
npm ci
npm run typecheck
npm test
npx --no-install cf deploy --dry-run
```

使用 `cf` 和 `cloudflare.config.ts`，没有Wrangler配置。`cf`/Vite/测试工具版本与锁文件一起固定；undici固定7.29.1修复初次安装工具链的已知安全问题。测试阻断外部网络，供应商返回均为合成替身；不能证明线上可达、翻译质量或真机拔线能力。故意删除测试表的用例会打印 `no such table: counters`，并断言服务拒绝发送，不是生产数据库故障。

项目根目录使用JDK17运行相称Gradle检查；脚本测试：

```sh
python3 -B -m unittest discover -s scripts -p 'test_provision_translation_cloud.py' -v
```

## 经确认后启用

当前仅核对了已登录Cloudflare账号可访问、订阅列表为空。尚未选择/开通付费计划；不升级套餐，不承诺任意流量均免费。启用前明确确认账号、以上资源/额度、将现有DeepL密钥作为服务端secret上传，以及后续合成/真机验证范围。AGENTS中既有commit/push许可不包含部署。

1. 确定该账号已有的workers.dev子域，记录实际目标origin。不要读/改/复用其他项目资源。
2. 项目根目录运行下列命令；endpoint只是服务地址，不含凭据。脚本私下读取既有DeepL配置，生成忽略目录 `.local/translation/cloud/` 中0600的server-secrets.json和phone-config.json。重复prepare保留原凭据；轮换必须明确加 `--rotate`。

   ```sh
   python3 -B scripts/provision_translation_cloud.py prepare --endpoint https://kandong-translation-pilot.ACCOUNT.workers.dev
   ```

3. 在 `backend/translation`，核对账号后以 `CLOUDFLARE_ACCOUNT_ID`指定它；部署已验构件并私下提供secret文件。**以下是真正部署命令，未在本轮执行**：

   ```sh
   npx --no-install cf deploy --prebuilt --mode production --secrets-file ../../.local/translation/cloud/server-secrets.json
   ```

   初次部署应创建此Worker自己的SQLite命名空间；保存返回的版本、地址和元数据，不保存secret值。不得把secret作为命令行参数或打印文件内容。
4. 记录实际部署版本；只先检查无内容健康响应及鉴权拒绝。真实DeepL合成调用需要明确限定验证范围，真实页面只由用户主动开始。
5. 更新已核验的NAM-LX9/2AS0221B09001601开发版，再执行 `python3 -B scripts/provision_translation_cloud.py provision`。只通过stdin写入应用no_backup暂存；不开启权限、不创建USB转发、不自动发送页面。用户第一次开始翻译时完成Keystore导入。
6. 删除该手机 `tcp:18741` 旧反向转发并关闭本次KanDong relay；按[拔线验收](INTERNET_TRANSLATION_ACCEPTANCE.md)执行。系统安装/共享确认仍由用户操作。

## 撤销与回滚

`python3 -B scripts/provision_translation_cloud.py revoke`仅准备本地禁用secret文件（设备清单为空）；随后用明确批准的部署命令应用它，才算远端撤销。此操作不删除/重置额度库。轮换保持设备ID与authority名，凭据值会改变，需要重新配置手机。

失败时先禁用新请求，再部署已知兼容的Worker构件；不回滚/删除持久化额度与请求记录，不降级HTTP或自动改回Mac中转。新的云端App没有USB回退。初次上线前手机旧版仍可保留；需要恢复旧版必须明确操作，不能把旧路线称为独立联网。撤销生效前已经发送到DeepL的内容不能撤回。

## 官方依据

- [cf构建与部署、dry-run及secret文件](https://developers.cloudflare.com/cf/projects/)
- [cf配置与SQLite导出](https://developers.cloudflare.com/cf/projects/cloudflare-config/)
- [Durable Object持久存储](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/)；[免费/付费额度](https://developers.cloudflare.com/durable-objects/platform/pricing/)
- [DeepL上下文参数](https://developers.deepl.com/api-reference/translate/request-translation)；[码点计数及usage](https://developers.deepl.com/docs/resources/usage-limits)；[隐私第3/13节](https://www.deepl.com/en/privacy)
