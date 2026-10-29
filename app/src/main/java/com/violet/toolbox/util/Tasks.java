package com.violet.toolbox.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import android.os.Handler;
import android.os.Looper;

/**
 * 后台任务与主线程切换工具。
 */
public final class Tasks {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "violet-io");
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Tasks() {
    }

    public interface Job<T> {
        T run() throws Exception;
    }

    public interface Callback<T> {
        void onResult(T value);

        void onError(Throwable error);
    }

    public static <T> Future<?> submit(Job<T> job, Callback<T> callback) {
        return IO.submit(() -> {
            try {
                T value = job.run();
                MAIN.post(() -> callback.onResult(value));
            } catch (Throwable e) {
                MAIN.post(() -> callback.onError(e));
            }
            return null;
        });
    }

    public static void run(Runnable r) {
        MAIN.post(r);
    }
}
