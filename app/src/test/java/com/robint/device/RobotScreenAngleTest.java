package com.robint.device;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RobotScreenAngleTest {
    private static final String CLIENT = "com.robint.sdk.demo";

    @Test public void buildsEveryIntegerFromZeroToThirtyAndDefaultsToTen() throws Exception {
        assertEquals(10, RobotScreenAngle.DEFAULT_PITCH);
        for (int pitch = 0; pitch <= 30; pitch++) {
            JSONObject request = RobotScreenAngle.request(pitch, CLIENT);
            assertEquals("afr", request.getString("service"));
            assertEquals("neck_control", request.getString("action"));
            assertEquals(pitch, request.getJSONObject("data").getInt("pitch"));
            assertEquals(CLIENT, request.getString("fromId"));
            assertEquals("app_main", request.getString("toId"));
            assertEquals("AGENT_SERVICE_REQUEST", request.getString("type"));
        }
        assertNotEquals(RobotScreenAngle.request(10, CLIENT).getString("messageId"), RobotScreenAngle.request(10, CLIENT).getString("messageId"));
    }

    @Test public void rejectsUnsupportedAnglesBeforeSending() throws Exception {
        for (int pitch : new int[]{Integer.MIN_VALUE, -1, 31, Integer.MAX_VALUE}) {
            try { RobotScreenAngle.request(pitch, CLIENT); fail("Accepted unsupported angle " + pitch); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void ignoresNotificationsOtherClientsAndStaleReplies() throws Exception {
        JSONObject request = RobotScreenAngle.request(10, CLIENT);
        JSONObject reply = new JSONObject().put("type", "AGENT_SERVICE_RESPONSE")
            .put("requestMessageId", request.getString("messageId")).put("fromId", "app_main")
            .put("toId", CLIENT).put("service", "afr").put("action", "neck_control").put("code", 0);
        assertTrue(RobotScreenAngle.matches(reply, request));
        for (String key : new String[]{"requestMessageId", "fromId", "toId", "type", "service", "action", "code"}) {
            assertFalse(key, RobotScreenAngle.matches(new JSONObject(reply.toString()).put(key, "wrong"), request));
        }
        assertFalse(RobotScreenAngle.matches(new JSONObject(reply.toString()).put("code", false), request));
        assertFalse(RobotScreenAngle.matches(new JSONObject(reply.toString()).put("code", 0.5), request));
        JSONObject missingCode = new JSONObject(reply.toString()); missingCode.remove("code");
        assertFalse(RobotScreenAngle.matches(missingCode, request));
        assertTrue(RobotScreenAngle.matches(new JSONObject(reply.toString()).put("code", 1001), request));
        assertTrue(RobotScreenAngle.matches(new JSONObject(reply.toString()).put("action", "").put("service", JSONObject.NULL), request));
    }
}
