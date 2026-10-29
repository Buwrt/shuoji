package com.violet.toolbox.adb;

import java.io.Closeable;
import java.io.IOException;

/**
 * ADB 传输层抽象：USB OTG（主手机直连副手机）与无线 TCP 两种实现。
 */
public interface AdbTransport extends Closeable {

    void write(byte[] data, int offset, int length) throws IOException;

    void readFully(byte[] buffer, int offset, int length) throws IOException;

    /** 单次读写最大包长，避免超过 USB 端点上限。 */
    int getMaxPacketSize();
}
