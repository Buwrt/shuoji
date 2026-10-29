package com.violet.toolbox.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.violet.toolbox.usb.UsbHost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OTG 与连接环境诊断：逐项检查并给出中文修复建议。
 *
 * 重点解决「主手机要支持 OTG，副手机要先开 USB 调试」这两件事的不确定性：
 * 能自动判定的都自动判定，不能自动判定的给出具体到机型的按键组合。
 */
public final class OtgDiagnostics {

    public static final class Item {
        public final boolean ok;
        public final String title;
        public final String detail;

        Item(boolean ok, String title, String detail) {
            this.ok = ok;
            this.title = title;
            this.detail = detail;
        }
    }

    private OtgDiagnostics() {
    }

    public static List<Item> run(Context context) {
        List<Item> items = new ArrayList<Item>();

        // 1. 主机是否支持 USB Host（OTG）
        boolean hostSupported = false;
        try {
            hostSupported = context.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        } catch (Exception ignored) {
        }
        items.add(new Item(hostSupported, "主手机 USB Host（OTG）支持",
                hostSupported ? "系统已声明支持 USB Host，可用作主机。"
                        : "系统未声明支持 USB Host，本手机可能无法给副手机供电或识别设备。\n"
                        + "建议：换一台支持 OTG 的手机，或在「设置」中搜索「OTG」并打开开关（部分机型默认关闭且 5 分钟后自动断开）。"));

        // 2. UsbManager 是否可用
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        items.add(new Item(manager != null, "USB 服务可用",
                manager != null ? "UsbManager 获取正常。" : "获取不到 UsbManager，系统可能裁剪了 USB Host 功能。"));

        // 3. 当前已枚举到的设备
        if (manager != null) {
            Map<String, UsbDevice> devices = manager.getDeviceList();
            int total = devices == null ? 0 : devices.size();
            int usable = UsbHost.scan(context).size();
            StringBuilder sb = new StringBuilder();
            if (total == 0) {
                sb.append("当前没有识别到任何 USB 设备。\n")
                        .append("请确认：① OTG 转接头已插稳；② 数据线两端接好；③ 副手机已开机或已进入 fastboot。");
            } else {
                sb.append("已识别 USB 设备 ").append(total).append(" 个，其中可用（ADB / Fastboot）").append(usable).append(" 个。\n");
                for (UsbHost.Found f : UsbHost.scan(context)) {
                    sb.append("· ").append(f.describe()).append(" ｜ ").append(UsbHost.guessBrand(f.device)).append('\n');
                }
            }
            items.add(new Item(usable > 0, "副手机识别情况", sb.toString().trim()));

            // 4. USB 权限
            boolean allGranted = true;
            StringBuilder perm = new StringBuilder();
            for (UsbHost.Found f : UsbHost.scan(context)) {
                boolean granted = manager.hasPermission(f.device);
                if (!granted) {
                    allGranted = false;
                }
                perm.append("· ").append(f.describe()).append("：").append(granted ? "已授权" : "未授权").append('\n');
            }
            if (perm.length() == 0) {
                perm.append("暂无设备需要授权。");
            }
            items.add(new Item(allGranted, "USB 访问权限", perm.toString().trim()
                    + (allGranted ? "" : "\n建议：回到首页点「重新扫描设备」，在弹窗中点「允许」。")));
        }

        // 5. ADB 密钥是否就绪
        boolean keyReady = new java.io.File(context.getFilesDir(), "violet_adbkey").exists();
        items.add(new Item(true, "ADB 主机密钥",
                keyReady ? "主机 RSA 密钥已生成并保存，之后连接同一台副手机不会再弹指纹确认。"
                        : "尚未生成主机密钥，首次连接时会自动生成。"));

        return items;
    }

    /** 不依赖 USB 调试的两条直连路径说明。 */
    public static String noUsbDebugGuide() {
        return "副手机无法开启 USB 调试时，有两条路依然可用：\n\n"
                + "【一】Fastboot 直连（无需 USB 调试）\n"
                + "Bootloader 走的是 fastboot 协议，不需要系统授权，也不要求 USB 调试。\n"
                + "· 完全关机后，按住「音量下 + 电源」进 fastboot（小米 / Redmi / 真我）\n"
                + "· 一加 / OPPO 多为「音量上 + 电源」，部分机型需三键同按\n"
                + "· 插上 OTG 线后在本应用首页点「重新扫描设备」，识别为 Fastboot 即可刷写分区\n\n"
                + "【二】Recovery + ADB Sideload（无需 USB 调试）\n"
                + "· 关机后按「音量上 + 电源」进 Recovery\n"
                + "· 选「Apply update from ADB」/「从 ADB 安装升级」\n"
                + "· 在本应用「ADB Sideload」页选择官方 OTA 的 zip 直接刷入\n"
                + "（sideload 由 recovery 提供，同样不需要系统的 USB 调试授权）";
    }
}
