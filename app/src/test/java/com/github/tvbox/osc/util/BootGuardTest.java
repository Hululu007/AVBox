package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * [BootGuard] 停用判定的纯 JVM 单测(不碰 KV / 不碰文件 / 不碰 Android 存储)。
 *
 * <p>存在理由:这段判据的两侧都是真实伤害 ——
 * 判太松 ⇒ 用户进一次崩一次、连"换源"都做不到,只能清数据(2026-09-21 真机事故);
 * 判太紧 ⇒ 偶发崩溃(网络/播放器)就把用户能用的源停掉,比崩溃更难理解。
 *
 * <p>参数语义(都与实现里的同名量一致):
 * <ul>
 *   <li>{@code jar} —— 上次正在加载的 jar 地址;空 = 崩溃与加载无关。</li>
 *   <li>{@code count} —— 同一源累计装载次数(兜底判据)。</li>
 *   <li>{@code crashElapsed} —— 上次崩溃时的开机计时;{@code <= 0} = 没有崩溃标记。</li>
 *   <li>{@code startupCrash} —— 该崩溃是否落在"开始加载 jar 后 10 秒内"(由
 *       {@link BootGuard#crashedDuringStartup} 单独算好传入,因为它要读并删除标记文件,只能读一次)。</li>
 * </ul>
 */
public class BootGuardTest {

    /** 上次进程开始加载 jar 的开机计时 */
    private static final long LOAD = 5_000L;

    // ---------- ① 启动加载阶段崩:一次即停用 ----------

    /** 实测场景:开始加载 jar 后 28 毫秒就崩(GoProxy 把 CDN 报错当 .so 加载) */
    @Test
    public void startupCrash_disablesAfterSingleAttempt() {
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 28, true));
    }

    /** 边界:正好 10 秒算启动阶段 */
    @Test
    public void startupCrash_atThresholdCounts() {
        assertTrue(BootGuard.crashedDuringStartup(LOAD + 10_000, LOAD));
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 10_000, true));
    }

    /** 边界:10 秒零 1 毫秒就属于"跑了一阵才崩",单次不停用 */
    @Test
    public void slowCrash_oneMillisecondPastThreshold_doesNotDisable() {
        assertFalse(BootGuard.crashedDuringStartup(LOAD + 10_001, LOAD));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 10_001, false));
    }

    // ---------- ② 非启动阶段的崩溃:累计够次数才停用 ----------

    /**
     * 交付语义:老用户反馈"崩两次才弹 toast",现在**启动阶段崩一次就停用**(用例在 ①);
     * 剩下的慢崩路径保持保守 —— 单次绝不停用。
     */
    @Test
    public void slowCrash_singleAttemptNeverDisables() {
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 60_000, false));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 2, LOAD + 60_000, false));
    }

    /** 兜底阈值:同一源反复装载(拿不到启动阶段证据)达到 3 次也要能停用,否则会无限空转 */
    @Test
    public void repeatedLoads_disableAtFallbackThreshold() {
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 3, LOAD + 60_000, false));
    }

    // ---------- ③ 不该停用的情形 ----------

    /** 没有崩溃标记(没崩过)绝不停用 —— 否则每次冷启动都会把当前源停掉 */
    @Test
    public void noCrashNeverDisables() {
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 9, 0, true));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 9, -1, true));
    }

    /** 没有"正在加载的 jar"记录 ⇒ 崩溃与加载无关,不牵连任何源 */
    @Test
    public void crashWithoutLoadingJarNeverDisables() {
        assertFalse(BootGuard.shouldDisable("", 4, LOAD + 1, true));
        assertFalse(BootGuard.shouldDisable(null, 4, LOAD + 1, true));
    }

    /**
     * 关键回归锁:崩溃的开机计时**早于**加载起点(跨批次残留数据)时,不能按"启动阶段崩"处理 ——
     * 否则会把一次无关崩溃算成启动自锁。
     */
    @Test
    public void crashBeforeLoadStartIsNotStartupCrash() {
        assertFalse(BootGuard.crashedDuringStartup(LOAD - 1, LOAD));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD - 1, false));
    }

    /** 没有加载起点(旧数据/异常路径)时,只认计数那条兜底判据(阈值 MAX_LOAD_ATTEMPTS = 3) */
    @Test
    public void missingLoadStartFallsBackToCount() {
        assertFalse(BootGuard.crashedDuringStartup(LOAD + 1, 0));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 1, false));
        assertFalse("阈值以下是 2", BootGuard.shouldDisable("/x/spider.jar", 2, LOAD + 1, false));
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 3, LOAD + 1, false));
    }

    /**
     * 会话中途换仓/换源的回归锁(2026-09-21 十三轮):判据比的是"**最近一次**开始装载"的起点,
     * 不是"本进程第一次装载"。
     *
     * <p>旧写法只在本进程首次装载时记起点 ⇒ 用户用了半小时再换仓切到坏源,崩溃时刻减装载起点是
     * 30 分钟,判不出装载阶段崩溃,只能靠"累计装载 3 次"兜底 ⇒ 用户要崩两次才等到停用。
     * 换仓切到的坏源与启动源是同一类崩法(<clinit> 里 System.load 报错页,实测 28 毫秒),
     * 就该一次即停用。
     */
    @Test
    public void midSessionSwitchCrash_countsAsLoadStageCrash() {
        long firstLoadOfProcess = LOAD;
        long switchLoad = firstLoadOfProcess + 30 * 60_000L;
        long crash = switchLoad + 28;
        assertTrue(BootGuard.crashedDuringStartup(crash, switchLoad));
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 1, crash, true));
        assertFalse("拿进程第一次装载当起点(旧写法)会判成 false —— 这正是要修掉的", BootGuard.crashedDuringStartup(crash, firstLoadOfProcess));
    }

    // ---------- ④ 风险源黑名单(纯列表运算) ----------

    /** 同一个源反复崩只记一条 —— 名单是地址集合,重复项会让"标记/绕开"两处判定都失去意义 */
    @Test
    public void addDisabledSource_dedups() {
        ArrayList<String> list = BootGuard.addDisabledSource(new ArrayList<String>(), "http://a/1");
        list = BootGuard.addDisabledSource(list, "http://a/1");
        assertEquals(1, list.size());
        assertEquals("http://a/1", list.get(0));
    }

    /** 空地址(崩溃与加载无关时的残留)不写进名单 —— 否则会出现一条"空地址被禁用"的鬼记录 */
    @Test
    public void addDisabledSource_ignoresBlank() {
        assertTrue(BootGuard.addDisabledSource(new ArrayList<String>(), "").isEmpty());
        assertTrue(BootGuard.addDisabledSource(new ArrayList<String>(), null).isEmpty());
    }

    /** 二次确认启用后要真的能移出去,否则用户永远没法再试这个源 */
    @Test
    public void removeDisabledSource_removesMatch() {
        ArrayList<String> list = new ArrayList<>(Arrays.asList("http://a/1", "http://b/2"));
        list = BootGuard.removeDisabledSource(list, "http://a/1");
        assertEquals(1, list.size());
        assertEquals("http://b/2", list.get(0));
    }

    /** 移除不在名单里的地址是无操作 —— 删除订阅时会批量调用,不能把别人的记录一起带走 */
    @Test
    public void removeDisabledSource_missingIsNoop() {
        ArrayList<String> list = new ArrayList<>(Arrays.asList("http://a/1"));
        assertEquals(1, BootGuard.removeDisabledSource(list, "http://z/9").size());
        assertEquals(1, BootGuard.removeDisabledSource(list, "").size());
    }

    /** 入参为 null 不能抛:调用方传的是 KV 读取结果,缺键/解码失败都可能给到 null */
    @Test
    public void blacklist_toleratesNullList() {
        assertEquals(1, BootGuard.addDisabledSource(null, "http://a/1").size());
        assertTrue(BootGuard.removeDisabledSource(null, "http://a/1").isEmpty());
    }

    // ---------- ⑤ 崩溃栈过滤:界面/平台崩溃不算到源头上 ----------

    /** 纯界面栈(Compose + 消息循环 + 应用界面层)判为与源无关,否则界面 bug 崩一次就停用源 */
    @Test
    public void uiCrashIsNotSourceRelated() {
        RuntimeException crash = new RuntimeException("ui bug");
        crash.setStackTrace(new StackTraceElement[]{
                frame("androidx.compose.runtime.Recomposer", "run"),
                frame("kotlinx.coroutines.DispatchedTask", "run"),
                frame("kotlin.coroutines.jvm.internal.ContinuationImpl", "resumeWith"),
                frame("android.os.Handler", "handleCallback"),
                frame("com.github.tvbox.osc.ui.page.MainScreenKt", "MainContent"),
                frame("java.lang.Thread", "run"),
        });
        assertFalse(BootGuard.looksSourceRelated(crash));
    }

    /** 爬虫帧出现即算"有关";jar 装载器也在链上(它就是源的入口) */
    @Test
    public void spiderCrashIsSourceRelated() {
        RuntimeException crash = new RuntimeException("spider boom");
        crash.setStackTrace(new StackTraceElement[]{
                frame("com.github.catvod.spider.GoProxy", "<clinit>"),
                frame("com.github.catvod.crawler.JarLoader", "invokeInit"),
                frame("java.lang.Thread", "run"),
        });
        assertTrue(BootGuard.looksSourceRelated(crash));
    }

    /** 爬虫崩在别的线程、由界面层包装抛出时,不能只看最外层那几个界面帧 */
    @Test
    public void causeChainIsScanned() {
        RuntimeException inner = new RuntimeException("spider boom");
        inner.setStackTrace(new StackTraceElement[]{frame("com.github.catvod.spider.DouDou", "homeContent")});
        RuntimeException outer = new RuntimeException("wrapped", inner);
        outer.setStackTrace(new StackTraceElement[]{frame("com.github.tvbox.osc.ui.page.HomeViewModel", "loadHome")});
        assertTrue(BootGuard.looksSourceRelated(outer));
    }

    /** suppressed 里的爬虫帧同样不能漏(并发包装过的异常常挂在 suppressed 上) */
    @Test
    public void suppressedChainIsScanned() {
        RuntimeException crash = new RuntimeException("ui bug");
        crash.setStackTrace(new StackTraceElement[]{frame("androidx.compose.runtime.ComposerImpl", "applyChanges")});
        RuntimeException suppressed = new RuntimeException("spider boom");
        suppressed.setStackTrace(new StackTraceElement[]{frame("com.github.catvod.spider.GoProxy", "init")});
        crash.addSuppressed(suppressed);
        assertTrue(BootGuard.looksSourceRelated(crash));
    }

    /** 信息不足(无帧 / null)必须按"有关"处理,否则坏源的自锁防护会失效 */
    @Test
    public void unknownCrashCountsAsSourceRelated() {
        RuntimeException empty = new RuntimeException("no frames");
        empty.setStackTrace(new StackTraceElement[0]);
        assertTrue(BootGuard.looksSourceRelated(empty));
        assertTrue(BootGuard.looksSourceRelated(null));
    }

    private static StackTraceElement frame(String className, String method) {
        return new StackTraceElement(className, method, className + ".java", 1);
    }
}
