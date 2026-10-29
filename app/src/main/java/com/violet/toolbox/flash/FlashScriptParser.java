package com.violet.toolbox.flash;

import android.text.TextUtils;

import com.violet.toolbox.util.FileUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 线刷脚本解析器：识别 flash_all.bat / flash_all.sh 中的 fastboot 刷写行。
 *
 * 支持小米、OPPO、一加、真我等常见脚本写法，例如：
 *   fastboot %* flash boot %~dp0images\boot.img
 *   fastboot $* flash super images/super.img
 *   fastboot --disable-verity flash vbmeta vbmeta.img
 */
public final class FlashScriptParser {

    /** 按常见线刷顺序排列的分区。 */
    public static final List<String> KNOWN_PARTITIONS = Arrays.asList(
            "boot", "init_boot", "vendor_boot", "dtbo", "vbmeta", "vbmeta_system",
            "vbmeta_vendor", "recovery", "super", "odm", "product", "system_ext",
            "vendor", "system", "cust", "mi_ext", "vendor_dlkm", "odm_dlkm", "system_dlkm"
    );

    /** 这些分区必须刷入 fastbootd（用户态）。 */
    public static final List<String> DYNAMIC_PARTITIONS = Arrays.asList(
            "super", "system", "vendor", "product", "odm", "system_ext",
            "vendor_dlkm", "odm_dlkm", "system_dlkm", "mi_ext", "cust"
    );

    public static final class Item {
        public String partition;
        public String file;
        public boolean disableVerity;
        public boolean disableVerification;
        public boolean valid = true;
        public String rawLine;

        @Override
        public String toString() {
            return "fastboot flash " + partition + " " + file
                    + (disableVerity ? "（关闭 verity）" : "");
        }
    }

    private FlashScriptParser() {
    }

    public static List<Item> parse(String script) {
        List<Item> items = new ArrayList<Item>();
        if (TextUtils.isEmpty(script)) {
            return items;
        }
        String[] lines = script.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("rem ")
                    || line.startsWith("::") || line.startsWith("@echo")) {
                continue;
            }
            if (!line.toLowerCase(Locale.US).contains("fastboot")) {
                continue;
            }
            List<String> tokens = new ArrayList<String>(Arrays.asList(line.split("\\s+")));
            int flashIdx = -1;
            for (int i = 0; i < tokens.size(); i++) {
                if ("flash".equalsIgnoreCase(tokens.get(i))) {
                    flashIdx = i;
                    break;
                }
            }
            if (flashIdx < 0 || flashIdx + 2 >= tokens.size()) {
                continue;
            }
            Item item = new Item();
            item.partition = tokens.get(flashIdx + 1).trim();
            String file = FileUtils.baseName(tokens.get(flashIdx + 2));
            if (file.isEmpty() || file.startsWith("%") || file.startsWith("$")) {
                continue;
            }
            String lower = file.toLowerCase(Locale.US);
            if (lower.endsWith(".txt") || lower.endsWith(".elf") || lower.endsWith(".cfg")
                    || lower.endsWith(".mbn") || lower.endsWith(".bin") || lower.endsWith(".fv")
                    || lower.endsWith(".xml") || lower.endsWith(".bat") || lower.endsWith(".sh")) {
                continue; // 非 fastboot 镜像，跳过
            }
            item.file = file;
            item.rawLine = line;
            for (int i = 0; i < flashIdx; i++) {
                String t = tokens.get(i).toLowerCase(Locale.US);
                if (t.contains("disable-verity")) {
                    item.disableVerity = true;
                }
                if (t.contains("disable-verification")) {
                    item.disableVerification = true;
                }
            }
            items.add(item);
        }
        return items;
    }

    /** 没有脚本时，按镜像文件名推断刷写顺序。 */
    public static List<Item> guess(List<String> imageNames) {
        List<Item> items = new ArrayList<Item>();
        List<String> rest = new ArrayList<String>();
        for (String name : imageNames) {
            String lower = name.toLowerCase(Locale.US);
            if (!lower.endsWith(".img")) {
                continue;
            }
            String base = lower.substring(0, lower.length() - 4);
            if (!KNOWN_PARTITIONS.contains(base)) {
                rest.add(name);
            }
        }
        for (String partition : KNOWN_PARTITIONS) {
            for (String name : imageNames) {
                String lower = name.toLowerCase(Locale.US);
                if (lower.endsWith(".img") && lower.substring(0, lower.length() - 4).equals(partition)) {
                    Item item = new Item();
                    item.partition = partition;
                    item.file = name;
                    if ("vbmeta".equals(partition)) {
                        item.disableVerity = true;
                        item.disableVerification = true;
                    }
                    items.add(item);
                }
            }
        }
        for (String name : rest) {
            String lower = name.toLowerCase(Locale.US);
            Item item = new Item();
            item.partition = lower.substring(0, lower.length() - 4);
            item.file = name;
            items.add(item);
        }
        return items;
    }

    /** 判断该计划是否需要切换到 fastbootd（含动态分区）。 */
    public static boolean needsFastbootd(List<Item> items) {
        if (items == null) {
            return false;
        }
        for (Item item : items) {
            String p = item.partition.toLowerCase(Locale.US);
            for (String d : DYNAMIC_PARTITIONS) {
                if (p.equals(d) || p.startsWith(d + "_")) {
                    return true;
                }
            }
        }
        return false;
    }
}
