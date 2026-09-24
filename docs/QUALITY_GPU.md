# GPU C 独立实验

本页记录2026-09-23独立实验阶段；后续已经接入可关闭的兼容镜面，当前状态见[清晰增强](CLARITY_ENHANCEMENT.md)与[资源管理迭代](MEMORY_STABILITY.md)。以下独立实验版本和数字保留为历史证据。

实现范围仅为 `qualitylab`。版本 `0.0.2-gpu-experiment` / code 2。原 A/B/C 页面与用户选择的 CPU C 保持原实现；入口“独立 GPU C 实验（未接入放大镜）”打开 `GpuLabActivity`。该 Activity 可直接启动，只额外接受固定自测action，不接受外部图片、尺寸或算法参数，不申请权限，不读取页面、不联网。没有生产集成。

## 管线与边界

`GpuC` 为 `AutoCloseable`，构造、分配、绘制、关闭均要求同一个工作线程。EGL14 创建 GLES 3.0 的 1×1 pbuffer 上下文，图像渲染到 RGBA8 FBO；不依赖浮点 framebuffer。关闭 dithering、blending、depth、scissor。GLES3.2使用GLSL320 precise；GLES3.1有EXT_gpu_shader5则使用310 precise；否则300。当前实际覆盖华为core320和模拟器standard300，EXT310尚未实测。着色器 float/int/sampler 均为 highp。

1. 源尺寸的第一遍：3×3 `[1,2,1]` 高斯差分，强度0.2，每通道 delta 限制±12个8位码值，显式 `floor(clamp(v,0,255)+0.5)` 取整后存入 RGBA8。
2. 输出尺寸的第二遍：Mitchell–Netravali B=C=1/3，16次源像素采样和2次权重读取。GpuSamplingWeights每次尺寸/倍率变化时按相同CPU Float顺序预计算系数，再上传到只供采样的RGBA32F纹理；没有浮点FBO。整数firstTap保持相同像素中心，夹取边界。每行先计算横向和，再计算纵向和，与 CPU 运算顺序一致。最后显式 `floor(clamp(v,0,255)+0.5)/255`。
3. 1倍只执行一遍 GPU 原样采样与实际读回，同时旁路锐化和插值；不调用 CPU clone 作为 GPU 结果。

ARGB整数显式打包为 RGBA 字节，读回显式转回 ARGB，和主机字节序无关。源逻辑首行对应纹理 y=0、FBO y=0 和读回首行，全流程不翻转；这只适用于当前离屏管线，不能直接推导屏幕 Surface 的方向。Bitmap 密度为 NONE。

每次输入最多51,200像素，输出最多1,280,000像素；使用 Long 在分配前验证维度、总数与倍率。源/输出两个轴都检查 `GL_MAX_TEXTURE_SIZE` 和 `GL_MAX_VIEWPORT_DIMS`。五张纹理（源/中间/输出/横纵权重）复用于同尺寸样本，读回缓冲区只增长到当前实验最大需求。每次 GPU 测量都重新上传输入。shader 编译/链接、FBO、GL、EGL 错误会中止并写出错误，不回退 CPU。

CPU公式及 Mitchell 归属沿用 [QUALITY_LAB.md](QUALITY_LAB.md#来源与归属)。`PixelEnhancer.kt` 和 `LabRenderer.kt` 未修改。

## 数值与性能门槛

“验证并测速”运行主320×160文字样本及11张小样本，各测1/2/3/5倍，共48组。小样本包括1×1、1×N、N×1、奇数宽高、非对称彩色角标、脉冲、棋盘、细线、纯色、灰阶/彩色渐变和固定种子噪声。逐张逐倍率处理，不拼成大纹理。

- 每一组的 R/G/B **分别**要求最大误差≤2/255、平均误差≤0.1/255。均值只用该通道的像素数作分母，不包含alpha，也不跨样本平均。
- 所有输出尺寸、alpha=255、输入未变、密度NONE均为硬门槛；1倍还必须全部像素完全一致。
- 非对称角标额外验证四角方位与RGB通道顺序；逐像素CPU对照同时保留每组的位置匹配检查。
- 不因设备结果而放宽阈值；完整执行完毕仍可能报告数值失败。

测速只用与原实验相同的文字样本。2/3/5倍每条路径预热3次，测15轮，CPU先/GPU先逐轮交替。CPU仍调用原 `LabRenderer.render(..., variant=2)`，计时包含 CPU C 计算和输出 Bitmap 创建。

| 记录 | 包含范围 |
| --- | --- |
| inputPacking | 直接缓冲区分配和显式ARGB→RGBA打包，单列，不含在每轮内 |
| initializationMs | EGL上下文、着色器编译/链接、GL对象及初始化完成等待 |
| allocations | 源/中间/输出RGBA8纹理、CPU系数生成/采样纹理上传、读回容量分配与完成等待；复用记0 |
| gpuUploadAndCompletion | 新上传、两遍绘制（1倍一遍）、CPU驱动调用、此次glFinish等待完成 |
| gpuReadbackAndBitmap | glReadPixels、RGBA→ARGB整数转换及Bitmap创建 |
| gpuEndToEnd | 上面两个连续区间之和；不含初始化、分配、打包、UI或PNG |

每次样本前有一次计时外 glFinish 清空此前工作；此次绘制的 glFinish 在“上传+GPU完成”计时内。此记录不是纯GPU耗时。P95使用nearest-rank；15轮的P95就是最大值。每轮原始值、顺序、中位数和P95均写入报告。

`numericalPassed` 与 `timingGate3and5` 分开。后者要求3倍和5倍的“上传+GPU完成”与“GPU端到端”的中位数/P95，四项都严格低于**同轮同倍率**CPU C。2倍仍报告实际结果。`allPassed` 仅为两门槛的合取，不能自动批准生产集成，也不代表帧率、省电、温升、持续体验或其他OEM兼容性。

## 界面、报告、取消

默认生成3倍CPU C/GPU C实际像素对照。完整验证之后保留同样的3倍对照。成功状态“GPU验证完成”仅在两个门槛均通过时显示；阈值或运行失败有明确状态。

私有目录输出 `gpu-report.json` 与 `comparison-gpu-3x.png`，PNG只有合成图片。报告包含型号/API、GL renderer/version/vendor、能力上限、样本尺寸/倍率、原始误差、实际计时、门槛、限制及时间/runId。先写 `running/ready=false`；成功完成资源清理与文件写入后才成为 `completed/ready=true`。仅预览为 `comparison-ready/fullSuite=false`，不能作为完整验证通过证据。失败/取消保留已测结果，标记 `error` / `cancelled`、`ready=false`、`allPassed=false`。临时文件在同一私有目录原子重命名，失败可见。

`onStop` 立即发出中断并使旧代回调失效，工作线程在CPU行/字节转换/阶段边界协作检查。已经进入的驱动调用无法被Java中断强行终止；退出后仍在拥有者线程直接执行 EGL finally 关闭。GpuLabTasks为进程级串行队列，Activity销毁仅取消自己的任务，旧清理/报告结束后新实例才开始写入，防止跨实例覆盖。返回可重新生成或重新验证。源Bitmap仅由单次工作持有；未显示的过期输出回收，已交给UI的Bitmap交由GC，避免RenderThread使用已回收图片。

## 已验证结果（2026-09-23）

父任务实际完成debug构建、20项JUnit与Lint（零错误/12提示）。APK无请求权限。原CPU C、app、compat和fixture未改动。独立只读审查发现跨Activity报告竞争，修复后定向复审未发现阻塞问题；模拟器后台取消/恢复及3次运行中旋转重建共5项通过。

目标华为和专用API36模拟器各48组均逐像素完全一致（最大/平均误差0），预设门槛没有放宽。用户在手机3倍对照后确认“同样清楚、好读”。[证据索引](evidence/qualitylab/gpu/verification.json)包含实际APK摘要。

| 华为API31 | CPU C中位数/P95 | 上传+GPU完成 | 加上读回/Bitmap |
| --- | --- | --- | --- |
| 2倍 | 58.05 / 65.35 ms | 3.68 / 4.71 ms | 10.67 / 11.77 ms |
| 3倍 | 99.51 / 103.31 ms | 5.95 / 15.19 ms | 23.91 / 32.62 ms |
| 5倍 | 223.79 / 227.51 ms | 10.32 / 11.86 ms | 59.60 / 64.08 ms |

[华为报告](evidence/qualitylab/gpu/huawei-api31/gpu-report.json) · [3倍合成输出](evidence/qualitylab/gpu/huawei-api31/comparison-gpu-3x.png) · [模拟器报告](evidence/qualitylab/gpu/emulator-api36/gpu-report.json) · [生命周期检查](evidence/qualitylab/gpu/emulator-api36/lifecycle.json)

USB连接期间debug短测，每条路径3次预热/15轮交错。最后一列排除初始化、输入打包、系数/纹理准备、采屏/显示/PNG，不代表实时镜面帧率、耗电或持续温升。模拟器2/3/5倍完整处理中位数9.07/18.53/50.21ms，不能代表手机。

初始shader直接计算系数时，极细水平渐变2倍有1码值取整差异，未达该通道平均误差门槛。precise改善后仍有临界差异；采用同一CPU Float系数后两设备全部精确匹配。[修复前报告](evidence/qualitylab/gpu/before-fix)完整保留，不删失败样本、不改CPU参考或阈值。

真机悬浮按钮影响系统控件树定位，最终使用应用自身固定自测入口，只读取其生成的合成图/数值，不需要整屏截图。显式启动：
~~~sh
adb -s <目标设备> shell am start --activity-clear-top -a com.kandong.qualitylab.VERIFY_GPU -n com.kandong.qualitylab/.GpuLabActivity
~~~
正常打开只生成3倍预览，按钮可运行全套；旋转重建不会自动重跑全套。

## 下一阶段门槛

当前仅完成独立GPU验证，正式放大镜尚未接入。接下来复用GPU处理，按镜面尺寸渲染，避免5倍整张巨图及不必要的读回；继续使用MagnifierViewport的倍率/焦点/平移，覆盖1～5连续倍率、自由选区、直接拖框和缩放。提供可关闭的清晰增强设置，保留原效果与错误回退；限制在途帧数量并明确Bitmap所有权。

必须进一步验证真实采集/显示时延、动态合成页与用户常用App、收起/菜单/恢复/停止/锁屏/旋转/撤销及窗口保护，再做同亮度、页面、倍率、充电条件下持续性能/温度/电量对照。未承诺恢复真实高清，也未证明其他OEM兼容。未推送或发布新版。

## 官方依据

[Android EGL14](https://developer.android.com/reference/android/opengl/EGL14)、[GLES30](https://developer.android.com/reference/android/opengl/GLES30)；[Khronos EXT_gpu_shader5](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_gpu_shader5.txt)说明precise限制运算重排/合并，不能单凭该限定符声称与CPU完全一致。Mitchell公式归属沿用[QUALITY_LAB](QUALITY_LAB.md#来源与归属)。实现独立编写，没有第三方源码、模型或新运行时依赖。

后续进度：已将共享后端接入兼容放大镜的可选开关，连续视窗与设备回归见 [CLARITY_ENHANCEMENT](CLARITY_ENHANCEMENT.md)。本页上述原独立实验记录保持历史范围。
