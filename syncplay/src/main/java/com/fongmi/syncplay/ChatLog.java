package com.fongmi.syncplay;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天环形缓冲：只保留最近 {@link SyncTuning#MESSAGE_LIMIT} 条。
 *
 * <p>纯内存，不落盘。重进房间或重连时由会话层 {@link #clear()}。
 * 所有方法自带同步，会话层不必再包一层。
 */
final class ChatLog {

    private final List<SyncPlaySession.Message> messages = new ArrayList<>();

    void append(SyncPlaySession.Message message) {
        synchronized (messages) {
            messages.add(message);
            while (messages.size() > SyncTuning.MESSAGE_LIMIT) messages.remove(0);
        }
    }

    List<SyncPlaySession.Message> snapshot() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    void clear() {
        synchronized (messages) {
            messages.clear();
        }
    }
}
