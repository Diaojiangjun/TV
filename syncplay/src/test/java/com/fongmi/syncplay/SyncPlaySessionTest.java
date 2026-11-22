package com.fongmi.syncplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话层与宿主的接缝测试：配置、主线程投递都靠注入，
 * 这里用同步 Executor 把异步回调压平，验证行为不依赖 Android。
 */
public class SyncPlaySessionTest {

    private final FakeConfig config = new FakeConfig();
    private final AtomicInteger posts = new AtomicInteger();
    // 同步执行，同时记录投递次数，用来证明回调确实走注入的 Executor
    private final Executor direct = runnable -> {
        posts.incrementAndGet();
        runnable.run();
    };
    private final SyncPlaySession session = new SyncPlaySession(config, direct);

    @After
    public void tearDown() {
        session.shutdown();
    }

    @Test
    public void rejectsUnparsableServer() {
        AtomicInteger errors = new AtomicInteger();
        session.connect("no-port", "tester", "123456", errors::incrementAndGet);
        assertEquals(1, errors.get());
        assertEquals(SyncPlaySession.State.IDLE, session.getState());
        // 参数不合法时不应该污染配置
        assertEquals("", config.server);
    }

    @Test
    public void rejectsEmptyRoom() {
        AtomicInteger errors = new AtomicInteger();
        session.connect("syncplay.pl:8996", "tester", "", errors::incrementAndGet);
        assertEquals(1, errors.get());
        assertEquals(SyncPlaySession.State.IDLE, session.getState());
    }

    @Test
    public void appliesConfigWhenConnectStarts() {
        // 用 192.0.2.0/24（TEST-NET-1，保证不可路由）避免扫到真实局域网；
        // 配置写入发生在发起连接之前，连接失败不影响断言
        session.connect("192.0.2.1:8996", "客厅", "888888", null);
        assertEquals("192.0.2.1:8996", config.server);
        assertEquals("客厅", config.username);
        assertEquals("888888", config.room);
        assertEquals(SyncPlaySession.State.CONNECTING, session.getState());
    }

    @Test
    public void ignoresChatWhileDisconnected() {
        session.sendChat("在吗");
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    public void notifiesObserversThroughInjectedExecutor() {
        AtomicInteger changed = new AtomicInteger();
        session.addObserver(changed::incrementAndGet);
        session.disconnect();
        assertTrue(posts.get() > 0);
        assertTrue(changed.get() > 0);
    }

    @Test
    public void reportsServerAndUsernameFromConfig() {
        config.server = "syncplay.pl:8999";
        config.username = "阿伟";
        assertEquals("syncplay.pl:8999", session.getServer());
        // 未连接时用配置里的用户名兜底
        assertEquals("阿伟", session.getUsername());
    }

    @Test
    public void shutdownLeavesSessionIdle() {
        session.disconnect();
        session.shutdown();
        assertEquals(SyncPlaySession.State.IDLE, session.getState());
        assertFalse(session.isConnected());
    }

    private static final class FakeConfig implements SyncConfig {

        String server = "";
        String username = "tester";
        String room = "";
        boolean followEpisode = true;
        boolean reconnect = true;

        @Override
        public String getServer() {
            return server;
        }

        @Override
        public void setServer(String value) {
            server = value;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public void setUsername(String value) {
            username = value;
        }

        @Override
        public String getRoom() {
            return room;
        }

        @Override
        public void setRoom(String value) {
            room = value;
        }

        @Override
        public boolean isFollowEpisode() {
            return followEpisode;
        }

        @Override
        public boolean isReconnect() {
            return reconnect;
        }
    }
}
