# 构建与验证

沿用 AGP 7.1.2、Gradle 7.2、JDK 11、Java 8 字节码和现有 WebRTC SDK。Android Studio 配置 Gradle JDK 11 与本机 SDK；不提交 local.properties。

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

指定 JDK 可在命令前设置 JAVA_HOME。多个设备使用 adb -s 指定序列号。保留 applicationId 和既有签名，正常覆盖安装无需清空患者业务服务或 App 数据。

仪器测试位于 app/src/androidTest/java/com/robint/RehabFlowTest.java，连接独立测试服务与临时数据库；不要对正式患者服务执行。支持的参数与测试服务地址以测试代码为准。

`DailyModesTest` 验证日常设置跨登录保留和按模式检查能力；`SkeletonOverlayTest` 覆盖 FIT、镜像、竖图、序号、会话、预测手点及超时清除。`TrainingPerformanceTest` 为显式开启的实体机器人采样，需 `benchmarkUrl` 指向独立 18121 端口，可传 `action=rub|fist`、`mode=local|skeleton|annotated|diagnostic`、`sampleSeconds=30`。每段预热 15 秒后采样，数据保存在 App 私有 `files/benchmarks/`；正式测试前备份连接配置，完成后恢复。服务端仓库 `tests/rtc_fixture.py` 启动临时测试服务，`tools/benchmark_robot.py` 负责按指定次数采集，`tools/compare_benchmarks.py` 汇总原始指标。

手部骨架显示由服务端提供平滑前真实检测点，动作计数仍使用服务端原有平滑点；Android 不额外平滑或预测。点击训练页 FPS 可查看人体、左右手的新检测更新率，以及最近帧人体推理、手部检测、跟踪与平滑耗时；这些字段也进入性能采样。缓存/预测点不计入新检测更新率，人体复用时推理耗时为 0；旧服务未提供新字段时显示“服务端未提供”。

性能采样的 `skeleton.pipeline` 按骨架消息序号记录首次 Canvas 绘制前的 JSON 解析、主线程等待、屏幕刷新等待，以及服务端分段和返回链路估计；完整定义在服务端 `docs/display_modes.md`。未校准时返回链路和解码→绘制估计为 null；Canvas 开始绘制不代表物理显示完成。

动作准确性和主观跟随评估需要真人配合；无真人的相机短测只验证链路、显示和设备开销，需在报告中注明。采样不能与离线推理或构建同时进行。完整画面延迟需外部拍摄；测试数据中的 RTT 和解码→绘制估计不能代替它。测试结束后的外部强停如导致仪器运行器报告失败，应保留退出信息并单独注明，不能将其记为完整 JUnit 通过。

`TrainingPerformanceTest` 的 annotated 测试断言没有 pose 通道和本地骨架绘制；diagnostic 测试验证工作人员单选设置、覆盖并消耗一次性开关，以及计划第二个动作恢复固定日常模式（中途修改偏好不改变计划）。可选 `testReturnPause=true` 仅用于带暂停/恢复端点的独立测试夹具，验证远端断流回退和手动恢复，不用于生产服务。

完整运行 11 项还需提供 `staffToken`、仅含 cup 动作生效计划的 `patientToken`，以及一次性找回夹具 `resetUsername/resetEmail/resetCode`。夹具在临时库中通过认证服务创建，邮件发送使用测试替身；不提供患者或找回夹具时，相应用例会跳过。测试服务需清空 SMTP_* 环境，便于验证未配置邮件的提示。

```bash
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.server=http://10.0.2.2:测试端口
```

仪器测试覆盖计划/患者切换、登录账号、找回密码界面、反馈、真实 RTC、前后台中断、不适结束、服务错误及缓存失败回退。测试相机可能是模拟器彩条，不能据此声称真实动作准确率或目标设备帧率。真实邮件、实体摄像头、中文语音和 Jetson 六动作性能需单独验收。

当前 Lint 有 5 项 NewApi 错误：日期解析 XXX 格式和三处 Map.getOrDefault 要求 API 24，forceDarkAllowed 主题属性要求 API 29，而 minSdk 为 21。Gradle 沿用 abortOnError=false，因此需单独检查 Lint 报告；旧设备兼容修复列在后端 remaining_work.md。

## 界面约定

采用固定浅色主题：森林绿导航、绿色主操作、浅色工作区、白色卡片；系统夜间模式下保留已设计配色。普通页面横竖屏适配，大屏侧栏、竖屏底部导航，低高度横屏紧凑布局。

主要触控至少 48dp，主按钮 56dp；字号使用 sp，关键次数、阶段、提示、预览切换和结束操作优先可见。视频保持比例，训练中不加入图片加载或复杂动画。系统关闭动画时不执行页面淡入。

首页周完成进度来自真实日程，计数、FPS、时长和结果来自服务，不使用原型模拟数值。教学照片属于示范而非实时画面。患者登录默认显示账号密码，管理员入口单独提供。

## 机器人摄像头与编码

训练固定使用 Android 标记的后置相机。当前 RK3588/Android 12 的后置 ID 102（AX-8M-V2.0）可连续采集 640×480；前置 ID 100（CCB Camera）曾报设备资源占用，系统标签不代表外壳实际位置。不要因格式列表声称支持就判断采集可用，也不要自动停止厂商相机或 ROS 服务。设备实际可能约 30 FPS，请以采样为准。

RK3588 保留软件 VP8，其他设备沿用 H.264。该机器人硬件编码曾产生服务端不可解码 RTP，若调整编码器，须先确认服务端实际收到并解码图像，不能只看连接成功或 RTT。

`CameraProbeTest#cameraDeliversFrames` 为显式启用的硬件检查，仅采集并丢弃图像，不上传或创建训练记录：

```bash
adb -s 机器人IP:端口 shell am instrument -w \
  -e class com.robint.CameraProbeTest#cameraDeliversFrames -e cameraId 102 \
  com.robint.sdk.demo.test/androidx.test.runner.AndroidJUnitRunner
```
