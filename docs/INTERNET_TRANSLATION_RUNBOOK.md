# 手机独立联网翻译

2026-10-01：用户明确允许后，**云端已部署，nova 9已更新配置；英语页及物理拔线后新翻译法语页均获用户成功确认**。最新证据见 `docs/HANDOFF.md`；不能把旧 USB 路线的人工验收当作本路线的验收。

## 运行方式

手机本机整屏 OCR → `kandong-translation-pilot` HTTPS Worker → DeepL API Free → 同次快照镜面。每页仍由用户点“开始翻译”，只选英语或法语；不新增识字预览/二次发送。选区、手势、敏感过滤、跳过不清楚文字和停止清理不变。60秒仅限制未完成的处理；已完成的快照译文持续可读，点“实时”退出，或等待下一次翻译成功替换。收起/菜单保留已完成结果，不自动发送请求。

`compat` debug 只接受 `https://kandong-translation-pilot.<账号子域>.workers.dev`，无回环/HTTP/任意供应商/重定向/重试/离线队列。供应商密钥只在 Worker secret。安装时单独配置随机256位设备凭据，最长90天；应用将其导入 Android Keystore AES-GCM 加密的 no-backup 文件并删除明文暂存。APK/Git不含密钥或设备凭据。日常使用无需 Mac 或 USB；首次配置、到期续期和撤销仍由安装者管理，尚无公开注册/账户/订阅功能。release仍仅本机放大。

## 资源和额度

- 独立 Worker：`kandong-translation-pilot`；仅 `/translate`、`/cancel` 和 `/health`。`/health`只表示程序可响应，不检查鉴权/额度或证明DeepL可用。
- SQLite Durable Object：`QuotaAuthority`，固定对象名 `deepl-api-free-pilot-v1`。这是一个共享 DeepL 账户的额度协调器；升级/轮换不得换名或删库来重置额度。
- Secret：`DEEPL_API_KEY`，只接受现有 API Free `:fx` 密钥；`DEVICE_CREDENTIALS`，只存设备ID、凭据SHA256校验值和到期时间。至多16条；配置工具仅允许已登记的nova9/NAM-LX9与lio/LIO-AN00，各自独立凭据和固定设备ID。
- 全局：20,000字符/日、100,000字符/月、100请求/日、2,000请求/月；每设备10,000字符/日、60,000字符/月、60请求/日、1,200请求/月。均为 UTC 日历窗口，按申请时间计数；额度为本试用服务的上限，不等同于 DeepL 账户的实际剩余额度。
- 单页最多50块、每块1,500 Unicode码点、合计6,000码点；请求/响应各32KiB。整屏所有可读块组成 `context`，不先裁红框。DeepL `context`不计翻译字符，但请求大小仍计入限制；实际账户额度先通过usage核对。
- 请求先持久化扣额、再最多发送一次；超时、崩溃和不确定结果不退款、不重发。串行租约限制此账户的并行处理；取消后不能撤回已经到达供应商的文字。

只持久化设备/随机请求标识、到期/截止时间、状态、额度计数。没有文字、文字哈希、截图或译文缓存。请求记录保留至凭据到期+60秒后清理；额度保留本月和上月，按月清理。清理通过请求和Durable Object alarm执行，无需用户再次打开手机；平台调度可能延迟。每设备最多10,000条请求元数据，全局40,000条。取消先到也建立记录；服务实例重启后仍拒绝重复请求。关闭默认日志/调用日志/追踪，避免平台记录请求细节，代价是当前只有平台汇总指标、固定错误码和认证响应头`X-KanDong-Upstream`可用于诊断；该头仅包含usage/translate及HTTP状态或transport，不包含供应商正文或异常。

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

## 本次已获授权的部署与后续配置

用户对专用服务部署、服务端密钥及小量合成/手机验证已明确回复“允许”。账号认证及订阅只读核对完成，没有购买或升级套餐；不承诺任意流量均免费。此次授权覆盖下述专用路线，无需重问；其他部署或Release不在范围内。

实际origin为 `https://kandong-translation-pilot.zhyphil.workers.dev`，当前版本 `306dcc9a-cc42-4876-826f-da5561e8c84a`（2026-10-02仅更新设备凭据；代码仍是10月1日已验版本）。首次先部署空凭据关闭态并验证503，再启用secret；已验证两项绑定类型为secret_text。当前43项本机Workers/类型/production预检通过；线上英法各一页返回正确时间和否定条件，鉴权/旧披露/重复/取消通过。初次失败及修复见[启用证据](evidence/live-translation/2026-10-01/cloud-activation.json)。步骤1–6的部署、手机更新配置与旧路线清理已完成；用户确认英语页显示中文、物理拔线后新翻译法语页成功，加密配置已核对。用户亦确认约两分钟持续阅读、全文返回、收起恢复及主动实时切换正常；当前下一项为真实断网/弱网及失败恢复。不要重置额度、重新生成凭据、重复安装或重复合成调用。

以下保留可重复执行的操作步骤：

1. 确定该账号已有的workers.dev子域，记录实际目标origin。不要读/改/复用其他项目资源。
2. 项目根目录运行下列命令；endpoint只是服务地址，不含凭据。脚本私下读取既有DeepL配置，生成忽略目录 `.local/translation/cloud/` 中0600的server-secrets.json和phone-config.json。重复prepare保留原凭据；轮换必须明确加 `--rotate`。

   ```sh
   python3 -B scripts/provision_translation_cloud.py prepare --endpoint https://kandong-translation-pilot.ACCOUNT.workers.dev
   ```

3. 在 `backend/translation`，核对账号后以 `CLOUDFLARE_ACCOUNT_ID`指定它；部署已验构件并私下提供secret文件。**以下会实际部署；本轮已按授权执行，后续仅在构件或secret变更时运行**：

   ```sh
   npx --no-install cf deploy --prebuilt --mode production --secrets-file ../../.local/translation/cloud/server-secrets.json
   ```

   初次部署应创建此Worker自己的SQLite命名空间；保存返回的版本、地址和元数据，不保存secret值。不得把secret作为命令行参数或打印文件内容。
4. 记录实际部署版本；只先检查无内容健康响应及鉴权拒绝。真实DeepL合成调用需要明确限定验证范围，真实页面只由用户主动开始。
5. 更新已核验的NAM-LX9/2AS0221B09001601开发版，再执行 `python3 -B scripts/provision_translation_cloud.py provision`。只通过stdin写入应用no_backup暂存；不开启权限、不创建USB转发、不自动发送页面。用户第一次开始翻译时完成Keystore导入。
6. 删除该手机 `tcp:18741` 旧反向转发并关闭本次KanDong relay；按[拔线验收](INTERNET_TRANSLATION_ACCEPTANCE.md)执行。系统安装/共享确认仍由用户操作。

## 增加已登记的LIO测试机

2026-10-02用户指定重新接入LIO-AN00/2KE0220109017133。已安装已验APK，已增加独立huawei-lio-an00凭据；nova9清单项与私有文件保持不变，两个凭据均在旧披露拒绝检查中鉴权成功，不调用DeepL。首次配置时USB中断；用户切换传输文件后已重连、成功provision并打开App，核对私有暂存167字节、首页前台、reverse为空。首次翻译时才导入Keystore，真实试用仍待用户启动；当前不再重复prepare/provision/安装。下述为通用命令，详见[手机接入证据](evidence/device-onboarding/2026-10-02-lio-cloud/summary.json)。

```sh
python3 -B scripts/provision_translation_cloud.py prepare --device lio --endpoint https://kandong-translation-pilot.zhyphil.workers.dev
python3 -B scripts/provision_translation_cloud.py provision --device lio
```

`prepare`只准备本机私有文件，不部署；重复调用幂等，`--rotate`只替换所选设备的凭据，不改其ID或其他设备。默认`--device nova9`兼容原操作。不得把nova9的phone-config.json复制给LIO；后者独立文件为phone-config-lio.json。只有准备内容变更时才更新云端secret；本次已经更新，不重复操作。

若后续仅设备清单变动，可从项目根目录私下生成补丁：

```python
from scripts.provision_translation_cloud import DIRECTORY, SERVER, read_private, write_private
server = read_private(DIRECTORY / SERVER)
write_private(DIRECTORY / "device-secrets-patch.json", {"secrets": {
    "DEVICE_CREDENTIALS": {"name": "DEVICE_CREDENTIALS", "type": "secret_text", "text": server["DEVICE_CREDENTIALS"]}
}})
```

在已核对的账号和`backend/translation`执行`cf workers secrets bulk --worker kandong-translation-pilot --file ../../.local/translation/cloud/device-secrets-patch.json`，标准输出/错误仅保存到0600私有日志、不打印原始内容。此[官方补丁接口](https://developers.cloudflare.com/api/resources/workers/subresources/scripts/subresources/secrets/methods/bulk_update/)保留未列出的secret；补丁不包含DeepL密钥，不改Worker代码或额度对象。记录活动部署版本，再用旧披露的无内容请求验证新旧凭据；不要用真实页面做自动探针。

## 撤销与回滚

`python3 -B scripts/provision_translation_cloud.py revoke`仅准备本地禁用secret文件（设备清单为空，清理两台手机的本机私有配置文件）；随后用明确批准的部署命令应用它，才算远端撤销。此操作不删除/重置额度库。轮换保持设备ID与authority名，凭据值会改变，需要重新配置手机。

失败时先禁用新请求，再部署已知兼容的Worker构件；不回滚/删除持久化额度与请求记录，不降级HTTP或自动改回Mac中转。新的云端App没有USB回退。初次上线前手机旧版仍可保留；需要恢复旧版必须明确操作，不能把旧路线称为独立联网。撤销生效前已经发送到DeepL的内容不能撤回。

## 官方依据

- [cf构建与部署、dry-run及secret文件](https://developers.cloudflare.com/cf/projects/)
- [cf配置与SQLite导出](https://developers.cloudflare.com/cf/projects/cloudflare-config/)
- [Durable Object持久存储](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/)；[免费/付费额度](https://developers.cloudflare.com/durable-objects/platform/pricing/)
- [DeepL上下文参数](https://developers.deepl.com/api-reference/translate/request-translation)；[码点计数及usage](https://developers.deepl.com/docs/resources/usage-limits)；[隐私第3/13节](https://www.deepl.com/en/privacy)
