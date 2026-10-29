package com.violet.toolbox.adb;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * ADB 主机身份认证（RSA-2048）。
 *
 * 实现参考：
 * · AOSP system/core/adb 的 adb_auth_sign / RSA 公钥（adbkey.pub）序列化格式
 * · cgutman/AdbLib（BSD）中 AdbCrypto 的 Java 等价实现
 *
 * 公钥二进制布局（小端）：
 *   uint32 len(=2048) | uint32 n0inv | uint8 n[256] | uint8 rr[256]
 */
public final class AdbCrypto {

    private static final String KEY_FILE = "violet_adbkey";
    private static final String PUB_FILE = "violet_adbkey.pub";
    private static final int KEY_BITS = 2048;
    private static final int KEY_BYTES = KEY_BITS / 8;

    private final RSAPrivateCrtKey privateKey;
    private final byte[] publicKeyPayload;

    private AdbCrypto(RSAPrivateCrtKey key, byte[] payload) {
        this.privateKey = key;
        this.publicKeyPayload = payload;
    }

    /** 读取已存在的密钥，不存在则生成并持久化到应用私有目录。 */
    public static AdbCrypto loadOrCreate(Context context) throws Exception {
        File dir = context.getFilesDir();
        File privFile = new File(dir, KEY_FILE);
        File pubFile = new File(dir, PUB_FILE);

        byte[] pkcs8;
        if (privFile.exists() && privFile.length() > 0) {
            pkcs8 = readAll(privFile);
        } else {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(KEY_BITS);
            KeyPair pair = gen.generateKeyPair();
            pkcs8 = pair.getPrivate().getEncoded();
            writeAll(privFile, pkcs8);
        }

        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));

        byte[] adbPub = toAdbPublicKey(key.getModulus());
        byte[] b64 = Base64.encode(adbPub, Base64.NO_WRAP);
        writeAll(pubFile, b64);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(b64);
        out.write(' ');
        out.write("violet-toolbox".getBytes("UTF-8"));
        out.write(0);

        return new AdbCrypto(key, out.toByteArray());
    }

    /** 用私钥对设备下发的 20 字节随机 token 签名（PKCS#1 v1.5）。 */
    public byte[] signToken(byte[] token) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(token);
    }

    /** AUTH(type=RSA_PUBLIC) 时发送的公钥载荷。 */
    public byte[] getPublicKeyPayload() {
        return publicKeyPayload;
    }

    private static byte[] toAdbPublicKey(BigInteger modulus) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(KEY_BITS);

        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = modulus.and(two32.subtract(BigInteger.ONE));
        BigInteger inv = n0.modInverse(two32);
        long n0inv = (0x100000000L - inv.longValue()) & 0xFFFFFFFFL;
        buf.putInt((int) n0inv);

        buf.put(toLittleEndian(modulus, KEY_BYTES));
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(modulus);
        buf.put(toLittleEndian(rr, KEY_BYTES));
        return buf.array();
    }

    private static byte[] toLittleEndian(BigInteger value, int bytes) {
        byte[] big = value.toByteArray();
        byte[] out = new byte[bytes];
        int copy = Math.min(bytes, big.length);
        for (int i = 0; i < copy; i++) {
            out[i] = big[big.length - 1 - i];
        }
        return out;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) {
                out.write(tmp, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.flush();
            out.close();
        }
    }
}
