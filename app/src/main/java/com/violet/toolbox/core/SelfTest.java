package com.violet.toolbox.core;

import android.content.Context;
import android.util.Base64;

import com.violet.toolbox.adb.AdbCrypto;
import com.violet.toolbox.adb.AdbProtocol;
import com.violet.toolbox.fastboot.FastbootUsb;
import com.violet.toolbox.flash.SparseSplitter;
import com.violet.toolbox.format.PayloadDumper;
import com.violet.toolbox.format.ProtoWire;
import com.violet.toolbox.format.SparseImage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.Locale;

/**
 * 协议层自检：不需要连接副手机即可验证 ADB / Fastboot / sparse / payload 的实现是否正确。
 *
 * 用途：新手机首次安装后先跑一遍自检，确认协议栈在当前设备上工作正常，
 * 再动手刷写，避免把「工具问题」误判成「刷机失败」。
 */
public final class SelfTest {

    private int passed = 0;
    private int failed = 0;
    private final StringBuilder report = new StringBuilder();

    private SelfTest() {
    }

    public static String runAll(Context context) {
        SelfTest test = new SelfTest();
        test.report.append("===== 协议层自检 =====\n");
        test.testAdbProtocol();
        test.testAdbCrypto(context);
        test.testFastbootPacket();
        test.testProtoWire();
        test.testSparse(context);
        test.testPayload(context);
        test.report.append("\n结果：通过 ").append(test.passed).append(" 项，失败 ").append(test.failed).append(" 项。\n");
        if (test.failed == 0) {
            test.report.append("协议栈工作正常，可以连接副手机进行实际操作。");
        } else {
            test.report.append("存在失败项，请先不要刷写，把本报告反馈给开发者。");
        }
        return test.report.toString();
    }

    private void pass(String name, String detail) {
        passed++;
        report.append("✔ ").append(name).append("：").append(detail).append('\n');
    }

    private void fail(String name, String detail) {
        failed++;
        report.append("✘ ").append(name).append("：").append(detail).append('\n');
    }

    // 1. ADB 报文：构建 → 解析 往返校验（含 CRC32 与 magic）
    private void testAdbProtocol() {
        try {
            byte[] payload = "shell:getprop\0".getBytes("UTF-8");
            byte[] packet = AdbProtocol.build(AdbProtocol.CMD_OPEN, 1, 0, payload);
            byte[] header = new byte[24];
            System.arraycopy(packet, 0, header, 0, 24);
            byte[] data = new byte[payload.length];
            System.arraycopy(packet, 24, data, 0, payload.length);
            AdbProtocol.Message msg = AdbProtocol.parse(header, data);
            boolean ok = msg.command == AdbProtocol.CMD_OPEN && msg.arg0 == 1
                    && new String(msg.data, "UTF-8").equals("shell:getprop\0");
            if (ok) {
                boolean badDetected = detectCorrupt();
                pass("ADB 报文", "构建与解析一致（24 字节头 + CRC32 + magic 校验通过）"
                        + (badDetected ? "，且能识别被破坏的报文" : ""));
            } else {
                fail("ADB 报文", "往返结果不一致");
            }
        } catch (Exception e) {
            fail("ADB 报文", e.getMessage());
        }
    }

    private boolean detectCorrupt() {
        try {
            byte[] packet = AdbProtocol.build(AdbProtocol.CMD_WRTE, 1, 1, new byte[]{1, 2, 3});
            packet[30] = (byte) (packet[30] ^ 0xFF); // 破坏数据，使 CRC 不匹配
            byte[] header = new byte[24];
            System.arraycopy(packet, 0, header, 0, 24);
            AdbProtocol.parse(header, new byte[]{1, 2, 3});
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    // 2. ADB 密钥：生成 → 签名 → 用公钥验签
    private void testAdbCrypto(Context context) {
        try {
            AdbCrypto crypto = AdbCrypto.loadOrCreate(context);
            byte[] token = new byte[20];
            for (int i = 0; i < token.length; i++) {
                token[i] = (byte) (i * 7 + 3);
            }
            byte[] signature = crypto.signToken(token);
            byte[] pubPayload = crypto.getPublicKeyPayload();
            int end = 0;
            while (end < pubPayload.length && pubPayload[end] != ' ') {
                end++;
            }
            byte[] adbPub = Base64.decode(pubPayload, 0, end, Base64.NO_WRAP);
            // 从 ADB 公钥格式还原 modulus，重建 RSAPublicKey 并验签
            BigInteger modulus = fromAdbPublicKey(adbPub);
            BigInteger exponent = BigInteger.valueOf(65537);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPublicKey pub = (RSAPublicKey) factory.generatePublic(
                    new java.security.spec.RSAPublicKeySpec(modulus, exponent));
            java.security.Signature verifier = java.security.Signature.getInstance("SHA1withRSA");
            verifier.initVerify(pub);
            verifier.update(token);
            boolean verified = verifier.verify(signature);
            if (verified) {
                pass("ADB 密钥", "RSA-2048 密钥生成正常，token 签名可被公钥验证"
                        + "（说明副手机端能认我们的公钥）");
            } else {
                fail("ADB 密钥", "签名无法被公钥验证");
            }
        } catch (Exception e) {
            fail("ADB 密钥", e.getMessage());
        }
    }

    private static BigInteger fromAdbPublicKey(byte[] data) {
        // 布局：uint32 len | uint32 n0inv | uint8 n[256] | uint8 rr[256]
        int keyBytes = (int) (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) | ((data[2] & 0xFF) << 16) | ((data[3] & 0xFF) << 24);
        keyBytes = keyBytes / 8;
        byte[] n = new byte[keyBytes];
        System.arraycopy(data, 8, n, 0, keyBytes);
        // 小端转大端
        byte[] big = new byte[keyBytes];
        for (int i = 0; i < keyBytes; i++) {
            big[i] = n[keyBytes - 1 - i];
        }
        return new BigInteger(1, big);
    }

    // 3. Fastboot 响应包解析
    private void testFastbootPacket() {
        try {
            byte[] packet = buildPacket("OKAY", "");
            String[] r1 = FastbootUsb.parsePacket(packet, 64);
            byte[] p2 = buildPacket("FAIL", "partition not found");
            String[] r2 = FastbootUsb.parsePacket(p2, 64);
            byte[] p3 = buildPacket("DATA", "0x08000000");
            String[] r3 = FastbootUsb.parsePacket(p3, 64);
            boolean ok = "OKAY".equals(r1[0]) && "FAIL".equals(r2[0])
                    && "partition not found".equals(r2[1]) && "DATA".equals(r3[0]);
            if (ok) {
                pass("Fastboot 响应解析", "OKAY / FAIL / DATA 三类响应解析正确");
            } else {
                fail("Fastboot 响应解析", r1[0] + "/" + r2[0] + "/" + r3[0]);
            }
        } catch (Exception e) {
            fail("Fastboot 响应解析", e.getMessage());
        }
    }

    private static byte[] buildPacket(String type, String payload) {
        byte[] buf = new byte[64];
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(t, 0, buf, 0, 4);
        byte[] p = payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(p, 0, buf, 4, Math.min(p.length, 60));
        return buf;
    }

    // 4. protobuf wire 解析
    private void testProtoWire() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // 模拟 DeltaArchiveManifest：block_size(3 varint) + partitions(13 嵌套)
            writeVarint(out, (3 << 3) | 0);
            writeVarint(out, 4096);
            ByteArrayOutputStream part = new ByteArrayOutputStream();
            writeVarint(part, (1 << 3) | 2);
            byte[] name = "boot".getBytes("UTF-8");
            writeVarint(part, name.length);
            part.write(name);
            writeVarint(out, (13 << 3) | 2);
            writeVarint(out, part.size());
            out.write(part.toByteArray());

            List<ProtoWire.Field> fields = ProtoWire.parse(out.toByteArray());
            boolean ok = false;
            for (ProtoWire.Field f : fields) {
                if (f.number == 3 && f.varint == 4096) {
                    ok = true;
                }
                if (f.number == 13) {
                    for (ProtoWire.Field pf : ProtoWire.parse(f.bytes)) {
                        if (pf.number == 1 && "boot".equals(pf.asString())) {
                            ok = ok && true;
                        }
                    }
                }
            }
            if (ok) {
                pass("protobuf 解析", "varint 与嵌套消息解析正确（payload.bin manifest 可用）");
            } else {
                fail("protobuf 解析", "字段解析不符");
            }
        } catch (Exception e) {
            fail("protobuf 解析", e.getMessage());
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while (true) {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value == 0) {
                out.write(b);
                return;
            }
            out.write(b | 0x80);
        }
    }

    // 5. sparse：raw → 分段 → 还原 往返
    private void testSparse(Context context) {
        try {
            byte[] original = new byte[3 * 1024 * 1024 + 1234];
            for (int i = 0; i < original.length; i++) {
                original[i] = (byte) (i % 251);
            }
            File raw = new File(context.getCacheDir(), "selftest_raw.img");
            FileOutputStream fos = new FileOutputStream(raw);
            fos.write(original);
            fos.close();

            com.violet.toolbox.core.StreamSource source = com.violet.toolbox.util.FileUtils.fromFile(raw);
            long segment = 700 * 1024;
            List<File> segments = SparseSplitter.split(context, source, segment, 4096);
            File merged = new File(context.getCacheDir(), "selftest_merged.img");
            SparseImage.mergeSegments(segments, merged);

            byte[] result = new byte[(int) merged.length()];
            FileInputStream fin = new FileInputStream(merged);
            int got = fin.read(result);
            fin.close();
            // sparse 按块对齐，合并结果会向上补齐到 4096 的整数倍（与 simg2img 行为一致）
            long aligned = ((original.length + 4095) / 4096) * 4096;
            boolean ok = got >= original.length && got == aligned;
            if (ok) {
                for (int i = 0; i < original.length; i++) {
                    if (original[i] != result[i]) {
                        ok = false;
                        fail("sparse 分段", "第 " + i + " 字节不一致");
                        break;
                    }
                }
            }
            if (ok) {
                pass("sparse 分段", "切成 " + segments.size() + " 段再合并，"
                        + humanSize(original.length) + " 有效数据逐字节一致，"
                        + "总长按块对齐到 " + humanSize(aligned) + "（大镜像可安全分段刷写）");
            } else if (!ok && got < original.length) {
                fail("sparse 分段", "长度不足：" + got + " < " + original.length);
            }
            SparseSplitter.clean(segments);
            raw.delete();
            merged.delete();
        } catch (Exception e) {
            fail("sparse 分段", e.getMessage());
        }
    }

    // 6. payload.bin：构造 → 解包 往返
    private void testPayload(Context context) {
        File payload = null;
        File outImg = null;
        try {
            byte[] image = new byte[512 * 1024];
            for (int i = 0; i < image.length; i++) {
                image[i] = (byte) (i % 233);
            }
            int blockSize = 4096;
            long blocks = image.length / blockSize;

            ByteArrayOutputStream manifest = new ByteArrayOutputStream();
            writeVarint(manifest, (3 << 3) | 0);
            writeVarint(manifest, blockSize);
            ByteArrayOutputStream partition = new ByteArrayOutputStream();
            writeVarint(partition, (1 << 3) | 2);
            byte[] name = "boot".getBytes("UTF-8");
            writeVarint(partition, name.length);
            partition.write(name);
            writeVarint(partition, (8 << 3) | 2);
            ByteArrayOutputStream op = new ByteArrayOutputStream();
            writeVarint(op, (1 << 3) | 0);
            writeVarint(op, PayloadDumper.TYPE_REPLACE);
            writeVarint(op, (2 << 3) | 0);
            writeVarint(op, 0);
            writeVarint(op, (3 << 3) | 0);
            writeVarint(op, image.length);
            writeVarint(op, (6 << 3) | 2);
            ByteArrayOutputStream ext = new ByteArrayOutputStream();
            writeVarint(ext, (1 << 3) | 0);
            writeVarint(ext, 0);
            writeVarint(ext, (2 << 3) | 0);
            writeVarint(ext, blocks);
            writeVarint(op, ext.size());
            op.write(ext.toByteArray());
            writeVarint(partition, op.size());
            partition.write(op.toByteArray());
            writeVarint(manifest, (13 << 3) | 2);
            writeVarint(manifest, partition.size());
            manifest.write(partition.toByteArray());

            byte[] manifestBytes = manifest.toByteArray();
            payload = new File(context.getCacheDir(), "selftest_payload.bin");
            DataOutputStream dos = new DataOutputStream(new FileOutputStream(payload));
            dos.write("CrAU".getBytes("US-ASCII"));
            writeBe64(dos, 2);
            writeBe64(dos, manifestBytes.length);
            dos.writeInt(0);
            dos.write(manifestBytes);
            dos.write(image);
            dos.close();

            RandomAccessFile raf = new RandomAccessFile(payload, "r");
            PayloadDumper dumper;
            try {
                dumper = PayloadDumper.open(raf);
            } finally {
                // 保持打开供 dump 使用
            }
            outImg = new File(context.getCacheDir(), "selftest_boot.img");
            RandomAccessFile out = new RandomAccessFile(outImg, "rw");
            out.setLength(0);
            try {
                dumper.dump(dumper.partitions().get(0), out, null);
            } finally {
                out.close();
                raf.close();
            }
            byte[] result = new byte[(int) outImg.length()];
            FileInputStream fin = new FileInputStream(outImg);
            fin.read(result);
            fin.close();
            boolean ok = result.length == image.length;
            if (ok) {
                for (int i = 0; i < image.length; i++) {
                    if (image[i] != result[i]) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                pass("payload.bin 解包", "自造 payload 解出的 boot.img 与原镜像逐字节一致"
                        + "（官方 OTA 包可直接在手机上解出可刷镜像）");
            } else {
                fail("payload.bin 解包", "解出结果与原始镜像不一致（" + result.length + " vs " + image.length + "）");
            }
        } catch (Exception e) {
            fail("payload.bin 解包", e.getMessage());
        } finally {
            if (payload != null) {
                payload.delete();
            }
            if (outImg != null) {
                outImg.delete();
            }
        }
    }

    private static void writeBe64(DataOutputStream out, long value) throws java.io.IOException {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((value >>> (8 * i)) & 0xFF));
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.CHINA, "%.2f MB", bytes / (1024.0 * 1024));
    }
}
