package com.violet.toolbox.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.AppCompatEditText;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.violet.toolbox.R;
import com.violet.toolbox.core.LogBus;
import com.violet.toolbox.format.OplusKeys;
import com.violet.toolbox.format.PayloadDumper;
import com.violet.toolbox.format.RomUnpacker;
import com.violet.toolbox.util.FileUtils;
import com.violet.toolbox.util.Tasks;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ROM 解包：把官方 OTA zip / payload.bin / OPPO 一加真我的 ofp、ops、ozip
 * 直接在主手机上解成可刷写的 img（不需要电脑）。
 */
public class RomUnpackActivity extends BaseActivity {

    private Uri romUri;
    private TextView tvResult;
    private AppCompatEditText etPartitions;
    private File outDir;
    private final List<RomUnpacker.Output> outputs = new ArrayList<RomUnpacker.Output>();

    private final ActivityResultLauncher<String[]> romLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    romUri = uri;
                    String name = FileUtils.displayName(this, uri);
                    log("已选择：" + name + "（" + RomUnpacker.humanSize(FileUtils.size(this, uri)) + "）");
                    setStatus("待解包：" + name);
                }
            });

    private final ActivityResultLauncher<String[]> keyLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    importKeys(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool);
        bindCommonViews();
        setContainer(R.id.container);
        outDir = new File(getExternalFilesDir(null), "unpacked");
        setStatus("选择 ROM 包后即可在主手机上直接解包，无需电脑");
        buildUi();
    }

    private void buildUi() {
        LinearLayout container = findViewById(R.id.container);

        addSection("解包设置");
        etPartitions = new AppCompatEditText(this);
        etPartitions.setHint("只解这些分区（留空=全部），逗号分隔，如 boot,init_boot,vbmeta");
        etPartitions.setSingleLine(true);
        etPartitions.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        etPartitions.setTextSize(13f);
        styleInput(etPartitions);
        etPartitions.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (container != null) {
            container.addView(etPartitions);
        }

        addButton("① 选择 ROM 包（zip / payload.bin / ofp / ops / ozip）",
                v -> romLauncher.launch(new String[]{"*/*"}));

        addSection("执行");
        addButton("② 开始解包", v -> startUnpack());

        addButton("解出后：合并 super 分片（super.0/1/2 → super.img）", v -> mergeSuper());

        addButton("导入 OPPO / 一加密钥表（解 ofp / ops 用）", v ->
                keyLauncher.launch(new String[]{"text/plain", "*/*"}));

        addButton("打线刷中心（用解出的镜像刷机）", v ->
                startActivity(new Intent(this, FlashCenterActivity.class)));

        tvResult = new TextView(this);
        tvResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvResult.setTextSize(12f);
        tvResult.setText("解包结果会显示在这里");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        tvResult.setLayoutParams(lp);
        if (container != null) {
            container.addView(tvResult);
        }
    }

    private void startUnpack() {
        if (romUri == null) {
            toast("请先选择 ROM 包");
            return;
        }
        String text = etPartitions.getText() == null ? "" : etPartitions.getText().toString().trim();
        List<String> want = new ArrayList<String>();
        if (!TextUtils.isEmpty(text)) {
            for (String part : text.split("[,，\\s]+")) {
                if (!part.isEmpty()) {
                    want.add(part);
                }
            }
        }
        String name = FileUtils.displayName(this, romUri);
        setStatus("正在解包：" + name + "（大包请耐心等待，不要切走应用）");
        Tasks.submit(() -> {
            File input = cacheRom();
            List<RomUnpacker.Output> list = RomUnpacker.unpack(this, input, outDir, want,
                    new RomUnpacker.Listener() {
                        @Override
                        public void onLog(String message) {
                            log(message);
                        }

                        @Override
                        public void onProgress(String item, int percent) {
                            if (percent % 20 == 0) {
                                log("进度 " + item + "：" + percent + "%");
                            }
                        }
                    });
            return list;
        }, new Tasks.Callback<List<RomUnpacker.Output>>() {
            @Override
            public void onResult(List<RomUnpacker.Output> list) {
                outputs.clear();
                outputs.addAll(list);
                StringBuilder sb = new StringBuilder("解包完成，共 " + list.size() + " 个文件：\n");
                for (RomUnpacker.Output o : list) {
                    sb.append("· ").append(o.name).append("（")
                            .append(RomUnpacker.humanSize(o.size)).append("）\n");
                }
                sb.append("\n输出目录：").append(outDir.getAbsolutePath());
                tvResult.setText(sb.toString());
                setStatus("解包完成：" + list.size() + " 个文件");
                toast("解包完成");
            }

            @Override
            public void onError(Throwable error) {
                log("解包失败：" + error.getMessage());
                setStatus("解包失败：" + error.getMessage());
            }
        });
    }

    /** 把选择的 ROM 复制到私有目录，便于随机访问。 */
    private File cacheRom() throws Exception {
        File dir = new File(getCacheDir(), "rom");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建缓存目录");
        }
        File file = new File(dir, FileUtils.displayName(this, romUri).replace(" ", "_"));
        if (file.exists() && file.length() > 0 && file.length() == FileUtils.size(this, romUri)) {
            return file;
        }
        InputStream in = getContentResolver().openInputStream(romUri);
        if (in == null) {
            throw new Exception("无法读取所选文件");
        }
        FileOutputStream out = new FileOutputStream(file);
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.close();
        in.close();
        log("已缓存 ROM 到本地：" + file.getName() + "（" + RomUnpacker.humanSize(file.length()) + "）");
        return file;
    }

    private void mergeSuper() {
        List<File> segments = new ArrayList<File>();
        File[] files = outDir.listFiles();
        if (files != null) {
            List<File> sorted = new ArrayList<File>(Arrays.asList(files));
            java.util.Collections.sort(sorted, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : sorted) {
                String lower = f.getName().toLowerCase(Locale.US);
                if (lower.startsWith("super") && lower.endsWith(".img")) {
                    segments.add(f);
                }
            }
        }
        if (segments.isEmpty()) {
            toast("没有找到 super 分片（super.*.img）");
            return;
        }
        Set<String> confirm = new LinkedHashSet<String>();
        for (File f : segments) {
            confirm.add(f.getName());
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("合并 super 分片")
                .setMessage("将按文件名顺序合并以下 " + segments.size() + " 个分片：\n"
                        + TextUtils.join("\n", confirm)
                        + "\n\n注意：不同运营商 / 地区的分片不能混着合并，请确认它们来自同一版本。")
                .setNegativeButton("取消", null)
                .setPositiveButton("合并", (dialog, which) -> doMerge(segments))
                .show();
    }

    private void doMerge(List<File> segments) {
        setStatus("正在合并 super 分片……");
        Tasks.submit(() -> {
            File out = new File(outDir, "super.img");
            RandomAccessFile raf = new RandomAccessFile(out, "rw");
            raf.setLength(0);
            raf.close();
            com.violet.toolbox.format.SparseImage.mergeSegments(segments, out);
            return out;
        }, new Tasks.Callback<File>() {
            @Override
            public void onResult(File file) {
                log("已合并为 " + file.getName() + "（" + RomUnpacker.humanSize(file.length()) + "）");
                outputs.add(new RomUnpacker.Output(file.getName(), file, file.length()));
                setStatus("已生成 super.img");
            }

            @Override
            public void onError(Throwable error) {
                log("合并失败：" + error.getMessage());
                setStatus("合并失败：" + error.getMessage());
            }
        });
    }

    private void importKeys(Uri uri) {
        Tasks.submit(() -> {
            File dir = new File(getCacheDir(), "keys");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("无法创建目录");
            }
            File file = new File(dir, "oplus_keys.txt");
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new Exception("无法读取密钥表");
            }
            FileOutputStream out = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            OplusKeys.load(file);
            return OplusKeys.count();
        }, new Tasks.Callback<Integer>() {
            @Override
            public void onResult(Integer count) {
                log("已导入密钥 " + count + " 条，现在可以重试解包 ofp / ops 文件。");
                toast("已导入 " + count + " 条密钥");
            }

            @Override
            public void onError(Throwable error) {
                log("导入失败：" + error.getMessage());
            }
        });
    }
}
