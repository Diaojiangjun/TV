package com.fongmi.android.tv.syncplay;

import android.text.TextUtils;

import com.fongmi.syncplay.SyncConfig;
import com.fongmi.syncplay.SyncPlayEndpoint;
import com.fongmi.syncplay.SyncTuning;

import com.github.catvod.utils.Prefers;

import java.util.Random;

public class SyncPlaySetting {

    /**
     * 交给 :syncplay 模块的配置实现。模块不依赖 Prefers，所有持久化都收敛在这里，
     * 换存储方式（DataStore、文件、内存）不需要动协议层。
     */
    public static final SyncConfig CONFIG = new SyncConfig() {

        @Override
        public String getServer() {
            return SyncPlaySetting.getServer();
        }

        @Override
        public void setServer(String value) {
            SyncPlaySetting.putServer(value);
        }

        @Override
        public String getUsername() {
            return SyncPlaySetting.getUsername();
        }

        @Override
        public void setUsername(String value) {
            SyncPlaySetting.putUsername(value);
        }

        @Override
        public String getRoom() {
            return SyncPlaySetting.getRoom();
        }

        @Override
        public void setRoom(String value) {
            SyncPlaySetting.putRoom(value);
        }

        @Override
        public boolean isFollowEpisode() {
            return SyncPlaySetting.isFollowEpisode();
        }

        @Override
        public boolean isReconnect() {
            return SyncPlaySetting.isReconnect();
        }
    };

    // 沿用协议模块里的官方字符上限，避免两处各写一个数字后漂移
    private static final int USERNAME_LIMIT = SyncTuning.USERNAME_LIMIT;
    private static final int ROOM_LIMIT = SyncTuning.ROOM_LIMIT;

    public static String getServer() {
        String value = Prefers.getString("syncplay_server", SyncPlayEndpoint.DEFAULT);
        return TextUtils.isEmpty(value) ? SyncPlayEndpoint.DEFAULT : value;
    }

    /** 设置页展示用：有别名显示别名，没有就显示地址。 */
    public static String getServerLabel() {
        return SyncPlayServers.label(getServer());
    }

    public static void putServer(String value) {
        String address = value == null ? "" : value.trim();
        Prefers.put("syncplay_server", address);
        // 手填的新地址补进自定义列表，否则设置页里「当前服务器」在列表中找不到对应项
        SyncPlayServers.ensure(address);
    }

    public static String getUsername() {
        String value = Prefers.getString("syncplay_username", "");
        if (TextUtils.isEmpty(value)) {
            value = limit("用户" + (1000 + new Random().nextInt(9000)), USERNAME_LIMIT);
            Prefers.put("syncplay_username", value);
        }
        return value;
    }

    public static void putUsername(String value) {
        String text = limit(value == null ? "" : value.trim(), USERNAME_LIMIT);
        Prefers.put("syncplay_username", TextUtils.isEmpty(text) ? getUsername() : text);
    }

    public static String getRoom() {
        return Prefers.getString("syncplay_room", "");
    }

    public static void putRoom(String value) {
        Prefers.put("syncplay_room", limit(value == null ? "" : value.trim(), ROOM_LIMIT));
    }

    public static String createRoom() {
        int value = 100000 + new Random().nextInt(900000);
        return String.valueOf(value);
    }

    /** 房主换集时是否自动跟到同一集。 */
    public static boolean isFollowEpisode() {
        return Prefers.getBoolean("syncplay_follow", true);
    }

    public static void putFollowEpisode(boolean value) {
        Prefers.put("syncplay_follow", value);
    }

    /** 意外掉线后是否自动重连（主动断开不重连）。 */
    public static boolean isReconnect() {
        return Prefers.getBoolean("syncplay_reconnect", true);
    }

    public static void putReconnect(boolean value) {
        Prefers.put("syncplay_reconnect", value);
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
