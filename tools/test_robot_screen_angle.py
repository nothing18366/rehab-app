import contextlib
import io
import unittest
from unittest.mock import patch

import robot_screen_angle as angle


class ScreenAngleTest(unittest.TestCase):
    def test_preview_never_connects(self):
        for command in ("pitch15", "reset"):
            with patch.object(angle, "exchange") as exchange, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(angle.main([command]), 0)
                exchange.assert_not_called()

    def test_only_documented_motion_commands(self):
        pitch = angle.request("pitch15")
        self.assertEqual((pitch["service"], pitch["action"], pitch["data"]),
                         ("afr", "neck_control", {"pitch": 15}))
        reset = angle.request("reset")
        self.assertEqual(reset["action"], "reset_neck")
        self.assertNotIn("data", reset)
        self.assertNotEqual(reset["messageId"], pitch["messageId"])
        with self.assertRaises(ValueError):
            angle.request("pitch90")

    def test_reply_must_belong_to_this_request_and_client(self):
        request = angle.request("pitch15")
        reply = dict(type="AGENT_SERVICE_RESPONSE", requestMessageId=request["messageId"],
                     fromId="app_main", toId=angle.CLIENT_ID, service="afr",
                     action="neck_control", code=0)
        self.assertTrue(angle.matching_response(reply, request))
        for key, value in (("requestMessageId", "old"), ("toId", "other"),
                           ("fromId", "other"), ("service", "ros"), ("action", "reset_neck"),
                           ("type", "AGENT_SERVICE_NOTIFY"), ("code", False)):
            self.assertFalse(angle.matching_response({**reply, key: value}, request))
        self.assertTrue(angle.matching_response({**reply, "service": None, "action": None}, request))
        self.assertTrue(angle.matching_response({**reply, "service": "", "action": ""}, request))
        self.assertFalse(angle.matching_response([], request))

    def test_send_failure_is_not_success_and_does_not_retry(self):
        with patch.object(angle, "exchange", return_value={"code": 1001}) as exchange:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(angle.main(["reset", "--host", "robot", "--send"]), 1)
            exchange.assert_called_once()
        with patch.object(angle, "exchange", side_effect=TimeoutError("timeout")) as exchange:
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(angle.main(["pitch15", "--host", "robot", "--send"]), 1)
            exchange.assert_called_once()


if __name__ == "__main__":
    unittest.main()
