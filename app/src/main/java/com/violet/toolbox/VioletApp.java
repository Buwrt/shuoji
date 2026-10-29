package com.violet.toolbox;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.violet.toolbox.core.LogBus;

/**
 * 应用入口：强制深色主题（保证配色一致），并初始化全局日志（中文）。
 */
public class VioletApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 固定深色，避免跟随系统亮色模式导致深色 UI 上的白字看不清
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        LogBus.get().log("紫罗兰工具箱（手机版）已启动");
        LogBus.get().log("请用 OTG 线连接主手机与副手机（副手机需开启 USB 调试）");
    }
}
