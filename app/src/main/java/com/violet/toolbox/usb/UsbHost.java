package com.violet.toolbox.usb;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * USB Host（OTG）设备枚举：识别副手机当前处于 ADB 还是 Fastboot / FastbootD 模式。
 */
public final class UsbHost {

    public static final int CLASS_VENDOR = 0xFF;      // 255
    public static final int SUBCLASS_ADB = 0x42;      // 66
    public static final int PROTOCOL_ADB = 0x01;      // 开机（ADB）
    public static final int PROTOCOL_FASTBOOT = 0x03; // fastboot / fastbootd

    public enum Mode {
        NONE, ADB, FASTBOOT
    }

    public static final class Found {
        public final UsbDevice device;
        public final UsbInterface iface;
        public final UsbEndpoint endpointIn;
        public final UsbEndpoint endpointOut;
        public final Mode mode;

        Found(UsbDevice device, UsbInterface iface, UsbEndpoint in, UsbEndpoint out, Mode mode) {
            this.device = device;
            this.iface = iface;
            this.endpointIn = in;
            this.endpointOut = out;
            this.mode = mode;
        }

        public String describe() {
            String name = device.getProductName();
            if (name == null) {
                name = "VID_" + device.getVendorId() + " PID_" + device.getProductId();
            }
            return name + "（" + (mode == Mode.ADB ? "ADB" : "Fastboot") + "）";
        }
    }

    private UsbHost() {
    }

    /** 返回当前所有可识别的副手机（ADB 或 Fastboot）。 */
    public static List<Found> scan(Context context) {
        List<Found> result = new ArrayList<Found>();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            return result;
        }
        Map<String, UsbDevice> devices = manager.getDeviceList();
        if (devices == null) {
            return result;
        }
        for (Map.Entry<String, UsbDevice> e : devices.entrySet()) {
            UsbDevice device = e.getValue();
            Found found = inspect(device);
            if (found != null) {
                result.add(found);
            }
        }
        return result;
    }

    /** 优先返回 Fastboot 设备，其次 ADB 设备。 */
    public static Found findOne(Context context) {
        List<Found> all = scan(context);
        for (Found f : all) {
            if (f.mode == Mode.FASTBOOT) {
                return f;
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private static Found inspect(UsbDevice device) {
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() != CLASS_VENDOR || iface.getInterfaceSubclass() != SUBCLASS_ADB) {
                continue;
            }
            int protocol = iface.getInterfaceProtocol();
            Mode mode = null;
            if (protocol == PROTOCOL_ADB) {
                mode = Mode.ADB;
            } else if (protocol == PROTOCOL_FASTBOOT) {
                mode = Mode.FASTBOOT;
            }
            if (mode == null) {
                continue;
            }
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    in = ep;
                } else {
                    out = ep;
                }
            }
            if (in != null && out != null) {
                return new Found(device, iface, in, out, mode);
            }
        }
        return null;
    }

    public static boolean hasPermission(Context context, UsbDevice device) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return manager != null && device != null && manager.hasPermission(device);
    }

    /** 已知厂商 VID，用于界面显示品牌提示（仅做展示，不影响功能）。 */
    public static String guessBrand(UsbDevice device) {
        Map<Integer, String> map = new HashMap<Integer, String>();
        map.put(0x2717, "小米 / Redmi");
        map.put(0x18D1, "Google / 一加");
        map.put(0x2A70, "OPPO / 一加 / 真我");
        map.put(0x22D9, "OPPO / 真我");
        map.put(0x05C6, "高通 EDL");
        map.put(0x0BB4, "HTC");
        map.put(0x04E8, "三星");
        map.put(0x2D95, "realme");
        String brand = map.get(device.getVendorId());
        return brand == null ? "未知品牌（VID_" + device.getVendorId() + "）" : brand;
    }
}
