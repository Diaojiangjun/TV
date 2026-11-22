package com.fongmi.android.tv.syncplay;

import com.fongmi.android.tv.App;
import com.fongmi.syncplay.SyncPlaySession;

/**
 * 进程内唯一的房间会话。
 *
 * <p>:syncplay 模块自身不持有任何静态状态（便于单测与复用），装配集中在这里：
 * 配置走 Prefers，所有回调投递回主线程。
 */
public final class SyncPlay {

    private static SyncPlaySession session;

    private SyncPlay() {
    }

    public static SyncPlaySession get() {
        SyncPlaySession value = session;
        if (value == null) {
            synchronized (SyncPlay.class) {
                value = session;
                if (value == null) {
                    value = new SyncPlaySession(SyncPlaySetting.CONFIG, runnable -> App.post(runnable));
                    session = value;
                }
            }
        }
        return value;
    }
}
