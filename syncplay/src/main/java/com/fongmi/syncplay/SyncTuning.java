package com.fongmi.syncplay;

/**
 * 协议相关的数值约定集中在这里，避免散落在会话类里。
 *
 * <p>带「官方」注释的几项来自参考客户端的 {@code constants.py}
 * （{@code MAX_CHAT_MESSAGE_LENGTH}/{@code MAX_USERNAME_LENGTH}/{@code MAX_ROOM_NAME_LENGTH}），
 * 超出会被服务器静默丢弃，不是可调参数，改动前必须先确认服务器行为。
 * 其余是本实现的本地策略，可以按体验调整。
 */
public final class SyncTuning {

    private SyncTuning() {
    }

    /** 官方限制：单条聊天消息 150 字符。 */
    public static final int CHAT_LIMIT = 150;

    /** 官方限制：用户名 16 字符。 */
    public static final int USERNAME_LIMIT = 16;

    /** 官方限制：房间名 35 字符。 */
    public static final int ROOM_LIMIT = 35;

    /** 本地策略：seek 后允许的误差，避免自己刚拖完就被别人的状态拉回。 */
    public static final long SEEK_GUARD_MS = 400;

    /** 本地策略：常态漂移容忍度，超过才硬校正。 */
    public static final long DRIFT_GUARD_MS = 1500;

    /** 本地策略：两次自动校正的最小间隔。 */
    public static final long APPLY_COOLDOWN_MS = 1000;

    /** 本地策略：聊天只保留最近 200 条，纯内存，不落盘。 */
    public static final int MESSAGE_LIMIT = 200;

    /** 本地策略：连续重连次数上限。 */
    public static final int RECONNECT_LIMIT = 5;

    /** 本地策略：重连间隔。 */
    public static final long RECONNECT_DELAY_MS = 3000;

    /** 本地策略：握手超时。 */
    public static final long CONNECT_TIMEOUT_MS = 12000;
}
