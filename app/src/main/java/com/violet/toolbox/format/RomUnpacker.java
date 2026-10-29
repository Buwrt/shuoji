package com.violet.toolbox.format;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ROM 包统一解包入口。
 *
 * 支持：
 * · 官方 OTA 的 .zip（内含 payload.bin，可直接解出 boot / init_boot / 各分区 img）
 * · 单独的 payload.bin
 * · OPPO / 一加 / 真我的 .ofp / .ops / .ozip（明文部分直接提取；加密部分依赖导入的密钥表）
 * · sparse 镜像还原为 raw（等价于 simg2img）
 * · super 多分片合并
 */
public final class RomUnpacker {

    public interface Listener {
        void onLog(String message);

        void onProgress(String item, int percent);
    }

    /** 解包产出的文件。 */
    public static final class Output {
        public final String name;
        public final File file;
        public final long size;

        public Output(String name, File file, long size) {
            this.name = name;
            this.file = file;
            this.size = size;
        }
    }

    private RomUnpacker() {
    }

    /** 根据文件名与文件头判断包类型。 */
    public static String detectType(File file) {
        String lower = file.getName().toLowerCase(Locale.US);
        if (lower.endsWith(".ofp") || lower.endsWith(".ops") || lower.endsWith(".ozip")) {
            return "oplus";
        }
        if (lower.endsWith(".bin")) {
            return "payload";
        }
        byte[] head = new byte[8];
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                if (raf.read(head) < 4) {
                    return "unknown";
                }
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            return "unknown";
        }
        if (head[0] == 'C' && head[1] == 'r' && head[2] == 'A' && head[3] == 'U') {
            return "payload";
        }
        if (head[0] == 'P' && head[1] == 'K') {
            return "zip";
        }
        if (head[0] == 0x3A && head[1] == 0xFF && head[2] == 0x26 && head[3] == 0xED) {
            return "sparse";
        }
        if (lower.endsWith(".zip")) {
            return "zip";
        }
        return "unknown";
    }

    /** 解包主入口，返回产出文件列表。 */
    public static List<Output> unpack(Context context, File input, File outDir,
                                      List<String> wantPartitions, Listener listener) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建输出目录：" + outDir.getAbsolutePath());
        }
        String type = detectType(input);
        List<Output> results = new ArrayList<Output>();
        switch (type) {
            case "payload":
                results.addAll(unpackPayload(input, outDir, wantPartitions, listener));
                break;
            case "zip":
                results.addAll(unpackZip(input, outDir, wantPartitions, listener));
                break;
            case "oplus":
                results.addAll(unpackOplus(input, outDir, listener));
                break;
            case "sparse": {
                File out = new File(outDir, stripExt(input.getName()) + ".raw.img");
                RandomAccessFile raf = new RandomAccessFile(out, "rw");
                try {
                    raf.setLength(0);
                    InputStream in = new BufferedInputStream(new FileInputStream(input), 1 << 20);
                    try {
                        SparseImage.toRaw(in, raf, 0);
                    } finally {
                        in.close();
                    }
                } finally {
                    raf.close();
                }
                if (listener != null) {
                    listener.onLog("已把 sparse 镜像还原为 raw：" + out.getName());
                }
                results.add(new Output(out.getName(), out, out.length()));
                break;
            }
            default:
                throw new IOException("无法识别的文件格式：" + input.getName()
                        + "\n支持：官方 OTA 的 zip、payload.bin、OPPO/一加/真我的 ofp / ops / ozip、sparse 镜像。");
        }
        return results;
    }

    // ---------------- payload.bin ----------------

    private static List<Output> unpackPayload(File input, File outDir, List<String> want,
                                              Listener listener) throws IOException {
        List<Output> results = new ArrayList<Output>();
        RandomAccessFile raf = new RandomAccessFile(input, "r");
        try {
            PayloadDumper dumper = PayloadDumper.open(raf);
            if (listener != null) {
                listener.onLog("payload 版本 minor=" + dumper.minorVersion()
                        + "，块大小=" + dumper.blockSize()
                        + "，分区数=" + dumper.partitions().size());
            }
            for (PayloadDumper.Partition p : dumper.partitions()) {
                if (want != null && !want.isEmpty() && !want.contains(p.name)) {
                    continue;
                }
                if (!p.offlineSupported()) {
                    if (listener != null) {
                        listener.onLog("跳过 " + p.name + "：增量包，离线无法还原"
                                + "（建议改用官方全量包或 ADB Sideload）");
                    }
                    continue;
                }
                File out = new File(outDir, p.name + ".img");
                RandomAccessFile fo = new RandomAccessFile(out, "rw");
                try {
                    fo.setLength(0);
                    dumper.dump(p, fo, new PayloadDumper.Listener() {
                        @Override
                        public void onProgress(String partition, int done, int total) {
                            if (listener != null && total > 0 && (done % 20 == 0 || done == total)) {
                                listener.onProgress(partition, done * 100 / total);
                            }
                        }
                    });
                } catch (IOException e) {
                    if (listener != null) {
                        listener.onLog("分区 " + p.name + " 解包失败：" + e.getMessage());
                    }
                    continue;
                } finally {
                    fo.close();
                }
                if (listener != null) {
                    listener.onLog("已解出 " + p.name + ".img（" + humanSize(out.length())
                            + "，操作类型 " + p.typeSummary() + "）");
                }
                results.add(new Output(p.name + ".img", out, out.length()));
            }
        } finally {
            raf.close();
        }
        return results;
    }

    // ---------------- ZIP（官方 OTA） ----------------

    private static List<Output> unpackZip(File input, File outDir, List<String> want,
                                          Listener listener) throws IOException {
        List<Output> results = new ArrayList<Output>();
        File payloadFile = null;
        List<String> extracted = new ArrayList<String>();

        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(input), 1 << 20));
        try {
            ZipEntry entry;
            byte[] buffer = new byte[1 << 20];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.US);
                if (lower.endsWith("payload.bin")) {
                    payloadFile = new File(outDir, "payload.bin");
                    FileOutputStream fos = new FileOutputStream(payloadFile);
                    try {
                        int n;
                        while ((n = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, n);
                        }
                    } finally {
                        fos.close();
                    }
                    if (listener != null) {
                        listener.onLog("已从 OTA 包取出 payload.bin（" + humanSize(payloadFile.length()) + "）");
                    }
                    continue;
                }
                if (lower.endsWith(".img") || lower.endsWith(".mbn") || lower.endsWith(".elf")
                        || lower.endsWith(".xml") || lower.endsWith(".csv")) {
                    File out = new File(outDir, new File(name).getName());
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        int n;
                        while ((n = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, n);
                        }
                    } finally {
                        fos.close();
                    }
                    extracted.add(out.getName());
                    results.add(new Output(out.getName(), out, out.length()));
                }
            }
        } finally {
            zis.close();
        }

        if (!extracted.isEmpty() && listener != null) {
            listener.onLog("已直接取出 " + extracted.size() + " 个文件：" + Arrays.toString(extracted.toArray()));
        }
        if (payloadFile != null && payloadFile.exists()) {
            results.addAll(unpackPayload(payloadFile, outDir, want, listener));
        } else if (listener != null) {
            listener.onLog("该 zip 内没有 payload.bin，已按原样取出可用镜像。");
        }
        return results;
    }

    // ---------------- OFP / OPS / OZIP ----------------

    private static List<Output> unpackOplus(File input, File outDir, Listener listener) throws IOException {
        List<Output> results = new ArrayList<Output>();
        if (listener != null) {
            listener.onLog("检测到 OPPO / 一加 / 真我封装包：" + input.getName()
                    + "（" + humanSize(input.length()) + "）");
        }

        // 1) 尝试当作 ZIP 直接读（部分 ozip / ofp 内部是明文或标准加密 zip）
        if (tryZipFast(input, outDir, results, listener)) {
            return results;
        }

        // 2) 明文扫描：在整文件中查找已知文件头并导出
        int scanned = scanAndExtract(input, outDir, results, false, listener);
        if (scanned > 0) {
            if (listener != null) {
                listener.onLog("明文扫描提取到 " + scanned + " 个文件。");
            }
            return results;
        }

        // 3) 加密尝试：用导入的密钥表试解前若干 MB
        if (OplusKeys.count() == 0) {
            if (listener != null) {
                listener.onLog("该文件为加密封装，且尚未导入密钥表。\n"
                        + "请在电脑上用 oppo_decrypt（bkerler）解密，或把该项目的密钥复制到手机后"
                        + "在本页导入密钥表再试。");
            }
            return results;
        }
        byte[] probe = new byte[(int) Math.min(input.length(), 4L * 1024 * 1024)];
        RandomAccessFile raf = new RandomAccessFile(input, "r");
        try {
            raf.readFully(probe);
        } finally {
            raf.close();
        }
        byte[] plain = OplusKeys.tryDecrypt(probe, probe.length);
        if (plain == null) {
            if (listener != null) {
                listener.onLog("已用 " + OplusKeys.count() + " 个密钥尝试解密，均未命中，"
                        + "该机型可能使用了更新的密钥。");
            }
            return results;
        }
        File plainFile = new File(outDir, "decrypted_probe.bin");
        FileOutputStream fos = new FileOutputStream(plainFile);
        try {
            fos.write(plain);
        } finally {
            fos.close();
        }
        if (listener != null) {
            listener.onLog("密钥命中！已解密前 " + humanSize(plain.length) + "，正在扫描内容……");
        }
        scanAndExtract(plainFile, outDir, results, true, listener);
        return results;
    }

    private static boolean tryZipFast(File input, File outDir, List<Output> results,
                                      Listener listener) {
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(input), 1 << 20));
            byte[] buffer = new byte[1 << 20];
            int count = 0;
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                File out = new File(outDir, new File(entry.getName()).getName());
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    int n;
                    while ((n = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, n);
                    }
                } finally {
                    fos.close();
                }
                results.add(new Output(out.getName(), out, out.length()));
                count++;
                if (count > 200) {
                    break;
                }
            }
            if (count > 0) {
                if (listener != null) {
                    listener.onLog("该封装包内部是 ZIP 结构，已直接解压出 " + count + " 个文件。");
                }
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (zis != null) {
                try {
                    zis.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    /** 扫描整个文件，按已知文件头导出可识别内容。 */
    private static int scanAndExtract(File input, File outDir, List<Output> results,
                                      boolean decrypted, Listener listener) {
        int found = 0;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(input, "r");
            long length = raf.length();
            byte[] buffer = new byte[4 * 1024 * 1024];
            long pos = 0;
            while (pos < length) {
                int want = (int) Math.min(buffer.length, length - pos);
                raf.seek(pos);
                raf.readFully(buffer, 0, want);
                int i = 0;
                while (i + 16 < want) {
                    Signature sig = matchSignature(buffer, i);
                    if (sig != null) {
                        long offset = pos + i;
                        long size = estimateSize(raf, offset, sig, length);
                        File out = new File(outDir, (decrypted ? "dec_" : "scan_") + offset + "_" + sig.name);
                        if (writeRange(raf, offset, size, out)) {
                            results.add(new Output(out.getName(), out, out.length()));
                            found++;
                            if (listener != null) {
                                listener.onLog("提取到 " + sig.desc + "：" + out.getName()
                                        + "（偏移 " + offset + "，约 " + humanSize(out.length()) + "）");
                            }
                            i += (int) Math.max(size, 4096);
                            continue;
                        }
                    }
                    i++;
                }
                pos += Math.max(want - 16, 1);
            }
        } catch (IOException e) {
            if (listener != null) {
                listener.onLog("扫描中断：" + e.getMessage());
            }
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
        return found;
    }

    private static final class Signature {
        final byte[] bytes;
        final String name;
        final String desc;

        Signature(byte[] bytes, String name, String desc) {
            this.bytes = bytes;
            this.name = name;
            this.desc = desc;
        }
    }

    private static final List<Signature> SIGNATURES = Arrays.asList(
            new Signature(new byte[]{'C', 'r', 'A', 'U'}, "payload.bin", "OTA 载荷"),
            new Signature(new byte[]{0x3A, (byte) 0xFF, 0x26, (byte) 0xED}, "image.img", "sparse 镜像"),
            new Signature(new byte[]{0x7F, 'E', 'L', 'F'}, "prog_firehose.elf", "高通 Firehose 编程器"),
            new Signature(new byte[]{'A', 'N', 'D', 'R', 'O', 'I', 'D', '!'}, "boot.img", "boot 镜像"),
            new Signature(new byte[]{'P', 'K', 3, 4}, "inner.zip", "ZIP 压缩包"),
            new Signature(new byte[]{(byte) 0xFD, '7', 'z', 'X', 'Z', 0}, "data.xz", "XZ 压缩数据"),
            new Signature(new byte[]{0x1F, (byte) 0x8B}, "data.gz", "GZIP 压缩数据"),
            new Signature(new byte[]{'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}, "data.7z", "7z 压缩包")
    );

    private static Signature matchSignature(byte[] buffer, int offset) {
        for (Signature sig : SIGNATURES) {
            boolean ok = true;
            for (int i = 0; i < sig.bytes.length; i++) {
                if (buffer[offset + i] != sig.bytes[i]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return sig;
            }
        }
        // XML 文本
        if (buffer[offset] == '<' && buffer[offset + 1] == '?' && buffer[offset + 2] == 'x'
                && buffer[offset + 3] == 'm' && buffer[offset + 4] == 'l') {
            return new Signature(new byte[]{'<', '?', 'x'}, "config.xml", "XML 配置");
        }
        return null;
    }

    /** 估算可导出长度：遇到下一个已知签名或文件尾为止。 */
    private static long estimateSize(RandomAccessFile raf, long offset, Signature sig, long fileLength) {
        long max = Math.min(fileLength - offset, 2L * 1024 * 1024 * 1024);
        if ("payload.bin".equals(sig.name)) {
            return max;
        }
        if ("config.xml".equals(sig.name)) {
            try {
                raf.seek(offset);
                byte[] buf = new byte[256 * 1024];
                int n = raf.read(buf);
                for (int i = 0; i + 1 < n; i++) {
                    if (buf[i] == '>' && buf[i + 1] == '<') {
                        return i + 1;
                    }
                }
                return Math.min(n, max);
            } catch (IOException e) {
                return Math.min(256 * 1024, max);
            }
        }
        // 扫描到下一个已知签名处结束
        try {
            long step = 1024 * 1024;
            byte[] buf = new byte[(int) step];
            long pos = offset + step;
            while (pos < fileLength) {
                raf.seek(pos);
                int n = raf.read(buf);
                if (n <= 16) {
                    break;
                }
                for (int i = 0; i + 16 < n; i++) {
                    if (matchSignature(buf, i) != null) {
                        return pos + i - offset;
                    }
                }
                pos += step;
            }
        } catch (IOException ignored) {
        }
        return max;
    }

    private static boolean writeRange(RandomAccessFile raf, long offset, long size, File out) {
        FileOutputStream fos = null;
        try {
            if (size <= 0 || size > 4L * 1024 * 1024 * 1024) {
                return false;
            }
            fos = new FileOutputStream(out);
            raf.seek(offset);
            byte[] buf = new byte[1 << 20];
            long left = size;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int n = raf.read(buf, 0, want);
                if (n <= 0) {
                    break;
                }
                fos.write(buf, 0, n);
                left -= n;
            }
            return out.length() > 0;
        } catch (IOException e) {
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String stripExt(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.CHINA, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
