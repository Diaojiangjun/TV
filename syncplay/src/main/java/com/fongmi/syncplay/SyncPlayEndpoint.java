package com.fongmi.syncplay;

import java.util.Set;

public final class SyncPlayEndpoint {

    public static final String DEFAULT = "syncplay.pl:8996";

    // 官方公共服务器一律要求 STARTTLS
    private static final Set<String> OFFICIAL = Set.of("syncplay.pl:8995", "syncplay.pl:8996", "syncplay.pl:8997", "syncplay.pl:8998", "syncplay.pl:8999");

    private final String host;
    private final int port;

    private SyncPlayEndpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean isOfficial() {
        return OFFICIAL.contains(key());
    }

    private String key() {
        return host.toLowerCase() + ":" + port;
    }

    @Override
    public String toString() {
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }

    /** 解析 host:port，支持 [::1]:8996 形式的 IPv6；格式不合法返回 null。 */
    public static SyncPlayEndpoint parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return null;
        String host;
        String port;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0 || close + 1 >= text.length() || text.charAt(close + 1) != ':') return null;
            host = text.substring(1, close);
            port = text.substring(close + 2);
        } else {
            int colon = text.lastIndexOf(':');
            if (colon <= 0) return null;
            host = text.substring(0, colon);
            port = text.substring(colon + 1);
        }
        host = host.trim();
        port = port.trim();
        if (host.isEmpty() || !port.matches("[0-9]{1,5}")) return null;
        int value = Integer.parseInt(port);
        if (value <= 0 || value > 65535) return null;
        return new SyncPlayEndpoint(host, value);
    }
}
