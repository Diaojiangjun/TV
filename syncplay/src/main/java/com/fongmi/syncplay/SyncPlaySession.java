package com.fongmi.syncplay;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 房间会话：负责上下行节流、远端状态校正、内容切换、聊天与断线重连。
 *
 * <p>本类不依赖 Android：播放器相关操作通过 {@link Host} 回调给界面层，
 * 线程调度通过构造时注入的 {@link Executor} 决定（Android 端传主线程），
 * 配置读写通过 {@link SyncConfig} 注入。
 */
public class SyncPlaySession {

    public enum State {IDLE, CONNECTING, CONNECTED}

    public interface Host {

        boolean isPlaybackActive();

        long getPositionMs();

        long getDurationMs();

        boolean isPaused();

        void seekToPosition(long positionMs);

        void setPaused(boolean paused);

        /** 形如「庆余年#第1集」，用于和其他端对齐同一集。 */
        String getContentId();

        void playContent(String contentId);

        /** 关闭跟随时只提示不切集。 */
        default void notifyContent(String contentId) {}

        void onSyncPlayChanged();

        void onSyncPlayError(String message);
    }

    public interface Observer {

        void onSyncPlayChanged();
    }

    /** 房间消息，self 用于界面区分左右。 */
    public static final class Message {

        private final String user;
        private final String text;
        private final boolean self;

        Message(String user, String text, boolean self) {
            this.user = user;
            this.text = text;
            this.self = self;
        }

        public String getUser() {
            return user;
        }

        public String getText() {
            return text;
        }

        public boolean isSelf() {
            return self;
        }
    }

    private final SyncConfig config;
    /** 所有对外回调都投递到这里；Android 端是主线程，单测可以直接用 Runnable::run。 */
    private final Executor main;
    private final ScheduledExecutorService scheduler;
    private WeakReference<Host> host = new WeakReference<>(null);
    private final RoomMembers members = new RoomMembers();
    private final ChatLog chat = new ChatLog();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private volatile SyncBackend client;
    private volatile State state = State.IDLE;
    private volatile String room = "";
    private volatile long latencyMs;
    private volatile long lastApplyTime;
    private volatile boolean userClosed;
    private int reconnectAttempts;
    private boolean reconnectScheduled;
    private volatile ScheduledFuture<?> connectTimeout;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile ScheduledFuture<?> ticker;
    private SyncPlayEndpoint lastEndpoint;
    private String lastUsername;

    public SyncPlaySession(SyncConfig config, Executor main) {
        this.config = config;
        this.main = main;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "syncplay-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public State getState() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public String getRoom() {
        return room;
    }

    public String getServer() {
        return config.getServer();
    }

    /** 服务器可能在握手里改写用户名，聊天里显示的以自己的实际名字为准。 */
    public String getUsername() {
        SyncBackend current = client;
        String value = current == null ? null : current.getUsername();
        return Texts.isEmpty(value) ? config.getUsername() : value;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public int getMemberCount() {
        return members.size();
    }

    public List<String> getMembers() {
        return members.snapshot();
    }

    public List<Message> getMessages() {
        return chat.snapshot();
    }

    public void attach(Host value) {
        host = new WeakReference<>(value);
    }

    public void detach(Host value) {
        if (host.get() == value) host = new WeakReference<>(null);
    }

    public void addObserver(Observer observer) {
        if (observer != null && !observers.contains(observer)) observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    public void connect(String server, String username, String room, Runnable onError) {
        disconnect();
        SyncPlayEndpoint endpoint = SyncPlayEndpoint.parse(server);
        if (endpoint == null || Texts.isEmpty(room)) {
            if (onError != null) onError.run();
            return;
        }
        config.setServer(endpoint.toString());
        config.setUsername(username);
        config.setRoom(room);
        lastEndpoint = endpoint;
        lastUsername = config.getUsername();
        open(lastUsername, room);
    }

    private void open(String username, String room) {
        SyncBackend created = SyncBackends.create(lastEndpoint, username, room, new Listener(), this::post);
        client = created;
        userClosed = false;
        this.room = room;
        state = State.CONNECTING;
        notifyHost();
        // 先挂超时再起连接线程：连接若立刻失败，fail() 里的 disconnect() 能取消掉这个定时任务
        cancel(connectTimeout);
        connectTimeout = scheduler.schedule(() -> post(() -> {
            if (client == created && state == State.CONNECTING) fail("连接超时");
        }), SyncTuning.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Thread thread = new Thread(() -> {
            try {
                created.connect();
                created.start();
            } catch (Exception e) {
                if (client == created) fail(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }, "syncplay-connect");
        thread.setDaemon(true);
        thread.start();
    }

    public void disconnect() {
        userClosed = true;
        reconnectScheduled = false;
        reconnectAttempts = 0;
        cancel(connectTimeout);
        cancel(reconnectTask);
        connectTimeout = null;
        reconnectTask = null;
        stopTicker();
        SyncBackend current = client;
        client = null;
        if (current != null) current.close();
        state = State.IDLE;
        room = "";
        latencyMs = 0;
        members.clear();
        notifyHost();
    }

    /** 释放定时线程：会话对象不再使用时调用。 */
    public void shutdown() {
        disconnect();
        scheduler.shutdownNow();
    }

    public void onLocalPlayState() {
        SyncBackend current = client;
        Host value = host.get();
        if (current == null || value == null || state != State.CONNECTED) return;
        current.sendState(value.getPositionMs() / 1000.0, value.isPaused(), false);
    }

    public void onLocalSeek() {
        SyncBackend current = client;
        Host value = host.get();
        lastApplyTime = System.currentTimeMillis();
        if (current == null || value == null || state != State.CONNECTED) return;
        current.sendState(value.getPositionMs() / 1000.0, value.isPaused(), true);
    }

    public void onLocalContent() {
        SyncBackend current = client;
        Host value = host.get();
        if (current == null || value == null || state != State.CONNECTED) return;
        current.sendFile(value.getContentId(), value.getDurationMs() / 1000.0, 0);
    }

    public void sendChat(String text) {
        SyncBackend current = client;
        String body = text == null ? "" : text.trim();
        if (current == null || state != State.CONNECTED || body.isEmpty()) return;
        if (body.length() > SyncTuning.CHAT_LIMIT) body = body.substring(0, SyncTuning.CHAT_LIMIT);
        append(new Message(getUsername(), body, true));
        current.sendChat(body);
    }

    private void append(Message message) {
        chat.append(message);
        notifyHost();
    }

    private void applyRemote(double positionSec, boolean paused, boolean doSeek) {
        Host value = host.get();
        SyncBackend current = client;
        if (value == null || current == null || !value.isPlaybackActive()) return;
        long now = System.currentTimeMillis();
        boolean playing = !paused;
        long target = Math.max(0, (long) ((positionSec + (playing ? current.getForwardDelay() : 0)) * 1000));
        long delta = target - value.getPositionMs();
        long guard = doSeek ? SyncTuning.SEEK_GUARD_MS : SyncTuning.DRIFT_GUARD_MS;
        if (Math.abs(delta) > guard && (doSeek || now - lastApplyTime > SyncTuning.APPLY_COOLDOWN_MS)) {
            lastApplyTime = now;
            post(() -> value.seekToPosition(target));
        }
        if (value.isPaused() != paused) {
            lastApplyTime = now;
            post(() -> value.setPaused(paused));
        }
    }

    private void applyFile(String name) {
        Host value = host.get();
        if (value == null || Texts.isEmpty(name)) return;
        if (name.equals(value.getContentId())) return;
        if (!config.isFollowEpisode()) {
            // 关闭跟随时仍然提示，但不自动切集
            post(() -> value.notifyContent(name));
            return;
        }
        post(() -> value.playContent(name));
    }

    private void startTicker(SyncBackend target) {
        stopTicker();
        ticker = scheduler.scheduleAtFixedRate(() -> post(() -> {
            SyncBackend current = client;
            Host value = host.get();
            if (current != target || current == null || value == null) return;
            if (state != State.CONNECTED || !value.isPlaybackActive()) return;
            // 读播放器状态必须在回调线程（Android 上是主线程）上进行，
            // sendState 只做入队，不会在这里产生网络写
            current.sendState(value.getPositionMs() / 1000.0, value.isPaused(), false);
        }), 1, 1, TimeUnit.SECONDS);
    }

    private void stopTicker() {
        cancel(ticker);
        ticker = null;
    }

    private void notifyHost() {
        Host value = host.get();
        if (value != null) post(value::onSyncPlayChanged);
        for (Observer observer : observers) post(observer::onSyncPlayChanged);
    }

    private void fail(String message) {
        boolean active = client != null;
        disconnect();
        Host value = host.get();
        if (active && value != null && !Texts.isEmpty(message)) post(() -> value.onSyncPlayError(message));
    }

    /** 掉线自动重连：只针对「连接意外中断」，用户主动断开或地址填错不重试。 */
    private void scheduleReconnect() {
        if (reconnectScheduled || lastEndpoint == null) return;
        if (reconnectAttempts >= SyncTuning.RECONNECT_LIMIT) return;
        reconnectScheduled = true;
        reconnectAttempts++;
        state = State.CONNECTING;
        notifyHost();
        reconnectTask = scheduler.schedule(() -> post(() -> {
            reconnectScheduled = false;
            if (state == State.CONNECTED || lastEndpoint == null) return;
            open(lastUsername == null ? config.getUsername() : lastUsername, room);
        }), SyncTuning.RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void post(Runnable runnable) {
        try {
            main.execute(runnable);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }

    private class Listener implements SyncBackend.Listener {

        @Override
        public void onConnected(String username, String roomName, String motd) {
            members.reset(username);
            chat.clear();
            state = State.CONNECTED;
            room = roomName;
            reconnectAttempts = 0;
            SyncBackend current = client;
            if (current != null) startTicker(current);
            notifyHost();
        }

        @Override
        public void onRemoteState(double positionSec, boolean paused, boolean doSeek, String setBy) {
            // 服务器只把「之后加入的人」广播给房间里的人，新加入者拿不到已有名单；
            // 但每个成员每秒都在上报 State 并带 setBy，用它补全，几秒内就能收敛
            if (!Texts.isEmpty(setBy) && members.addIfAbsent(setBy)) notifyHost();
            applyRemote(positionSec, paused, doSeek);
        }

        @Override
        public void onRemoteFile(String name, String setBy) {
            applyFile(name);
        }

        @Override
        public void onRemoteChat(String username, String message) {
            append(new Message(Texts.isEmpty(username) ? "?" : username, message, false));
        }

        @Override
        public void onMember(String type, String username) {
            if ("joined".equals(type)) members.add(username);
            else if ("left".equals(type)) members.remove(username);
            notifyHost();
        }

        @Override
        public void onLatency(double rttSec) {
            latencyMs = Math.round(rttSec * 1000);
            notifyHost();
        }

        @Override
        public void onError(String message) {
            fail(message);
        }

        @Override
        public void onClosed() {
            if (userClosed) return;
            stopTicker();
            client = null;
            state = State.IDLE;
            notifyHost();
            if (config.isReconnect()) scheduleReconnect();
        }
    }
}
