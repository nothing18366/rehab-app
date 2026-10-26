# rehab-app · 复健 Android 客户端

Java/XML 客户端，提供患者与工作人员登录、计划日程、六动作教学、摄像头训练、前后反馈、记录和趋势。识别由独立 Python 服务通过 WebRTC 提供。

```bash
./gradlew :app:assembleDebug
```

构建需 JDK 11 和 Android SDK；应用 ID、签名及本地登录/教学缓存保持现有兼容。机器人 SDK 示例与旧设计截图已清理。

GitHub 仓库为 [nothing18366/rehab-app](https://github.com/nothing18366/rehab-app)，本地文件夹仍可使用 `android-roslift-sdk-demo`。工程显示名为 `rehab-app`，不改变应用 ID。

`app/key.jks` 仅保存在本机，不提交仓库；新电脑构建前需通过私下传输放置同一签名文件，才能沿用现有签名覆盖安装。`local.properties` 由 Android Studio 在本机配置，不提交 SDK 路径。不要将真实服务凭据、患者数据库或构建缓存提交 Git。

- [使用与账号](docs/usage.md)
- [结构与命名](docs/architecture.md)
- [构建、测试与界面约定](docs/development.md)

后端部署与协议见配套服务仓库 docs/deployment.md 和 docs/api.md。人工复核、备份界面、审计和报告导出仍是明确禁用的待接入入口。
