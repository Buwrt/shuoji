package com.violet.toolbox.format;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * OFP / OPS / OZIP 密钥表。
 *
 * OPPO、一加、真我的 .ofp / .ops / .ozip 为厂商私有加密封装，
 * 官方密钥会随机型与安全补丁变化，因此这里不内置任何厂商密钥，
 * 而是提供「导入密钥表」接口：把电脑上 oppo_decrypt 项目里的密钥
 * （每行一条，支持 16 进制或明文字符串，支持 key:iv 写法）复制到手机，
 * 用本应用导入即可解锁对应机型。
 */
public final class OplusKeys {

    private static volatile List<byte[]> keys = new ArrayList<byte[]>();
    private static volatile List<byte[]> ivs = new ArrayList<byte[]>();

    private OplusKeys() {
    }

    public static synchronized void load(File file) throws IOException {
        List<byte[]> newKeys = new ArrayList<byte[]>();
        List<byte[]> newIvs = new ArrayList<byte[]>();
        InputStream in = new FileInputStream(file);
        try {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            for (String rawLine : sb.toString().split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String keyPart = line;
                String ivPart = null;
                if (line.contains(":")) {
                    String[] kv = line.split(":", 2);
                    keyPart = kv[0].trim();
                    ivPart = kv[1].trim();
                }
                byte[] key = decode(keyPart);
                if (key != null && (key.length == 16 || key.length == 24 || key.length == 32)) {
                    newKeys.add(key);
                    byte[] iv = decode(ivPart == null ? "" : ivPart);
                    newIvs.add(iv != null && iv.length == 16 ? iv : null);
                }
            }
        } finally {
            in.close();
        }
        keys = newKeys;
        ivs = newIvs;
    }

    private static byte[] decode(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String t = text.replace("0x", "").replace("0X", "").replace(" ", "");
        if (t.matches("[0-9a-fA-F]+") && t.length() % 2 == 0 && t.length() >= 16) {
            byte[] out = new byte[t.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
        return t.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static int count() {
        return keys.size();
    }

    /** 用密钥表尝试解密一段数据，命中则返回解密结果，否则返回 null。 */
    public static byte[] tryDecrypt(byte[] data, int length) {
        List<byte[]> ks = keys;
        List<byte[]> vs = ivs;
        for (int i = 0; i < ks.size(); i++) {
            byte[] key = ks.get(i);
            byte[] iv = vs.get(i);
            byte[] result = attempt(key, iv, data, length, "AES/CBC/PKCS5Padding");
            if (result != null) {
                return result;
            }
            result = attempt(key, iv, data, length, "AES/ECB/PKCS5Padding");
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static byte[] attempt(byte[] key, byte[] iv, byte[] data, int length, String alg) {
        try {
            SecretKeySpec spec = new SecretKeySpec(key, "AES");
            Cipher cipher;
            if (alg.contains("ECB") || iv == null) {
                cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, spec);
            } else {
                cipher = Cipher.getInstance(alg);
                cipher.init(Cipher.DECRYPT_MODE, spec, new IvParameterSpec(iv));
            }
            int block = cipher.getBlockSize();
            int usable = (length / block) * block;
            if (usable <= 0) {
                return null;
            }
            byte[] out = cipher.doFinal(data, 0, usable);
            return containsKnownSignature(out) ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解密结果中是否出现已知文件头（作为密钥是否正确的判据）。 */
    static boolean containsKnownSignature(byte[] data) {
        int limit = Math.min(data.length, 512 * 1024);
        for (int i = 0; i + 8 < limit; i++) {
            if (matches(data, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(byte[] data, int offset) {
        return (data[offset] == 'C' && data[offset + 1] == 'r' && data[offset + 2] == 'A' && data[offset + 3] == 'U')
                || (data[offset] == 0x3A && data[offset + 1] == 0xFF && data[offset + 2] == 0x26 && data[offset + 3] == 0xED)
                || (data[offset] == 'P' && data[offset + 1] == 'K' && data[offset + 2] == 3 && data[offset + 3] == 4)
                || (data[offset] == 0x7F && data[offset + 1] == 'E' && data[offset + 2] == 'L' && data[offset + 3] == 'F')
                || (data[offset] == 'A' && data[offset + 1] == 'N' && data[offset + 2] == 'D' && data[offset + 3] == 'R');
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
