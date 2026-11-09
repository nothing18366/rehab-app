# 触摸屏俯仰控制与独立测试

来源：[Home MQTT 服务交互协议文档](https://wcnhf9yf71j2.feishu.cn/wiki/NnFAwMOrNij6fMk5aX5c5rnJnkd)，2026-11-04 读取，第 4.1.1、4.2.1 节。
屏幕俯仰使用本机Home MQTT服务；底盘旋转及屏幕电源接口不适用。

当前控制已内置到 App：冷启动默认档位 10，训练页右上角“屏幕角度”提供 0–30 的整数滑动条（步长 1），选好后点“应用角度”才发送；不自动保存到下次启动。原电脑端测试工具保留，仍只提供文档示例 15 和归位，不与 App 调节同时运行，以免相同 clientId 相互断开。

| 项目 | 文档规定 |
| --- | --- |
| MQTT | 机器人 IP，TCP 1883，免密码，局域网使用 |
| clientId / fromId | `com.robint.sdk.demo`，与本项目包名一致 |
| 请求 / 响应 Topic | `homeservice/downlink` / `homeservice/uplink` |
| 俯仰 | `service: afr`，`action: neck_control`，`data: {"pitch": 15}` |
| 归位 | `service: afr`，`action: reset_neck`，无 data，恢复厂家默认位置 |

**文档只提供 pitch=15 示例，未定义单位、增量/绝对值、正负方向、机械限位、当前角度查询或急停接口。15 不能直接认定为 15°。** 2026-11-05 根据用户指定逐次发送 15/10/20/5，均得到实机 code=0；随后按用户要求将 App 调整为 0–30 的整数滑动条，拖动不发送，点“应用角度”只发一次；此前只实机验证了 5/10/15/20，未对整个范围逐点验证。这不等于已经获得厂家限位或传感器反馈。独立工具的归位不代表恢复测试前角度，也不是急停。

## 准备

在本 Android 项目根目录运行。依赖安装在被 Git 忽略的独立环境中，不影响后端 Conda 环境和 Android 依赖：

```sh
python3 -m venv artifacts/screen-angle/venv
artifacts/screen-angle/venv/bin/pip install --index-url https://pypi.org/simple -r tools/requirements-screen-angle.txt
```

同一 clientId 只能有一个连接。不要与其他使用 `com.robint.sdk.demo` 的 MQTT 测试客户端同时运行。

## 测试

只读连接检查，不会触发运动：

```sh
artifacts/screen-angle/venv/bin/python tools/robot_screen_angle.py info --host 192.168.3.121
```

查看两种运动请求，默认不连接、不发送：

```sh
python3 tools/robot_screen_angle.py pitch15
python3 tools/robot_screen_angle.py reset
```

确认硬件参数后，在现场执行单次运动；只有明确添加 `--send` 才会发送：

```sh
artifacts/screen-angle/venv/bin/python tools/robot_screen_angle.py pitch15 --host 192.168.3.121 --send
```

需要恢复厂家默认位置时，单独执行：

```sh
artifacts/screen-angle/venv/bin/python tools/robot_screen_angle.py reset --host 192.168.3.121 --send
```

工具先确认订阅成功，再发送一条 QoS 0、非保留消息，按 requestMessageId、收发身份和响应类型匹配，最长等待 10 秒，不自动重发。收到失败码时以非零状态退出。超时不能推断“没有运动”，应先检查现场，避免重复发送。`code=0` 不等于角度传感器确认到位。

## 已验证

2026-11-04，ADB 目标 `192.168.3.121:5555`：

- 实机只读 MQTT 查询成功，返回 `code: 0`、`data.success: true`、`data.available: true`、`data.service_code: afr`。
- 该响应没有角度单位、范围或当前位置。
- 4 项自动测试通过：默认不连接、仅生成文档运动命令、过滤其他请求/客户端响应、失败和超时不重试。
- 当日仅做只读查询，未发送运动命令；2026-11-05 开始按用户要求进行俯仰测试。

```sh
python3 -m unittest discover -s tools -p 'test_robot_screen_angle.py' -v
```

实机只读响应与预览记录在本地忽略目录 `artifacts/screen-angle-20261104/`；协议 HTML/JSON 也仅保留在此，不放入 Git。

## App 开发验证

新增 `RobotScreenAngleTest` 单元测试覆盖默认值、档位边界、报文身份、过期/错误响应和错误码类型。`TrainingPerformanceTest` 可显式传入 `-e screenAngle true`，使用临时数据库检查启动默认 10、选择但关闭不移动、训练中 15→10 的服务确认，以及训练状态/预览保持。该检查会实际移动机构，不在普通测试时默认开启。命令和验收记录见 [development.md](development.md)。
