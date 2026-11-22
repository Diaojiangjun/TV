package com.fongmi.syncplay;

/**
 * 会话需要的全部可变配置。模块本身不碰任何存储框架，
 * 由调用方（Android 端是 Prefers）实现，换成别的持久化方式不用改协议层。
 */
public interface SyncConfig {

    String getServer();

    void setServer(String value);

    String getUsername();

    void setUsername(String value);

    String getRoom();

    void setRoom(String value);

    /** 房主换集时是否自动跟到同一集。 */
    boolean isFollowEpisode();

    /** 意外掉线后是否自动重连（主动断开不重连）。 */
    boolean isReconnect();
}
