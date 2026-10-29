package com.violet.toolbox.core;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局运行日志（界面显示固定为中文）。
 */
public final class LogBus {

    public interface Listener {
        void onLog(String line);
    }

    private static final LogBus INSTANCE = new LogBus();
    private static final int MAX_BUFFER = 120000;

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final StringBuilder buffer = new StringBuilder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private LogBus() {
    }

    public static LogBus get() {
        return INSTANCE;
    }

    public void log(String msg) {
        String line = "[" + fmt.format(new Date()) + "] " + msg;
        synchronized (buffer) {
            buffer.append(line).append('\n');
            if (buffer.length() > MAX_BUFFER) {
                buffer.delete(0, buffer.length() - MAX_BUFFER / 2);
            }
        }
        for (Listener l : listeners) {
            main.post(() -> l.onLog(line));
        }
    }

    public void clear() {
        synchronized (buffer) {
            buffer.setLength(0);
        }
    }

    public String getText() {
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }
}
