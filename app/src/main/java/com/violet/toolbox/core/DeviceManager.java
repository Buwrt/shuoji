package com.violet.toolbox.core;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.violet.toolbox.adb.AdbConnection;
import com.violet.toolbox.adb.AdbCrypto;
import com.violet.toolbox.adb.AdbUsbTransport;
import com.violet.toolbox.fastboot.FastbootUsb;
import com.violet.toolbox.usb.UsbHost;

/**
 * 设备会话管理：维护主手机与副手机之间的 USB 连接（ADB 或 Fastboot）。
 */
public final class DeviceManager {

    private static final DeviceManager INSTANCE = new DeviceManager();

    private Context context;
    private AdbCrypto crypto;
    private UsbHost.Found found;
    private UsbDeviceConnection usbConnection;
    private AdbConnection adbConnection;
    private FastbootUsb fastboot;
    private UsbHost.Mode mode = UsbHost.Mode.NONE;
    private String wirelessLabel;

    private DeviceManager() {
    }

    public static DeviceManager get() {
        return INSTANCE;
    }

    public void init(Context context) {
        this.context = context.getApplicationContext();
    }

    public UsbHost.Mode mode() {
        return mode;
    }

    public AdbConnection adb() {
        return adbConnection;
    }

    public FastbootUsb fastboot() {
        return fastboot;
    }

    public boolean isReady() {
        return (mode == UsbHost.Mode.ADB && adbConnection != null && adbConnection.isConnected())
                || (mode == UsbHost.Mode.FASTBOOT && fastboot != null);
    }

    public String describe() {
        if (found == null) {
            if (wirelessLabel != null) {
                return wirelessLabel;
            }
            return "未连接副手机";
        }
        return found.describe() + " ｜ " + UsbHost.guessBrand(found.device);
    }

    /** 无线 ADB 连接建立后挂载到管理器。 */
    public synchronized void attachAdb(AdbConnection connection, String label) {
        this.adbConnection = connection;
        this.mode = UsbHost.Mode.ADB;
        this.wirelessLabel = label;
    }

    public AdbCrypto crypto() throws Exception {
        if (crypto == null) {
            crypto = AdbCrypto.loadOrCreate(context);
        }
        return crypto;
    }

    /** 打开与副手机的 USB 会话；ADB 模式下会等待副手机授权。 */
    public synchronized void open(UsbHost.Found target) throws Exception {
        close();
        if (context == null) {
            throw new IllegalStateException("DeviceManager 尚未初始化");
        }
        if (crypto == null) {
            crypto = AdbCrypto.loadOrCreate(context);
        }
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("当前设备不支持 USB Host（OTG）");
        }
        UsbDeviceConnection connection = manager.openDevice(target.device);
        if (connection == null) {
            throw new IllegalStateException("无法打开 USB 设备，请重新插拔 OTG 线");
        }
        if (!connection.claimInterface(target.iface, true)) {
            connection.close();
            throw new IllegalStateException("USB 接口被占用，请关闭其它占用副手机的程序");
        }
        usbConnection = connection;
        found = target;
        mode = target.mode;

        if (target.mode == UsbHost.Mode.ADB) {
            AdbUsbTransport transport = new AdbUsbTransport(connection, target.endpointIn, target.endpointOut);
            adbConnection = new AdbConnection(transport, crypto);
            adbConnection.start();
            if (!adbConnection.waitForConnect(20000)) {
                if (adbConnection.isWaitingUserAuth()) {
                    throw new IllegalStateException("等待副手机授权：请在副手机屏幕上勾选「一律允许」并点击「允许 USB 调试」");
                }
                throw new IllegalStateException("ADB 连接超时，请确认副手机已开启 USB 调试");
            }
        } else {
            fastboot = new FastbootUsb(connection, target.iface, target.endpointIn, target.endpointOut);
        }
    }

    public synchronized void close() {
        if (adbConnection != null) {
            try {
                adbConnection.close();
            } catch (Exception ignored) {
            }
            adbConnection = null;
        }
        if (fastboot != null) {
            try {
                fastboot.close();
            } catch (Exception ignored) {
            }
            fastboot = null;
        }
        if (usbConnection != null) {
            try {
                usbConnection.close();
            } catch (Exception ignored) {
            }
            usbConnection = null;
        }
        mode = UsbHost.Mode.NONE;
        found = null;
        wirelessLabel = null;
    }
}
