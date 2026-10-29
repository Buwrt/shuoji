package com.violet.toolbox.ui;

import android.os.Bundle;
import android.text.TextUtils;

import androidx.appcompat.widget.AppCompatEditText;

import com.violet.toolbox.R;
import com.violet.toolbox.adb.AdbConnection;
import com.violet.toolbox.core.DeviceManager;
import com.violet.toolbox.fastboot.FastbootUsb;
import com.violet.toolbox.usb.UsbHost;
import com.violet.toolbox.util.Tasks;

import java.util.List;
import java.util.Locale;

/**
 * 命令终端：支持 adb xxx 与 fastboot xxx 两种命令。
 */
public class TerminalActivity extends BaseActivity {

    private AppCompatEditText etCommand;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        bindCommonViews();
        etCommand = findViewById(R.id.et_command);

        UsbHost.Mode mode = DeviceManager.get().mode();
        String modeText = mode == UsbHost.Mode.ADB ? "ADB（开机）"
                : (mode == UsbHost.Mode.FASTBOOT ? "Fastboot / FastbootD" : "未连接");
        setStatus("当前模式：" + modeText + " ｜ " + DeviceManager.get().describe());

        findViewById(R.id.btn_send).setOnClickListener(v -> send());
    }

    private void send() {
        String cmd = etCommand.getText() == null ? "" : etCommand.getText().toString().trim();
        if (TextUtils.isEmpty(cmd)) {
            toast("请输入命令");
            return;
        }
        log("$ " + cmd);
        String lower = cmd.toLowerCase(Locale.US);
        if (lower.startsWith("adb ")) {
            String rest = cmd.substring(4).trim();
            if (rest.toLowerCase(Locale.US).startsWith("shell ")) {
                String shell = rest.substring(6);
                log("$ adb shell " + shell);
                runShell(shell);
            } else if (rest.toLowerCase(Locale.US).startsWith("reboot")) {
                String target = rest.substring("reboot".length()).trim();
                runAdbReboot(target);
            } else if (rest.toLowerCase(Locale.US).startsWith("devices")) {
                log("已连接设备：" + DeviceManager.get().describe());
            } else {
                runShell(rest);
            }
            return;
        }
        if (lower.startsWith("fastboot ")) {
            runFastbootRaw(cmd.substring(9).trim());
            return;
        }
        if (DeviceManager.get().mode() == UsbHost.Mode.ADB) {
            runShell(cmd);
        } else {
            runFastbootRaw(cmd);
        }
    }

    private void runShell(String command) {
        Tasks.submit(() -> {
            AdbConnection adb = DeviceManager.get().adb();
            if (adb == null || !adb.isConnected()) {
                throw new IllegalStateException("ADB 未连接");
            }
            return adb.shell(command);
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                log(value == null || value.trim().isEmpty() ? "（无输出）" : value);
            }

            @Override
            public void onError(Throwable error) {
                log("执行失败：" + error.getMessage());
            }
        });
    }

    private void runAdbReboot(String target) {
        Tasks.submit(() -> {
            AdbConnection adb = DeviceManager.get().adb();
            if (adb == null || !adb.isConnected()) {
                throw new IllegalStateException("ADB 未连接");
            }
            adb.reboot(target == null ? "" : target);
            return "已下发重启指令：" + (target == null || target.isEmpty() ? "系统" : target);
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                log(value);
            }

            @Override
            public void onError(Throwable error) {
                log("执行失败：" + error.getMessage());
            }
        });
    }

    private void runFastbootRaw(String command) {
        Tasks.submit(() -> {
            FastbootUsb fb = DeviceManager.get().fastboot();
            if (fb == null) {
                throw new IllegalStateException("Fastboot 未连接（副手机需处于 fastboot / fastbootd）");
            }
            List<String> out = fb.runRawQuiet(command);
            return TextUtils.join("\n", out);
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                log(value);
            }

            @Override
            public void onError(Throwable error) {
                log("执行失败：" + error.getMessage());
            }
        });
    }
}
