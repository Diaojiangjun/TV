package com.fongmi.syncplay;

import java.io.IOException;

/**
 * 同步后端抽象：房间会话只依赖这个接口，不关心底下是 Syncplay 的行式 JSON、
 * 还是将来可能接入的 WebSocket / WebRTC 实现。
 *
 * <p>实现方必须保证：所有 write 都不能发生在主线程（见 {@link SyncPlayClient} 的写队列），
 * 所有回调都通过主线程派发。
 */
public interface SyncBackend {

    interface Listener {

        default void onConnected(String username, String room, String motd) {}

        default void onRemoteState(double positionSec, boolean paused, boolean doSeek, String setBy) {}

        default void onRemoteFile(String name, String setBy) {}

        default void onRemoteChat(String username, String message) {}

        default void onMember(String type, String username) {}

        default void onLatency(double rttSec) {}

        default void onError(String message) {}

        default void onClosed() {}
    }

    /** 建立连接，允许阻塞，调用方负责放到后台线程。 */
    void connect() throws IOException;

    /** 启动收发循环。 */
    void start();

    /** 位置单位为秒，与 Syncplay 协议一致。 */
    void sendState(double positionSec, boolean paused, boolean doSeek);

    void sendFile(String name, double durationSec, long size);

    void sendChat(String message);

    /** 前向延迟（秒），用于补偿消息在链路上的耗时。 */
    double getForwardDelay();

    /** 服务器可能在握手里改写用户名，握手后以这里返回的为准。 */
    String getUsername();

    boolean isClosed();

    void close();
}
