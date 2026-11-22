package com.fongmi.syncplay;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Syncplay 协议客户端：明文 TCP + STARTTLS，报文为一行一个 JSON 对象。
 * 协议参见 https://syncplay.pl/about/protocol/
 */
public class SyncPlayClient implements SyncBackend {

    public static final String PROTOCOL_VERSION = "1.7.0";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int MAX_PENDING = 1 << 20;
    private static final double RTT_SMOOTHING = 0.85;

    private final SyncPlayEndpoint endpoint;
    private final String room;
    private final SyncBackend.Listener listener;
    // 回调线程由调用方决定：Android 端是主线程，单测里可以直接跑在当前线程
    private final Executor callback;
    // 服务器可能改写用户名（重名时追加后缀），握手后以服务器返回值为准
    private volatile String username;
    // 所有出站报文都排队由写线程发送：sendState() 会在主线程被调用，直接写 socket 会触发
    // NetworkOnMainThreadException
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private final StringBuilder pending = new StringBuilder();
    private final AtomicBoolean finished = new AtomicBoolean();
    private static final ExecutorService CLOSER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "syncplay-close");
        thread.setDaemon(true);
        return thread;
    });

    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private Thread reader;
    private volatile Thread writer;
    private volatile boolean closed;

    private double clientRtt;
    private double serverRtt;
    private double averageRtt;
    // 写线程更新、主线程读取
    private volatile double forwardDelay;

    public SyncPlayClient(SyncPlayEndpoint endpoint, String username, String room, SyncBackend.Listener listener, Executor callback) {
        this.endpoint = endpoint;
        this.username = username;
        this.room = room;
        this.listener = listener;
        this.callback = callback;
    }

    public String getUsername() {
        return username;
    }

    public String getRoom() {
        return room;
    }

    /** 前向延迟（秒）：估算一条状态从服务器到达本地所花的时间。 */
    public double getForwardDelay() {
        return forwardDelay;
    }

    public void connect() throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), CONNECT_TIMEOUT_MS);
        raw.setTcpNoDelay(true);
        raw.setKeepAlive(true);
        Socket active = endpoint.isOfficial() ? upgrade(raw) : raw;
        socket = active;
        input = active.getInputStream();
        output = active.getOutputStream();
        // 连接期间可能已被取消，此时 close() 看不到 socket，必须自己收尾，否则 fd 泄漏
        if (closed) {
            close();
            throw new IOException("SyncPlay: 连接已取消");
        }
    }

    private Socket upgrade(Socket raw) throws IOException {
        OutputStream stream = raw.getOutputStream();
        stream.write("{\"TLS\":{\"startTLS\":\"send\"}}\r\n".getBytes(StandardCharsets.UTF_8));
        stream.flush();
        String reply = readLine(raw.getInputStream());
        if (reply == null) throw new IOException("SyncPlay: 服务器未响应 TLS 请求");
        JSONObject tls;
        try {
            tls = new JSONObject(reply).optJSONObject("TLS");
        } catch (JSONException e) {
            throw new IOException("SyncPlay: TLS 应答无法解析", e);
        }
        if (tls == null || !"true".equals(tls.optString("startTLS"))) throw new IOException("SyncPlay: 服务器拒绝 TLS 升级");
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket secure = (SSLSocket) factory.createSocket(raw, endpoint.host(), endpoint.port(), true);
        secure.setUseClientMode(true);
        secure.startHandshake();
        return secure;
    }

    /** 逐字节读到行尾，避免把后续报文一起吃掉。 */
    private static String readLine(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = stream.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buffer.write(b);
            if (buffer.size() > MAX_PENDING) throw new IOException("SyncPlay: 应答过长");
        }
        if (b == -1 && buffer.size() == 0) return null;
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    public void start() {
        Thread thread = new Thread(this::writeLoop, "syncplay-writer");
        thread.setDaemon(true);
        writer = thread;
        thread.start();
        sendHello();
        reader = new Thread(this::readLoop, "syncplay-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** 唯一的写入口，保证同一时刻只有一条线程碰 OutputStream。 */
    private void writeLoop() {
        try {
            while (!closed) {
                String line = outbox.take();
                OutputStream stream = output;
                if (stream == null) continue;
                stream.write(line.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            fail(e);
        }
    }

    private void sendHello() {
        JSONObject features = new JSONObject();
        try {
            features.put("sharedPlaylists", true);
            features.put("chat", true);
            features.put("featureList", true);
            features.put("readiness", true);
            features.put("managedRooms", false);
            JSONObject hello = new JSONObject();
            hello.put("username", username);
            hello.put("room", new JSONObject().put("name", room));
            hello.put("version", PROTOCOL_VERSION);
            hello.put("features", features);
            send(new JSONObject().put("Hello", hello));
        } catch (JSONException | IOException e) {
            fail(e);
        }
    }

    public void sendState(double positionSec, boolean paused, boolean doSeek) {
        JSONObject playstate = new JSONObject();
        try {
            playstate.put("position", Math.max(0, positionSec));
            playstate.put("paused", paused);
            playstate.put("doSeek", doSeek);
            playstate.put("setBy", username);
            JSONObject ping = new JSONObject();
            ping.put("clientRtt", clientRtt);
            ping.put("clientLatencyCalculation", now());
            JSONObject state = new JSONObject();
            state.put("ping", ping);
            state.put("playstate", playstate);
            send(new JSONObject().put("State", state));
        } catch (JSONException | IOException e) {
            fail(e);
        }
    }

    public void sendFile(String name, double durationSec, long size) {
        try {
            JSONObject file = new JSONObject();
            file.put("name", name);
            file.put("duration", durationSec);
            file.put("size", size);
            JSONObject body = new JSONObject();
            body.put("file", file);
            body.put("user", new JSONObject().put(username, new JSONObject().put("room", new JSONObject().put("name", room))));
            send(new JSONObject().put("Set", body));
        } catch (JSONException | IOException e) {
            fail(e);
        }
    }

    public void sendChat(String message) {
        try {
            send(new JSONObject().put("Chat", message));
        } catch (JSONException | IOException e) {
            fail(e);
        }
    }

    private void sendJoined() {
        try {
            JSONObject detail = new JSONObject();
            detail.put("room", new JSONObject().put("name", room));
            detail.put("event", new JSONObject().put("joined", true));
            send(new JSONObject().put("Set", new JSONObject().put(room, detail)));
            send(new JSONObject().put("Set", new JSONObject().put("ready", new JSONObject().put("isReady", true).put("manuallyInitiated", false))));
        } catch (JSONException | IOException e) {
            fail(e);
        }
    }

    private void send(JSONObject message) throws IOException {
        if (closed) throw new IOException("SyncPlay: 连接已关闭");
        outbox.offer(message.toString() + "\r\n");
    }

    /**
     * 读循环：服务器会把多条 JSON 连在一起发，按大括号配对切帧（字符串内的括号不参与计数）。
     */
    private void readLoop() {
        byte[] buffer = new byte[8192];
        try {
            while (!closed) {
                int count = input.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            if (!closed) fail(e);
        } finally {
            close();
            if (claimFinished()) post(listener::onClosed);
        }
    }

    private void append(String text) {
        pending.append(text);
        while (true) {
            int start = pending.indexOf("{");
            if (start < 0) {
                pending.setLength(0);
                return;
            }
            if (start > 0) pending.delete(0, start);
            int end = findEnd(pending);
            if (end < 0) {
                if (pending.length() > MAX_PENDING) pending.setLength(0);
                return;
            }
            String frame = pending.substring(0, end + 1);
            pending.delete(0, end + 1);
            dispatch(frame);
        }
    }

    static int findEnd(CharSequence text) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private void dispatch(String frame) {
        JSONObject json;
        try {
            json = new JSONObject(frame);
        } catch (JSONException e) {
            return;
        }
        if (json.has("TLS")) return;
        if (json.has("Hello")) {
            onHello(json.optJSONObject("Hello"));
            return;
        }
        if (json.has("State")) {
            onState(json.optJSONObject("State"));
            return;
        }
        if (json.has("Set")) {
            onSet(json.optJSONObject("Set"));
            return;
        }
        if (json.has("Chat")) {
            onChat(json.opt("Chat"));
            return;
        }
        if (json.has("Error")) post(() -> listener.onError(String.valueOf(json.opt("Error"))));
    }

    private void onHello(JSONObject hello) {
        if (hello == null) return;
        String name = hello.optString("username", username);
        if (!name.isEmpty()) username = name;
        JSONObject roomObject = hello.optJSONObject("room");
        String roomName = roomObject == null ? room : roomObject.optString("name", room);
        String motd = hello.optString("motd", "");
        String resolved = name;
        post(() -> listener.onConnected(resolved, roomName, motd));
        sendJoined();
    }

    private void onState(JSONObject state) {
        if (state == null) return;
        updateLatency(state.optJSONObject("ping"));
        JSONObject play = state.optJSONObject("playstate");
        if (play == null) return;
        double position = play.optDouble("position", 0);
        boolean paused = play.optBoolean("paused", true);
        boolean doSeek = play.optBoolean("doSeek", false);
        String setBy = play.isNull("setBy") ? "" : play.optString("setBy", "");
        // setBy 为空是服务器发的房间初始状态，不代表任何人的真实进度，不能据此校正本地
        if (setBy.isEmpty() || username.equals(setBy)) return;
        post(() -> listener.onRemoteState(position, paused, doSeek, setBy));
    }

    private void updateLatency(JSONObject ping) {
        if (ping == null) return;
        double server = ping.optDouble("serverRtt", 0);
        double sent = ping.optDouble("clientLatencyCalculation", -1);
        if (sent <= 0 || server < 0) return;
        double rtt = now() - sent;
        if (rtt < 0) return;
        clientRtt = rtt;
        serverRtt = server;
        averageRtt = averageRtt == 0 ? rtt : averageRtt * RTT_SMOOTHING + rtt * (1 - RTT_SMOOTHING);
        // 对端往返比自己快时，额外补上差值，抵消服务器端排队
        forwardDelay = server < rtt ? averageRtt / 2 + (rtt - server) : averageRtt / 2;
        double value = rtt;
        post(() -> listener.onLatency(value));
    }

    private void onSet(JSONObject set) {
        if (set == null) return;
        JSONObject user = set.optJSONObject("user");
        if (user == null) return;
        for (String name : keySet(user)) {
            JSONObject detail = user.optJSONObject(name);
            if (detail == null) continue;
            JSONObject event = detail.optJSONObject("event");
            if (event != null) {
                String type = keySet(event).isEmpty() ? "unknown" : keySet(event).get(0);
                post(() -> listener.onMember(type, name));
            }
            JSONObject file = detail.optJSONObject("file");
            if (file != null) {
                String fileName = file.optString("name", "");
                if (!fileName.isEmpty() && !name.equals(username)) post(() -> listener.onRemoteFile(fileName, name));
            }
        }
    }

    private void onChat(Object chat) {
        if (chat instanceof JSONObject object) {
            String message = object.optString("message", "");
            String sender = object.optString("username", "");
            if (!message.isEmpty() && !sender.equals(username)) post(() -> listener.onRemoteChat(sender, message));
        }
    }

    private static List<String> keySet(JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        return keys;
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    private void post(Runnable runnable) {
        callback.execute(runnable);
    }

    public void close() {
        if (closed) return;
        closed = true;
        Thread thread = writer;
        writer = null;
        if (thread != null) thread.interrupt();
        releaseSocket();
    }

    /**
     * SSLSocket.close() 会把发送缓冲里剩下的数据 flush 出去，这是一次网络写；
     * 在严格模式（targetSdk 高版本默认开启）下主线程调用会抛
     * NetworkOnMainThreadException。因此统一交给后台线程处理，close() 保持非阻塞。
     */
    private void releaseSocket() {
        Socket target = socket;
        if (target == null) return;
        socket = null;
        CLOSER.execute(() -> {
            try {
                target.close();
            } catch (IOException ignored) {
            }
        });
    }

    private void fail(Exception e) {
        if (!closed) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            post(() -> listener.onError(message));
        }
        close();
    }

    public boolean isClosed() {
        return closed;
    }

    /** 连接断开后只回调一次，避免读循环与异常路径重复通知。 */
    public boolean claimFinished() {
        return finished.compareAndSet(false, true);
    }
}
