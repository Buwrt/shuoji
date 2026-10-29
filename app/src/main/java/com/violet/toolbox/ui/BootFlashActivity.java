package com.violet.toolbox.ui;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.AppCompatEditText;

import com.violet.toolbox.R;
import com.violet.toolbox.core.DeviceManager;
import com.violet.toolbox.core.StreamSource;
import com.violet.toolbox.fastboot.FastbootUsb;
import com.violet.toolbox.flash.FlashEngine;
import com.violet.toolbox.util.FileUtils;
import com.violet.toolbox.util.Tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 单独刷写 boot / init_boot（Root 修补最常用的场景）。
 */
public class BootFlashActivity extends BaseActivity {

    private Uri mainUri;
    private Uri vbmetaUri;
    private AppCompatEditText etPartition;
    private TextView tvSelected;

    private final ActivityResultLauncher<String[]> mainLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    mainUri = uri;
                    String name = FileUtils.displayName(this, uri);
                    String partition = guessPartition(name);
                    etPartition.setText(partition);
                    tvSelected.setText("已选择：" + name + "（" + FileUtils.humanSize(FileUtils.size(this, uri)) + "）");
                    log("已选择镜像 " + name + "，自动识别目标分区：" + partition);
                }
            });

    private final ActivityResultLauncher<String[]> vbmetaLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    vbmetaUri = uri;
                    log("已选择 vbmeta 镜像：" + FileUtils.displayName(this, uri));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool);
        bindCommonViews();
        setContainer(R.id.container);

        if (DeviceManager.get().fastboot() == null) {
            setStatus("需要 Fastboot 模式：请在首页连接处于 fastboot / fastbootd 的副手机");
            toast("请先让副手机进入 fastboot 并在首页连接");
            finish();
            return;
        }
        setStatus("已连接：" + DeviceManager.get().describe());
        buildUi();
    }

    private void buildUi() {
        LinearLayout container = findViewById(R.id.container);

        tvSelected = new TextView(this);
        tvSelected.setTextColor(0xFFCE93D8);
        tvSelected.setTextSize(13f);
        tvSelected.setText("尚未选择镜像");
        tvSelected.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (container != null) {
            container.addView(tvSelected);
        }

        addButton(getString(R.string.bf_pick_image), v -> mainLauncher.launch(new String[]{"*/*"}));

        etPartition = new AppCompatEditText(this);
        etPartition.setHint(getString(R.string.bf_partitions));
        etPartition.setSingleLine(true);
        etPartition.setTextColor(0xFFFFFFFF);
        etPartition.setTextSize(14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 10;
        etPartition.setLayoutParams(lp);
        if (container != null) {
            container.addView(etPartition);
        }

        addButton("（可选）选择 vbmeta.img 并关闭校验", v -> vbmetaLauncher.launch(new String[]{"*/*"}));

        addButton(getString(R.string.bf_start), v -> startFlash());

        addButton("刷写后重启到系统", v -> runFastboot(() -> {
            fb().reboot("");
            return "已重启";
        }));
    }

    private void startFlash() {
        if (mainUri == null) {
            toast(getString(R.string.bf_pick_image));
            return;
        }
        String partition = etPartition.getText() == null ? "" : etPartition.getText().toString().trim();
        if (TextUtils.isEmpty(partition)) {
            toast("请填写目标分区");
            return;
        }
        runFastboot(() -> {
            List<FlashEngine.PlanItem> items = new ArrayList<FlashEngine.PlanItem>();
            if (vbmetaUri != null) {
                StreamSource vbmeta = FileUtils.fromUri(this, vbmetaUri);
                items.add(new FlashEngine.PlanItem("vbmeta", vbmeta, true, true));
            }
            StreamSource source = FileUtils.fromUri(this, mainUri);
            items.add(new FlashEngine.PlanItem(partition, source, false, false));
            final String target = partition;
            FlashEngine.execute(this, fb(), items, new FlashEngine.Callback() {
                @Override
                public void onLog(String message) {
                    log(message);
                }

                @Override
                public void onProgress(String part, long done, long total) {
                    int percent = total > 0 ? (int) (done * 100 / total) : 0;
                    if (percent % 10 == 0) {
                        log("进度 " + part + "：" + percent + "%");
                    }
                }
            });
            return "刷写完成：" + target + "。如需获取 Root，请确认镜像已修补（Magisk / KernelSU）。";
        });
    }

    /** 按镜像文件名自动判断目标分区。 */
    private String guessPartition(String fileName) {
        String lower = fileName.toLowerCase(Locale.US);
        if (lower.contains("init_boot")) {
            return "init_boot";
        }
        if (lower.contains("vendor_boot")) {
            return "vendor_boot";
        }
        if (lower.contains("recovery")) {
            return "recovery";
        }
        if (lower.contains("boot")) {
            return "boot";
        }
        return "init_boot";
    }

    private FastbootUsb fb() throws Exception {
        FastbootUsb fb = DeviceManager.get().fastboot();
        if (fb == null) {
            throw new IllegalStateException("Fastboot 连接已断开，请重新连接");
        }
        return fb;
    }

    private void runFastboot(Tasks.Job<String> job) {
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
