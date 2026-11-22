package com.fongmi.syncplay;

import java.util.concurrent.Executor;

/**
 * 后端工厂。目前只有 Syncplay 一种实现，但会话层不再直接 new 具体类，
 * 将来接入别的同步协议时只需要在这里按 scheme/端口分派，不用改 {@link SyncPlaySession}。
 */
public final class SyncBackends {

    private SyncBackends() {
    }

    public static SyncBackend create(SyncPlayEndpoint endpoint, String username, String room, SyncBackend.Listener listener, Executor callback) {
        return new SyncPlayClient(endpoint, username, room, listener, callback);
    }
}
