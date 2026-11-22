package com.fongmi.syncplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SyncPlayProtocolTest {

    @Test
    public void parsesHostAndPort() {
        SyncPlayEndpoint endpoint = SyncPlayEndpoint.parse(" syncplay.pl:8996 ");
        assertEquals("syncplay.pl", endpoint.host());
        assertEquals(8996, endpoint.port());
    }

    @Test
    public void parsesIpv6Literal() {
        SyncPlayEndpoint endpoint = SyncPlayEndpoint.parse("[2001:db8::1]:9000");
        assertEquals("2001:db8::1", endpoint.host());
        assertEquals(9000, endpoint.port());
    }

    @Test
    public void rejectsMalformedEndpoints() {
        assertNull(SyncPlayEndpoint.parse(""));
        assertNull(SyncPlayEndpoint.parse("syncplay.pl"));
        assertNull(SyncPlayEndpoint.parse("syncplay.pl:"));
        assertNull(SyncPlayEndpoint.parse("syncplay.pl:0"));
        assertNull(SyncPlayEndpoint.parse("syncplay.pl:70000"));
        assertNull(SyncPlayEndpoint.parse("syncplay.pl:abc"));
    }

    @Test
    public void marksOfficialEndpointsForTls() {
        assertTrue(SyncPlayEndpoint.parse("syncplay.pl:8996").isOfficial());
        assertTrue(SyncPlayEndpoint.parse("SYNCPLAY.PL:8999").isOfficial());
        assertFalse(SyncPlayEndpoint.parse("192.168.1.10:8996").isOfficial());
        assertFalse(SyncPlayEndpoint.parse("syncplay.pl:9999").isOfficial());
    }

    @Test
    public void splitsConcatenatedFrames() {
        String first = "{\"State\":{\"playstate\":{\"position\":12.5,\"paused\":false}}}";
        String second = "{\"Chat\":\"hi\"}";
        String stream = first + "\r\n" + second + "\r\n";
        int end = SyncPlayClient.findEnd(stream);
        assertEquals(first.length() - 1, end);
        assertEquals(second, stream.substring(end + 1).trim());
    }

    @Test
    public void ignoresBracesInsideStrings() {
        String frame = "{\"Chat\":\"a } b { c\"}";
        int end = SyncPlayClient.findEnd(frame);
        assertEquals(frame.length() - 1, end);
    }

    @Test
    public void waitsForIncompleteFrame() {
        assertEquals(-1, SyncPlayClient.findEnd("{\"State\":{\"playstate\":"));
        assertEquals(-1, SyncPlayClient.findEnd("{\"Chat\":\"unterminated"));
    }

    @Test
    public void handlesEscapedQuoteBeforeBrace() {
        String frame = "{\"Chat\":\"end \\\" }\"}";
        int end = SyncPlayClient.findEnd(frame);
        assertEquals(frame.length() - 1, end);
    }

    /** 掉线后断连按钮、页面退出都会重复 close()，必须幂等且不碰网络。 */
    @Test
    public void closeIsIdempotentWithoutConnection() {
        SyncPlayClient client = client();
        client.close();
        client.close();
        assertTrue(client.isClosed());
    }

    /** 断连瞬间每秒一次的上报可能还在路上，close() 之后必须安全降级而不是抛异常。 */
    @Test
    public void sendingAfterCloseIsHarmless() {
        SyncPlayClient client = client();
        client.close();
        client.sendState(12.5, false, false);
        client.sendChat("hi");
        client.sendFile("剧名#第1集", 1440, 0);
        assertTrue(client.isClosed());
    }

    private static SyncPlayClient client() {
        return new SyncPlayClient(SyncPlayEndpoint.parse("syncplay.pl:8996"), "probe", "room", new SyncBackend.Listener() {}, Runnable::run);
    }
}
