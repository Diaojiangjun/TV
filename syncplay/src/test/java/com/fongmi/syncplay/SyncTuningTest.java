package com.fongmi.syncplay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 这几项不是可调参数，是服务器的硬限制（参考客户端 constants.py）：
 * 超长会被静默丢弃，改小会截断用户输入，改大会导致消息发不出去。
 * 用一个显式断言把它们钉住，避免以后「顺手调大一点」。
 */
public class SyncTuningTest {

    @Test
    public void matchesServerLimits() {
        assertEquals(150, SyncTuning.CHAT_LIMIT);
        assertEquals(16, SyncTuning.USERNAME_LIMIT);
        assertEquals(35, SyncTuning.ROOM_LIMIT);
    }
}
