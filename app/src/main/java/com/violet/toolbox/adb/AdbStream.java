package com.violet.toolbox.adb;

import java.io.IOException;
import java.util.LinkedList;

/**
 * ADB 逻辑流（对应 adb 的 OPEN / WRTE / OKAY / CLSE）。
 */
public final class AdbStream {

    private final AdbConnection connection;
    final int localId;
    int remoteId = -1;

    private final Object lock = new Object();
    private final LinkedList<byte[]> queue = new LinkedList<byte[]>();
    private volatile boolean ready = false;
    private volatile boolean closed = false;
    private volatile boolean writePending = false;
    private volatile boolean writeAcked = false;

    AdbStream(AdbConnection connection, int localId) {
        this.connection = connection;
        this.localId = localId;
    }

    /** 读取一块数据；流已关闭且无剩余数据时返回 null。 */
    public byte[] read() throws IOException {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 60000;
            while (queue.isEmpty() && !closed) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    throw new IOException("读取超时（60 秒无数据）");
                }
                try {
                    lock.wait(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("读取被中断");
                }
                if (connection.hasError()) {
                    throw connection.getError();
                }
            }
            if (queue.isEmpty()) {
                return null;
            }
            return queue.poll();
        }
    }

    /** 发送一次数据并等待设备确认（有流控）。 */
    public void write(byte[] data) throws IOException {
        connection.sendWrite(this, data);
    }

    /** 发送数据但不等待确认（sync 协议连续写时使用）。 */
    public void writeNoAck(byte[] data) throws IOException {
        connection.sendWriteNoAck(this, data);
    }

    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            lock.notifyAll();
        }
        try {
            connection.sendClose(this);
        } catch (IOException ignored) {
        }
        connection.removeStream(localId);
    }

    public boolean isClosed() {
        return closed;
    }

    // ---- 以下方法由读取线程调用 ----

    void onOkay(int remoteId) {
        synchronized (lock) {
            if (!ready) {
                this.remoteId = remoteId;
                ready = true;
                lock.notifyAll();
            } else if (writePending) {
                writePending = false;
                writeAcked = true;
                lock.notifyAll();
            }
        }
    }

    void onData(byte[] data) {
        synchronized (lock) {
            queue.add(data);
            lock.notifyAll();
        }
    }

    void onClose() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    boolean awaitOpen(long timeoutMs) {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!ready && !closed) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    break;
                }
                try {
                    lock.wait(Math.min(wait, 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return ready;
        }
    }

    void markWritePending() {
        synchronized (lock) {
            writePending = true;
            writeAcked = false;
        }
    }

    void awaitWriteAck() throws IOException {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 30000;
            while (!writeAcked && !closed) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    throw new IOException("设备未确认数据接收，写入超时");
                }
                try {
                    lock.wait(Math.min(wait, 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("写入被中断");
                }
                if (connection.hasError()) {
                    throw connection.getError();
                }
            }
        }
    }
}
