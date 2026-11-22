package com.fongmi.syncplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class SyncPlayServerTest {

    @Test
    public void rejectsInvalidAddress() {
        assertFalse(new SyncPlayServer("x", "no-port").isValid());
        assertFalse(new SyncPlayServer("x", "").isValid());
        assertTrue(new SyncPlayServer("x", "192.168.1.10:8996").isValid());
    }

    @Test
    public void labelFallsBackToAddress() {
        assertEquals("192.168.1.10:8996", new SyncPlayServer("", "192.168.1.10:8996").getLabel());
        assertEquals("家里（192.168.1.10:8996）", new SyncPlayServer("家里", "192.168.1.10:8996").getLabel());
    }

    @Test
    public void equalsIgnoresAddressCase() {
        assertEquals(new SyncPlayServer("a", "SyncPlay.pl:8996"), new SyncPlayServer("b", "syncplay.pl:8996"));
    }

    @Test
    public void survivesJsonRoundTrip() {
        SyncPlayServer origin = new SyncPlayServer("客厅", "192.168.1.10:8996");
        SyncPlayServer parsed = SyncPlayServer.fromJson(origin.toJson());
        assertEquals("客厅", parsed.getName());
        assertEquals("192.168.1.10:8996", parsed.getAddress());
        assertFalse(parsed.isBuiltin());
    }

    @Test
    public void dropsEntriesWithBrokenAddress() throws Exception {
        JSONObject object = new JSONObject();
        object.put("name", "坏条目");
        object.put("address", "192.168.1.10");
        assertNull(SyncPlayServer.fromJson(object));
        assertNull(SyncPlayServer.fromJson(null));
    }
}
