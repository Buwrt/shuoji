package com.violet.toolbox;

import android.app.Application;

import com.violet.toolbox.core.LogBus;

/**
 * 应用入口：初始化全局日志（中文）。
 */
public class VioletApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        LogBus.get().log("紫罗兰工具箱（手机版）已启动");
        LogBus.get().log("请用 OTG 线连接主手机与副手机（副手机需开启 USB 调试）");
    }
}
