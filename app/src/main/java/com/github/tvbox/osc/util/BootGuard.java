package com.github.tvbox.osc.util;

import android.os.SystemClock;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 启动看门狗:把用户从"坏源导致的开机必崩死循环"里救出来(2026-09-21)。
 *
 * <h3>要解决的问题(真机实测)</h3>
 * 某第三方仓的子源爬虫在 {@code GoProxy.<clinit>} 里从云存储下载原生库;远端对象已不存在时
 * CDN 返回一段 XML 报错,而爬虫把报错原文当 {@code .so} 落盘再 {@code System.load} ⇒
 * {@code UnsatisfiedLinkError: has bad ELF magic}({@code 3c3f786d} 就是 ASCII 的 {@code <?xm})。
 *
 * <p>致命之处在于它**自锁**:源地址是持久化的,该爬虫每次冷启动都会重新下载(实测它不判断
 * 文件是否已存在)再 load 一次 ⇒ 进一次崩一次,用户连"换源"这个操作都做不了,只能清数据。
 *
 * <p>为什么"清掉坏文件"解决不了:实测清理后 2 秒内爬虫又下了一遍同样的垃圾
 * (07:57:42 清理 → 07:57:44 又崩)。为什么接不住异常:崩在爬虫自己的线程上,不在我们的调用栈里。
 *
 * <h3>机制</h3>
 * <ol>
 *   <li>每次要加载某个 jar 前,记下"正在加载谁"、**此刻哪个源是启动源**、以及本次进程开始加载 jar 的
 *       开机计时({@link #onJarLoadStart});</li>
 *   <li>进程级 {@code UncaughtExceptionHandler} 把崩溃时的开机计时**同步写进一个标记文件**
 *       ({@link #writeCrashMarker})—— 必须同步文件 IO:MMKV 是异步写,实测崩溃路径上的 KV 写入会丢;</li>
 *   <li>下次启动、加载任何 jar 之前 {@link #disableBootLoopingSource()} 判定:
 *       崩溃发生在"开始加载 jar 后 10 秒内"⇒ **一次即停用**;否则累计装载 {@link #MAX_LOAD_ATTEMPTS} 次停用。</li>
 * </ol>
 *
 * <p>判定刻意收窄到"**同一个源** + 崩溃发生在启动加载阶段":偶发崩溃(网络、OOM、播放器)不会触发,
 * 只有"一启动就崩、崩完重启还是它"这种自锁才命中。停用只清启动指针、**不动订阅列表**,
 * 用户回到配置管理页即可重新启用或改选别的源。
 */
public final class BootGuard {

    /**
     * "启动即崩"的判定阈值:开始加载 jar 后这么久之内崩溃,算"在启动加载阶段崩"(2026-09-21)。
     *
     * <p>实测的自锁崩溃是"加载 jar 后 28 毫秒"发生的,所以 10 秒足够宽松;
     * 这类崩溃**一次就停用**(见 {@link #disableBootLoopingSource()}),不必让用户白崩第二次。
     */
    private static final long QUICK_CRASH_MS = 10_000L;

    /**
     * "连续"的判定窗口:距上次同源装载超过这么久,视为**另一批**问题,重新计数。
     *
     * <p>取 10 分钟的依据:自锁的特征是"启动后几秒内就崩",两次重启间隔通常 < 1 分钟;
     * 而一个源正常用了半小时才崩一次属于偶发,不该被停用。设备上实测两次重启之间隔了 1 分钟,
     * 早先的 60 秒窗口会把它们判成"不连续",计数归零 ⇒ 看门狗永远不触发。
     */
    private static final long ATTEMPT_WINDOW_MS = 10 * 60_000L;

    /** 连续存活这么久即认定为"稳定源",清掉崩溃计数 */
    private static final long STABLE_RUN_MS = 10 * 60_000L;

    /** 崩溃标记文件名(放在私有 files 目录;与 FileUtils 的原生库自检互不干扰) */
    private static final String CRASH_MARKER_NAME = "boot_crash.marker";

    private BootGuard() {
    }

    /** 在 Application.onCreate 里装崩溃记录器:先记录,再原样交回原处理器 */
    public static void install() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrashMarker();
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    /**
     * 同步写"崩溃标记"文件。
     *
     * <p>⚠️ 为什么不用 KV:MMKV 是异步写(排进 Scheduler 约 1 秒后落盘),2.4.2 也没有同步写 flag。
     * 实测进程级崩溃处理里写的 {@code boot_last_crash_at} **根本没落盘**(设备上一直停在几分钟前),
     * 于是"启动阶段崩一次就停用"的判据从未成立 —— 这是本机制第二次失效的原因。
     * 崩溃路径必须用**同步文件 IO**:{@code write} 返回即已进 page cache,进程随后被杀也不丢。
     */
    private static void writeCrashMarker() {
        try {
            File file = crashMarkerFile();
            if (file == null) return;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // 内容 = 崩溃发生时的开机计时(毫秒),启动时据此换算"上次崩溃发生在加载后多久"
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(String.valueOf(SystemClock.elapsedRealtime()).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Throwable ignored) {
            // 崩溃路径上不能再抛:记录失败就放弃,绝不能盖住原始异常
        }
    }

    private static File crashMarkerFile() {
        try {
            return new File(App.getInstance().getFilesDir(), CRASH_MARKER_NAME);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 读取并删除崩溃标记,返回其中的开机计时值;无标记返回 -1 */
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
            // 读完即删:一次崩溃只算一次;不删会让下次启动反复判同一次崩溃
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return len > 0 ? value : -1L;
        } catch (Throwable e) {
            return -1L;
        }
    }

    /**
     * 开始加载某个 jar:与上次同一个就累计尝试次数,换了源说明用户改过配置,计数归零。
     */
    public static void onJarLoadStart(String jarUrl) {
        try {
            if (isEmpty(jarUrl)) return;
            recordCurrentSource();
            long now = System.currentTimeMillis();
            // 启动标记只在**本次进程**里第一次进入本方法时重置:
            // 用它区分"这次崩溃发生在启动加载阶段",而不是"启动 5 秒后播放崩了、用户马上重开"
            if (sProcessStartWallMs <= 0) {
                sProcessStartWallMs = now;
                sProcessStartElapsedMs = SystemClock.elapsedRealtime();
                // 崩溃标记里的开机计时要与它同源比较,所以落盘的是 elapsed(墙钟只用于人看日志)
                KV.put(HawkConfig.BOOT_LOAD_START_ELAPSED, sProcessStartElapsedMs);
            }
            String previous = KV.get(HawkConfig.BOOT_LOADING_JAR, "");
            // 距上次"同源装载"是否太久 ⇒ 新一批尝试,重新计数
            // (判据用装载序列时间而不是崩溃时刻:崩溃时刻现在走同步标记文件,不落 KV)
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
     * 本次进程里第一次开始加载 jar 的时刻(墙钟 + 开机计时);墙钟为 0 = 本次进程还没加载过 jar。
     * 只判"本次进程"是刻意的:这样"启动 5 秒后播放崩了、用户马上重开"不会被误算成启动崩溃。
     */
    private static volatile long sProcessStartWallMs = 0L;
    private static volatile long sProcessStartElapsedMs = 0L;

    /**
     * 运行满 {@link #STABLE_RUN_MS} 未崩 ⇒ 这个源是稳定的,清掉计数。
     *
     * <p>⚠️ 刻意**不**在"jar 装载成功"时就清:实测爬虫的 {@code <clinit>} 跑在自己的线程上,
     * 装载线程会先报成功、28 毫秒后那个线程才崩(08:01:25.512 成功 / 08:01:25.540 崩溃)。
     * 早清等于把唯一的证据擦掉,看门狗永远凑不满阈值 —— 这正是第一版没起作用的原因。
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

    /**
     * 记下"此刻哪个源是启动源"。
     *
     * <p>为什么不拿 jar 地址去反查源:同一个爬虫 jar 常被多个源/多条链路引用,地址对不上号;
     * 而"正在加载这个 jar 时,启动源是谁"是事实,直接记下来最可靠 —— 崩了就是它。
     */
    private static void recordCurrentSource() {
        KV.put(HawkConfig.BOOT_VOD_SOURCE, KV.get(HawkConfig.API_URL, ""));
        KV.put(HawkConfig.BOOT_LIVE_SOURCE, KV.get(HawkConfig.LIVE_API_URL, ""));
    }

    /**
     * 同一源在 {@link #ATTEMPT_WINDOW_MS} 内累计到这么多次 jar 装载就认为在空转。
     *
     * <p>取值依据:一次正常启动里同一 jar 通常装载 1~2 次,取 3 能区分"正常多次装载"与"反复重启";
     * 这是**兜底**路径(正常路径是"启动阶段崩一次即停用"),所以宁可多给一次余量也不要误停用。
     */
    private static final int MAX_LOAD_ATTEMPTS = 3;

    /**
     * 启动早期(加载任何 jar/爬虫之前)调用。
     *
     * @return 因连续崩溃被停用的源地址;没有则返回空串
     */
    public static String disableBootLoopingSource() {
        try {
            String loading = KV.get(HawkConfig.BOOT_LOADING_JAR, "");
            long count = KV.get(HawkConfig.BOOT_LOADING_COUNT, 0L);
            // 崩溃标记走同步文件(MMKV 异步队列会丢,见 writeCrashMarker 的注释)
            // ⚠️ takeCrashMarkerElapsed() 读完即删,所以判定结果必须**只算一次**再复用 ——
            // 早先在这里又调了一次 crashedDuringStartup(),读的是已被删掉的文件,日志里的
            // startupCrash 恒为 false(判定本身没受影响,但排查时会误导)。
            long crashElapsed = takeCrashMarkerElapsed();
            long loadStartElapsed = KV.get(HawkConfig.BOOT_LOAD_START_ELAPSED, 0L);
            boolean startupCrash = crashedDuringStartup(crashElapsed, loadStartElapsed);
            if (!shouldDisable(loading, count, crashElapsed, startupCrash)) {
                return "";
            }
            LOG.i("boot-guard: disable looping source attempt=" + count
                    + " startupCrash=" + startupCrash + " jar=" + loading);
            disableRecordedSource();
            // 清标记:停用后不再累计;用户换源或作者修好后能重新开始
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
     * 停用判定(纯函数,便于单测):命中任一条即停用 ——
     * <ul>
     *   <li>①上次进程是**在启动加载阶段**崩的({@code startupCrash})—— 这类崩溃只会无限重复,
     *       让用户白崩第二次没有收益(用户实测反馈"会闪退两次才弹 toast");</li>
     *   <li>②同一个源累计装载次数达到 {@link #MAX_LOAD_ATTEMPTS} —— 兜住"跑了一阵才崩"的慢崩
     *       与"没拿到崩溃标记"的异常路径。</li>
     * </ul>
     *
     * <p>{@code startupCrash} 由调用方传入而不是在这里现算:算它要读并删除崩溃标记,
     * 只能读一次(见 {@link #disableBootLoopingSource()})。
     *
     * @param crashElapsed 上次崩溃时的开机计时(毫秒);&lt;= 0 表示没有崩溃标记
     * @param startupCrash 该崩溃是否发生在启动加载阶段
     */
    static boolean shouldDisable(String jar, long count, long crashElapsed, boolean startupCrash) {
        if (isEmpty(jar) || crashElapsed <= 0) return false;
        return startupCrash || count >= MAX_LOAD_ATTEMPTS;
    }

    /**
     * 崩溃是否发生在**启动加载阶段**:崩溃时的开机计时落在"本次进程开始加载 jar"之后
     * {@link #QUICK_CRASH_MS} 内。
     *
     * <p>两个值都是**同一次进程**里的 {@code SystemClock.elapsedRealtime()},所以
     * "启动 5 秒后播放崩了、用户马上重开"不会被误算成启动崩溃。
     */
    static boolean crashedDuringStartup(long crashElapsed, long loadStartElapsed) {
        return loadStartElapsed > 0 && crashElapsed >= loadStartElapsed
                && crashElapsed - loadStartElapsed <= QUICK_CRASH_MS;
    }

    /**
     * 与 TextUtils.isEmpty 等价。
     *
     * <p>⚠️ 刻意不用 {@code android.text.TextUtils}:单测开了 {@code returnDefaultValues},
     * 那些 Android 桩方法会**静默返回 false**,于是"空 jar 不停用"这条守卫在单测里失效
     * (本次就是被 {@link BootGuardTest} 当场抓到的)。同一坑在本仓已踩过两次。
     */
    private static boolean isEmpty(String text) {
        return text == null || text.length() == 0;
    }

    /**
     * 停用"崩溃时正在使用"的启动源。
     *
     * <p>直播优先:直播源独立配置时只清直播指针,点播完全不受影响;
     * 直播跟随点播(LIVE_API_URL 为空)时清点播指针 —— 那时两者本就是同一个源。
     */
    private static void disableRecordedSource() {
        String liveSource = KV.get(HawkConfig.BOOT_LIVE_SOURCE, "");
        String vodSource = KV.get(HawkConfig.BOOT_VOD_SOURCE, "");
        if (!liveSource.isEmpty()) {
            KV.put(HawkConfig.LIVE_API_URL, "");
            // 仓列表必须跟着一起作废(2026-09-21):只清地址会留下"列表非空但地址为空"的组合,
            // 与 clearLiveConfig() 的清场口径不一致,「配置切换」组会在无源时列出已失效的子源
            HistoryHelper.clearLiveApiLineList();
            KV.put(HawkConfig.BOOT_SAFE_DISABLED, liveSource);
        } else if (!vodSource.isEmpty()) {
            KV.put(HawkConfig.API_URL, "");
            HistoryHelper.clearApiLineList();
            KV.put(HawkConfig.BOOT_SAFE_DISABLED, vodSource);
        }
        KV.put(HawkConfig.BOOT_VOD_SOURCE, "");
        KV.put(HawkConfig.BOOT_LIVE_SOURCE, "");
    }

    /** 上一次自动停用的源地址(空串表示没有);UI 读它弹一次提示,读完即清 */
    public static String takeSafeDisabledNotice() {
        try {
            String url = KV.get(HawkConfig.BOOT_SAFE_DISABLED, "");
            if (!url.isEmpty()) KV.put(HawkConfig.BOOT_SAFE_DISABLED, "");
            return url;
        } catch (Throwable ignored) {
            return "";
        }
    }
}
