package com.violet.toolbox.ui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.violet.toolbox.R;
import com.violet.toolbox.brand.BrandKit;
import com.violet.toolbox.core.DeviceManager;
import com.violet.toolbox.core.StreamSource;
import com.violet.toolbox.fastboot.FastbootUsb;
import com.violet.toolbox.flash.FlashEngine;
import com.violet.toolbox.flash.FlashScriptParser;
import com.violet.toolbox.usb.UsbHost;
import com.violet.toolbox.util.FileUtils;
import com.violet.toolbox.util.Tasks;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 品牌线刷中心：解析线刷包目录中的 flash_all 脚本或镜像，一键线刷。
 * 支持小米 / 红米、OPPO、一加、真我的 fastboot 散包；
 * 需要刷写 super 等动态分区时会自动切换到 fastbootd 并等待设备重连。
 */
public class FlashCenterActivity extends BaseActivity {

    private static final String ACTION_USB_PERMISSION = "com.violet.toolbox.USB_PERMISSION";

    private Uri packageUri;
    private final Map<String, Uri> imageUris = new LinkedHashMap<String, Uri>();
    private List<FlashScriptParser.Item> plan = new ArrayList<FlashScriptParser.Item>();
    private TextView tvPlan;
    private boolean pendingFlash = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Uri> treeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) {
                    return;
                }
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    log("目录权限保存失败：" + e.getMessage());
                }
                packageUri = uri;
                log("已选择线刷包目录：" + uri.getLastPathSegment());
                parsePackage();
            });

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                if (pendingFlash) {
                    log("检测到副手机重新接入，正在重连……");
                    handler.postDelayed(FlashCenterActivity.this::reconnectAndFlash, 1500);
                }
            } else if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    reconnectAndFlash();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool);
        bindCommonViews();
        setContainer(R.id.container);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        if (DeviceManager.get().fastboot() == null) {
            setStatus("线刷需要 Fastboot / FastbootD 模式，请在首页先连接副手机");
        } else {
            setStatus("已连接：" + DeviceManager.get().describe());
        }
        buildUi();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout container = findViewById(R.id.container);

        TextView tip = new TextView(this);
        tip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tip.setTextSize(13f);
        tip.setText("支持：小米 / Redmi 官方 fastboot 线刷包（flash_all.bat、flash_all.sh）、"
                + "OPPO / 一加 / 真我的 fastboot 散包（images 目录）。");
        tip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (container != null) {
            container.addView(tip);
        }

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, BrandKit.brands());
        spinner.setAdapter(adapter);
        android.graphics.drawable.Drawable spinBg =
                ContextCompat.getDrawable(this, R.drawable.bg_edittext);
        if (spinBg != null) {
            spinner.setBackground(spinBg);
        }
        float density = getResources().getDisplayMetrics().density;
        spinner.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * density));
        lp.topMargin = (int) (10 * density);
        spinner.setLayoutParams(lp);
        if (container != null) {
            container.addView(spinner);
        }
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                BrandKit.Brand brand = BrandKit.Brand.values()[position];
                log(BrandKit.tips(brand));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        addSection("线刷准备");
        addButton(getString(R.string.fc_pick_package), v -> treeLauncher.launch(null));
        addButton(getString(R.string.fc_parse), v -> {
            if (packageUri == null) {
                toast("请先选择线刷包目录");
                return;
            }
            parsePackage();
        });

        tvPlan = new TextView(this);
        tvPlan.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tvPlan.setTextSize(12f);
        tvPlan.setText(getString(R.string.fc_no_plan));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = 10;
        tvPlan.setLayoutParams(lp2);
        if (container != null) {
            container.addView(tvPlan);
        }

        addSection("执行刷写");
        addButton(getString(R.string.fc_start), v -> startFlash());
        addButton("刷完重启到系统", v -> runFastboot(() -> {
            fb().reboot("");
            return "已重启";
        }));
    }

    // ---------------- 解析线刷包 ----------------

    private void parsePackage() {
        if (packageUri == null) {
            return;
        }
        Tasks.submit(() -> {
            imageUris.clear();
            plan.clear();
            DocumentFile root = DocumentFile.fromTreeUri(this, packageUri);
            if (root == null) {
                throw new IllegalStateException("无法访问所选目录");
            }
            StringBuilder report = new StringBuilder();
            List<DocumentFile> scripts = new ArrayList<DocumentFile>();
            List<DocumentFile> images = new ArrayList<DocumentFile>();
            boolean ofp = false;
            collect(root, scripts, images, 0);
            for (DocumentFile f : images) {
                String name = f.getName();
                if (name != null) {
                    imageUris.put(name, f.getUri());
                }
            }
            for (DocumentFile f : images) {
                String n = f.getName() == null ? "" : f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".ofp") || n.endsWith(".ops") || n.endsWith(".ozip")) {
                    ofp = true;
                }
            }
            if (ofp) {
                report.append(getString(R.string.fc_ofp_tip)).append("\n");
            }

            String scriptText = null;
            for (DocumentFile f : scripts) {
                String text = readText(f.getUri());
                if (text != null && text.toLowerCase(Locale.US).contains("fastboot")) {
                    scriptText = text;
                    report.append("已识别刷机脚本：").append(f.getName()).append("\n");
                    break;
                }
            }
            if (scriptText != null) {
                plan = FlashScriptParser.parse(scriptText);
            } else {
                report.append("未找到脚本，按镜像文件名自动排序\n");
                plan = FlashScriptParser.guess(new ArrayList<String>(imageUris.keySet()));
            }
            if (plan.isEmpty()) {
                report.append("未能解析出刷写计划，请确认目录内包含 fastboot 镜像或 flash_all 脚本。");
            } else {
                report.append("刷写计划共 ").append(plan.size()).append(" 项：\n");
                for (int i = 0; i < plan.size(); i++) {
                    report.append(" ").append(i + 1).append(". ").append(plan.get(i).toString()).append("\n");
                }
            }
            return report.toString();
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                tvPlan.setText(value);
                log("解析完成：\n" + value);
            }

            @Override
            public void onError(Throwable error) {
                log("解析失败：" + error.getMessage());
            }
        });
    }

    private void collect(DocumentFile dir, List<DocumentFile> scripts,
                         List<DocumentFile> images, int depth) {
        if (dir == null || depth > 3) {
            return;
        }
        DocumentFile[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (DocumentFile f : files) {
            if (f.isDirectory()) {
                collect(f, scripts, images, depth + 1);
                continue;
            }
            String name = f.getName();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.US);
            if (lower.endsWith(".img") || lower.endsWith(".ofp") || lower.endsWith(".ops")) {
                images.add(f);
            } else if (lower.endsWith(".bat") || lower.endsWith(".sh")
                    || lower.endsWith(".cmd") || lower.endsWith(".txt")) {
                scripts.add(f);
            }
        }
    }

    private String readText(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 执行线刷 ----------------

    private void startFlash() {
        if (plan.isEmpty()) {
            toast(getString(R.string.fc_no_plan));
            return;
        }
        if (DeviceManager.get().fastboot() == null) {
            toast("请先在首页连接处于 fastboot / fastbootd 的副手机");
            return;
        }
        Tasks.submit(() -> {
            FastbootUsb fb = fb();
            boolean need = FlashScriptParser.needsFastbootd(plan);
            String userspace = fb.getvar("is-userspace");
            if (need && !"yes".equalsIgnoreCase(userspace.trim())) {
                fb.reboot("fastboot");
                return "switching";
            }
            return "go";
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                if ("switching".equals(value)) {
                    pendingFlash = true;
                    log("计划包含动态分区（super 等），已让副手机切换到 FastbootD。");
                    log("等待设备重新接入……（若 40 秒内未自动继续，请重新插拔后点「一键线刷」）");
                    handler.postDelayed(() -> {
                        if (pendingFlash) {
                            reconnectAndFlash();
                        }
                    }, 8000);
                } else {
                    doFlashNow();
                }
            }

            @Override
            public void onError(Throwable error) {
                log("启动线刷失败：" + error.getMessage());
            }
        });
    }

    private void reconnectAndFlash() {
        Tasks.submit(() -> {
            UsbHost.Found found = UsbHost.findOne(this);
            if (found == null) {
                return "未检测到设备";
            }
            if (!UsbHost.hasPermission(this, found.device)) {
                requestPermission(found.device);
                return "等待权限";
            }
            DeviceManager.get().open(found);
            return "ok";
        }, new Tasks.Callback<String>() {
            @Override
            public void onResult(String value) {
                if ("ok".equals(value)) {
                    log("已重新连接：" + DeviceManager.get().describe());
                    pendingFlash = false;
                    doFlashNow();
                } else {
                    log("重连状态：" + value);
                }
            }

            @Override
            public void onError(Throwable error) {
                log("重连失败：" + error.getMessage());
            }
        });
    }

    private void doFlashNow() {
        List<FlashEngine.PlanItem> items = new ArrayList<FlashEngine.PlanItem>();
        for (FlashScriptParser.Item item : plan) {
            Uri uri = imageUris.get(item.file);
            if (uri == null) {
                log("跳过（目录中没有该镜像）：" + item.file);
                continue;
            }
            StreamSource source = FileUtils.fromUri(this, uri);
            items.add(new FlashEngine.PlanItem(item.partition, source,
                    item.disableVerity, item.disableVerification));
        }
        if (items.isEmpty()) {
            toast("没有可刷写的镜像");
            return;
        }
        log("开始执行线刷，共 " + items.size() + " 个分区，请勿断开 OTG 线！");
        runFastboot(() -> {
            FlashEngine.execute(this, fb(), items, new FlashEngine.Callback() {
                @Override
                public void onLog(String message) {
                    log(message);
                }

                @Override
                public void onProgress(String part, long done, long total) {
                    int percent = total > 0 ? (int) (done * 100 / total) : 0;
                    if (percent % 20 == 0) {
                        log("进度 " + part + "：" + percent + "%");
                    }
                }
            });
            return "全部刷写完成，建议点「刷完重启到系统」。";
        });
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
        PendingIntent pi = PendingIntent.getBroadcast(this, 1,
                new Intent(ACTION_USB_PERMISSION), flags);
        manager.requestPermission(device, pi);
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
