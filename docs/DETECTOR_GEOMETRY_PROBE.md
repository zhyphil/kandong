# Android 几何基础探针

本阶段在独立 modelprobe 的 androidTest 中实现，不接正式放大镜。验证两条分开的路径：固定概率图→二值掩码/膨胀/轮廓计数；固定原图＋宿主参考四角→透视裁剪/旋转。使用参考四角不等于 Android 已能自动生成文字框；框评分、polygon unclip、识别和翻译仍在后续阶段。

## 运行前冻结的门槛

参考沿用[宿主几何数据](DETECTOR_GEOMETRY_REFERENCE.md)，manifest SHA-256：1aa14f2efb50e62c5a2d95b45b7d5b5044d4ec90afa47a976e8dea50bbe466a6。16场景/13裁剪/78文件保持不变。

- 16组严格 `>0.3f` 二值掩码、2×2默认anchor一次膨胀结果逐字节相同；RETR_LIST/CHAIN_APPROX_SIMPLE轮廓数相同。
- 13张裁剪尺寸、旋转及关联相同，BGR像素零差异。目标四角使用 `(0,0),(W,0),(W,H),(0,H)`；INTER_CUBIC/BORDER_REPLICATE；裁剪前高宽比≥1.5时逆时针旋转90°。
- 归一化透视矩阵采用 `abs(actual-reference) <= 1e-9 + 1e-9 * abs(reference)`。
- 四角正逆往返采用绝对容差1e-6像素＋相对容差1e-9。旋转的像素中心映射为 `(x,y) -> (y,W-1-x)`，与几何边界概念分别记录。
- 所有差异先记报告再判失败；不根据运行结果放宽门槛或修改固定图。

图片认证使用 Android 官方 BitmapFactory：先认证压缩字节，检查PNG头部尺寸/预算，再解码为sRGB ARGB8888，核验原始BGR哈希后才创建OpenCV Mat。Bitmap原生分配发生在尺寸校验之后、原始像素哈希校验之前，不声称哈希能先于所有原生分配。使用官方解码器，未采纳方案中自写PNG解码器的建议。

## 资源与异常范围

单文件≤2MiB，manifest≤128KiB，单边≤4096，单图≤1,048,576像素。概率、四角和矩阵拒绝非法/非有限/退化输入；解压长度和SHA需一致。原ID/索引/阅读顺序/分数与裁剪保持成组关系，不重新计算分数。

轮廓提取前用逐行0/1游程数保守限制Java轮廓对象数量：超过8192时返回 `INCOMPLETE_CONTOUR_BUDGET`。此限制可能对复杂合法图像提前拒绝，属于本次诊断预算。轮廓数超过1000返回 `INCOMPLETE_CANDIDATE_LIMIT`，不默默将截断结果标为完整。另用1024个分离块与密集棋盘格检验两种边界。

成功及故意异常都释放Bitmap和所有取得的Mat像素缓冲；包括contour、hierarchy、kernel、矩阵、warp及rotation临时对象。计数不证明原生header立即析构或长期内存稳定：header仍由OpenCV finalizer管理。

## 验证状态

初版电脑端34项单测、构建/Lint（0错误/7警告）、签名/对齐与资产验证通过，独立只读审查未发现P1/P2。初次构建发现测试Lint消费暂存资产缺依赖，已为对应androidTest消费者补依赖；主包/JVM/compat任务图不触发此暂存。实验主APK仍为7f9ff255…5a6aea，初版测试APK为d8ae5fc2…04478，382个保护文件未变。

目标华为初轮组合8项180秒超时，原因未明；随后分别运行原7项通过（5.908秒），新几何项正常完成并按固定门槛失败（1.147秒）。不把超时说成算法失败，也不抹去它。新的细项结果：16组掩码/膨胀/轮廓一致，13组正逆矩阵和坐标、16组防护均通过，2233个Mat缓冲释放和31个Bitmap回收计数闭合；12/13裁剪像素一致，繁体首行有2067个BGR通道差异、最大5/255，矩阵本身零差异。**该4.14初版几何像素验收失败；后续5.0结果见下方，不覆盖旧失败。**

宿主以关闭优化、四通道、显式逆矩阵、float remap对照，受支持路径13/13均匹配原参考；五通道cubic不受支持，保留错误，不作为候选。不能用宿主结果推断Android通过。

正在进行NORMAL限定诊断：只改两个现有测试文件，整组统一选择DEFAULT、UNOPTIMIZED、FOUR_CHANNEL、EXPLICIT_INVERSE或REMAP_FLOAT，并记录实际像素哈希；默认旧路线保留，参考及门槛不变。新诊断再次34单测/构建/Lint/包检查通过。前述独立审查针对初版，不冒称覆盖后续诊断改动。这五条4.14路线已在华为运行，全部具有完整计数/16防护及释放结果，且失败裁剪的实际哈希完全相同。

[初版证据及失败记录](evidence/detector-geometry-probe/2026-09-24)。相同OpenCV4.14在专用API36模拟器加载SIGILL仍未解决；本阶段不冒称双设备、自动框生成或OCR质量通过。正式放大镜保持原样。

## API来源

[OpenCV 几何变换](https://docs.opencv.org/4.13.0/da/d54/group__imgproc__transform.html)、[轮廓 API](https://docs.opencv.org/4.13.0/d3/dc0/group__imgproc__shape.html)、[图像滤波](https://docs.opencv.org/4.13.0/d4/d86/group__imgproc__filter.html)。官方4.x文档入口本次重定向4.13.0；测试二进制仍固定4.14.0并独立验证，不把文档版本当作二进制等价证据。上游裁剪来源与许可见[归属记录](ATTRIBUTION.md)。

## 插值版本一致性修正及目标机验收

官方实现对照说明：宿主5.0.0已移除cubic查表路径，改用无表kernel；4.14.0仍使用32档坐标量化与固定系数表。五条旧库路线相同不证明矩阵错误；当前已确认算法版本不一致，最终是否消除全部差异由目标机固定门槛裁决。

- [4.14 imgwarp.cpp](https://github.com/opencv/opencv/blob/4.14.0/modules/imgproc/src/imgwarp.cpp)
- [5.0 imgwarp.cpp](https://github.com/opencv/opencv/blob/5.0.0/modules/imgproc/src/imgwarp.cpp)
- [5.0 warp kernels](https://github.com/opencv/opencv/blob/5.0.0/modules/imgproc/src/warp_kernels.simd.hpp)

仅独立测试依赖改为org.opencv:opencv:5.0.0.1，实际153,027,846字节/SHA edb1406a223d2820460b8366a790238b400f5d5c9ea2e98d44b889f0f3c66849与Maven单独校验和匹配。透视矩阵API从Imgproc迁至官方Geometry模块；其余裁剪规则、固定参考、数值门槛与报告完整性不变。原图探针仅更新版本断言及报告元数据，测试NOTICE同步来源；这两项是保护清单中的显式版本例外，其余380文件保持原字节，实验主APK哈希仍不变。

候选34单测、构建、Lint（0错误/6警告）、签名/对齐及包内194项旧/新资产验证通过。测试APK0d6d524983ae57c922e40799c7e9c7000412d85dc49443686e7a2217f6d109e0，247,875,893字节；体积包含所有测试ABI，不代表产品包。首次升级编译发现API模块迁移后已修正，失败日志保留。候选已安装，两轮各原7项＋新1项全部通过；没有放宽旧验收标准。所有4.14失败和初轮超时继续保留。

目标华为最终结果：13张裁剪/139,875个BGR通道零差异，16组掩码＋膨胀（各979,968值）＋轮廓计数完全一致；13组矩阵/关联/旋转及832个坐标标量符合原门槛，16项异常/压力防护通过。每轮2233个Mat缓冲释放、31个Bitmap回收计数闭合。几何项1161/1321毫秒，仅作运行记录，不是性能门槛。

两轮所有非计时字段一致；原7项中的检测及原图各场景结果与4.14已验收记录完全一致（仅去掉resizeNs计时）。更换到与宿主一致的5.0插值实现后，旧繁体差异消失；没有改图片、答案、坐标或误差标准。当前**目标华为的合成几何基础子项通过**。仍不包括Android自动生成检测框、unclip、OCR/翻译、真实页面或长期内存验收。专用API36此前5.0加载SIGILL未解决，没有新运行证据或双设备通过声明。

复验可分别运行`AndroidDetectorProbeTest,OriginalImageProbeTest`（7项）和`DetectorGeometryProbeTest`（1项）；默认使用统一DEFAULT路线，诊断参数`geometryCropRoute`保留用于已记录的失败对照。每轮先移除自有报告并核对实际安装包SHA，结果保存在实验应用私有files目录；两组之间停止实验进程，结束后再次停止。完整报告与包身份见[最终证据](evidence/detector-geometry-probe/2026-09-24/summary.json)。
