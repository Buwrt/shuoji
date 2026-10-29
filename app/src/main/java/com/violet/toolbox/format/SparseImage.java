package com.violet.toolbox.format;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Android sparse 镜像处理：
 * · 识别 sparse 格式
 * · sparse → raw（等价于 PC 端 simg2img）
 * · 多个 super 分片合并成一个镜像
 *
 * sparse 文件结构：
 *   文件头 28 字节：magic(0xED26FF3A) major(2) minor(2) file_hdr_sz(2) chunk_hdr_sz(2)
 *                   blk_sz(4) total_blks(4) total_chunks(4) crc(4)
 *   chunk：type(2) reserved(2) chunk_blocks(4) total_sz(4) + 数据
 *   type：0xCAC1 RAW、0xCAC2 FILL、0xCAC3 DONT_CARE
 */
public final class SparseImage {

    public static final int SPARSE_MAGIC = 0xED26FF3A;
    private static final int FILE_HDR_SZ = 28;
    private static final int CHUNK_HDR_SZ = 12;
    private static final int TYPE_RAW = 0xCAC1;
    private static final int TYPE_FILL = 0xCAC2;
    private static final int TYPE_DONT_CARE = 0xCAC3;

    private SparseImage() {
    }

    public static boolean isSparse(byte[] head) {
        if (head == null || head.length < 4) {
            return false;
        }
        return ByteBuffer.wrap(head, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == SPARSE_MAGIC;
    }

    /** 把 sparse 镜像还原为 raw 镜像（追加写，用于分片合并）。 */
    public static long toRaw(InputStream in, RandomAccessFile out, long writeOffset) throws IOException {
        byte[] header = new byte[FILE_HDR_SZ];
        if (readFully(in, header, FILE_HDR_SZ) != FILE_HDR_SZ) {
            throw new IOException("sparse 文件头读取失败");
        }
        int blkSz = le32(header, 12);
        long totalChunks = le32(header, 20) & 0xFFFFFFFFL;
        if (blkSz <= 0) {
            throw new IOException("sparse 块大小非法：" + blkSz);
        }
        long pos = writeOffset;
        byte[] buffer = new byte[1024 * 1024];
        for (long i = 0; i < totalChunks; i++) {
            byte[] ch = new byte[CHUNK_HDR_SZ];
            if (readFully(in, ch, CHUNK_HDR_SZ) != CHUNK_HDR_SZ) {
                throw new IOException("sparse chunk 头读取失败（第 " + (i + 1) + " 块）");
            }
            int type = le16(ch, 0);
            long chunkBlocks = le32(ch, 4) & 0xFFFFFFFFL;
            long totalSz = le32(ch, 8) & 0xFFFFFFFFL;
            long dataLen = totalSz - CHUNK_HDR_SZ;
            long bytes = chunkBlocks * blkSz;

            if (type == TYPE_RAW) {
                out.seek(pos);
                long left = dataLen;
                while (left > 0) {
                    int want = (int) Math.min(buffer.length, left);
                    int n = in.read(buffer, 0, want);
                    if (n <= 0) {
                        throw new IOException("sparse 数据不足");
                    }
                    out.write(buffer, 0, n);
                    left -= n;
                }
            } else if (type == TYPE_FILL) {
                byte[] fill = new byte[4];
                if (readFully(in, fill, 4) != 4) {
                    throw new IOException("FILL 数据读取失败");
                }
                out.seek(pos);
                long left = bytes;
                byte[] block = buildFillBlock(fill, blkSz);
                while (left > 0) {
                    int chunk = (int) Math.min(block.length, left);
                    out.write(block, 0, chunk);
                    left -= chunk;
                }
            } else if (type == TYPE_DONT_CARE) {
                out.setLength(Math.max(out.length(), pos + bytes));
            } else {
                throw new IOException("未知 sparse chunk 类型：0x" + Integer.toHexString(type));
            }
            pos += bytes;
        }
        return pos - writeOffset;
    }

    /** 把一个 sparse 分片还原成独立的 raw 文件。 */
    public static File toRawFile(InputStream in, File outFile) throws IOException {
        RandomAccessFile out = new RandomAccessFile(outFile, "rw");
        try {
            out.setLength(0);
            toRaw(in, out, 0);
        } finally {
            out.close();
        }
        return outFile;
    }

    /** 合并多个 sparse 分片为一个 raw 镜像（super.0 / super.1 … 顺序拼接）。 */
    public static File mergeSegments(List<File> segments, File outFile) throws IOException {
        RandomAccessFile out = new RandomAccessFile(outFile, "rw");
        try {
            out.setLength(0);
            long pos = 0;
            for (File f : segments) {
                RandomAccessFile in = new RandomAccessFile(f, "r");
                try {
                    pos += toRaw(new FileStream(in), out, pos);
                } finally {
                    in.close();
                }
            }
        } finally {
            out.close();
        }
        return outFile;
    }

    /** 判断文件是否为 sparse 格式（读取前 4 字节）。 */
    public static boolean fileIsSparse(File file) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            byte[] head = new byte[4];
            if (raf.read(head) != 4) {
                return false;
            }
            return isSparse(head);
        } catch (IOException e) {
            return false;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static byte[] buildFillBlock(byte[] fill, int blockSize) {
        byte[] block = new byte[Math.min(blockSize, 1 << 20)];
        for (int i = 0; i + 4 <= block.length; i += 4) {
            System.arraycopy(fill, 0, block, i, 4);
        }
        return block;
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

    /** 把 RandomAccessFile 包装成 InputStream（在文件起始处开始读）。 */
    private static final class FileStream extends InputStream {
        private final RandomAccessFile raf;

        FileStream(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public int read() throws IOException {
            return raf.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return raf.read(b, off, len);
        }
    }
}
