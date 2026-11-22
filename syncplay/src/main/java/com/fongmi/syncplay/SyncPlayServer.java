package com.fongmi.syncplay;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * 一个同步服务器条目：展示名 + host:port。
 * 内置条目只读，用户添加的条目可以删除，两者在 {@link SyncPlayServers} 里统一排序。
 */
public final class SyncPlayServer {

    private final String name;
    private final String address;
    private final boolean builtin;

    public SyncPlayServer(String name, String address) {
        this(name, address, false);
    }

    public SyncPlayServer(String name, String address, boolean builtin) {
        this.name = name == null ? "" : name.trim();
        this.address = address == null ? "" : address.trim();
        this.builtin = builtin;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public boolean isValid() {
        return SyncPlayEndpoint.parse(address) != null;
    }

    /** 列表里显示的文案；没有起名就退回地址。 */
    public String getLabel() {
        return name.isEmpty() ? address : name + "（" + address + "）";
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("name", name);
            object.put("address", address);
            return object;
        } catch (JSONException e) {
            return null;
        }
    }

    public static SyncPlayServer fromJson(JSONObject object) {
        if (object == null) return null;
        String address = object.optString("address", "").trim();
        if (SyncPlayEndpoint.parse(address) == null) return null;
        return new SyncPlayServer(object.optString("name", ""), address);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SyncPlayServer other)) return false;
        return address.equalsIgnoreCase(other.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address.toLowerCase());
    }
}
