package com.fongmi.android.tv.syncplay;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.syncplay.SyncPlayEndpoint;
import com.fongmi.syncplay.SyncPlayServer;

import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器清单：内置若干可用的公共服务器，用户可自行增删自定义服务器（含局域网自建）。
 * 存档格式是 JSON 数组，放在 Prefers 里，因此不需要额外建表。
 */
public final class SyncPlayServers {

    /** 添加结果，界面据此给出不同提示。 */
    public enum Result {OK, INVALID, DUPLICATE, LIMIT}

    private static final String KEY = "syncplay_servers";
    private static final int LIMIT = 20;

    // 官方公共服务器同时开放 8995-8999，这几个端口都实测可连；默认用 8996
    private static final List<SyncPlayServer> BUILTIN = List.of(
            new SyncPlayServer("Syncplay 官方", SyncPlayEndpoint.DEFAULT, true),
            new SyncPlayServer("Syncplay 官方备用", "syncplay.pl:8999", true));

    private SyncPlayServers() {
    }

    public static List<SyncPlayServer> builtin() {
        return new ArrayList<>(BUILTIN);
    }

    public static List<SyncPlayServer> custom() {
        List<SyncPlayServer> list = new ArrayList<>();
        String raw = Prefers.getString(KEY, "");
        if (TextUtils.isEmpty(raw)) return list;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                SyncPlayServer server = SyncPlayServer.fromJson(array.optJSONObject(i));
                if (server != null && !contains(list, server.getAddress())) list.add(server);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** 内置在前、自定义在后，内置项不可删除。 */
    public static List<SyncPlayServer> all() {
        List<SyncPlayServer> list = builtin();
        for (SyncPlayServer server : custom()) if (!contains(list, server.getAddress())) list.add(server);
        return list;
    }

    public static boolean isBuiltin(String address) {
        if (address == null) return false;
        for (SyncPlayServer server : BUILTIN) if (server.getAddress().equalsIgnoreCase(address.trim())) return true;
        return false;
    }

    public static Result add(String name, String address) {
        String value = address == null ? "" : address.trim();
        if (SyncPlayEndpoint.parse(value) == null) return Result.INVALID;
        if (isBuiltin(value) || contains(custom(), value)) return Result.DUPLICATE;
        List<SyncPlayServer> list = custom();
        if (list.size() >= LIMIT) return Result.LIMIT;
        list.add(new SyncPlayServer(name, value));
        save(list);
        return Result.OK;
    }

    public static boolean remove(String address) {
        if (address == null || isBuiltin(address)) return false;
        List<SyncPlayServer> list = custom();
        boolean removed = list.removeIf(server -> server.getAddress().equalsIgnoreCase(address.trim()));
        if (removed) save(list);
        return removed;
    }

    /**
     * 保证地址出现在列表里：用户手填了一个没登记过的地址时，把它补进自定义列表，
     * 否则下次打开设置页会看到「当前服务器」但列表里找不到它。
     */
    public static void ensure(String address) {
        String value = address == null ? "" : address.trim();
        if (SyncPlayEndpoint.parse(value) == null) return;
        if (isBuiltin(value) || contains(custom(), value)) return;
        add("", value);
    }

    /** 按地址找展示名，找不到就返回地址本身。 */
    public static String label(String address) {
        String value = address == null ? "" : address.trim();
        for (SyncPlayServer server : all()) {
            if (server.getAddress().equalsIgnoreCase(value)) return server.getLabel();
        }
        return value;
    }

    @Nullable
    static SyncPlayServer find(String address) {
        String value = address == null ? "" : address.trim();
        for (SyncPlayServer server : all()) {
            if (server.getAddress().equalsIgnoreCase(value)) return server;
        }
        return null;
    }

    private static boolean contains(List<SyncPlayServer> list, String address) {
        for (SyncPlayServer server : list) if (server.getAddress().equalsIgnoreCase(address)) return true;
        return false;
    }

    private static void save(List<SyncPlayServer> list) {
        JSONArray array = new JSONArray();
        for (SyncPlayServer server : list) {
            if (server.toJson() != null) array.put(server.toJson());
        }
        Prefers.put(KEY, array.toString());
    }
}
