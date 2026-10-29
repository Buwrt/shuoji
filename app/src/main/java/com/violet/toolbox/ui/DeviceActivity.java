package com.violet.toolbox.ui;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.AppCompatEditText;

import com.violet.toolbox.R;
import com.violet.toolbox.adb.AdbConnection;
import com.violet.toolbox.brand.BrandKit;
import com.violet.toolbox.core.DeviceManager;
import com.violet.toolbox.core.StreamSource;
import com.violet.toolbox.util.FileUtils;
import com.violet.toolbox.util.Tasks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADB 工具箱：副手机处于开机状态时，采集信息、重启、安装应用、传输文件、执行 shell。
 */
public class DeviceActivity extends BaseActivity {

    private boolean pickForInstall = true;

    private final ActivityResultLauncher<String[]> fileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    handlePicked(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool);
        bindCommonViews();
        setContainer(R.id.container);

        AdbConnection adb = DeviceManager.get().adb();
        if (adb == null || !adb.isConnected()) {
            setStatus("尚未连接副手机（ADB 模式），请先回到首页连接");
            toast("请先回到首页连接副手机");
            finish();
            return;
        }
        setStatus("已连接：" + DeviceManager.get().describe());
        buildButtons();
    }

    private void buildButtons() {
        addButton(getString(R.string.adb_collect), v -> runAdb(this::collectInfo));

        addButton(getString(R.string.reboot_system), v -> runAdb(() -> reboot("")));
        addButton(getString(R.string.reboot_recovery), v -> runAdb(() -> reboot("recovery")));
        addButton(getString(R.string.reboot_bootloader), v -> runAdb(() -> reboot("bootloader")));
        addButton(getString(R.string.reboot_fastbootd), v -> runAdb(() -> reboot("fastboot")));
        addButton(getString(R.string.reboot_edl), v -> runAdb(() -> reboot("edl")));

        addButton(getString(R.string.adb_install_apk), v -> {
            pickForInstall = true;
            fileLauncher.launch(new String[]{"application/vnd.android.package-archive", "*/*"});
        });

        addButton(getString(R.string.adb_push), v -> {
            pickForInstall = false;
            fileLauncher.launch(new String[]{"*/*"});
        });

        addButton(getString(R.string.adb_xiaomi_usb), v -> runAdb(() -> {
            String out = adb().shell("su -c setprop persist.sys.usb.config adb 2>&1; getprop persist.sys.usb.config");
            return "当前 USB 配置：" + out.trim() + "\n（未 Root 的机型可能无法修改该属性）";
        }));

        addButton("读取一加 / OPPO DDR 与基带信息", v -> runAdb(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append(shellKeep("ro.boot.ddr"));
            sb.append(shellKeep("ro.boot.ddr_type"));
            sb.append(shellKeep("gsm.version.baseband"));
            sb.append(shellKeep("ro.boot.hwversion"));
            return sb.length() == 0 ? "未读取到相关信息" : sb.toString();
        }));

        // 自定义 shell 命令
        AppCompatEditText input = new AppCompatEditText(this);
        input.setHint(getString(R.string.adb_shell_hint));
        input.setSingleLine(true);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        input.setLayoutParams(lp);
        LinearLayout container = findViewById(R.id.container);
        if (container != null) {
            container.addView(input);
        }
        addButton(getString(R.string.adb_shell_label) + "／" + getString(R.string.run),
                v -> {
                    String cmd = input.getText() == null ? "" : input.getText().toString().trim();
                    if (cmd.isEmpty()) {
                        toast("请输入命令");
                        return;
                    }
                    log("$ adb shell " + cmd);
                    runAdb(() -> adb().shell(cmd));
                });
    }

    private String shellKeep(String prop) throws Exception {
        String out = adb().shell("getprop " + prop).trim();
        return out.isEmpty() ? "" : prop + " = " + out + "\n";
    }

    private AdbConnection adb() throws Exception {
        AdbConnection adb = DeviceManager.get().adb();
        if (adb == null || !adb.isConnected()) {
            throw new IllegalStateException("ADB 连接已断开，请重新连接");
        }
        return adb;
    }

    private String reboot(String target) throws Exception {
        adb().reboot(target == null ? "" : target);
        return "已下发重启指令：" + (target == null || target.isEmpty() ? "系统" : target);
    }

    private String collectInfo() throws Exception {
        String props = adb().shell("getprop");
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (String line : props.split("\n")) {
            String t = line.trim();
            int idx = t.indexOf("]: [");
            if (t.startsWith("[") && idx > 0 && t.endsWith("]")) {
                String key = t.substring(1, idx);
                String value = t.substring(idx + 4, t.length() - 1);
                map.put(key, value);
            }
        }
        BrandKit.Brand brand = BrandKit.detect(map);
        StringBuilder sb = new StringBuilder();
        sb.append("===== 副手机信息 =====\n");
        sb.append(kv(map, "品牌", "ro.product.brand"));
        sb.append(kv(map, "厂商", "ro.product.manufacturer"));
        sb.append(kv(map, "型号", "ro.product.model"));
        sb.append(kv(map, "代号", "ro.product.device"));
        sb.append(kv(map, "ROM 版本", "ro.build.display.id"));
        sb.append(kv(map, "安卓版本", "ro.build.version.release"));
        sb.append(kv(map, "SDK", "ro.build.version.sdk"));
        sb.append(kv(map, "安全补丁", "ro.build.version.security_patch"));
        sb.append(kv(map, "当前槽位", "ro.boot.slot_suffix"));
        sb.append(kv(map, "CPU 架构", "ro.product.cpu.abi"));
        sb.append(kv(map, "基带", "gsm.version.baseband"));
        sb.append(kv(map, "DDR", "ro.boot.ddr"));
        sb.append("识别品牌：" + brand.label + "\n");
        sb.append("建议 Root 修补分区：" + BrandKit.rootPartition(map, hasInitBoot(map)) + "\n");
        sb.append(BrandKit.tips(brand));
        return sb.toString();
    }

    private boolean hasInitBoot(Map<String, String> map) {
        try {
            String out = adb().shell("ls /dev/block/by-name/init_boot 2>&1").trim();
            return !out.contains("No such file") && !out.contains("not found");
        } catch (Exception e) {
            return false;
        }
    }

    private String kv(Map<String, String> map, String label, String key) {
        String v = map.get(key);
        if (TextUtils.isEmpty(v)) {
            return "";
        }
        return label + "：" + v + "\n";
    }

    private void handlePicked(Uri uri) {
        String name = FileUtils.displayName(this, uri);
        if (pickForInstall) {
            log("准备安装：" + name);
            runAdb(() -> {
                StreamSource source = FileUtils.fromUri(this, uri);
                String out = adb().install(source.open(), source.size(), name.replace(" ", "_"));
                return "安装结果：\n" + out.trim();
            });
        } else {
            log("准备推送：" + name);
            runAdb(() -> {
                StreamSource source = FileUtils.fromUri(this, uri);
                String remote = "/sdcard/Download/" + name.replace(" ", "_");
                try {
                    adb().push(source.open(), source.size(), remote, null);
                    return "已推送到 " + remote;
                } catch (Exception e) {
                    // 部分机型不支持 adb push，退回到 shell 写入
                    return "adb push 失败：" + e.getMessage()
                            + "\n可尝试改用「命令终端」执行 adb push 命令。";
                }
            });
        }
    }

    private void runAdb(Tasks.Job<String> job) {
        Tasks.submit(job, new Tasks.Callback<String>() {
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
