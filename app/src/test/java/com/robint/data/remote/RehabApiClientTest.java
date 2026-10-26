package com.robint.data.remote;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RehabApiClientTest {
    @Test public void normalizesServiceAddressWithoutChangingWireProtocol() {
        assertEquals("http://192.168.1.2:8000", RehabApiClient.normalize(" 192.168.1.2:8000 "));
        assertEquals("https://example.com", RehabApiClient.normalize("wss://example.com/path?q=1#fragment"));
        assertEquals("", RehabApiClient.normalize("http://user:password@example.com"));
    }
}
