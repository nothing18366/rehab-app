package com.robint.data.remote;

import org.junit.Test;
import org.json.JSONObject;

import static org.junit.Assert.assertEquals;

public class RehabApiClientTest {
    @Test public void progressUsesQualifiedCountOnlyWhenProvided() throws Exception {
        JSONObject legacy = new JSONObject("{\"count\":10,\"passed\":3}");
        assertEquals(10, RehabApiClient.progressCount(legacy));
        JSONObject fist = new JSONObject("{\"count\":10,\"passed\":3,\"progress_count\":3}");
        assertEquals(3, RehabApiClient.progressCount(fist));
        fist.put("progress_count", 0);
        assertEquals(0, RehabApiClient.progressCount(fist));
    }

    @Test public void normalizesServiceAddressWithoutChangingWireProtocol() {
        assertEquals("http://192.168.1.2:8000", RehabApiClient.normalize(" 192.168.1.2:8000 "));
        assertEquals("https://example.com", RehabApiClient.normalize("wss://example.com/path?q=1#fragment"));
        assertEquals("", RehabApiClient.normalize("http://user:password@example.com"));
    }
}
