#!/usr/bin/env python3
"""Isolated Home MQTT test; motion commands only publish with --send."""
import argparse
import json
import sys
import threading
import time
import uuid

CLIENT_ID = "com.robint.sdk.demo"
UPLINK = "homeservice/uplink"
DOWNLINK = "homeservice/downlink"


def request(command):
    message = {
        "messageId": str(uuid.uuid4()),
        "type": "AGENT_SERVICE_REQUEST",
        "fromId": CLIENT_ID,
        "toId": "app_main",
        "time": int(time.time() * 1000),
        "service": "afr",
        "action": "neck_control" if command == "pitch15" else "reset_neck",
        "version": 1,
    }
    if command == "info":
        message.update(service="system", action="get_service_info",
                       data={"service_code": "afr"})
    elif command == "pitch15":
        # shortcut: only the documented example; expand after vendor confirms units/limits.
        message["data"] = {"pitch": 15}
    elif command != "reset":
        raise ValueError("Unknown command")
    return message


def matching_response(response, message):
    return (
        isinstance(response, dict)
        and response.get("type") == "AGENT_SERVICE_RESPONSE"
        and response.get("requestMessageId") == message["messageId"]
        and response.get("fromId") == "app_main"
        and response.get("toId") == CLIENT_ID
        and response.get("service") in (None, "", message["service"])
        and response.get("action") in (None, "", message["action"])
        and type(response.get("code")) is int
    )


def exchange(host, message):
    import paho.mqtt.client as mqtt

    done = threading.Event()
    result = {}
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=CLIENT_ID,
                         clean_session=True, reconnect_on_failure=False)

    def fail(error):
        result["error"] = str(error)
        done.set()

    def on_connect(client, userdata, flags, reason, properties):
        if reason.is_failure:
            fail(reason)
        elif client.subscribe(UPLINK, qos=0)[0] != mqtt.MQTT_ERR_SUCCESS:
            fail("Subscription failed")

    def on_subscribe(client, userdata, mid, reasons, properties):
        if not reasons or any(reason.is_failure for reason in reasons):
            fail("Subscription rejected")
            return
        # No retain, no automatic retry: a timeout must not repeat a motor command.
        published = client.publish(DOWNLINK, json.dumps(message), qos=0, retain=False)
        if published.rc != mqtt.MQTT_ERR_SUCCESS:
            fail("Publish failed")

    def on_message(client, userdata, incoming):
        if incoming.retain or incoming.topic != UPLINK:
            return
        try:
            response = json.loads(incoming.payload)
        except (ValueError, UnicodeError):
            return
        if matching_response(response, message):
            result["response"] = response
            done.set()

    def on_disconnect(client, userdata, flags, reason, properties):
        if not done.is_set():
            fail("MQTT disconnected; command outcome unknown")

    client.on_connect = on_connect
    client.on_subscribe = on_subscribe
    client.on_message = on_message
    client.on_disconnect = on_disconnect
    client.connect_timeout = 5
    try:
        client.connect(host, 1883, keepalive=20)
        client.loop_start()
        if not done.wait(10):
            raise TimeoutError("No matching reply in 10s; do not automatically retry")
        if "error" in result:
            raise RuntimeError(result["error"])
        return result["response"]
    finally:
        client.disconnect()
        client.loop_stop()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["info", "pitch15", "reset"])
    parser.add_argument("--host", help="Robot IP, required for info or --send")
    parser.add_argument("--send", action="store_true", help="Actually send ONE motion request")
    args = parser.parse_args(argv)
    message = request(args.command)
    if args.command != "info" and not args.send:
        print("仅预览，未连接机器人，未发送运动命令：")
        print(json.dumps(message, ensure_ascii=False, indent=2))
        return 0
    if not args.host:
        parser.error("info / --send requires --host")
    try:
        response = exchange(args.host, message)
    except (ImportError, OSError, RuntimeError) as error:
        print(f"测试失败：{error}", file=sys.stderr)
        return 1
    print(json.dumps(response, ensure_ascii=False, indent=2))
    if args.command != "info":
        print("响应 code=0 仅代表服务接收/处理成功，实际角度和是否到位仍需现场确认。")
    return 0 if response["code"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
