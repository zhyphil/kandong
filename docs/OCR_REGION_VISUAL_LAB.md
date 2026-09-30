# 整页与选区可视化实验

后续更新：固定图片的实际整页OCR已接入同一实验界面，见[实际OCR接线与验证](OCR_REGION_REAL_LAB.md)。下文保留原合成阶段的范围、哈希和证据，不能将其改称实际模型推理。

2026-09-30，起点5fa799a。新增可独立打开的“看懂选区实验”，把已验收的整页证据与选区控制器接到可操作界面。它使用固定繁体中文样例和空白页，不运行OCR、翻译或屏幕采集，不代表正式放大镜已接入翻译。

## 本轮界面

- 整页概览保留框外文字，红框内部／边框直接拖动；右上角24dp箭头调整宽高，48dp触摸区在视图边缘向内平移，避免贴边后变小。
- 选区大小与倍率独立。滑杆1～5倍、默认2倍；镜面双指缩放同步数字和滑杆，单指平移。镜面放大的是固定样例原图，编号和框对应候选位置，不把原图字形当作自动选择的识别答案。
- 镜面与概览共享显示比例s：原坐标到概览乘s，镜面乘s×倍率，手势分别除以s或s×倍率。倍率明确以整页预览为参照；传入控制器的镜面尺寸除以s，其输出位置再乘s。
- 候选卡片保留7条原始输入，包括“稅／税”冲突、空字符串及框外条件。展示优先级为选区关联冲突、其他关联、框外上下文，原ID／编号与整页对象保持；这不是语义排序或挑选正确文本。
- 点击“展示样例”才装载本次整页证据。移框、缩放、平移只更新显示；收起、菜单、换页、停止、后台或原60秒展示期限到期后清空活跃Frame和结果卡片。收起恢复保留选区／倍率／偏移，重新展示仍需点击。
- 菜单占满安全内容区，避开系统栏／刘海；关闭菜单保持进入前的展开／收起状态。本页收起和菜单都是Activity内实验，不是新的系统悬浮窗。

固定源页1200×800，由同一套坐标绘制概览与镜面；两条冲突候选处于条带0／1实际规划的重叠区。合成模型字段、收据和关闭计数仅为输入契约样例，不计作本轮实际模型推理或Image资源验收。合成批次使用visual-synthetic标识，本次取得时间是展示会话起点，不是实际手机页面的新鲜度证明。

## 打包与运行

新增Java debug薄宿主RegionVisualLabActivity，使用固定且与宿主签名相同的测试包加载实验Surface。普通冷启动通过PathClassLoader、以宿主为父加载器提供Kotlin运行时；instrumentation使用已有同一加载器。忽略外部Intent数据，没有网络、共享或悬浮权限；缺少配套包时显示本地错误，不静默切换实现。

控制器、样例、会话和界面仍在测试源集中，没有把testShared整体加入主包。旧main源码及app／compat／graphics／ocrlab未改，也没有新增依赖。由于添加debug入口，modelprobe主实验APK哈希本轮改变；历史测试包的固定身份检查仍保留，不能跳过。

从项目根目录使用JDK17执行：

```sh
JAVA_HOME=/Users/haoyuzuo/Library/Java/JavaVirtualMachines/temurin-17.0.20.1/Contents/Home \
  ./gradlew --offline :modelprobe:testDebugUnitTest :modelprobe:assembleDebug \
  :modelprobe:assembleDebugAndroidTest :modelprobe:lintDebug
```

仅在[项目专用模拟器](OCR_EMULATOR.md)核对身份后安装配套两包，随后打开“看懂选区实验”：

```sh
/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb -s emulator-5582 install -r -t modelprobe/build/outputs/apk/debug/modelprobe-debug.apk
/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb -s emulator-5582 install -r -t modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk
/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb -s emulator-5582 shell am start -n com.kandong.modelprobe/.RegionVisualLabActivity
```

系统安装／屏幕共享确认在真机上仍由用户完成。本轮不更新nova 9，不把模拟器结果当作真机手势、性能或兼容验收。

最终主实验APK：55291acf66cc2bdb5d7b40ffd0bc368bcf441ce61f33f9ffa3b0f173e7b279ca；配套测试APK：7f3816273ada270057f8a58dfcab85622b98bb3733c9ff5bebecf63cce6affb7。实际安装哈希核对一致。专用模拟器已停止，手机未更新。

## 验证与边界

最终结果与构件身份见[证据入口](evidence/region-visual-lab/2026-09-30/summary.json)。初始9项行为测试全部实际失败，原测试保留，实现后与新增6项状态测试通过；最终217项JVM、离线构建通过，Lint0错误4既有警告。

根任务另加贴边角标回归，模拟器先证实错误变成整体移动；修正触摸区后，首轮59项中58通过。剩下一项测试要求已达48dp最小高度的框继续缩小，独立复核确认是测试前提问题。随后先通过手势拉高选框，再保留左边界不移动、宽高缩小、倍率不变和不重载的断言；旧失败记录原样保留。最终复验59/59项通过，70.856秒，包含15项新共享状态测试、7项界面检查、24项选区控制器及13项发布回归；结果单独归档。

UI检查使用真实View接收单／双指MotionEvent，覆盖拖动、角标、滑杆、缩放、平移、菜单、收起、切页、后台返回及真实等待60秒自动清理；不是人手真机体验。截图仅来自自有固定样例，未读取手机页面。最终同包独立冷启动及真实触摸“展示样例”成功；竖屏和横屏截图已检查，旋转重建后展示次数归零、没有自动载入。临时卸载仅模拟器中的配套测试包，主入口明确报缺包错误；随后恢复原包并复核哈希和入口。横屏检查为布局／清理检查，手势用例在竖屏运行，不泛化为各尺寸或真机验收。

下一项：在独立实验页中用实际整页OCR替换合成候选，固定单一英语／法语／中文页，保留框外上下文、全部候选与不确定性；处理后台执行和主线程显示之间的取消与迟到结果。此后再接真实翻译对应。现有OCR／翻译质量问题、真实采集隐私和正式镜面接入仍分别待验收，不重新以混排语言筛选作为门槛。

[竖屏实际画面](evidence/region-visual-lab/2026-09-30/standalone/shown.png) · [横屏菜单](evidence/region-visual-lab/2026-09-30/standalone/landscape-menu.png) · [60秒失效画面](evidence/region-visual-lab/2026-09-30/ui/expired.png)。

实现参考Android官方[Instrumentation](https://developer.android.com/reference/android/app/Instrumentation)、[ScaleGestureDetector](https://developer.android.com/reference/android/view/ScaleGestureDetector)和[UiAutomation](https://developer.android.com/reference/android/app/UiAutomation)。测试没有向第三方App注入操作。
