# 目录与命名

Java + XML，保留单个 app 模块。生产 Java 代码按职责组织：

```text
com.robint/
  feature/
    login/LoginActivity.java
    home/HomeActivity.java
    training/TrainingActivity.java
    training/TrainingCueAudio.java
    training/TrainingSpeechPolicy.java
    feedback/FeedbackActivity.java
    records/TrendsView.java
    records/ReviewWorkspace.java
  data/
    remote/RehabApiClient.java
    local/SessionStore.java
    local/TeachingMediaCache.java
  rtc/
    WebRtcClient.java
    ExternalTrainingPresentation.java
    SkeletonOverlayView.java
    PerformanceStats.java
  device/RobotScreenAngle.java
  RehabApplication.java
  media/Subtitles.java
  ui/component/
    UiComponents.java
    ChoiceView.java
    IconDrawable.java
    TrainingProgressBar.java
```

package 使用全小写；类和 Java 文件 UpperCamelCase，方法/字段 lowerCamelCase，常量 UPPER_SNAKE_CASE。公共组件公开跨 package 所需成员，内部实现维持 private。

布局按用途命名 activity_home/activity_login/activity_training，配置限定版本保持相同文件名；资源名使用 snake_case，背景 drawable 使用 bg_ 前缀。教学内置图片位于 assets/teaching_images，服务端目录缓存保持原存储格式。

HTTP 请求、凭证和素材缓存分别负责网络、认证状态与校验缓存；RTC 管理摄像头、预览和连接资源。Activity 保留现有流程与生命周期行为，页面与流程沿用现有实现，不额外引入MVVM层。

`WebRtcClient` 统一管理三条路径：keypoints 上传视频并接收 pose；annotated 接收服务端标注视频、不创建 pose 通道；diagnostic 接收原图视频和 pose，并支持授权 PNG 抓帧。可靠 rehab 通道共用业务结果和结束确认。`SkeletonOverlayView` 校验会话、序号、坐标和 300 ms 时效，使用与本地视频相同的 FIT/镜像变换。`PerformanceStats` 统计实际区间帧率、编码、流量与丢包。日常模式保存在 SessionStore，TrainingActivity 进入计划时固定选择；显示切换不影响连接或计数。

页面、RTC 连接和后端动作阶段是不同状态，后续修改应维持边界。应用 applicationId、签名、SharedPreferences 名称/字段、Keystore 别名、接口字段和教学缓存键保持兼容。Activity 类名变更时同步 Manifest、显式 Intent 和仪器测试。

生产依赖为当前源码直接需要的 AppCompat、Material、OkHttp、WebRTC 和 Paho MQTT Java 客户端。机器人底盘 SDK、示例二进制和相应依赖已移除；已有不可用的功能占位页仍保留，因为它们属于当前产品界面。

`RehabApplication` 在进程启动时异步请求默认屏幕档位 10。`RobotScreenAngle` 独立管理本机 `127.0.0.1:1883` 的 MQTT 请求、确认和界面状态，接受 0–30 整数档位，入口使用步长为 1 的滑动条，点击应用才发送。使用应用包名作 clientId/fromId，先订阅后发送，按消息 ID 和收发身份匹配响应；无保留、无运动重试、同一时刻只允许一条请求。Activity 仅在前台订阅状态，暂停时移除监听并关闭弹窗；网络线程不持有 Activity。档位不写入账号/训练配置，不介入后端、摄像头和计数。

`ReviewWorkspace` 在 `HomeActivity` 内容区复用后端 `/review/`，保留 App 左侧菜单和顶部栏，仅向同源可信页面注入现有工作人员令牌，无JavascriptInterface或文件访问。App 内不显示网页登录表单，登录完成后才显示内容，电脑网页使用独立工作人员登录。嵌入页隐藏重复网页页头，标题与原生内容边线对齐；界面约定见 [development.md](development.md)。填写时固定方向，切后台保留 WebView 并暂停视频；返回、菜单切换和选择患者前检查未保存输入，退出后释放 WebView。患者没有入口且服务端再次检查staff权限。训练片段开关只传会话参数，录像和事件持久化由后端完成。

后续功能统一维护于配套后端 [开发清单](../../yolo26-pose-detection/docs/remaining_work.md)（同级工作区链接），不在本文件重复排期。
