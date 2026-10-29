package com.violet.toolbox.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.AppCompatEditText;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.violet.toolbox.R;
import com.violet.toolbox.core.DeviceManager;
import com.violet.toolbox.core.StreamSource;
import com.violet.toolbox.fastboot.FastbootUsb;
import com.violet.toolbox.flash.FlashEngine;
import com.violet.toolbox.util.FileUtils;
import com.violet.toolbox.util.Tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fastboot 工具箱：副手机处于 fastboot / fastbootd 时的分区刷写与解锁操作。
 */
public class FastbootActivity extends BaseActivity {

    private String pendingPartition;
    private boolean tempBoot = false;
    private boolean vbmetaMode = false;
    private int lastPercent = -1;

    private final ActivityResultLauncher<String[]> imageLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && pendingPartition != null) {
                    doFlash(pendingPartition, uri, vbmetaMode, vbmetaMode);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool);
        bindCommonViews();
        setContainer(R.id.container);

        if (DeviceManager.get().fastboot() == null) {
            setStatus("尚未连接副手机（Fastboot 模式），请先回到首页连接");
            toast("请先回到首页连接副手机");
            finish();
            return;
        }
        setStatus("已连接：" + DeviceManager.get().describe());
        buildButtons();
        refreshVariables();
    }

    private void buildButtons() {
        addButton(getString(R.string.fb_getvar), v -> runFastboot(() -> {
            Map<String, String> vars = fb().getvarAll();
            StringBuilder sb = new StringBuilder("===== Fastboot 变量 =====\n");
            for (Map.Entry<String, String> e : vars.entrySet()) {
                sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
            return sb.toString();
        }));

        addButton(getString(R.string.fb_flash_boot), v -> pickAndFlash("boot"));
        addButton(getString(R.string.fb_flash_init_boot), v -> pickAndFlash("init_boot"));
        addButton(getString(R.string.fb_flash_vendor_boot), v -> pickAndFlash("vendor_boot"));
        addButton(getString(R.string.fb_flash_vbmeta), v -> {
            log("请选择 vbmeta.img（将以关闭校验的方式刷入）");
            pendingPartition = "vbmeta";
            tempBoot = false;
            vbmetaMode = true;
            imageLauncher.launch(new String[]{"*/*"});
        });
        addButton(getString(R.string.fb_flash_recovery), v -> pickAndFlash("recovery"));
        addButton(getString(R.string.fb_flash_partition), v -> askPartition());
        addButton(getString(R.string.fb_boot_temp), v -> {
            pendingPartition = "boot";
            vbmetaMode = false;
            log("请选择要临时启动的镜像（不会写入分区）");
            tempBoot = true;
            imageLauncher.launch(new String[]{"*/*"});
        });

        addButton(getString(R.string.fb_erase), v -> askText("擦除分区", "请输入分区名（如 userdata / cache）", name -> runFastboot(() -> {
            fb().erase(name);
            return "已擦除分区：" + name;
        })));

        addButton(getString(R.string.fb_unlock), v -> confirm(getString(R.string.fb_warning_unlock), () -> runFastboot(() -> {
            FastbootUsb.Response r = fb().unlock(false);
            return r.ok ? "解锁指令已下发，请按副手机屏幕提示确认" : "解锁失败：" + r.payload;
        })));

        addButton(getString(R.string.fb_lock), v -> confirm("回锁会清空数据，确定继续？", () -> runFastboot(() -> {
            FastbootUsb.Response r = fb().lock(false);
            return r.ok ? "回锁指令已下发" : "回锁失败：" + r.payload;
        })));

        addButton(getString(R.string.fb_slot_switch), v -> askText("切换槽位", "输入 a 或 b", slot -> runFastboot(() -> {
            fb().setActive(slot);
            return "已切换到槽位 " + slot;
        })));

        addButton(getString(R.string.fb_reboot_system), v -> runFastboot(() -> {
            fb().reboot("");
            return "已重启副手机到系统";
        }));
        addButton(getString(R.string.fb_reboot_fastbootd), v -> runFastboot(() -> {
            fb().reboot("fastboot");
            return "正在切换到 FastbootD，设备重启后请重新连接";
        }));
        addButton("重启到 Bootloader", v -> runFastboot(() -> {
            fb().reboot("bootloader");
            return "已重启到 Bootloader";
        }));
        addButton("重启到 EDL（9008）", v -> runFastboot(() -> {
            fb().reboot("edl");
            return "已尝试重启到 EDL（仅部分机型支持）";
        }));

        // 自定义 fastboot 命令
        AppCompatEditText input = new AppCompatEditText(this);
        input.setHint("fastboot 命令，例如：getvar product");
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
        addButton("执行 fastboot 命令", v -> {
            String cmd = input.getText() == null ? "" : input.getText().toString().trim();
            if (cmd.isEmpty()) {
                toast("请输入命令");
                return;
            }
            log("$ fastboot " + cmd);
            runFastboot(() -> {
                List<String> out = fb().runRawQuiet(cmd);
                return android.text.TextUtils.join("\n", out);
            });
        });
    }

    private void refreshVariables() {
        runFastboot(() -> {
            String product = fb().getvar("product");
            String slot = fb().currentSlot();
            String unlocked = fb().getvar("unlocked");
            String userspace = fb().getvar("is-userspace");
            return "产品：" + product
                    + "\n当前槽位：" + (slot.isEmpty() ? "未知" : slot)
                    + "\n解锁状态：" + ("yes".equalsIgnoreCase(unlocked) ? "已解锁" : "未解锁/未知")
                    + "\n模式：" + ("yes".equalsIgnoreCase(userspace) ? "FastbootD（用户态）" : "Bootloader（工厂态）");
        });
    }

    private void pickAndFlash(String partition) {
        pendingPartition = partition;
        tempBoot = false;
        vbmetaMode = false;
        log("请选择 " + partition + ".img");
        imageLauncher.launch(new String[]{"*/*"});
    }

    private void askPartition() {
        askText("刷写分区", "请输入分区名（如 boot / init_boot / vendor_boot / super）", name -> {
            pendingPartition = name;
            tempBoot = false;
            vbmetaMode = false;
            imageLauncher.launch(new String[]{"*/*"});
        });
    }

    private void doFlash(String partition, Uri uri, boolean disableVerity, boolean disableVerification) {
        if (tempBoot) {
            tempBoot = false;
            runFastboot(() -> {
                StreamSource source = FileUtils.fromUri(this, uri);
                fb().boot(source.open(), source.size(), null);
                return "已临时启动镜像（未写入分区）";
            });
            return;
        }
        vbmetaMode = false;
        runFastboot(() -> {
            StreamSource source = FileUtils.fromUri(this, uri);
            List<FlashEngine.PlanItem> items = new ArrayList<FlashEngine.PlanItem>();
            items.add(new FlashEngine.PlanItem(partition, source, disableVerity, disableVerification));
            FlashEngine.execute(this, fb(), items, new FlashEngine.Callback() {
                @Override
                public void onLog(String message) {
                    log(message);
                }

                @Override
                public void onProgress(String part, long done, long total) {
                    int percent = total > 0 ? (int) (done * 100 / total) : 0;
                    updateProgress(part, percent);
                }
            });
            return "分区 " + partition + " 刷写完成";
        });
    }

    private void updateProgress(String partition, int percent) {
        if (percent != lastPercent && percent % 10 == 0) {
            lastPercent = percent;
            log("刷写 " + partition + "：" + percent + "%");
        }
    }

    private FastbootUsb fb() throws Exception {
        FastbootUsb fb = DeviceManager.get().fastboot();
        if (fb == null) {
            throw new IllegalStateException("Fastboot 连接已断开，请重新连接");
        }
        return fb;
    }

    private void confirm(String message, Runnable action) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("请确认")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> action.run())
                .show();
    }

    private void askText(String title, String hint, java.util.function.Consumer<String> consumer) {
        AppCompatEditText input = new AppCompatEditText(this);
        input.setHint(hint);
        input.setTextColor(0xFFFFFFFF);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        consumer.accept(text);
                    }
                })
                .show();
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
