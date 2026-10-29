package com.violet.toolbox.ui;

import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.violet.toolbox.R;
import com.violet.toolbox.adb.AdbConnection;
import com.violet.toolbox.core.DeviceManager;
import com.violet.toolbox.core.StreamSource;
import com.violet.toolbox.util.FileUtils;
import com.violet.toolbox.util.Tasks;

/**
 * ADB Sideload：副手机进 Recovery 的「Apply update from ADB」后，
 * 主手机直接把官方 OTA 包推过去升级 / 救砖。
 *
 * 关键点：sideload 由 Recovery 提供，不需要系统已开启 USB 调试，
 * 因此副手机系统起不来时依然可用。
 */
public class SideloadActivity extends BaseActivity {

    private int lastPercent = -1;

    private final ActivityResultLauncher<String[]> otaLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    startSideload(uri);
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
            setStatus("尚未连接副手机。\n请让副手机进入 Recovery → 选择「Apply update from ADB」，"
                    + "再用 OTG 线连上后回到首页重新扫描。");
        } else {
            setStatus("已连接：" + DeviceManager.get().describe()
                    + "\n请确认副手机已进入 sideload 等待界面。");
        }
        buildUi();
    }

    private void buildUi() {
        addButton("选择官方 OTA 包（.zip）并开始 Sideload", v -> otaLauncher.launch(new String[]{"application/zip", "*/*"}));

        addButton("让副手机重启到 Recovery", v -> Tasks.submit(() -> {
            AdbConnection adb = DeviceManager.get().adb();
            if (adb == null || !adb.isConnected()) {
                throw new IllegalStateException("ADB 未连接");
            }
            adb.reboot("recovery");
            return "已下发重启到 Recovery 的指令，进入后请选择「Apply update from ADB」，"
                    + "然后回到首页重新扫描连接。";
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                log(value);
                setStatus(value);
            }

            @Override
            public void onError(Throwable error) {
                log("执行失败：" + error.getMessage());
            }
        }));

        addButton("查看 Sideload 操作步骤", v -> {
            String steps = "步骤：\n"
                    + "1. 副手机关机后按「音量上 + 电源」进入 Recovery；\n"
                    + "2. 选择「Apply update from ADB」（从 ADB 安装升级）；\n"
                    + "3. 用 OTG 线连接两台手机，回到本应用首页点「重新扫描设备」；\n"
                    + "4. 进入本页选择官方 OTA 的 zip，等待传输完成；\n"
                    + "5. 副手机显示完成后重启即可。\n\n"
                    + "注意：sideload 会校验签名，只能用官方完整包，且不能跨机型、跨地区。";
            log(steps);
            setStatus(steps);
        });
    }

    private void startSideload(Uri uri) {
        String name = FileUtils.displayName(this, uri);
        long size = FileUtils.size(this, uri);
        setStatus("正在 sideload：" + name + "（" + FileUtils.humanSize(size) + "）");
        lastPercent = -1;
        Tasks.submit(() -> {
            AdbConnection adb = DeviceManager.get().adb();
            if (adb == null || !adb.isConnected()) {
                throw new IllegalStateException("ADB 未连接，请确认副手机已进入 sideload 等待界面");
            }
            StreamSource source = FileUtils.fromUri(this, uri);
            String result = adb.sideload(source.open(), source.size(), (done, total) -> {
                int percent = total > 0 ? (int) (done * 100 / total) : 0;
                if (percent != lastPercent && percent % 10 == 0) {
                    lastPercent = percent;
                    log("传输进度：" + percent + "%");
                }
            });
            return "副手机返回：" + (result == null || result.trim().isEmpty() ? "（无输出，通常表示已完成）" : result.trim());
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                log(value);
                setStatus(value + "\n\n如副手机显示安装完成，重启即可。");
            }

            @Override
            public void onError(Throwable error) {
                log("Sideload 失败：" + error.getMessage());
                setStatus("Sideload 失败：" + error.getMessage()
                        + "\n常见原因：副手机不在 sideload 等待界面、包名不匹配、或 OTG 线接触不良。");
            }
        });
    }
}
