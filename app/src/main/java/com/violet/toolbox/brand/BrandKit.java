package com.violet.toolbox.brand;

import java.util.Locale;
import java.util.Map;

/**
 * 品牌识别与品牌专属刷机要点（中文提示）。
 * 参考 PC 端紫罗兰工具箱对各品牌的处理方式。
 */
public final class BrandKit {

    public enum Brand {
        XIAOMI("小米 / Redmi"),
        OPPO("OPPO"),
        ONEPLUS("一加"),
        REALME("真我 realme"),
        OTHER("其它 / 通用");

        public final String label;

        Brand(String label) {
            this.label = label;
        }
    }

    private BrandKit() {
    }

    /** 从 getprop 结果或 fastboot 变量中识别品牌。 */
    public static Brand detect(Map<String, String> props) {
        if (props == null) {
            return Brand.OTHER;
        }
        String text = "";
        for (String key : new String[]{
                "ro.product.brand", "ro.product.manufacturer", "ro.product.odm.brand",
                "ro.product.vendor.brand", "ro.product.product.brand", "product",
                "ro.product.device", "ro.product.name", "ro.product.model"}) {
            String v = props.get(key);
            if (v != null && v.length() > 0) {
                text += " " + v.toLowerCase(Locale.US);
            }
        }
        if (text.contains("redmi") || text.contains("xiaomi") || text.contains("poco")) {
            return Brand.XIAOMI;
        }
        if (text.contains("oneplus") || text.contains("oppo") && text.contains("oneplus")) {
            return Brand.ONEPLUS;
        }
        if (text.contains("realme") || text.contains("rmx") || text.contains("rmt")) {
            return Brand.REALME;
        }
        if (text.contains("oppo") || text.contains("oplus") || text.contains("coloros")
                || text.contains("cph") || text.contains("pht") || text.contains("pgc")) {
            return Brand.OPPO;
        }
        return Brand.OTHER;
    }

    /** Root 修补时应该刷的分区：Android 13+ 且有 init_boot 分区的机型刷 init_boot，否则刷 boot。 */
    public static String rootPartition(Map<String, String> props, boolean hasInitBoot) {
        Brand brand = detect(props);
        int sdk = sdkLevel(props);
        if (hasInitBoot || sdk >= 33) {
            return "init_boot";
        }
        if (brand == Brand.XIAOMI && sdk >= 31) {
            return "init_boot";
        }
        return "boot";
    }

    public static int sdkLevel(Map<String, String> props) {
        if (props == null) {
            return 0;
        }
        String sdk = props.get("ro.build.version.sdk");
        if (sdk == null) {
            sdk = props.get("ro.system.build.version.sdk");
        }
        try {
            return Integer.parseInt(sdk.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 各品牌解锁/刷机的关键提示。 */
    public static String tips(Brand brand) {
        switch (brand) {
            case XIAOMI:
                return "小米 / Redmi：需要在副手机登录小米账号并绑定设备，通过「设置 - 我的设备 - 全部参数」"
                        + "或在系统更新中申请解锁资格；解锁会清空数据。线刷请使用官方 fastboot 线刷包 "
                        + "（含 flash_all.bat / flash_all.sh），本应用可直接解析执行。";
            case OPPO:
                return "OPPO：ColorOS 官方已收紧 Bootloader 解锁，多数新机型无法解锁；"
                        + "可在 fastbootd 下刷写官方散包或 init_boot 修补镜像，OFP / OPS 格式请先用电脑转换。";
            case ONEPLUS:
                return "一加：部分机型支持 fastboot oem unlock；ColorOS 13 及以上优先刷 init_boot 获取 Root。"
                        + "刷写 super 需进入 fastbootd（本应用可自动切换）。";
            case REALME:
                return "真我 realme：解锁需官方深度测试申请；线刷散包需在 fastbootd 下刷写 super 分区。";
            default:
                return "通用机型：请先解锁 Bootloader，再按刷写计划逐分区写入；"
                        + "刷写 super 等动态分区必须处于 fastbootd 模式。";
        }
    }

    public static String[] brands() {
        Brand[] values = Brand.values();
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].label;
        }
        return out;
    }
}
