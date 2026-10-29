package com.violet.toolbox.adb;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.io.IOException;

/**
 * ADB over USB Host（OTG）：主手机通过 OTG 线直接读写副手机的 ADB 接口。
 */
public final class AdbUsbTransport implements AdbTransport {

    private static final int TIMEOUT = 8000;

    private final UsbDeviceConnection connection;
    private final UsbEndpoint endpointIn;
    private final UsbEndpoint endpointOut;

    public AdbUsbTransport(UsbDeviceConnection connection, UsbEndpoint in, UsbEndpoint out) {
        this.connection = connection;
        this.endpointIn = in;
        this.endpointOut = out;
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        int sent = 0;
        while (sent < length) {
            int chunk = Math.min(length - sent, getMaxPacketSize());
            int n = connection.bulkTransfer(endpointOut, data, offset + sent, chunk, TIMEOUT);
            if (n <= 0) {
                throw new IOException("USB 写入失败：已发送 " + sent + "/" + length + " 字节");
            }
            sent += n;
        }
    }

    @Override
    public void readFully(byte[] buffer, int offset, int length) throws IOException {
        int got = 0;
        int empty = 0;
        while (got < length) {
            int chunk = Math.min(length - got, getMaxPacketSize());
            int n = connection.bulkTransfer(endpointIn, buffer, offset + got, chunk, TIMEOUT);
            if (n > 0) {
                got += n;
                empty = 0;
            } else {
                empty++;
                if (empty > 300) {
                    throw new IOException("USB 读取超时：已接收 " + got + "/" + length + " 字节");
                }
            }
        }
    }

    @Override
    public int getMaxPacketSize() {
        int size = endpointOut.getMaxPacketSize();
        return Math.max(512, Math.min(size, 16384));
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }
}
