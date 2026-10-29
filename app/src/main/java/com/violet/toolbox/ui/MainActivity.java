package com.violet.toolbox.ui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.violet.toolbox.R;
import com.violet.toolbox.adb.AdbConnection;
import com.violet.toolbox.adb.AdbTcpTransport;
import com.violet.toolbox.core.DeviceManager;
import com.violet.toolbox.usb.UsbHost;
import com.violet.toolbox.util.Tasks;

import java.util.List;

/**
 * 首页：检测副手机、申请 USB 权限、建立 ADB / Fastboot 会话，并进入各功能模块。
 */
public class MainActivity extends BaseActivity {

    private static final String ACTION_USB_PERMISSION = "com.violet.toolbox.USB_PERMISSION";

    private View statusDot;

    @Override
    protected void setStatus(String text) {
        super.setStatus(text);
        if (statusDot == null) {
            return;
        }
        int res = R.drawable.bg_dot_idle;
        if (text != null) {
            if (text.contains("已连接")) {
                res = R.drawable.bg_dot_ok;
            } else if (text.contains("失败") || text.contains("拒绝")) {
                res = R.drawable.bg_dot_error;
            } else if (text.contains("正在")) {
                res = R.drawable.bg_dot_warn;
            }
        }
        statusDot.setBackgroundResource(res);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    log("USB 权限已授予，正在连接……");
                    scanAndConnect();
                } else {
                    log(getString(R.string.usb_permission_denied));
                    setStatus(getString(R.string.usb_permission_denied));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("检测到 USB 设备插入，正在识别副手机……");
                scanAndConnect();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                DeviceManager.get().close();
                log("副手机已断开");
                setStatus(getString(R.string.home_status_idle));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DeviceManager.get().init(this);
        setContentView(R.layout.activity_main);
        bindCommonViews();
        statusDot = findViewById(R.id.view_status_dot);

        findViewById(R.id.card_device).setOnClickListener(v -> open(DeviceActivity.class));
        findViewById(R.id.card_fastboot).setOnClickListener(v -> open(FastbootActivity.class));
        findViewById(R.id.card_boot_flash).setOnClickListener(v -> open(BootFlashActivity.class));
        findViewById(R.id.card_flash_center).setOnClickListener(v -> open(FlashCenterActivity.class));
        findViewById(R.id.card_terminal).setOnClickListener(v -> open(TerminalActivity.class));
        findViewById(R.id.card_rom).setOnClickListener(v -> open(RomUnpackActivity.class));
        findViewById(R.id.card_diagnose).setOnClickListener(v -> open(DiagnoseActivity.class));
        findViewById(R.id.card_sideload).setOnClickListener(v -> open(SideloadActivity.class));
        findViewById(R.id.card_help).setOnClickListener(v -> open(HelpActivity.class));
        findViewById(R.id.btn_scan).setOnClickListener(v -> {
            log("正在扫描 USB 设备……");
            scanAndConnect();
        });
        findViewById(R.id.card_wireless).setOnClickListener(v -> showWirelessDialog());

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        log(getString(R.string.home_hint_otg));
        scanAndConnect();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private void open(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    private void scanAndConnect() {
        List<UsbHost.Found> list = UsbHost.scan(this);
        if (list.isEmpty()) {
            setStatus(getString(R.string.home_status_idle));
            log("未发现 ADB / Fastboot 设备");
            return;
        }
        UsbHost.Found target = list.get(0);
        for (UsbHost.Found f : list) {
            if (f.mode == UsbHost.Mode.FASTBOOT) {
                target = f;
                break;
            }
        }
        if (!UsbHost.hasPermission(this, target.device)) {
            log("正在申请 USB 访问权限，请在弹窗中允许……");
            requestPermission(target.device);
            return;
        }
        connect(target);
    }

    private void requestPermission(UsbDevice device) {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            return;
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), flags);
        manager.requestPermission(device, pi);
    }

    private void connect(UsbHost.Found target) {
        setStatus(getString(R.string.connecting));
        Tasks.submit(() -> {
            DeviceManager.get().open(target);
            return DeviceManager.get().describe();
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                String text = target.mode == UsbHost.Mode.ADB
                        ? getString(R.string.connected_adb) + "\n" + value
                        : getString(R.string.connected_fastboot) + "\n" + value;
                setStatus(text);
                log("连接成功：" + value);
            }

            @Override
            public void onError(Throwable error) {
                String msg = error.getMessage();
                setStatus("连接失败：" + msg);
                log("连接失败：" + msg);
            }
        });
    }

    private void showWirelessDialog() {
        AppCompatEditText input = new AppCompatEditText(this);
        input.setHint("192.168.1.20:5555");
        input.setTextSize(14f);
        new MaterialAlertDialogBuilder(this)
                .setTitle("无线 ADB 连接")
                .setMessage("副手机需开启「无线调试」，输入 地址:端口（默认 5555）")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("连接", (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString().trim();
                    connectWireless(text);
                })
                .show();
    }

    private void connectWireless(String text) {
        if (text.isEmpty()) {
            toast("请输入地址");
            return;
        }
        String host = text;
        int port = 5555;
        int idx = text.lastIndexOf(':');
        if (idx > 0) {
            host = text.substring(0, idx);
            try {
                port = Integer.parseInt(text.substring(idx + 1));
            } catch (NumberFormatException e) {
                port = 5555;
            }
        }
        final String finalHost = host;
        final int finalPort = port;
        setStatus("正在通过无线调试连接 " + host + ":" + port + " …");
        Tasks.submit(() -> {
            AdbConnection connection = new AdbConnection(
                    new AdbTcpTransport(finalHost, finalPort), DeviceManager.get().crypto());
            connection.start();
            if (!connection.waitForConnect(20000)) {
                throw new IllegalStateException("连接超时，请确认副手机已开启无线调试且端口正确");
            }
            DeviceManager.get().attachAdb(connection, "无线：" + finalHost + ":" + finalPort);
            return "ok";
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                setStatus("已连接（无线 ADB）：" + finalHost + ":" + finalPort);
                log("无线连接成功");
                Toast.makeText(MainActivity.this, "无线连接成功", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Throwable error) {
                setStatus("无线连接失败：" + error.getMessage());
                log("无线连接失败：" + error.getMessage());
            }
        });
    }
}
