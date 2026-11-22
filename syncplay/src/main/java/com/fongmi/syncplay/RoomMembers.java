package com.fongmi.syncplay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 房间成员集合，保持加入顺序，界面按这个顺序显示。
 *
 * <p>服务器不会把「加入前已存在的人」补发给新加入者，
 * 因此会话层还会用每条 State 里的 {@code setBy} 调 {@link #addIfAbsent(String)} 逐步补全。
 */
final class RoomMembers {

    private final Set<String> names = new LinkedHashSet<>();

    /** 握手成功后重置：房间里至少有自己的名字。 */
    void reset(String self) {
        synchronized (names) {
            names.clear();
            if (!Texts.isEmpty(self)) names.add(self);
        }
    }

    /** 返回 true 表示这次真的新增了成员，调用方据此决定是否刷新界面。 */
    boolean addIfAbsent(String name) {
        if (Texts.isEmpty(name)) return false;
        synchronized (names) {
            return names.add(name);
        }
    }

    void add(String name) {
        addIfAbsent(name);
    }

    void remove(String name) {
        if (Texts.isEmpty(name)) return;
        synchronized (names) {
            names.remove(name);
        }
    }

    void clear() {
        synchronized (names) {
            names.clear();
        }
    }

    int size() {
        synchronized (names) {
            return names.size();
        }
    }

    List<String> snapshot() {
        synchronized (names) {
            return new ArrayList<>(names);
        }
    }
}
