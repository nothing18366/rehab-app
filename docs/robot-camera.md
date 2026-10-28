# 机器人摄像头实机排查

2026-10-26，RK3588 / Android 12，使用现有 Camera2 和 WebRTC SDK，不改动后端、动作算法及图像方向。

设备通过 Android 暴露两路外置相机：

| Android 相机 ID | 系统方向标签 | Linux 设备名称 | 实测 |
| --- | --- | --- | --- |
| 100 | 前置 | CCB Camera | 配置 640×480 时失败；HAL 日志为 `VIDIOC_S_FMT: Device or resource busy` |
| 102 | 后置 | AX-8M-V2.0 | Camera2 / WebRTC 请求 640×480、15 FPS，成功收到连续帧 |

“后置”是设备驱动提供的标签，不据此判断摄像头在机器人外壳上的实际位置。100 的元数据虽然声明支持 640×480，但驱动实际配置失败；不是仅凭支持列表就能确认可用。系统还运行厂商相机与 ROS 服务，本轮未停止或修改它们，也未确认具体哪个进程持有该路设备。需要使用 100 时，向厂商确认相机共享接口、资源释放方式及用途。

App 原先在采集失败后仍将采集器当成运行中，调用 `switchCamera` 会得到 `camera is not running`。修复后失败状态不再标记就绪，切换时释放失败采集器并重新创建目标相机；成功出帧后恢复开始按钮及准备提示。普通正常采集时仍使用原切换路径，不自动停止机器人服务，不更改算法输入。

## 使用

2026-10-28 起，训练页固定使用系统标记的“后置”（当前机器人 ID 102）。准备页及设置页已移除摄像头切换与默认前置选项，不再读取旧的摄像头偏好。等待画面和“摄像头已就绪”后开始。

## 可重复的硬件检查

测试仅采集并丢弃图像，不上传、不保存视频，不创建患者训练记录；只在显式参数启用时运行。恢复路径测试需要已登录并能够打开首页，且前置相机不可用、后置可用。

```bash
adb -s 机器人IP:端口 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 机器人IP:端口 shell am instrument -w \
  -e class com.robint.CameraProbeTest#cameraDeliversFrames -e cameraId 102 \
  com.robint.sdk.demo.test/androidx.test.runner.AndroidJUnitRunner
adb -s 机器人IP:端口 shell am instrument -w \
  -e class com.robint.CameraProbeTest#failedPreviewCanSwitchToAnotherCamera -e probeSwitch true \
  com.robint.sdk.demo.test/androidx.test.runner.AndroidJUnitRunner
```

相机驱动日志显示 102 可能以 30 FPS 运行，即使请求为 15 FPS；这不代表算法处理 FPS。完整动作计数及性能仍需实际训练验证。

## WebRTC 编码兼容性（2026-10-27）

机器人上的 RK3588 硬件 VP8 和 H.264 编码器都能建立 WebRTC 连接，但发送的 RTP 包在电脑端 aiortc 解码时持续报 `avcodec_send_packet()`，服务端解码帧数为 0，随后 App 按 10 秒无结果中断。局域网 RTT、相机采集和编码耗时均正常。

App 在检测到 RK3588 设备时改用 WebRTC 软件 VP8 编码；手机仍使用 H.264 路径。电脑回环测试收到 31 张 640×480 帧并通过，服务端保留 VP8/H.264 协商。若后续需要恢复硬件编码，应先在 Jetson/机器人实机上用相同回环测试确认 RTP 解码兼容性。

## 本轮验证结果

- APK、测试 APK 构建及 Android 单元测试通过；修复版 APK 已覆盖安装，保留原 App 数据。
- 102 独立出帧仪器测试通过（2.144 秒），收到至少 3 张 640×480 图像。
- 恢复路径的断言及日志已走到“正在打开后置摄像头 / 后置摄像头已就绪”。但测试结束阶段，系统记录了来自其他 PID 的 force-stop，仪器报告 `Process crashed`；不能将该项标为完整通过，具体停止来源尚未确认。
- 没有停止厂商相机或 ROS 服务，没有改动后端、数据库、模型、动作算法；尚未进行六动作识别与真实患者计数回归。
