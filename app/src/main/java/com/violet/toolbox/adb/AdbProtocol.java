package com.violet.toolbox.adb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * ADB 线协议报文封装（24 字节头部 + 可选数据）。
 */
public final class AdbProtocol {

    public static final int CMD_CNXN = 0x4e584e43;
    public static final int CMD_AUTH = 0x48545541;
    public static final int CMD_OPEN = 0x4e45504f;
    public static final int CMD_OKAY = 0x59414b4f;
    public static final int CMD_CLSE = 0x45534c43;
    public static final int CMD_WRTE = 0x45545257;

    public static final int AUTH_TYPE_TOKEN = 1;
    public static final int AUTH_TYPE_SIGNATURE = 2;
    public static final int AUTH_TYPE_RSA_PUBLIC = 3;

    public static final int ADB_VERSION = 0x01000001;

    private AdbProtocol() {
    }

    public static final class Message {
        public int command;
        public int arg0;
        public int arg1;
        public int dataLength;
        public byte[] data;
    }

    public static byte[] build(int command, int arg0, int arg1, byte[] data) {
        int len = data == null ? 0 : data.length;
        ByteBuffer buf = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(len);
        buf.putInt(checksum(data));
        buf.putInt(command ^ 0xFFFFFFFF);
        if (len > 0) {
            buf.put(data);
        }
        return buf.array();
    }

    public static Message parse(byte[] header, byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = buf.getInt();
        m.arg0 = buf.getInt();
        m.arg1 = buf.getInt();
        m.dataLength = buf.getInt();
        int check = buf.getInt();
        int magic = buf.getInt();
        int expectMagic = m.command ^ 0xFFFFFFFF;
        if (magic != expectMagic) {
            throw new IllegalArgumentException("ADB 报文校验失败：magic 不匹配");
        }
        m.data = data == null ? new byte[0] : data;
        if (m.dataLength > 0 && checksum(m.data) != check) {
            throw new IllegalArgumentException("ADB 报文校验失败：CRC32 不匹配");
        }
        return m;
    }

    private static int checksum(byte[] data) {
        CRC32 crc = new CRC32();
        if (data != null && data.length > 0) {
            crc.update(data, 0, data.length);
        }
        return (int) crc.getValue();
    }
}
