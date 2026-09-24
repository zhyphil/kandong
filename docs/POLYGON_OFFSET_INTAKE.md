# Polygon offset 候选初筛

2026-09-24在临时目录完成宿主比较，未将新依赖加入项目、APK或手机。这项准备不依赖正在实施的裁剪基础探针，也不改变它的验收门槛。

候选为[lightbringer/clipper-java固定提交](https://github.com/lightbringer/clipper-java/tree/5ef8c0a467023c495e44e582e9cbd8ca7308a590)。官方README称其为Clipper6.4.2的Java移植，并已声明停止维护；采用Boost Software License1.0。只取11个库源码文件（175,411字节）在临时目录以Java8目标编译；没有取演示应用、没有修改仓库的依赖源。

与当前宿主Pyclipper1.4.0比较11项：2项已冻结微型用例、8个角度矩形、1个靠边的6.25×6.25分数正方形。比较polygon顶点精确值，允许起点和方向不同，不忽略顶点缺失。默认round join、闭合polygon、miterLimit2、arcTolerance0.25，offset距离仍由原浮点polygon面积×1.6/周长计算，再将传入路径坐标截断为整数。

**未修改候选仅10/11通过。** 靠边分数正方形offset2.5时，参考为12个顶点，Java候选仅8个；负方向最远坐标参考-3，候选-2。其余10项一致不能抵消这项差异，当前不采用原版作为数值等价实现。

独立临时诊断仅把ClipperOffset中的Math.round替换为半值远离零的舍入后，11/11顶点一致；这支持负半值舍入是本组差异的原因。诊断改动没有进入项目，不代表修补版已被正式采用，也没有验证全部布尔运算或Android行为。

[原始结果与诊断](evidence/polygon-offset/2026-09-24/summary.json)保留完整各项polygon、来源指纹和许可。初次编译因临时目录有同名源码被javac隐式发现而失败；明确sourcepath后成功，属于宿主命令配置问题，不是库运行缺陷。

后续选择仍需：明确维护/归属策略、检查整个移植中的舍入语义、更多旋转/靠边/小框及多路径用例、目标Android验证。Clipper2-Java是另一候选，但版本变化不能直接假定与当前Pyclipper等价。本阶段未验证或采用Clipper2。
