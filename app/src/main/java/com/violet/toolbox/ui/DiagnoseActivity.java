package com.violet.toolbox.ui;

import androidx.core.content.ContextCompat;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.violet.toolbox.R;
import com.violet.toolbox.core.DeviceManager;
import com.violet.toolbox.core.OtgDiagnostics;
import com.violet.toolbox.core.SelfTest;
import com.violet.toolbox.util.Tasks;

import java.util.List;

/**
 * 连接诊断 + 协议自检 + 免 USB 调试指引。
 */
public class DiagnoseActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool);
        bindCommonViews();
        setContainer(R.id.container);
        setStatus("先跑一遍诊断，确认主手机能当主机、协议栈工作正常，再动手刷写");
        buildUi();
        runDiagnostics();
    }

    private void buildUi() {
        LinearLayout container = findViewById(R.id.container);

        addSection("连接检测");
        addButton("重新诊断连接环境", v -> runDiagnostics());

        addButton("运行协议层自检（不需连接副手机）", v -> runSelfTest());

        addButton("真机连通性验证（getvar / 读设备信息）", v -> runLiveCheck());

        addSection("帮助与指引");
        addButton("副手机无法开启 USB 调试怎么办", v -> {
            String guide = OtgDiagnostics.noUsbDebugGuide();
            log(guide);
            setStatus(guide);
        });

        addButton("ADB Sideload（Recovery 下刷官方 OTA）", v ->
                startActivity(new Intent(this, SideloadActivity.class)));

        TextView tv = new TextView(this);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tv.setTextSize(12f);
        tv.setText("提示：若副手机无法开机，通常仍可进 fastboot；"
                + "若系统起不来但能进 Recovery，用 Sideload 官方包救回最快。");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        tv.setLayoutParams(lp);
        if (container != null) {
            container.addView(tv);
        }
    }

    private void runDiagnostics() {
        setStatus("正在诊断……");
        Tasks.submit(() -> OtgDiagnostics.run(this), new Tasks.Callback<List<OtgDiagnostics.Item>>() {
            @Override
            public void onResult(List<OtgDiagnostics.Item> items) {
                StringBuilder sb = new StringBuilder("===== 连接环境诊断 =====\n");
                for (OtgDiagnostics.Item item : items) {
                    sb.append(item.ok ? "✔ " : "✘ ").append(item.title).append("\n  ")
                            .append(item.detail).append('\n');
                    log((item.ok ? "✔ " : "✘ ") + item.title + "：" + item.detail);
                }
                sb.append("\n当前连接：").append(DeviceManager.get().describe());
                setStatus(sb.toString());
            }

            @Override
            public void onError(Throwable error) {
                log("诊断失败：" + error.getMessage());
                setStatus("诊断失败：" + error.getMessage());
            }
        });
    }

    private void runSelfTest() {
        setStatus("协议自检运行中……");
        Tasks.submit(() -> SelfTest.runAll(this), new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                log(value);
                setStatus(value);
                toast("自检完成");
            }

            @Override
            public void onError(Throwable error) {
                log("自检异常：" + error.getMessage());
                setStatus("自检异常：" + error.getMessage());
            }
        });
    }

    /** 真机连通性：fastboot 读变量，或 ADB 读设备信息。 */
    private void runLiveCheck() {
        setStatus("正在与副手机通信……");
        Tasks.submit(() -> {
            String result;
            if (DeviceManager.get().mode() == com.violet.toolbox.usb.UsbHost.Mode.FASTBOOT
                    && DeviceManager.get().fastboot() != null) {
                java.util.Map<String, String> vars = DeviceManager.get().fastboot().getvarAll();
                StringBuilder sb = new StringBuilder("Fastboot 连通正常，共读到 " + vars.size() + " 个变量：\n");
                int i = 0;
                for (java.util.Map.Entry<String, String> e : vars.entrySet()) {
                    sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
                    if (++i > 30) {
                        sb.append("  ……（其余已省略）\n");
                        break;
                    }
                }
                result = sb.toString();
            } else if (DeviceManager.get().adb() != null && DeviceManager.get().adb().isConnected()) {
                String model = DeviceManager.get().adb().shell("getprop ro.product.model").trim();
                String device = DeviceManager.get().adb().shell("getprop ro.product.device").trim();
                String version = DeviceManager.get().adb().shell("getprop ro.build.version.release").trim();
                result = "ADB 连通正常：\n  型号 = " + model + "\n  代号 = " + device
                        + "\n  安卓版本 = " + version;
            } else {
                result = "尚未连接到副手机，请先回到首页用 OTG 连接（或让副手机进 fastboot 后重新扫描）。";
            }
            return result;
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                log(value);
                setStatus(value);
            }

            @Override
            public void onError(Throwable error) {
                log("连通性验证失败：" + error.getMessage());
                setStatus("连通性验证失败：" + error.getMessage()
                        + "\n建议：重新插拔 OTG 线，确认副手机屏幕上的授权弹窗已点「允许」。");
            }
        });
    }
}
