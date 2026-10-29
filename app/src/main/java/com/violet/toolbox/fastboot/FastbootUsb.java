package com.violet.toolbox.fastboot;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import com.violet.toolbox.core.LogBus;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fastboot over USB Host（OTG）：主手机直接向副手机的 fastboot / fastbootd 发命令。
 *
 * 协议说明：命令通过批量 OUT 端点发送（不超过 64 字节），响应为 64 字节包，
 * 类型包括 INFO / OKAY / FAIL / DATA。
 */
public class FastbootUsb implements Closeable {

    public interface Progress {
        void onProgress(long done, long total);
    }

    public static final class Response {
        public final boolean ok;
        public final String payload;
        public final String info;
        public final long dataLength;

        Response(boolean ok, String payload, String info, long dataLength) {
            this.ok = ok;
            this.payload = payload;
            this.info = info;
            this.dataLength = dataLength;
        }
    }

    private static final int TIMEOUT_CMD = 15000;
    private static final int TIMEOUT_DATA = 60000;

    private final UsbDeviceConnection connection;
    private final UsbInterface iface;
    private final UsbEndpoint endpointIn;
    private final UsbEndpoint endpointOut;

    public FastbootUsb(UsbDeviceConnection connection, UsbInterface iface,
                       UsbEndpoint in, UsbEndpoint out) {
        this.connection = connection;
        this.iface = iface;
        this.endpointIn = in;
        this.endpointOut = out;
    }

    // ---------------- 基础命令 ----------------

    /** 下发一条 fastboot 命令，返回设备响应。 */
    public Response raw(String command) throws IOException {
        byte[] data = command.getBytes(StandardCharsets.US_ASCII);
        if (data.length > 64) {
            throw new IOException("命令超长：" + command);
        }
        int w = connection.bulkTransfer(endpointOut, data, data.length, TIMEOUT_CMD);
        if (w < 0) {
            throw new IOException("命令发送失败：" + command);
        }
        return readResponse();
    }

    /** 解析一个 64 字节响应包，返回 [类型, 载荷]。 */
    public static String[] parsePacket(byte[] buf, int n) {
        String type = new String(buf, 0, 4, StandardCharsets.US_ASCII);
        String payload = trimZero(buf, 4, n - 4);
        return new String[]{type, payload};
    }

    private Response readResponse() throws IOException {
        byte[] buf = new byte[64];
        StringBuilder info = new StringBuilder();
        while (true) {
            int n = connection.bulkTransfer(endpointIn, buf, buf.length, TIMEOUT_CMD);
            if (n <= 0) {
                throw new IOException("读取 fastboot 响应超时");
            }
            String[] parsed = parsePacket(buf, n);
            String type = parsed[0];
            String payload = parsed[1];
            if ("INFO".equals(type)) {
                if (payload.length() > 0) {
                    info.append(payload).append('\n');
                    LogBus.get().log("设备信息：" + payload);
                }
                continue;
            }
            if ("OKAY".equals(type)) {
                return new Response(true, payload, info.toString(), 0);
            }
            if ("FAIL".equals(type)) {
                return new Response(false, payload, info.toString(), 0);
            }
            if ("DATA".equals(type)) {
                long len = parseHex(payload);
                return new Response(true, payload, info.toString(), len);
            }
            throw new IOException("未知响应：" + type + payload);
        }
    }

    private static String trimZero(byte[] buf, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (buf[i] == 0) {
                end = i;
                break;
            }
        }
        return new String(buf, offset, Math.max(0, end - offset), StandardCharsets.US_ASCII).trim();
    }

    private static long parseHex(String s) {
        String t = s.trim();
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                return Long.parseLong(t.substring(2), 16);
            }
            return Long.parseLong(t, 16);
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------- 变量读取 ----------------

    public Map<String, String> getvarAll() throws IOException {
        Response r = raw("getvar:all");
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (r.info != null && r.info.length() > 0) {
            String[] lines = r.info.split("\n");
            for (String line : lines) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
        }
        if (!r.ok) {
            throw new IOException("读取变量失败：" + r.payload);
        }
        return map;
    }

    public String getvar(String name) throws IOException {
        Response r = raw("getvar:" + name);
        if (!r.ok) {
            return "";
        }
        if (r.payload != null && r.payload.length() > 0) {
            return r.payload;
        }
        String[] lines = r.info.split("\n");
        for (String line : lines) {
            if (line.startsWith(name + ":")) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return "";
    }

    public long getMaxDownloadSize() {
        try {
            String v = getvar("max-download-size");
            long size = parseHex(v);
            return size > 0 ? size : Long.MAX_VALUE;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    public boolean isUnlocked() {
        try {
            String v = getvar("unlocked");
            return "yes".equalsIgnoreCase(v);
        } catch (Exception e) {
            return false;
        }
    }

    public String currentSlot() {
        try {
            return getvar("current-slot").toLowerCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 数据传输 ----------------

    private void download(InputStream input, long size, Progress progress) throws IOException {
        Response r = raw(String.format(Locale.US, "download:%08x", size));
        if (!r.ok || r.dataLength <= 0) {
            throw new IOException("设备拒绝接收数据：" + r.payload);
        }
        long toSend = Math.min(size, r.dataLength);
        byte[] buffer = new byte[1024 * 1024];
        long sent = 0;
        while (sent < toSend) {
            int want = (int) Math.min(buffer.length, toSend - sent);
            int read = readFully(input, buffer, want);
            if (read <= 0) {
                break;
            }
            int offset = 0;
            while (offset < read) {
                int n = connection.bulkTransfer(endpointOut, buffer, offset, read - offset, TIMEOUT_DATA);
                if (n <= 0) {
                    throw new IOException("数据传输中断，已发送 " + sent + " 字节");
                }
                offset += n;
            }
            sent += read;
            if (progress != null) {
                progress.onProgress(sent, toSend);
            }
        }
        if (sent != toSend) {
            throw new IOException("数据长度不足（期望 " + toSend + "，实发 " + sent + "）");
        }
        Response end = readResponse();
        if (!end.ok) {
            throw new IOException("下载失败：" + end.payload);
        }
    }

    private static int readFully(InputStream in, byte[] buffer, int want) throws IOException {
        int got = 0;
        while (got < want) {
            int n = in.read(buffer, got, want - got);
            if (n < 0) {
                break;
            }
            got += n;
        }
        return got;
    }

    /** 刷写分区：先 download 数据，再下发 flash 命令。 */
    public Response flash(String partition, InputStream input, long size, Progress progress) throws IOException {
        download(input, size, progress);
        String cmd = "flash:" + partition;
        Response r = raw(cmd);
        if (!r.ok) {
            throw new IOException("刷写 " + partition + " 失败：" + r.payload);
        }
        return r;
    }

    /** 带选项的 vbmeta 刷写（--disable-verity / --disable-verification）。 */
    public Response flashVbmeta(InputStream input, long size, boolean disableVerity,
                                boolean disableVerification, Progress progress) throws IOException {
        download(input, size, progress);
        String cmd = "flash:vbmeta";
        if (disableVerity) {
            cmd += "--disable-verity";
        }
        if (disableVerification) {
            cmd += "--disable-verification";
        }
        Response r = raw(cmd);
        if (!r.ok) {
            throw new IOException("刷写 vbmeta 失败：" + r.payload);
        }
        return r;
    }

    /** 临时启动镜像（不写入分区）。 */
    public Response boot(InputStream input, long size, Progress progress) throws IOException {
        download(input, size, progress);
        Response r = raw("boot");
        if (!r.ok) {
            throw new IOException("临时启动失败：" + r.payload);
        }
        return r;
    }

    public Response erase(String partition) throws IOException {
        Response r = raw("erase:" + partition);
        if (!r.ok) {
            throw new IOException("擦除 " + partition + " 失败：" + r.payload);
        }
        return r;
    }

    public Response setActive(String slot) throws IOException {
        Response r = raw("set_active:" + slot);
        if (!r.ok) {
            throw new IOException("切换槽位失败：" + r.payload);
        }
        return r;
    }

    /** target 可为空（系统）或 bootloader / recovery / fastboot / edl。 */
    public Response reboot(String target) throws IOException {
        String cmd = "reboot";
        if (target != null && target.length() > 0) {
            cmd += "-" + target;
        }
        Response r = raw(cmd);
        if (!r.ok) {
            // 部分设备在重启时来不及回包，属于正常现象
            LogBus.get().log("重启命令响应：" + r.payload);
        }
        return r;
    }

    public Response unlock(boolean critical) throws IOException {
        Response r = raw(critical ? "flashing unlock_critical" : "flashing unlock");
        if (!r.ok) {
            r = raw("oem unlock");
        }
        return r;
    }

    public Response lock(boolean critical) throws IOException {
        Response r = raw(critical ? "flashing lock_critical" : "flashing lock");
        if (!r.ok) {
            r = raw("oem lock");
        }
        return r;
    }

    public Response continueBoot() throws IOException {
        return raw("continue");
    }

    public List<String> runRawQuiet(String command) {
        List<String> out = new ArrayList<String>();
        try {
            Response r = raw(command);
            out.add(r.ok ? "OKAY" : "FAIL");
            if (r.payload != null && r.payload.length() > 0) {
                out.add(r.payload);
            }
            if (r.info != null && r.info.length() > 0) {
                out.add(r.info);
            }
        } catch (IOException e) {
            out.add("FAIL: " + e.getMessage());
        }
        return out;
    }

    @Override
    public void close() {
        try {
            connection.releaseInterface(iface);
        } catch (Exception ignored) {
        }
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }
}
