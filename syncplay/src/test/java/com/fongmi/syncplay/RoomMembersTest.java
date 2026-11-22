package com.fongmi.syncplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 成员集合：新加入者拿不到历史名单，只能靠 State.setBy 逐步补全，
 * 所以 addIfAbsent 的返回值（是否真的新增）必须准确，界面才不会每秒重绘。
 */
public class RoomMembersTest {

    @Test
    public void resetSeedsSelf() {
        RoomMembers members = new RoomMembers();
        members.reset("alice");
        assertEquals(1, members.size());
        assertEquals("alice", members.snapshot().get(0));
    }

    @Test
    public void addIfAbsentReportsOnlyFirstTime() {
        RoomMembers members = new RoomMembers();
        assertTrue(members.addIfAbsent("bob"));
        assertFalse(members.addIfAbsent("bob"));
    }

    @Test
    public void ignoresBlankNames() {
        RoomMembers members = new RoomMembers();
        assertFalse(members.addIfAbsent(null));
        assertFalse(members.addIfAbsent(""));
        assertEquals(0, members.size());
    }

    @Test
    public void keepsJoinOrder() {
        RoomMembers members = new RoomMembers();
        members.add("alice");
        members.add("bob");
        members.add("carol");
        assertEquals(List.of("alice", "bob", "carol"), members.snapshot());
    }

    @Test
    public void removeAndClear() {
        RoomMembers members = new RoomMembers();
        members.add("alice");
        members.add("bob");
        members.remove("alice");
        assertEquals(List.of("bob"), members.snapshot());
        members.clear();
        assertEquals(0, members.size());
    }

    @Test
    public void resetDropsPreviousRoom() {
        RoomMembers members = new RoomMembers();
        members.add("old");
        members.reset("new");
        assertEquals(List.of("new"), members.snapshot());
    }
}
