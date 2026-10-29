package com.violet.toolbox.flash;

import android.content.Context;

import com.violet.toolbox.core.StreamSource;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Android sparse 镜像分段器。
 *
 * 副手机 fastboot 有单次 download 上限（max-download-size），
 * super.img 等大镜像必须拆成多个 sparse 分段分别 download + flash。
 * 每个分段内部的块偏移均为「分区内的绝对块偏移」，因此可独立刷写。
 */
public final class SparseSplitter {

    public static final int SPARSE_MAGIC = 0xED26FF3A;
    private static final int FILE_HDR_SZ = 28;
    private static final int CHUNK_HDR_SZ = 12;
    private static final int TYPE_RAW = 0xCAC1;
    private static final int TYPE_FILL = 0xCAC2;
    private static final int TYPE_DONT_CARE = 0xCAC3;

    private SparseSplitter() {
    }

    public static boolean isSparse(StreamSource source) {
        try {
            InputStream in = new BufferedInputStream(source.open(), 65536);
            try {
                byte[] magic = new byte[4];
                if (readFully(in, magic, 4) != 4) {
                    return false;
                }
                return ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN).getInt() == SPARSE_MAGIC;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 将镜像切成若干 sparse 分段（若本身已是 sparse 则按原 chunk 边界拆分）。
     *
     * @param maxSegmentBytes 单段最大字节数（一般取 fastboot 的 max-download-size）
     */
    public static List<File> split(Context context, StreamSource source, long maxSegmentBytes,
                                   int blockSize) throws IOException {
        File dir = new File(context.getCacheDir(), "violet_sparse/" + System.currentTimeMillis());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建分段缓存目录");
        }
        List<File> out = new ArrayList<File>();
        InputStream probe = new BufferedInputStream(source.open(), 65536);
        boolean sparse;
        try {
            probe.mark(8);
            byte[] magic = new byte[4];
            readFully(probe, magic, 4);
            sparse = ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN).getInt() == SPARSE_MAGIC;
            probe.reset();
        } finally {
            probe.close();
        }

        InputStream in = new BufferedInputStream(source.open(), 1 << 20);
        try {
            if (sparse) {
                splitSparse(in, dir, out, maxSegmentBytes);
            } else {
                splitRaw(in, dir, out, maxSegmentBytes, blockSize, source.size());
            }
        } finally {
            in.close();
        }
        return out;
    }

    // ---------------- sparse 输入 ----------------

    private static void splitSparse(InputStream in, File dir, List<File> out,
                                    long maxSegmentBytes) throws IOException {
        byte[] header = new byte[FILE_HDR_SZ];
        if (readFully(in, header, FILE_HDR_SZ) != FILE_HDR_SZ) {
            throw new IOException("sparse 文件头读取失败");
        }
        int blkSz = le32(header, 12);
        long totalBlks = le32(header, 16) & 0xFFFFFFFFL;
        int totalChunks = le32(header, 20);

        File current = null;
        OutputStream os = null;
        long segBytes = FILE_HDR_SZ;
        int chunksInSeg = 0;

        for (int i = 0; i < totalChunks; i++) {
            byte[] ch = new byte[CHUNK_HDR_SZ];
            if (readFully(in, ch, CHUNK_HDR_SZ) != CHUNK_HDR_SZ) {
                throw new IOException("sparse chunk 头读取失败");
            }
            int type = le16(ch, 0);
            long chunkBlocks = le32(ch, 4) & 0xFFFFFFFFL;
            long totalSz = le32(ch, 8) & 0xFFFFFFFFL;
            long dataLen = totalSz - CHUNK_HDR_SZ;
            if (dataLen < 0) {
                throw new IOException("sparse chunk 长度异常");
            }

            if (current == null || segBytes + totalSz > maxSegmentBytes) {
                if (current != null) {
                    os.close();
                    writeTotalChunks(current, chunksInSeg);
                }
                current = new File(dir, "seg_" + out.size() + ".img");
                out.add(current);
                os = new FileOutputStream(current);
                os.write(buildHeader(blkSz, totalBlks, 0));
                segBytes = FILE_HDR_SZ;
                chunksInSeg = 0;
            }

            os.write(ch);
            copy(in, os, dataLen);
            segBytes += totalSz;
            chunksInSeg++;
            if (type != TYPE_RAW && type != TYPE_FILL && type != TYPE_DONT_CARE) {
                throw new IOException("未知的 sparse chunk 类型：0x" + Integer.toHexString(type));
            }
        }
        if (os != null) {
            os.close();
            writeTotalChunks(current, chunksInSeg);
        }
    }

    // ---------------- raw 输入 ----------------

    private static void splitRaw(InputStream in, File dir, List<File> out,
                                 long maxSegmentBytes, int blockSize, long totalSize) throws IOException {
        long budget = maxSegmentBytes - FILE_HDR_SZ - CHUNK_HDR_SZ;
        if (budget <= blockSize) {
            throw new IOException("单段容量过小，无法分段");
        }
        long remaining = totalSize > 0 ? totalSize : Long.MAX_VALUE;
        long totalBlocks = 0;
        int index = 0;

        while (remaining > 0) {
            long segData = Math.min(budget, remaining);
            segData = (segData / blockSize) * blockSize;
            if (segData <= 0) {
                segData = remaining;
            }
            File f = new File(dir, "seg_" + index + ".img");
            out.add(f);
            OutputStream os = new FileOutputStream(f);
            long written;
            try {
                os.write(buildHeader(blockSize, 0, 1));
                os.write(new byte[CHUNK_HDR_SZ]); // chunk 头占位，写完数据后回填
                written = copyAvailable(in, os, segData);
            } finally {
                os.close();
            }
            if (written <= 0) {
                out.remove(f);
                f.delete();
                break;
            }
            long blocks = (written + blockSize - 1) / blockSize;
            totalBlocks += blocks;
            writeChunkHeader(f, blocks, CHUNK_HDR_SZ + written);
            if (remaining != Long.MAX_VALUE) {
                remaining -= written;
            }
            index++;
            if (written < segData) {
                break; // 已读到文件尾
            }
        }

        if (out.isEmpty()) {
            throw new IOException("镜像为空或读取失败");
        }
        for (File f : out) {
            writeTotalBlocks(f, totalBlocks);
        }
    }

    /** 复制最多 length 字节，遇到 EOF 提前返回实际复制的字节数。 */
    private static long copyAvailable(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[256 * 1024];
        long left = length;
        long done = 0;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n = in.read(buf, 0, want);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            left -= n;
            done += n;
        }
        return done;
    }

    private static void writeChunkHeader(File file, long blocks, long totalSz) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.seek(FILE_HDR_SZ);
            ByteBuffer ch = ByteBuffer.allocate(CHUNK_HDR_SZ).order(ByteOrder.LITTLE_ENDIAN);
            ch.putShort((short) TYPE_RAW);
            ch.putShort((short) 0);
            ch.putInt((int) blocks);
            ch.putInt((int) totalSz);
            raf.write(ch.array());
        } finally {
            raf.close();
        }
    }

    private static void writeTotalBlocks(File file, long totalBlocks) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.seek(16);
            byte[] v = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) totalBlocks).array();
            raf.write(v);
        } finally {
            raf.close();
        }
    }

    // ---------------- 工具 ----------------

    private static byte[] buildHeader(int blockSize, long totalBlks, int totalChunks) {
        ByteBuffer b = ByteBuffer.allocate(FILE_HDR_SZ).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(SPARSE_MAGIC);
        b.putShort((short) 1);          // major_version
        b.putShort((short) 0);          // minor_version
        b.putShort((short) FILE_HDR_SZ);
        b.putShort((short) CHUNK_HDR_SZ);
        b.putInt(blockSize);
        b.putInt((int) totalBlks);
        b.putInt(totalChunks);
        b.putInt(0);                    // image_checksum（填 0，设备端不做强制校验）
        return b.array();
    }

    private static void writeTotalChunks(File file, int chunks) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.seek(24);
            byte[] v = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunks).array();
            raf.write(v);
        } finally {
            raf.close();
        }
    }

    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[256 * 1024];
        long left = length;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n = in.read(buf, 0, want);
            if (n < 0) {
                throw new IOException("镜像数据不足，提前结束");
            }
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, got, len - got);
            if (n < 0) {
                break;
            }
            got += n;
        }
        return got;
    }

    private static int le16(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    private static int le32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    public static void clean(List<File> files) {
        if (files == null) {
            return;
        }
        for (File f : files) {
            try {
                if (f.exists()) {
                    f.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
