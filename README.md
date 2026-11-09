# rehab-app · 复健 Android 客户端

Java/XML 客户端，提供患者与工作人员登录、计划日程、六动作教学、摄像头训练、前后反馈、记录和趋势。识别由独立 Python 服务通过 WebRTC 提供。

当前客户端为 **1.3.0**，配套服务端 1.2.0。工作人员可在“设置 → 设备与服务”选择本机日常模式：默认“本地骨架”（本地／骨架，无回传视频），或“远端标注”（本地／服务端标注视频）。设置跨登录保留，进入训练计划时固定；患者使用同一设置。两种模式均保持 640×480。工作人员原图诊断和无损 PNG 仍为独立的一次性功能。

```bash
./gradlew :app:assembleDebug
```

构建需JDK 11和Android SDK；应用ID、签名及本地登录/教学缓存保持兼容。构建、测试、隔离夹具与界面约定统一见开发文档。

GitHub 仓库为 [nothing18366/rehab-app](https://github.com/nothing18366/rehab-app)，本地文件夹仍可使用 `android-roslift-sdk-demo`。工程显示名为 `rehab-app`，不改变应用 ID。

`app/key.jks` 仅保存在本机，不提交仓库；新电脑构建前需通过私下传输放置同一签名文件，才能沿用现有签名覆盖安装。`local.properties` 由 Android Studio 在本机配置，不提交 SDK 路径。不要将真实服务凭据、患者数据库或构建缓存提交 Git。

- [使用与账号](docs/usage.md)
- [结构与命名](docs/architecture.md)
- [构建、测试与界面约定](docs/development.md)
- [机器人屏幕角度协议、操作与测试](docs/screen-angle-test.md)
- [配套后端文档入口](../yolo26-pose-detection/README.md)（同级工作区）
- [统一后续开发清单](../yolo26-pose-detection/docs/remaining_work.md)（同级工作区）

后端部署与协议见配套服务仓库 docs/deployment.md 和 docs/api.md。人工复核已嵌入App原生导航，复用工作人员登录，支持片段时间轴、逐条结论和手填训练单；电脑访问 `/review/` 独立登录。视频保存到后端，不进入APK。存储看板、审核后归档、待审视频保护预警及其他未完成功能统一列入后续开发清单，不在此重复排期。
