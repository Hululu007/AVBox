package com.github.tvbox.osc.util;

import android.os.SystemClock;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;

/**
 * 启动看门狗:坏源把应用锁进"一启动就崩"的死循环时,下次启动自动停用它(2026-09-21)。
 *
 * <p>场景:第三方爬虫在静态初始化里把 CDN 的报错页当 {@code .so} 加载 ⇒ 每次冷启动必崩,
 * 而源地址是持久化的,用户连"换源"都进不去,只能清数据。崩在爬虫自己的线程上,接不住异常。
 *
 * <p>判据:①崩溃发生在"开始加载 jar 后 10 秒内"⇒ 一次即停用;②同一源累计装载 3 次 ⇒ 停用。
 * 判据①比的是**最近一次**开始装载的时刻,不是"本进程第一次装载" —— 会话中途换仓/换源切到坏源
 * 也是装载阶段崩,拿进程第一次装载当起点会把差值算成几分钟,于是要崩两次才停用。
 * 停用只清启动指针与仓列表,**不动订阅列表**;同时把源地址记进黑名单
 * ({@link HawkConfig#BOOT_DISABLED_SOURCES}),让界面能标出来、仓改写能绕开它。
 */
public final class BootGuard {

    /** 开始加载 jar 后这么久之内崩溃,算"崩在装载阶段"(实测是 28 毫秒) */
    private static final long QUICK_CRASH_MS = 10_000L;

    /** 距上次同源装载超过这么久视为另一批问题,重新计数 */
    private static final long ATTEMPT_WINDOW_MS = 10 * 60_000L;

    /** 连续存活这么久即认定为稳定源,清掉计数 */
    private static final long STABLE_RUN_MS = 10 * 60_000L;

    /** 同一源累计装载这么多次就认为在空转(兜底判据;正常启动同源只装 1~2 次) */
    private static final int MAX_LOAD_ATTEMPTS = 3;

    private static final String CRASH_MARKER_NAME = "boot_crash.marker";

    private BootGuard() {
    }

    /** 在 Application.onCreate 里装:先记录,再原样交回原处理器 */
    public static void install() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrashMarker();
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    /**
     * 同步写崩溃标记(内容 = 崩溃时的开机计时)。
     *
     * <p>不用 KV:MMKV 是异步写、2.4.2 又没有同步写 flag,实测崩溃路径上的 KV 写入会丢,
     * 导致判据从未成立。崩溃路径必须用同步文件 IO。
     */
    private static void writeCrashMarker() {
        try {
            File file = crashMarkerFile();
            if (file == null) return;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(String.valueOf(SystemClock.elapsedRealtime()).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Throwable ignored) {
            // 崩溃路径上不能再抛
        }
    }

    private static File crashMarkerFile() {
        try {
            return new File(App.getInstance().getFilesDir(), CRASH_MARKER_NAME);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 读取并删除崩溃标记,返回其中的开机计时;无标记返回 -1(读后即删:一次崩溃只算一次) */
    private static long takeCrashMarkerElapsed() {
        try {
            File file = crashMarkerFile();
            if (file == null || !file.exists()) return -1L;
            byte[] buf = new byte[32];
            int len;
            try (FileInputStream in = new FileInputStream(file)) {
                len = in.read(buf);
            }
            long value = -1L;
            if (len > 0) {
                try {
                    value = Long.parseLong(new String(buf, 0, len, StandardCharsets.UTF_8).trim());
                } catch (NumberFormatException ignored) {
                    value = -1L;
                }
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return value;
        } catch (Throwable e) {
            return -1L;
        }
    }

    /**
     * 开始加载某个 jar:记下"最近一次装载起点",同源累计计数,换源则从 1 重新计。
     *
     * <p>起点**每次装载都覆盖**(与 {@code BOOT_LOADING_JAR} 保持同一批数据):只记本进程第一次的话,
     * 会话中途换仓切到坏源崩掉时,崩溃时刻减装载起点是几分钟 ⇒ 判不出装载阶段崩溃,要崩两次才停用。
     * 代价是"非装载期的崩溃若正好落在某次装载后 10 秒内"会被误算 —— 但误算的后果已被压到
     * "源被标记已禁用、二次确认即可恢复"(见黑名单),不再是静默清掉启动指针。
     */
    public static void onJarLoadStart(String jarUrl) {
        try {
            if (isEmpty(jarUrl)) return;
            recordCurrentSource();
            long now = System.currentTimeMillis();
            KV.put(HawkConfig.BOOT_LOAD_START_ELAPSED, SystemClock.elapsedRealtime());
            String previous = KV.get(HawkConfig.BOOT_LOADING_JAR, "");
            long lastAttemptAt = KV.get(HawkConfig.BOOT_LAST_ATTEMPT_AT, 0L);
            boolean stale = lastAttemptAt > 0 && now - lastAttemptAt > ATTEMPT_WINDOW_MS;
            long count = (previous.equals(jarUrl) && !stale) ? KV.get(HawkConfig.BOOT_LOADING_COUNT, 0L) + 1L : 1L;
            KV.put(HawkConfig.BOOT_LOADING_COUNT, count);
            KV.put(HawkConfig.BOOT_LOADING_JAR, jarUrl);
            KV.put(HawkConfig.BOOT_LAST_ATTEMPT_AT, now);
        } catch (Throwable ignored) {
            // 看门狗自身失败不能影响正常加载
        }
    }

    /**
     * 存活满 {@link #STABLE_RUN_MS} 未崩 ⇒ 清计数。
     *
     * <p>刻意不在"装载成功"时就清:爬虫的 {@code <clinit>} 在另一个线程,装载线程先报成功、
     * 28 毫秒后才崩,早清等于擦掉唯一证据。
     */
    public static void scheduleStableRunReset() {
        try {
            mainHandler.removeCallbacks(stableReset);
            mainHandler.postDelayed(stableReset, STABLE_RUN_MS);
        } catch (Throwable ignored) {
        }
    }

    private static final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static final Runnable stableReset = () -> {
        try {
            KV.put(HawkConfig.BOOT_LOADING_JAR, "");
            KV.put(HawkConfig.BOOT_LOADING_COUNT, 0L);
        } catch (Throwable ignored) {
        }
    };

    /** 记下"此刻哪个源是启动源":崩了就是它(拿 jar 地址反查源对不上号,同一个 jar 常被多个源引用) */
    private static void recordCurrentSource() {
        KV.put(HawkConfig.BOOT_VOD_SOURCE, KV.get(HawkConfig.API_URL, ""));
        KV.put(HawkConfig.BOOT_LIVE_SOURCE, KV.get(HawkConfig.LIVE_API_URL, ""));
    }

    /**
     * 启动早期(加载任何 jar 之前)调用。
     *
     * @return 被停用的源地址;没有则空串
     */
    public static String disableBootLoopingSource() {
        try {
            String loading = KV.get(HawkConfig.BOOT_LOADING_JAR, "");
            long count = KV.get(HawkConfig.BOOT_LOADING_COUNT, 0L);
            // takeCrashMarkerElapsed 读完即删,故判定结果只算一次再复用
            long crashElapsed = takeCrashMarkerElapsed();
            long loadStartElapsed = KV.get(HawkConfig.BOOT_LOAD_START_ELAPSED, 0L);
            boolean startupCrash = crashedDuringStartup(crashElapsed, loadStartElapsed);
            if (!shouldDisable(loading, count, crashElapsed, startupCrash)) {
                return "";
            }
            LOG.i("boot-guard: disable looping source attempt=" + count
                    + " startupCrash=" + startupCrash + " jar=" + loading);
            disableRecordedSource();
            KV.put(HawkConfig.BOOT_LOADING_JAR, "");
            KV.put(HawkConfig.BOOT_LOADING_COUNT, 0L);
            KV.put(HawkConfig.BOOT_LOAD_START_ELAPSED, 0L);
            return loading;
        } catch (Throwable e) {
            LOG.i("boot-guard failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * 停用判定(纯函数,便于单测):崩在装载阶段 **或** 累计装载达 {@link #MAX_LOAD_ATTEMPTS} 次。
     *
     * <p>{@code startupCrash} 由调用方传入:算它要读并删除崩溃标记,只能读一次。
     */
    static boolean shouldDisable(String jar, long count, long crashElapsed, boolean startupCrash) {
        if (isEmpty(jar) || crashElapsed <= 0) return false;
        return startupCrash || count >= MAX_LOAD_ATTEMPTS;
    }

    /**
     * 崩溃是否落在"开始加载 jar 后 {@link #QUICK_CRASH_MS} 内"(两个值同为开机计时,可跨进程比较)。
     *
     * <p>名字与日志字段里的 "startup" 是沿用的历史叫法,别按字面理解成"应用启动":判据现在比的是
     * **最近一次**装载(见 {@link #onJarLoadStart}),会话中途换仓/换源同样是装载阶段崩。
     * 名字与日志字段刻意不改 —— 既有真机日志与 `history/` 归档里都是这个字段名,改了对不上号。
     */
    static boolean crashedDuringStartup(long crashElapsed, long loadStartElapsed) {
        return loadStartElapsed > 0 && crashElapsed >= loadStartElapsed
                && crashElapsed - loadStartElapsed <= QUICK_CRASH_MS;
    }

    /**
     * 与 TextUtils.isEmpty 等价。
     *
     * <p>不用 {@code android.text.TextUtils}:单测开了 returnDefaultValues,它会静默返回 false
     * (本仓已踩三次:ConfigParser、Depot、这里)。
     */
    private static boolean isEmpty(String text) {
        return text == null || text.length() == 0;
    }

    /** 停用崩溃时正在使用的启动源;直播优先(独立直播源时点播不受影响),只清指针与仓列表 */
    private static void disableRecordedSource() {
        String liveSource = KV.get(HawkConfig.BOOT_LIVE_SOURCE, "");
        String vodSource = KV.get(HawkConfig.BOOT_VOD_SOURCE, "");
        if (!liveSource.isEmpty()) {
            KV.put(HawkConfig.LIVE_API_URL, "");
            HistoryHelper.clearLiveApiLineList();
            KV.put(HawkConfig.BOOT_SAFE_DISABLED, liveSource);
            rememberDisabled(liveSource);
        } else if (!vodSource.isEmpty()) {
            KV.put(HawkConfig.API_URL, "");
            HistoryHelper.clearApiLineList();
            KV.put(HawkConfig.BOOT_SAFE_DISABLED, vodSource);
            rememberDisabled(vodSource);
        }
        KV.put(HawkConfig.BOOT_VOD_SOURCE, "");
        KV.put(HawkConfig.BOOT_LIVE_SOURCE, "");
    }

    /** 上一次自动停用的源地址(空串表示没有);UI 读后即清 */
    public static String takeSafeDisabledNotice() {
        try {
            String url = KV.get(HawkConfig.BOOT_SAFE_DISABLED, "");
            if (!url.isEmpty()) KV.put(HawkConfig.BOOT_SAFE_DISABLED, "");
            return url;
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ---- 风险源黑名单 ----
    // 停用只是"这次别用它",源地址还在订阅列表里、还能被用户点中 —— 名单让界面能标出"这个源崩过",
    // 并让仓改写绕开它,否则重新启用那个仓又会被改写到坏子源、再崩一次。

    /** 这个源地址是否在黑名单里 */
    public static boolean isDisabledSource(String url) {
        if (isEmpty(url)) return false;
        try {
            return disabledSources().contains(url.trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 黑名单快照(源地址);返回副本,调用方改它不会写回存储 */
    public static ArrayList<String> disabledSources() {
        try {
            return new ArrayList<>(KV.get(HawkConfig.BOOT_DISABLED_SOURCES, new ArrayList<String>()));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** 用户二次确认后把源移出名单(允许再试;真修好了就不会再进来,仍坏则下次启动重新记入) */
    public static void enableSource(String url) {
        if (isEmpty(url)) return;
        try {
            KV.put(HawkConfig.BOOT_DISABLED_SOURCES, removeDisabledSource(disabledSources(), url));
        } catch (Throwable ignored) {
        }
    }

    /** 订阅被删除时一起清掉名单记录,免得名单里堆着用户已经不要的地址 */
    public static void forgetSources(Collection<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        try {
            ArrayList<String> list = disabledSources();
            for (String url : urls) list = removeDisabledSource(list, url);
            KV.put(HawkConfig.BOOT_DISABLED_SOURCES, list);
        } catch (Throwable ignored) {
        }
    }

    private static void rememberDisabled(String url) {
        if (isEmpty(url)) return;
        try {
            KV.put(HawkConfig.BOOT_DISABLED_SOURCES, addDisabledSource(disabledSources(), url));
        } catch (Throwable ignored) {
        }
    }

    /** 记入名单(已存在则不重复);纯函数,便于单测 */
    static ArrayList<String> addDisabledSource(ArrayList<String> list, String url) {
        ArrayList<String> next = new ArrayList<>(list == null ? new ArrayList<String>() : list);
        if (isEmpty(url)) return next;
        String value = url.trim();
        if (!next.contains(value)) next.add(value);
        return next;
    }

    /** 移出名单;不在名单里就原样返回 */
    static ArrayList<String> removeDisabledSource(ArrayList<String> list, String url) {
        ArrayList<String> next = new ArrayList<>(list == null ? new ArrayList<String>() : list);
        if (isEmpty(url)) return next;
        next.remove(url.trim());
        return next;
    }
}
