# 目录与命名

Java + XML，保留单个 app 模块。生产 Java 代码按职责组织：

```text
com.robint/
  feature/
    login/LoginActivity.java
    home/HomeActivity.java
    training/TrainingActivity.java
    feedback/FeedbackActivity.java
    records/TrendsView.java
  data/
    remote/RehabApiClient.java
    local/SessionStore.java
    local/TeachingMediaCache.java
  rtc/
    WebRtcClient.java
    SkeletonOverlayView.java
    PerformanceStats.java
  media/Subtitles.java
  ui/component/
    UiComponents.java
    ChoiceView.java
    IconDrawable.java
```

package 使用全小写；类和 Java 文件 UpperCamelCase，方法/字段 lowerCamelCase，常量 UPPER_SNAKE_CASE。公共组件公开跨 package 所需成员，内部实现维持 private。

布局按用途命名 activity_home/activity_login/activity_training，配置限定版本保持相同文件名；资源名使用 snake_case，背景 drawable 使用 bg_ 前缀。教学内置图片位于 assets/teaching_images，服务端目录缓存保持原存储格式。

HTTP 请求、凭证和素材缓存分别负责网络、认证状态与校验缓存；RTC 管理摄像头、预览和连接资源。Activity 保留现有流程与生命周期行为，本次不引入新的 MVVM 框架或改变训练时序。

`WebRtcClient` 统一管理三条路径：keypoints 上传视频并接收 pose；annotated 接收服务端标注视频、不创建 pose 通道；diagnostic 接收原图视频和 pose，并支持授权 PNG 抓帧。可靠 rehab 通道共用业务结果和结束确认。`SkeletonOverlayView` 校验会话、序号、坐标和 300 ms 时效，使用与本地视频相同的 FIT/镜像变换。`PerformanceStats` 统计实际区间帧率、编码、流量与丢包。日常模式保存在 SessionStore，TrainingActivity 进入计划时固定选择；显示切换不影响连接或计数。

页面、RTC 连接和后端动作阶段是不同状态，后续修改应维持边界。应用 applicationId、签名、SharedPreferences 名称/字段、Keystore 别名、接口字段和教学缓存键保持兼容。Activity 类名变更时同步 Manifest、显式 Intent 和仪器测试。

生产依赖仅保留当前源码直接需要的 AppCompat、Material、OkHttp 和 WebRTC。机器人 SDK、示例二进制和相应依赖已移除；已有不可用的功能占位页仍保留，因为它们属于当前产品界面。
