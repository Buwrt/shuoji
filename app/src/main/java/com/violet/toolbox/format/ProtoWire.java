package com.violet.toolbox.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 protobuf wire 解析（只读取需要的字段，未知字段直接跳过）。
 *
 * 用于解析 payload.bin 中的 DeltaArchiveManifest，避免引入 protobuf 运行时依赖。
 */
public final class ProtoWire {

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_64BIT = 1;
    public static final int WIRE_LENGTH = 2;
    public static final int WIRE_32BIT = 5;

    public static final class Field {
        public final int number;
        public final int wireType;
        public final long varint;
        public final byte[] bytes;

        Field(int number, int wireType, long varint, byte[] bytes) {
            this.number = number;
            this.wireType = wireType;
            this.varint = varint;
            this.bytes = bytes;
        }

        public String asString() {
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private final byte[] data;
    private int pos;
    private final int end;

    public ProtoWire(byte[] data) {
        this(data, 0, data.length);
    }

    private ProtoWire(byte[] data, int offset, int length) {
        this.data = data;
        this.pos = offset;
        this.end = offset + length;
    }

    /** 解析出全部顶层字段。 */
    public static List<Field> parse(byte[] data) {
        ProtoWire wire = new ProtoWire(data);
        List<Field> fields = new ArrayList<Field>();
        while (wire.hasNext()) {
            fields.add(wire.next());
        }
        return fields;
    }

    public boolean hasNext() {
        return pos < end;
    }

    public Field next() {
        long key = readVarint();
        int number = (int) (key >>> 3);
        int wireType = (int) (key & 0x07);
        switch (wireType) {
            case WIRE_VARINT:
                return new Field(number, wireType, readVarint(), null);
            case WIRE_64BIT: {
                long v = readFixed64();
                return new Field(number, wireType, v, null);
            }
            case WIRE_LENGTH: {
                int len = (int) readVarint();
                byte[] bytes = new byte[len];
                System.arraycopy(data, pos, bytes, 0, len);
                pos += len;
                return new Field(number, wireType, 0, bytes);
            }
            case WIRE_32BIT: {
                long v = readFixed32();
                return new Field(number, wireType, v, null);
            }
            default:
                throw new IllegalStateException("不支持的 protobuf wire 类型：" + wireType);
        }
    }

    private long readVarint() {
        long result = 0;
        int shift = 0;
        while (pos < end) {
            int b = data[pos++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalStateException("varint 超长");
            }
        }
        throw new IllegalStateException("数据意外结束");
    }

    private long readFixed64() {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) (data[pos + i] & 0xFF)) << (8 * i);
        }
        pos += 8;
        return v;
    }

    private long readFixed32() {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            v |= ((long) (data[pos + i] & 0xFF)) << (8 * i);
        }
        pos += 4;
        return v;
    }
}
