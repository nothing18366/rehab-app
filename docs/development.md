# 构建与验证

沿用 AGP 7.1.2、Gradle 7.2、JDK 11、Java 8 字节码和现有 WebRTC SDK。Android Studio 配置 Gradle JDK 11 与本机 SDK；不提交 local.properties。

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

指定 JDK 可在命令前设置 JAVA_HOME。多个设备使用 adb -s 指定序列号。保留 applicationId 和既有签名，正常覆盖安装无需清空患者业务服务或 App 数据。

仪器测试位于 app/src/androidTest/java/com/robint/RehabFlowTest.java，连接独立测试服务与临时数据库；不要对正式患者服务执行。支持的参数与测试服务地址以测试代码为准。

完整运行 11 项还需提供 `staffToken`、仅含 cup 动作生效计划的 `patientToken`，以及一次性找回夹具 `resetUsername/resetEmail/resetCode`。夹具在临时库中通过认证服务创建，邮件发送使用测试替身；不提供患者或找回夹具时，相应用例会跳过。测试服务需清空 SMTP_* 环境，便于验证未配置邮件的提示。

```bash
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.server=http://10.0.2.2:测试端口
```

仪器测试覆盖计划/患者切换、登录账号、找回密码界面、反馈、真实 RTC、前后台中断、不适结束、服务错误及缓存失败回退。测试相机可能是模拟器彩条，不能据此声称真实动作准确率或目标设备帧率。真实邮件、实体摄像头、中文语音和 Jetson 六动作性能需单独验收。

当前 Lint 存在两项既有 NewApi 错误：日期解析 XXX 格式要求 API 24，forceDarkAllowed 主题属性要求 API 29，而 minSdk 为 21。Gradle 沿用 abortOnError=false，因此需单独检查 Lint 报告；旧设备兼容修复列在后端 remaining_work.md。

## 界面约定

采用固定浅色主题：森林绿导航、绿色主操作、浅色工作区、白色卡片；系统夜间模式下保留已设计配色。普通页面横竖屏适配，大屏侧栏、竖屏底部导航，低高度横屏紧凑布局。

主要触控至少 48dp，主按钮 56dp；字号使用 sp，关键次数、阶段、提示、预览切换和结束操作优先可见。视频保持比例，训练中不加入图片加载或复杂动画。系统关闭动画时不执行页面淡入。

首页周完成进度来自真实日程，计数、FPS、时长和结果来自服务，不使用原型模拟数值。教学照片属于示范而非实时画面。患者登录默认显示账号密码，管理员入口单独提供。
