# 构建、测试与界面约定

结构、命名与模块职责统一见 [architecture.md](architecture.md)，操作流程见 [usage.md](usage.md)。本文件只维护构建、测试和界面开发约定。更新日期：2026-11-09。

## 构建与兼容

使用 AGP 7.1.2、Gradle 7.2、JDK 11、Java 8 字节码和现有 WebRTC SDK。Android Studio 配置 Gradle JDK 11 与本机 Android SDK，`local.properties` 不提交。当前 `minSdk=21`、`targetSdk=28`、客户端版本1.3.0。

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
./gradlew :app:lintDebug
adb -s 机器人IP:端口 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
```

需要时在命令前指定 `JAVA_HOME`。保留应用ID与同一签名覆盖安装，不通过卸载绕过版本限制。安装失败需先核对设备上的versionCode，不能清除登录/教学缓存来“解决”升级问题。

Gradle 当前设置 `abortOnError=false`，构建成功不代表 Lint 或旧设备兼容通过。旧版报告发现过日期 `XXX`、`Map.getOrDefault` 与 `forceDarkAllowed` 的 NewApi 问题；当前状态需单独运行 Lint 并用目标旧设备验证，不沿用固定错误数量。

## 测试范围与隔离

| 测试 | 范围 / 启用条件 |
|---|---|
| 本地单元测试 | 骨架映射/时效、日常模式偏好与能力检查、语音冷却、屏幕档位与MQTT响应等；运行 `:app:testDebugUnitTest` |
| `RehabFlowTest` | 登录、账号/找回、患者/计划、前后反馈、真实RTC、中断、服务错误及教学缓存；需参数 `server` 指向独立临时服务，完整参数以测试代码为准 |
| `TrainingPerformanceTest` | 显式传入 `benchmarkUrl` 的实体机性能采样；支持 `action=rub/fist`、`mode=local/skeleton/annotated/diagnostic`、`sampleSeconds` 等 |
| `TrainingPresentationTest` | 显式传入 `uiFixture`，验证训练布局与倒计时；只使用匹配该测试的临时夹具 |
| `TrainingVoiceTest` | 仅 `allowPhysicalVoice=true` 时实际播放内置语音 |
| `ReviewWorkspaceTest` | 仅接受 `reviewFixture=http://127.0.0.1:18123`，验证人工复核嵌入、登录、视频、表单和退出 |
| `CameraProbeTest` | 明确传入 `cameraId` 的独立硬件采集；不上传、不建训练记录 |

所有仪器测试先备份机器人连接、偏好与加密登录，结束后核对恢复。测试自身的finally不能保证外部杀进程时恢复；不要连接正式患者库，不使用真实邮件或把真实令牌写入文档。实体App冷启动会请求屏幕默认档位10，现场应先确认设备可安全调整。

`RehabFlowTest` 的完整场景还需要临时服务生成的工作人员、患者计划和一次性找回夹具；不提供对应参数时部分用例会跳过。不得用正式患者凭证补齐测试。

### 人工复核回归

在配套后端根目录启动 `python tests/review_fixture.py`：仅监听本机18123，临时数据库、合成视频、无真实模型和邮件。然后在本Android工程运行：

```bash
adb -s 机器人IP:端口 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 机器人IP:端口 reverse tcp:18123 tcp:18123
adb -s 机器人IP:端口 shell am instrument -w -r \
  -e class com.robint.ReviewWorkspaceTest \
  -e reviewFixture http://127.0.0.1:18123 \
  com.robint.sdk.demo.test/androidx.test.runner.AndroidJUnitRunner
adb -s 机器人IP:端口 reverse --remove tcp:18123
```

测试覆盖App菜单保留、直接复用登录、视频解码、手填信息初始空白、普通/150%字号、草稿、切后台保留输入、取消离开及菜单/工具栏/系统返回不循环重开。退出确认由WebView异步返回，测试需等待对话框实际出现。测试finally恢复全部原偏好；截图在App私有 `files/benchmarks/review-workspace-*.png`。重跑前重启临时夹具，避免上次保存的草稿影响空白断言；测试结束关闭夹具并移除端口转发。

### 性能与屏幕角度

配套后端 `tests/rtc_fixture.py` 使用临时库与固定18121端口；采样与汇总由后端 `tools/benchmark_robot.py`、`tools/compare_benchmarks.py` 执行。采样前15秒预热，默认30秒有效窗口，App原始记录位于 `files/benchmarks/`；不要同时构建或运行离线推理抢占资源。

`testReturnPause=true` 仅适用于带暂停/恢复端点的独立夹具，用于远端断流回退/手动恢复；`screenAngle=true` 会额外实际移动屏幕，只在现场明确安排时启用。命令、协议与机械边界见 [screen-angle-test.md](screen-angle-test.md)。

性能记录中RTT、解码→首次Canvas绘制估计与物理画面延迟是不同量，未校准值保留null。出现系统外部强停时，应区分采样完成与完整JUnit通过。相机彩条、无真人或合成片段只证明链路和界面，不证明动作准确率；计时定义与历史数据见配套后端 `docs/display_modes.md`。

## 界面开发约定

- 沿用森林绿导航、浅色工作区与白色卡片，固定浅色主题；普通页面横竖屏适配，训练横屏，编辑/复核固定方向保护输入。主要触控至少48dp，主按钮56dp，字号使用sp。
- 先用布局、层级、间距和清晰的主操作说明流程。避免重复标题、次要标签、slogan、教学段落和每个区块下常驻的辅助小字；重写已有界面时先删冗余，再检查首次使用者是否仍知道点哪里及结果。
- 必要的补充说明优先用有可访问名称的info图标与tooltip，支持键盘和触屏；仅在确有需要时增加，不为留白新增图标。
- 保留表单标签、可访问名称、加载/错误/保存反馈。删除、提交永久性变更等操作的后果必须清晰可见，不能只放tooltip。
- 标题与内容统一左右边线，App人工复核只保留原生页头，网页嵌入时隐藏重复页头；保留原生导航、返回和未保存确认。
- 次数、阶段、提示、预览切换和结束操作优先可见；视频保持比例，关闭系统动画时不执行页面淡入。日程与统计读取真实服务数据，不使用模拟数值。

## 机器人摄像头与编码

训练使用Android标记的后置相机。既有RK3588/Android 12验证中，后置ID102（AX-8M-V2.0）可连续采集640×480；前置ID100曾报资源占用。系统标签不代表外壳实际位置，格式列表不证明实际可用；不要自动停止厂商相机或ROS服务。

该机器人保留软件VP8，其他设备沿用H.264。硬件编码曾产生服务端无法解码的RTP，调整后必须核对服务端真实解码，不能只看连接成功或RTT。采集请求15FPS不代表实际相机只有15FPS。

```bash
adb -s 机器人IP:端口 shell am instrument -w \
  -e class com.robint.CameraProbeTest#cameraDeliversFrames -e cameraId 102 \
  com.robint.sdk.demo.test/androidx.test.runner.AndroidJUnitRunner
```
