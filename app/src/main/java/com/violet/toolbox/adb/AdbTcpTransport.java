package com.violet.toolbox.adb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * ADB over TCP：同一 WiFi 下通过无线调试控制副手机。
 */
public final class AdbTcpTransport implements AdbTransport {

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    public AdbTcpTransport(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), 8000);
        socket.setTcpNoDelay(true);
        input = socket.getInputStream();
        output = socket.getOutputStream();
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        output.write(data, offset, length);
        output.flush();
    }

    @Override
    public void readFully(byte[] buffer, int offset, int length) throws IOException {
        int got = 0;
        while (got < length) {
            int n = input.read(buffer, offset + got, length - got);
            if (n < 0) {
                throw new IOException("无线连接已断开");
            }
            got += n;
        }
    }

    @Override
    public int getMaxPacketSize() {
        return 65536;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
