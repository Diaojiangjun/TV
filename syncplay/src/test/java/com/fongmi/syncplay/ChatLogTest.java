package com.fongmi.syncplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 聊天缓冲的边界行为：只留最近 N 条、按顺序、清空彻底。
 * 界面依赖 snapshot 的顺序渲染，所以顺序也要钉住。
 */
public class ChatLogTest {

    @Test
    public void keepsOrder() {
        ChatLog log = new ChatLog();
        log.append(message("第一条"));
        log.append(message("第二条"));
        assertEquals(2, log.snapshot().size());
        assertEquals("第一条", log.snapshot().get(0).getText());
        assertEquals("第二条", log.snapshot().get(1).getText());
    }

    @Test
    public void dropsOldestWhenFull() {
        ChatLog log = new ChatLog();
        for (int i = 0; i < SyncTuning.MESSAGE_LIMIT + 5; i++) log.append(message("m" + i));
        assertEquals(SyncTuning.MESSAGE_LIMIT, log.snapshot().size());
        // 最旧的 5 条被挤掉
        assertEquals("m5", log.snapshot().get(0).getText());
    }

    @Test
    public void clearDropsEverything() {
        ChatLog log = new ChatLog();
        log.append(message("hi"));
        log.clear();
        assertTrue(log.snapshot().isEmpty());
    }

    /** 本地发出的消息要标记 self，界面据此区分左右。 */
    @Test
    public void keepsSelfFlag() {
        ChatLog log = new ChatLog();
        log.append(new SyncPlaySession.Message("我", "在吗", true));
        assertTrue(log.snapshot().get(0).isSelf());
    }

    private static SyncPlaySession.Message message(String text) {
        return new SyncPlaySession.Message("alice", text, false);
    }
}
