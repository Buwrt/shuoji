package com.violet.toolbox.flash;

import android.content.Context;

import com.violet.toolbox.core.StreamSource;
import com.violet.toolbox.fastboot.FastbootUsb;
import com.violet.toolbox.util.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 刷写执行引擎：负责分段、传输与进度回调（全部中文日志）。
 */
public final class FlashEngine {

    /** 单段默认上限（同时受设备 max-download-size 限制）。 */
    public static final long DEFAULT_SEGMENT = 128L * 1024 * 1024;
    private static final int BLOCK_SIZE = 4096;

    public static final class PlanItem {
        public final String partition;
        public final StreamSource source;
        public final boolean disableVerity;
        public final boolean disableVerification;

        public PlanItem(String partition, StreamSource source,
                        boolean disableVerity, boolean disableVerification) {
            this.partition = partition;
            this.source = source;
            this.disableVerity = disableVerity;
            this.disableVerification = disableVerification;
        }
    }

    public interface Callback {
        void onLog(String message);

        void onProgress(String partition, long done, long total);
    }

    private FlashEngine() {
    }

    public static void execute(Context context, FastbootUsb fastboot, List<PlanItem> items,
                               Callback callback) throws IOException {
        long maxDownload = fastboot.getMaxDownloadSize();
        long segmentLimit = Math.min(maxDownload, DEFAULT_SEGMENT);
        if (callback != null) {
            callback.onLog("设备单次下载上限：" + (maxDownload == Long.MAX_VALUE
                    ? "未限制" : FileUtils.humanSize(maxDownload)));
        }

        int index = 0;
        for (PlanItem item : items) {
            index++;
            if (callback != null) {
                callback.onLog("[" + index + "/" + items.size() + "] 开始刷写 " + item.partition
                        + "（" + item.source.name() + "，" + FileUtils.humanSize(item.source.size()) + "）");
            }
            long size = item.source.size();
            if (size > segmentLimit || size <= 0) {
                flashBySegments(context, fastboot, item, segmentLimit, callback);
            } else {
                InputStream in = item.source.open();
                try {
                    flashOne(fastboot, item, in, size, 0, callback);
                } finally {
                    in.close();
                }
            }
            if (callback != null) {
                callback.onLog("分区 " + item.partition + " 刷写完成");
            }
        }
    }

    private static void flashBySegments(Context context, FastbootUsb fastboot, PlanItem item,
                                        long segmentLimit, Callback callback) throws IOException {
        List<File> segments = SparseSplitter.split(context, item.source, segmentLimit, BLOCK_SIZE);
        if (callback != null) {
            callback.onLog("镜像较大，已切分为 " + segments.size() + " 段逐段刷写");
        }
        try {
            long doneBefore = 0;
            long total = item.source.size();
            for (int i = 0; i < segments.size(); i++) {
                File f = segments.get(i);
                final long base = doneBefore;
                InputStream in = new FileInputStream(f);
                try {
                    if (callback != null) {
                        callback.onLog("  第 " + (i + 1) + "/" + segments.size() + " 段（"
                                + FileUtils.humanSize(f.length()) + "）");
                    }
                    flashOne(fastboot, item, in, f.length(), base, callback);
                } finally {
                    in.close();
                }
                doneBefore += f.length();
                f.delete();
                if (callback != null && total > 0) {
                    callback.onProgress(item.partition, Math.min(doneBefore, total), total);
                }
            }
        } finally {
            SparseSplitter.clean(segments);
        }
    }

    private static void flashOne(FastbootUsb fastboot, PlanItem item, InputStream in, long size,
                                 long base, Callback callback) throws IOException {
        FastbootUsb.Progress progress = (done, total) -> {
            if (callback != null) {
                callback.onProgress(item.partition, base + done, Math.max(total, size));
            }
        };
        if ("vbmeta".equals(item.partition) && (item.disableVerity || item.disableVerification)) {
            fastboot.flashVbmeta(in, size, item.disableVerity, item.disableVerification, progress);
        } else {
            fastboot.flash(item.partition, in, size, progress);
        }
    }

    /** 回读分区（fastboot 不支持直接回读，需借助 ADB 或 fastbootd 的 fetch，这里给出中文提示）。 */
    public static String backupHint(String partition) {
        return "备份 " + partition + "：请在副手机开机并授权 ADB 后，使用本应用的「ADB 工具箱 - 一键采集/回读分区」功能，"
                + "或先用 fastboot boot 临时启动第三方 Recovery 再做完整备份。";
    }
}
