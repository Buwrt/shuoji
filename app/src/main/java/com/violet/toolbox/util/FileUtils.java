package com.violet.toolbox.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.violet.toolbox.core.StreamSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Uri / 文件工具。
 */
public final class FileUtils {

    private FileUtils() {
    }

    public static String displayName(Context context, Uri uri) {
        String result = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            Cursor c = null;
            try {
                c = context.getContentResolver().query(uri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        result = c.getString(idx);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result == null ? "unknown.img" : result;
    }

    public static long size(Context context, Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            Cursor c = null;
            try {
                c = context.getContentResolver().query(uri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.SIZE);
                    if (idx >= 0) {
                        return c.getLong(idx);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        return -1;
    }

    /** 把 Uri 包装成可重复打开的 StreamSource。 */
    public static StreamSource fromUri(Context context, Uri uri) {
        String name = displayName(context, uri);
        long len = size(context, uri);
        return new StreamSource() {
            @Override
            public InputStream open() throws IOException {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IOException("无法打开文件：" + name);
                }
                return in;
            }

            @Override
            public long size() {
                return len;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    public static StreamSource fromFile(File file) {
        return new StreamSource() {
            @Override
            public InputStream open() throws IOException {
                return new FileInputStream(file);
            }

            @Override
            public long size() {
                return file.length();
            }

            @Override
            public String name() {
                return file.getName();
            }
        };
    }

    /** 取文件名（去掉 Windows 路径前缀与目录）。 */
    public static String baseName(String path) {
        String p = path.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        if (idx >= 0) {
            p = p.substring(idx + 1);
        }
        return p.trim();
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
