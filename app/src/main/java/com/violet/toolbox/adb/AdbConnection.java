package com.violet.toolbox.adb;

import com.violet.toolbox.core.LogBus;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADB 连接：完成 CNXN 握手与 RSA 认证，并提供 shell / 文件传输 / 安装 / 重启等能力。
 */
public class AdbConnection implements Closeable {

    public interface Progress {
        void onProgress(long done, long total);
    }

    private static final int MAX_DATA = 256 * 1024;

    private final AdbTransport transport;
    private final AdbCrypto crypto;
    private final Map<Integer, AdbStream> streams = new ConcurrentHashMap<Integer, AdbStream>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Object writeLock = new Object();
    private final Object connectLock = new Object();

    private Thread readerThread;
    private volatile boolean closed = false;
    private volatile boolean connected = false;
    private volatile boolean waitingUserAuth = false;
    private volatile IOException error;
    private int maxData = MAX_DATA;
    private boolean sentPublicKey = false;

    public AdbConnection(AdbTransport transport, AdbCrypto crypto) {
        this.transport = transport;
        this.crypto = crypto;
    }

    public void start() throws IOException {
        readerThread = new Thread(this::readLoop, "violet-adb-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        byte[] payload = "host::\0".getBytes(StandardCharsets.UTF_8);
        writeMessage(AdbProtocol.CMD_CNXN, AdbProtocol.ADB_VERSION, MAX_DATA, payload);
        LogBus.get().log("已发送 ADB 握手请求，等待副手机响应……");
    }

    public boolean waitForConnect(long timeoutMs) throws IOException {
        synchronized (connectLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!connected && !closed) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    break;
                }
                try {
                    connectLock.wait(Math.min(wait, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("连接被中断");
                }
                if (error != null) {
                    throw error;
                }
            }
        }
        if (error != null) {
            throw error;
        }
        return connected;
    }

    public boolean isConnected() {
        return connected && !closed;
    }

    public boolean isWaitingUserAuth() {
        return waitingUserAuth && !connected;
    }

    public void ensureConnected() throws IOException {
        if (!isConnected()) {
            throw new IOException("ADB 尚未连接，请先连接副手机");
        }
    }

    // ---------------- 通道与常用能力 ----------------

    public AdbStream open(String destination) throws IOException {
        ensureConnected();
        int id = nextId.getAndIncrement();
        AdbStream stream = new AdbStream(this, id);
        streams.put(id, stream);
        byte[] body = destination.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[body.length + 1];
        System.arraycopy(body, 0, payload, 0, body.length);
        payload[body.length] = 0;
        writeMessage(AdbProtocol.CMD_OPEN, id, 0, payload);
        if (!stream.awaitOpen(15000)) {
            streams.remove(id);
            throw new IOException("打开通道失败：" + destination);
        }
        return stream;
    }

    /** 执行 shell 命令并返回标准输出。 */
    public String shell(String command) throws IOException {
        AdbStream stream = open("shell:" + command);
        StringBuilder sb = new StringBuilder();
        try {
            byte[] data;
            while ((data = stream.read()) != null) {
                sb.append(new String(data, StandardCharsets.UTF_8));
            }
        } finally {
            stream.close();
        }
        return sb.toString();
    }

    /** 通过 reboot: 服务重启副手机，target 可为 system / recovery / bootloader / fastboot / edl。 */
    public void reboot(String target) throws IOException {
        ensureConnected();
        int id = nextId.getAndIncrement();
        byte[] payload = ("reboot:" + target + "\0").getBytes(StandardCharsets.UTF_8);
        writeMessage(AdbProtocol.CMD_OPEN, id, 0, payload);
        LogBus.get().log("已下发重启指令：" + target);
    }

    /** 推送文件到副手机（adb push）。 */
    public void push(InputStream input, long total, String remotePath, Progress progress) throws IOException {
        AdbStream stream = open("sync:");
        try {
            byte[] path = remotePath.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            head.write("SEND".getBytes(StandardCharsets.UTF_8));
            head.write(intLe(path.length));
            head.write(path);
            stream.writeNoAck(head.toByteArray());

            byte[] buffer = new byte[64 * 1024];
            long sent = 0;
            int n;
            while ((n = input.read(buffer)) > 0) {
                ByteArrayOutputStream chunk = new ByteArrayOutputStream();
                chunk.write("DATA".getBytes(StandardCharsets.UTF_8));
                chunk.write(intLe(n));
                chunk.write(buffer, 0, n);
                stream.writeNoAck(chunk.toByteArray());
                sent += n;
                if (progress != null) {
                    progress.onProgress(sent, total);
                }
            }
            ByteArrayOutputStream done = new ByteArrayOutputStream();
            done.write("DONE".getBytes(StandardCharsets.UTF_8));
            done.write(intLe(0));
            stream.writeNoAck(done.toByteArray());

            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            byte[] resp = readSyncMessage(stream, acc);
            if (resp == null) {
                throw new IOException("推送未收到设备响应");
            }
            if (!"OKAY".equals(id(resp))) {
                throw new IOException("推送失败：" + new String(resp, StandardCharsets.UTF_8));
            }
        } finally {
            stream.close();
        }
    }

    /** 从副手机拉取文件（adb pull）。 */
    public void pull(String remotePath, OutputStream output) throws IOException {
        AdbStream stream = open("sync:");
        try {
            byte[] path = remotePath.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            head.write("RECV".getBytes(StandardCharsets.UTF_8));
            head.write(intLe(path.length));
            head.write(path);
            stream.writeNoAck(head.toByteArray());

            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            byte[] msg;
            while ((msg = readSyncMessage(stream, acc)) != null) {
                String id = id(msg);
                if ("DATA".equals(id)) {
                    int len = msg.length - 8;
                    if (len > 0) {
                        output.write(msg, 8, len);
                    }
                } else if ("DONE".equals(id)) {
                    break;
                } else if ("FAIL".equals(id)) {
                    throw new IOException("拉取失败：" + new String(msg, 8, Math.max(0, msg.length - 8), StandardCharsets.UTF_8));
                }
            }
        } finally {
            stream.close();
        }
    }

    /** ADB Sideload：把 OTA 包推送到处于 Recovery 的副手机（sideload 模式不需要 USB 调试）。 */
    public String sideload(InputStream input, long size, Progress progress) throws IOException {
        ensureConnected();
        AdbStream stream = open("sideload:" + size);
        StringBuilder sb = new StringBuilder();
        try {
            byte[] buffer = new byte[64 * 1024];
            long left = size;
            long sent = 0;
            while (left > 0) {
                int want = (int) Math.min(buffer.length, left);
                int n = readUpTo(input, buffer, want);
                if (n <= 0) {
                    break;
                }
                byte[] chunk = new byte[n];
                System.arraycopy(buffer, 0, chunk, 0, n);
                stream.write(chunk);
                left -= n;
                sent += n;
                if (progress != null) {
                    progress.onProgress(sent, size);
                }
            }
            byte[] data;
            while ((data = stream.read()) != null) {
                sb.append(new String(data, StandardCharsets.UTF_8));
            }
        } finally {
            stream.close();
        }
        return sb.toString();
    }

    private static int readUpTo(InputStream in, byte[] buf, int want) throws IOException {
        int got = 0;
        while (got < want) {
            int n = in.read(buf, got, want - got);
            if (n < 0) {
                break;
            }
            got += n;
        }
        return got;
    }

    /** 安装 APK：先推送到 /data/local/tmp，再执行 pm install。 */
    public String install(InputStream apk, long size, String fileName) throws IOException {
        String remote = "/data/local/tmp/" + (fileName == null ? "violet.apk" : fileName);
        push(apk, size, remote, null);
        LogBus.get().log("已推送 APK 到 " + remote + "，开始安装……");
        return shell("pm install -r " + remote);
    }

    // ---------------- sync 消息读取（处理跨包分片） ----------------

    private byte[] readSyncMessage(AdbStream stream, ByteArrayOutputStream acc) throws IOException {
        while (true) {
            if (acc.size() >= 8) {
                byte[] buf = acc.toByteArray();
                int len = readIntLe(buf, 4);
                if (len >= 0 && buf.length >= 8 + len) {
                    byte[] msg = new byte[8 + len];
                    System.arraycopy(buf, 0, msg, 0, 8 + len);
                    acc.reset();
                    if (buf.length > 8 + len) {
                        acc.write(buf, 8 + len, buf.length - 8 - len);
                    }
                    return msg;
                }
            }
            byte[] data = stream.read();
            if (data == null) {
                return null;
            }
            acc.write(data, 0, data.length);
        }
    }

    private static String id(byte[] msg) {
        if (msg.length < 4) {
            return "";
        }
        return new String(msg, 0, 4, StandardCharsets.UTF_8);
    }

    // ---------------- 读写与消息分发 ----------------

    void sendWrite(AdbStream stream, byte[] data) throws IOException {
        stream.markWritePending();
        synchronized (writeLock) {
            writeMessage(AdbProtocol.CMD_WRTE, stream.localId, stream.remoteId, data);
        }
        stream.awaitWriteAck();
    }

    void sendWriteNoAck(AdbStream stream, byte[] data) throws IOException {
        synchronized (writeLock) {
            writeMessage(AdbProtocol.CMD_WRTE, stream.localId, stream.remoteId, data);
        }
    }

    void sendClose(AdbStream stream) throws IOException {
        synchronized (writeLock) {
            writeMessage(AdbProtocol.CMD_CLSE, stream.localId, stream.remoteId, null);
        }
    }

    void removeStream(int localId) {
        streams.remove(localId);
    }

    private void writeMessage(int command, int arg0, int arg1, byte[] data) throws IOException {
        if (data != null && data.length > maxData) {
            // 按设备声明的 maxdata 分片
            int offset = 0;
            while (offset < data.length) {
                int len = Math.min(maxData, data.length - offset);
                byte[] part = new byte[len];
                System.arraycopy(data, offset, part, 0, len);
                offset += len;
                synchronized (writeLock) {
                    transport.write(AdbProtocol.build(command, arg0, arg1, part), 0, 24 + len);
                }
            }
            return;
        }
        byte[] msg = AdbProtocol.build(command, arg0, arg1, data);
        transport.write(msg, 0, msg.length);
    }

    private void sendOkay(int localId, int remoteId) throws IOException {
        synchronized (writeLock) {
            byte[] msg = AdbProtocol.build(AdbProtocol.CMD_OKAY, localId, remoteId, null);
            transport.write(msg, 0, msg.length);
        }
    }

    private void readLoop() {
        byte[] header = new byte[24];
        try {
            while (!closed) {
                transport.readFully(header, 0, 24);
                ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                int command = b.getInt();
                int arg0 = b.getInt();
                int arg1 = b.getInt();
                int len = b.getInt();
                if (len < 0 || len > 16 * 1024 * 1024) {
                    throw new IOException("非法报文长度：" + len);
                }
                byte[] data = null;
                if (len > 0) {
                    data = new byte[len];
                    transport.readFully(data, 0, len);
                }
                AdbProtocol.Message msg = AdbProtocol.parse(header, data);
                dispatch(msg);
            }
        } catch (IOException e) {
            if (!closed) {
                error = e;
                LogBus.get().log("ADB 连接中断：" + e.getMessage());
                synchronized (connectLock) {
                    connectLock.notifyAll();
                }
            }
        } catch (Exception e) {
            LogBus.get().log("ADB 读取异常：" + e.getMessage());
        }
    }

    private void dispatch(AdbProtocol.Message msg) throws IOException {
        switch (msg.command) {
            case AdbProtocol.CMD_CNXN:
                maxData = Math.min(maxData, msg.arg1 > 0 ? msg.arg1 : MAX_DATA);
                connected = true;
                waitingUserAuth = false;
                LogBus.get().log("ADB 握手成功，副手机已授权本应用");
                synchronized (connectLock) {
                    connectLock.notifyAll();
                }
                break;
            case AdbProtocol.CMD_AUTH:
                handleAuth(msg);
                break;
            case AdbProtocol.CMD_OKAY: {
                AdbStream s = streams.get(msg.arg0);
                if (s != null) {
                    s.onOkay(msg.arg1);
                }
                break;
            }
            case AdbProtocol.CMD_WRTE: {
                AdbStream s = streams.get(msg.arg0);
                if (s != null) {
                    s.onData(msg.data);
                    sendOkay(s.localId, s.remoteId);
                }
                break;
            }
            case AdbProtocol.CMD_CLSE: {
                AdbStream s = streams.get(msg.arg0);
                if (s != null) {
                    s.onClose();
                    streams.remove(msg.arg0);
                }
                break;
            }
            default:
                break;
        }
    }

    private void handleAuth(AdbProtocol.Message msg) throws IOException {
        if (msg.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) {
            byte[] signature = crypto.signToken(msg.data);
            writeMessage(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TYPE_SIGNATURE, 0, signature);
            if (!sentPublicKey) {
                sentPublicKey = true;
                waitingUserAuth = true;
                writeMessage(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TYPE_RSA_PUBLIC, 0, crypto.getPublicKeyPayload());
                LogBus.get().log("首次连接：已发送本机公钥，请在副手机屏幕上点击「允许 USB 调试」");
            }
        }
    }

    // ---------------- 工具方法 ----------------

    private static byte[] intLe(int value) {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(value);
        return b.array();
    }

    private static int readIntLe(byte[] data, int offset) {
        ByteBuffer b = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN);
        return b.getInt();
    }

    boolean hasError() {
        return error != null;
    }

    IOException getError() {
        return error;
    }

    @Override
    public void close() {
        closed = true;
        for (AdbStream s : streams.values()) {
            s.onClose();
        }
        streams.clear();
        try {
            transport.close();
        } catch (Exception ignored) {
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
