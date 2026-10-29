package com.violet.toolbox.format;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * payload.bin 解包（等价于 PC 端的 payload_dumper）。
 *
 * 文件格式（AOSP system/update_engine）：
 *   "CrAU" + uint64 file_format_version + uint64 manifest_size + uint32 metadata_signature_size
 *   之后依次是：manifest(protobuf) ｜ metadata_signature ｜ 数据区 ｜ payload_signature
 *
 * 解析出的镜像可直接交给线刷中心或单刷 boot / init_boot。
 */
public final class PayloadDumper {

    /** DeltaArchiveManifest 字段。 */
    private static final int M_BLOCK_SIZE = 3;
    private static final int M_MINOR_VERSION = 12;
    private static final int M_PARTITIONS = 13;
    private static final int M_PARTITIONS_OLD = 2;

    /** PartitionUpdate 字段。 */
    private static final int P_NAME = 1;
    private static final int P_OPERATIONS = 8;
    private static final int P_OPERATIONS_OLD = 3;

    /** InstallOperation 字段。 */
    private static final int O_TYPE = 1;
    private static final int O_DATA_OFFSET = 2;
    private static final int O_DATA_LENGTH = 3;
    private static final int O_SRC_EXTENTS = 4;
    private static final int O_DST_EXTENTS = 6;

    /** Extent 字段。 */
    private static final int E_START_BLOCK = 1;
    private static final int E_NUM_BLOCKS = 2;

    /** InstallOperation.Type */
    public static final int TYPE_REPLACE = 0;
    public static final int TYPE_REPLACE_BZ = 1;
    public static final int TYPE_SOURCE_COPY = 4;
    public static final int TYPE_SOURCE_BSDIFF = 5;
    public static final int TYPE_ZERO = 6;
    public static final int TYPE_REPLACE_XZ = 8;
    public static final int TYPE_ZSTD = 14;

    public static final class Extent {
        public long startBlock;
        public long numBlocks;
    }

    public static final class Operation {
        public int type;
        public long dataOffset;
        public long dataLength;
        public final List<Extent> dstExtents = new ArrayList<Extent>();
    }

    public static final class Partition {
        public String name;
        public long sizeBytes;
        public final List<Operation> operations = new ArrayList<Operation>();

        /** 是否为可离线还原的操作（全量包）。 */
        public boolean offlineSupported() {
            for (Operation op : operations) {
                if (op.type == TYPE_SOURCE_COPY || op.type == TYPE_SOURCE_BSDIFF) {
                    return false;
                }
            }
            return true;
        }

        public String typeSummary() {
            StringBuilder sb = new StringBuilder();
            for (Operation op : operations) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(typeName(op.type));
            }
            return sb.toString();
        }
    }

    public static String typeName(int type) {
        switch (type) {
            case TYPE_REPLACE:
                return "REPLACE";
            case TYPE_REPLACE_BZ:
                return "REPLACE_BZ";
            case TYPE_ZERO:
                return "ZERO";
            case TYPE_REPLACE_XZ:
                return "REPLACE_XZ";
            case TYPE_SOURCE_COPY:
                return "SOURCE_COPY";
            case TYPE_SOURCE_BSDIFF:
                return "SOURCE_BSDIFF";
            case TYPE_ZSTD:
                return "REPLACE_ZSTD";
            default:
                return "未知(" + type + ")";
        }
    }

    private final RandomAccessFile raf;
    private final long dataOffsetBase;
    private long blockSize = 4096;
    private long minorVersion = 0;
    private final List<Partition> partitions = new ArrayList<Partition>();

    private PayloadDumper(RandomAccessFile raf, long dataOffsetBase) {
        this.raf = raf;
        this.dataOffsetBase = dataOffsetBase;
    }

    // ---------------- 解析 ----------------

    /** 读取并解析 payload.bin 头与 manifest。 */
    public static PayloadDumper open(RandomAccessFile raf) throws IOException {
        byte[] header = new byte[24];
        raf.seek(0);
        raf.readFully(header);
        String magic = new String(header, 0, 4, "US-ASCII");
        if (!"CrAU".equals(magic)) {
            throw new IOException("不是有效的 payload.bin（文件头不是 CrAU）");
        }
        long version = readBe64(header, 4);
        if (version != 2) {
            throw new IOException("不支持的 payload 版本：" + version + "（仅支持版本 2）");
        }
        long manifestSize = readBe64(header, 12);
        long sigSize = readBe32(header, 20);
        if (manifestSize <= 0 || manifestSize > 64 * 1024 * 1024) {
            throw new IOException("manifest 长度异常：" + manifestSize);
        }
        byte[] manifest = new byte[(int) manifestSize];
        raf.readFully(manifest);

        PayloadDumper dumper = new PayloadDumper(raf, 24 + manifestSize + sigSize);
        dumper.parseManifest(manifest);
        return dumper;
    }

    private void parseManifest(byte[] manifest) throws IOException {
        for (ProtoWire.Field f : ProtoWire.parse(manifest)) {
            if (f.number == M_BLOCK_SIZE) {
                blockSize = f.varint;
            } else if (f.number == M_MINOR_VERSION) {
                minorVersion = f.varint;
            } else if (f.number == M_PARTITIONS || f.number == M_PARTITIONS_OLD) {
                partitions.add(parsePartition(f.bytes));
            }
        }
        if (blockSize <= 0) {
            blockSize = 4096;
        }
    }

    private Partition parsePartition(byte[] data) throws IOException {
        Partition p = new Partition();
        for (ProtoWire.Field f : ProtoWire.parse(data)) {
            if (f.number == P_NAME) {
                p.name = f.asString();
            } else if (f.number == P_OPERATIONS || f.number == P_OPERATIONS_OLD) {
                p.operations.add(parseOperation(f.bytes));
            }
        }
        long maxBlock = 0;
        for (Operation op : p.operations) {
            for (Extent e : op.dstExtents) {
                maxBlock = Math.max(maxBlock, e.startBlock + e.numBlocks);
            }
        }
        p.sizeBytes = maxBlock * blockSize;
        return p;
    }

    private Operation parseOperation(byte[] data) throws IOException {
        Operation op = new Operation();
        for (ProtoWire.Field f : ProtoWire.parse(data)) {
            switch (f.number) {
                case O_TYPE:
                    op.type = (int) f.varint;
                    break;
                case O_DATA_OFFSET:
                    op.dataOffset = f.varint;
                    break;
                case O_DATA_LENGTH:
                    op.dataLength = f.varint;
                    break;
                case O_DST_EXTENTS:
                    op.dstExtents.add(parseExtent(f.bytes));
                    break;
                default:
                    break;
            }
        }
        return op;
    }

    private Extent parseExtent(byte[] data) throws IOException {
        Extent e = new Extent();
        for (ProtoWire.Field f : ProtoWire.parse(data)) {
            if (f.number == E_START_BLOCK) {
                e.startBlock = f.varint;
            } else if (f.number == E_NUM_BLOCKS) {
                e.numBlocks = f.varint;
            }
        }
        return e;
    }

    public List<Partition> partitions() {
        return partitions;
    }

    public long blockSize() {
        return blockSize;
    }

    public long minorVersion() {
        return minorVersion;
    }

    // ---------------- 解包 ----------------

    /** 把指定分区解成原始镜像文件。 */
    public void dump(Partition partition, RandomAccessFile out, Listener listener) throws IOException {
        if (!partition.offlineSupported()) {
            throw new IOException("分区 " + partition.name + " 是增量包（含 SOURCE_COPY / SOURCE_BSDIFF），"
                    + "离线无法还原，请下载官方全量包，或在副手机上用 ADB Sideload 直接升级。");
        }
        extentIndex = 0;
        extentPos = 0;
        currentOp = null;
        byte[] buffer = new byte[1024 * 1024];
        int index = 0;
        for (Operation op : partition.operations) {
            index++;
            if (listener != null) {
                listener.onProgress(partition.name, index, partition.operations.size());
            }
            applyOperation(op, out, buffer);
        }
    }

    private void applyOperation(Operation op, RandomAccessFile out, byte[] buffer) throws IOException {
        switch (op.type) {
            case TYPE_ZERO: {
                for (Extent e : op.dstExtents) {
                    writeZeros(out, e.startBlock * blockSize, e.numBlocks * blockSize);
                }
                return;
            }
            case TYPE_REPLACE:
            case TYPE_REPLACE_BZ:
            case TYPE_REPLACE_XZ:
            case TYPE_ZSTD:
                break;
            default:
                throw new IOException("暂不支持的操作类型：" + typeName(op.type)
                        + "（增量包请改用官方全量包或 ADB Sideload）");
        }

        raf.seek(dataOffsetBase + op.dataOffset);
        if (op.dataLength == 0) {
            return;
        }
        InputStream raw = new LimitedInputStream(raf, op.dataLength);
        InputStream decoded = wrapDecoder(raw, op.type);
        try {
            long remaining = totalBlocks(op) * blockSize;
            int n;
            while (remaining > 0 && (n = decoded.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
                remaining -= n;
                writeAtExtents(out, op, buffer, n);
            }
        } finally {
            decoded.close();
        }
    }

    /** 按 dst_extents 顺序写入，extent 之间不连续时自动跳到下一个 extent 起点。 */
    private long extentPos = 0;
    private int extentIndex = 0;
    private Operation currentOp;

    private void writeAtExtents(RandomAccessFile out, Operation op, byte[] buf, int len) throws IOException {
        if (currentOp != op) {
            currentOp = op;
            extentIndex = 0;
            extentPos = op.dstExtents.isEmpty() ? 0 : op.dstExtents.get(0).startBlock * blockSize;
        }
        int written = 0;
        while (written < len) {
            if (extentIndex >= op.dstExtents.size()) {
                throw new IOException("数据超出目标 extent 范围（分区已写满）");
            }
            Extent e = op.dstExtents.get(extentIndex);
            long extentEnd = (e.startBlock + e.numBlocks) * blockSize;
            long room = extentEnd - extentPos;
            int chunk = (int) Math.min(room, len - written);
            out.seek(extentPos);
            out.write(buf, written, chunk);
            extentPos += chunk;
            written += chunk;
            if (extentPos >= extentEnd) {
                extentIndex++;
                if (extentIndex < op.dstExtents.size()) {
                    extentPos = op.dstExtents.get(extentIndex).startBlock * blockSize;
                }
            }
        }
    }

    private void writeZeros(RandomAccessFile out, long offset, long length) throws IOException {
        byte[] zeros = new byte[64 * 1024];
        long left = length;
        out.seek(offset);
        while (left > 0) {
            int chunk = (int) Math.min(zeros.length, left);
            out.write(zeros, 0, chunk);
            left -= chunk;
        }
    }

    private long totalBlocks(Operation op) {
        long blocks = 0;
        for (Extent e : op.dstExtents) {
            blocks += e.numBlocks;
        }
        return blocks;
    }

    private InputStream wrapDecoder(InputStream in, int type) throws IOException {
        switch (type) {
            case TYPE_REPLACE:
                return in;
            case TYPE_REPLACE_XZ:
                return new org.tukaani.xz.XZInputStream(new BufferedInputStream(in, 65536));
            case TYPE_REPLACE_BZ:
                return new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
                        new BufferedInputStream(in, 65536));
            case TYPE_ZSTD:
                throw new IOException("该包使用了 ZSTD 压缩（REPLACE_ZSTD），本应用暂不支持，"
                        + "请改用官方全量包或 ADB Sideload 升级。");
            default:
                throw new IOException("暂不支持的操作类型：" + typeName(type));
        }
    }

    /** 从流中读取全部字节（测试与小文件用）。 */
    public static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (limit > 0 && (n = in.read(buf, 0, Math.min(buf.length, limit))) > 0) {
            out.write(buf, 0, n);
            limit -= n;
        }
        return out.toByteArray();
    }

    public interface Listener {
        void onProgress(String partition, int done, int total);
    }

    /** 只允许读取固定长度的流包装。 */
    private static final class LimitedInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long left;

        LimitedInputStream(RandomAccessFile raf, long length) {
            this.raf = raf;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            left--;
            return raf.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int want = (int) Math.min(len, left);
            int n = raf.read(b, off, want);
            if (n > 0) {
                left -= n;
            }
            return n;
        }
    }

    private static long readBe64(byte[] data, int offset) {
        ByteBuffer b = ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN);
        return b.getLong();
    }

    private static long readBe32(byte[] data, int offset) {
        ByteBuffer b = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN);
        return b.getInt() & 0xFFFFFFFFL;
    }

    /** gzip 解压工具（处理 .gz 打包的镜像）。 */
    public static void gunzip(InputStream in, OutputStream out) throws IOException {
        GZIPInputStream gzip = new GZIPInputStream(in);
        byte[] buf = new byte[65536];
        int n;
        while ((n = gzip.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        gzip.close();
    }
}
