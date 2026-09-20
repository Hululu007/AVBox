package com.github.tvbox.osc.util;

import android.util.Log;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class LOG {
    private static String TAG = "TVBox-runtime";
    private static final int MAX_LOG_LENGTH = 3000;

    private static final boolean FILE_LOG = BuildConfig.DEBUG;
    private static final String[] FILE_LOG_PREFIXES = {"echo-preload", "echo-setDataSource", "echo-play-cache", "echo-kv", "echo-exo-cache", "echo-music", "echo-lyric", "echo-sub", "echo-danmu", "echo-p3", "echo-p4", "echo-p5", "clearCache", "echo--jar", "echo-local-src"};
    private static final String FILE_LOG_NAME = "preload_debug.log";
    private static ExecutorService fileLogExecutor;

    private static void fileLog(String level, String msg) {
        if (!FILE_LOG || msg == null) return;
        boolean match = false;
        for (String prefix : FILE_LOG_PREFIXES) {
            if (msg.startsWith(prefix)) {
                match = true;
                break;
            }
        }
        if (!match) return;
        final String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + level + " " + msg;
        synchronized (LOG.class) {
            if (fileLogExecutor == null) fileLogExecutor = Executors.newSingleThreadExecutor();
        }
        try {
            fileLogExecutor.execute(() -> {
                // 每行独立开关文件:保证进程被杀时已写入的内容不丢(排查场景量小,开销可接受)
                try (FileWriter writer = new FileWriter(new File(App.getInstance().getFilesDir(), FILE_LOG_NAME), true)) {
                    writer.write(line + "\n");
                } catch (Throwable ignored) {
                    // 落盘失败即放弃:fileLog 自身兜底,不能再走 LOG 以免递归
                }
            });
        } catch (Throwable ignored) {
            // 同上:提交落盘任务失败即放弃,防递归
        }
    }

    public static void e(String msg) {
        Log.e(TAG, "" + msg);
        fileLog("E", String.valueOf(msg));
    }

    public static void i(String msg) {
        Log.i(TAG, "" + msg);
        fileLog("I", String.valueOf(msg));
    }

    /** 带模块标签的 debug 日志:"有意忽略"的 catch 用它记录忽略原因 */
    public static void d(String tag, String msg) {
        Log.d(TAG, tag + ": " + msg);
        fileLog("D", String.valueOf(msg));
    }

    /**
     * 带模块标签的错误日志(含异常栈):异常无法恢复但不应静默时使用。
     * tr 同步落 System.err —— 本机 vivo ROM 吞 Logcat 时仍可见(见 MEMORY 排查记录)。
     */
    public static void e(String tag, String msg, Throwable tr) {
        Log.e(TAG, tag + ": " + msg, tr);
        fileLog("E", String.valueOf(msg));
        if (tr != null) tr.printStackTrace();
    }

    public static void longI(String prefix, String msg) {
        longLog(Log.INFO, prefix, msg);
    }

    public static void longE(String prefix, String msg) {
        longLog(Log.ERROR, prefix, msg);
    }

    private static void longLog(int priority, String prefix, String msg) {
        String text = msg == null ? "null" : msg;
        String title = prefix == null ? "" : prefix;
        int length = text.length();
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text);
            return;
        }
        int count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH;
        for (int i = 0; i < count; i++) {
            int start = i * MAX_LOG_LENGTH;
            int end = Math.min(start + MAX_LOG_LENGTH, length);
            Log.println(priority, TAG, title + "[" + (i + 1) + "/" + count + "] " + text.substring(start, end));
        }
    }
}
