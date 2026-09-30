# 固定整页实际 OCR 与选区界面

2026-09-30，起点7e45a3c。独立“看懂选区实验”默认运行固定图片的实际整页 OCR：点击后取得整页、在后台运行检测与识别，再把完整候选及选区位置显示出来。没有读取手机当前屏幕，没有翻译或联网，没有接入正式放大镜。

## 输入与显示

固定en-normal／fr-seam分别使用latin模型，hans-normal／hant-seam使用ch模型，均为1176×2400。模型映射按已认证fixture声明的单一语言预先确定；简体与繁体分别记录，不用期望答案或识别结果挑模型。沿用既有模型、字典、manifest、PNG及RGBA校验，没有修改冻结素材。

每页两个小图块只解码一次，私有像素数组不暴露，调用方只能得到行副本或绘制。ImageWriter输入、整页预览与镜面共享同一个PreparedPage，白色间隔和整数摆放位置也一致；不创建整页Bitmap、不按参考文字重绘。实际Image逐行RGBA对照覆盖四页，之后绘制和推理不再解码。

红框拖动、右上角改宽高、独立1～5倍滑杆、双指缩放与单指平移复用已验收界面。几何按实际页面尺寸限制；角标24dp、向视图内平移的48dp触摸区保持。倍率仍以整页预览为参照；这是固定长页的工程概览，不是正式放大镜的画质或长辈可读性验收。

原始候选、空字符串、非核心重复、来源四角、检测分数、空间组和冲突完整保留；卡片只改变展示顺序，框外上下文仍在。空间组不是语义理解；镜面中的原图不是译文，也不表示选择了某条识别答案。原合成手势模式由本地测试接口显式选择，不是实际模式的失败回退，不接受外部Intent参数选择。

## 后台与失效

- 进程共用一个串行原生执行器和运行槽：最多一项任务、零待运行OCR。忙碌时点击不排队，清理完成后需再次主动点击。
- 后台拥有原Controller／Guard／Ticket／Image／ORT／Mat。必须退出Image及两模型范围、核实实际关闭计数并恢复OpenCV线程数，才领取发布许可、关联并产生只读显示证据。原Guard不跨线程，清理不确定会禁止同进程继续识别。
- 界面只持有不可变证据，复核请求身份、生命周期、页面、单调时钟和原始取得时间＋60秒期限；接收结果和移框不会续期。几何只调用投影，不重复模型推理。
- 收起、菜单、换页、后台、停止和销毁同步撤下显示并撤销请求；待交付消息被清除，旧回调先核对身份，不能更改新结果或新时钟。恢复不自动读图。弱订阅及只持有applicationContext的后台任务避免持有Activity。
- 取消不会强制中断正在执行的JNI；在返回后的检查点丢弃结果，运行槽保持到清理结束。丢弃引用不是擦除所有堆副本。

## 独立加载与运行

Java debug宿主只接受固定com.kandong.modelprobe.test，校验同签名、协议2与ARM64未压缩ELF条目。缓存同一测试包类加载器；独立冷启动搜索安装nativeLibraryDir和APK内lib/arm64-v8a，Kotlin／ORT由主包父加载器提供、OpenCV由测试包加载。安装包身份变化后要求重启进程，不在同进程切换原生类加载器。fixture／detector读取测试包资源，recognizer／dictionary读取主包资源，不依赖InstrumentationRegistry。

依旧用JDK17运行：

```sh
JAVA_HOME=/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home \
  ./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:assembleDebug \
  :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug
```

仅在身份核验后的[项目专用模拟器](OCR_EMULATOR.md)安装配套两包，然后打开“看懂选区实验”或组件com.kandong.modelprobe/.RegionVisualLabActivity。点“整页 OCR”，等待候选出现；“换页”循环英语、法语、简体和繁体，换页后需再次点击。操作菜单、收起或离开会清除结果。无需屏幕共享或悬浮权限。

本轮主实验APK214b503f34ef14aa020773bd5ead66e4e51e7909c1a5dea34003f8ef1b2b3ed4；测试APKb6f66c56bcaa61258498f7ee32939dd1253db903a5e9d41c0723f1db2ade3ff8。没有新增依赖、权限或修改正式app／compat／graphics／ocrlab及modelprobe旧main源码。

## 验证

根任务先验证10项异步边界红灯，原断言保留；补充时钟／开始时间／加载失败／执行器拒绝及页面尺寸／失效测试。本机最终236项通过，两包离线构建成功，Lint0错误4既有警告。

第一轮实际OCR被完整性检查错误拒绝：关联器复制Provenance，而Candidate相等比较使用了Provenance对象身份。独立审查确认P1；根任务增加独立快照相等／18字段变化及分数正负零回归，实际复现1失败后改为逐字段比较，不删除完整性检查。第一轮模拟器批次在记录实际失败后由根任务主动停止，未完成部分不计通过。

修复后专用API37／ARM64／16KiB模拟器分两批通过：7项新专项31.306秒，79项回归150.332秒。前者包括四页实际模型推理、四页实际Image逐行像素对照、四页UI接线、不重新推理的几何操作，以及推理已经完成但Image／模型范围尚未关闭时重建Activity的取消验证。该验证用仅本地测试可注入的有界闩锁，确认旧结果未交付、旧槽占用时新请求被拒绝、关闭后不自动恢复、再次点击才成功。它不声称在JNI内部硬中断。

79项包括原7项合成手势UI、14异步桥、18会话、24选区控制器、13发布、2原生生命周期及1实际繁体／空白页投影测试；含真实等待60秒失效。两批是同一配套APK，不能称为一次86项运行。技术链路通过不等于OCR质量改善；旧错字、重复与翻译质量问题保持未通过。

最终同SHA配套包脱离instrumentation后冷启动成功，四页各4次检测，分别8／20／9／16次识别和相同数量候选，全部关闭计数平衡。旋转清除旧结果，同一进程／缓存类加载器下再次点击仍可实际推理；横屏菜单、缺配套包本地错误和恢复同SHA包均验证完成。竖屏英法／简繁及横屏截图已检查；只覆盖自有固定页面，不是第三方App或真机结果。结束已停止自有进程和专用模拟器。

证据入口：[本轮汇总](evidence/region-real-ocr/2026-09-30/summary.json)。[法语实际画面](evidence/region-real-ocr/2026-09-30/standalone/fr-seam.png)／[繁体冲突保留](evidence/region-real-ocr/2026-09-30/standalone/hant-seam.png)／[独立启动记录](evidence/region-real-ocr/2026-09-30/standalone/summary.json)。两次只读AO阶段指纹未变；实现工作者600秒超时留下代码，根任务完成实际检查、修复与验收，不把超时当完成。

## 下一步

将这份实际OCR整页证据接到既有按需翻译请求契约：完整上下文与区域目标分别绑定版本，明确不确定候选处理及译文回填规则，先验证供应商无关的本地请求／返回／失效接线。实际翻译调用、联网同意、服务端凭据、第三方屏幕采集和敏感过滤分别验收，不把预置译文当真实翻译，也不把模型密钥打入APK。

实现依据Android官方[线程与UI](https://developer.android.com/topic/performance/threads)及[PathClassLoader](https://developer.android.com/reference/dalvik/system/PathClassLoader)。本轮没有操作或更新手机，模拟器不能代表华为兼容／性能或人手体验。
