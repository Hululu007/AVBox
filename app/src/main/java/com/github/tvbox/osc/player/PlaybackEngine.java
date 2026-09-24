package com.github.tvbox.osc.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.player.usecase.PlayerSwitchUseCase;
import com.github.tvbox.osc.ui.player.PlayContainer;
import com.github.tvbox.osc.ui.player.PreloadCoordinator;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.HashMap;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.ProgressManager;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * 播放引擎(P2 播放服务化,`skill/avbox-playback-service-spec.md` §2.1/§2.3/§3-P2)。
 *
 * <p>**所有权模型**(照搬 fongmi,但落地为"进程级引擎 + 宿主服务"):播放器实例
 * ({@link MyVideoView} + {@link PlaybackController})由本引擎持有、由 {@link PlaybackService} 托管,
 * 页面({@link PlayContainer})只提供显示宿主与控制器覆盖层 ——
 * 进入页面 = 把渲染容器(`VideoView.mPlayerContainer`)搬进页面宿主,离开页面 = 摘回引擎。
 * 由此"跨页复用播放器"成立:进出详情页不再重建 ExoPlayer/RenderView
 * (改造前 12 次进出 = 36 个内核实例 / 249 线程,见 MEMORY.md hprof 取证)。
 *
 * <p>**为什么不做成 Service 本体**:页面对引擎的取用必须与页面构造同帧同步(否则要处理
 * "服务未就绪 → 控制器事后替换 → 在途取流/观察者双投递"的初始化竞态);因此引擎由页面
 * **同步**取用({@link PlaybackService#engine(Context)}),Service 随后接管它的生命周期
 * (前台服务/后台策略/媒体会话由 {@link PlaybackService} 承担,P3 已并入)。
 *
 * <p>**桥的归属**:控制器({@code PlaybackController.setViewBridge})同一时刻只认一个视图桥 ——
 * 挂载页面时为**页面桥**(提示/弹幕/字幕/控制器动作都在页面),无页面时为
 * {@link HeadlessView}(播放器机械动作照做,UI 动作空操作),因此退页面后音频/通知/预载仍能继续维护。
 *
 * <p>**行为边界(P2)**:
 * <ul>
 *   <li>影视离开页面仍暂停(沿用 {@code hostPause} 语义),但**实例不释放** —— 再进页面/换页面直接复用;</li>
 *   <li>确认纯音频(音乐)离开页面可继续播:媒体会话/通知由控制器 + {@link PlaybackService} 维护;</li>
 *   <li>前台服务与通知由 {@link PlaybackService} 承担(P3);后台档位(画中画等)未做(Spec §7-D3);</li>
 *   <li>任务被移除 / 宿主服务销毁时释放播放器({@link #release()})。</li>
 * </ul>
 */
public final class PlaybackEngine implements PlaybackHostApi {

    private static final String TAG = "echo-p2";

    /**
     * 空闲释放延迟:摘下页面后若一直没人再来取播放器,到点就释放内核(2026-09-14 架构评审第 1 项)。
     *
     * <p>背景:P2「保留实例」是为了消灭"每次进详情页都新建内核"的资源风暴,但改造后**没有任何上界** ——
     * 只要不划掉任务、进程不被杀,一个 ExoPlayer(含内部线程/解码器句柄)就永久常驻,哪怕用户早已
     * 回到首页刷了半小时。fongmi 是"没持有者就 shutdown";这里折中:秒级/十秒级的"快速返回"仍然
     * 命中复用(远小于本值),超过这个时长基本不会再是"刚退出又进来"的场景,释放掉换回内存。
     * 需要更激进/更保守就调这个常量(建议区间 30s~5min)。
     */
    private static final long IDLE_RELEASE_DELAY_MS = 60_000L;

    private final Context appContext;
    private final MyVideoView videoView;
    /** 播放调度(会话/取流/解析/重试/换线/预载/媒体会话;P1 已从页面整体搬出) */
    private final PlaybackController controller = new PlaybackController();
    /** 无页面时的视图桥:播放器机械动作照做,UI 动作空操作 */
    private final HeadlessView headlessView = new HeadlessView();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 点播进度落盘(直播模式摘下、退出直播恢复;P4) */
    private final ProgressManager progressManager = new ProgressManager() {
        @Override
        public void saveProgress(String url, long progress) {
            CacheManager.save(MD5.string2MD5(url), progress);
            if (controller.webPlayUrl() != null && progress > 0) {
                controller.markPlaybackStarted();
                activeView().hideTipOnUiThread();
            }
        }

        @Override
        public long getSavedProgress(String url) {
            return controller.getSavedProgress(url);
        }
    };
    private WeakReference<PlaybackPage> pageRef;
    private PlaybackSession session;
    private boolean released;
    /** 直播模式(P4):同一实例被直播页接管期间,点播侧(进度/预载/媒体会话/弹幕)一概不参与 */
    private boolean liveMode;

    public PlaybackEngine(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        LOG.i(TAG + " engine create");
        videoView = createPlayerView();
        controller.setViewBridge(headlessView);
        controller.initFetch();
        // 预载协调器随引擎走(P2):页面挂载时用页面快照评估,无页面时留空
        controller.initPreload();
    }

    // ==================== 页面取用 ====================

    @NonNull
    public MyVideoView player() {
        return videoView;
    }

    @NonNull
    public PlaybackController controller() {
        return controller;
    }

    @Nullable
    public PlaybackPage attachedPage() {
        return pageRef == null ? null : pageRef.get();
    }

    /** 无页面时的视图桥:非页面实现(音乐播放页)的桥按接口委托复用它,只覆写 UI 相关动作 */
    @NonNull
    public PlaybackViewBridge headlessBridge() {
        return headlessView;
    }

    // ==================== 挂摘协议(§2.3) ====================

    /** 建立播放器视图:主题化 application 上下文(不持有 Activity),点播磁盘缓存开,进度落盘中转 */
    private MyVideoView createPlayerView() {
        Context ctx = new ContextThemeWrapper(appContext, R.style.AppTheme_NoActionBar);
        MyVideoView view = new MyVideoView(ctx);
        view.setExoDiskCacheEnabled(true);
        view.setProgressManager(progressManager);
        view.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                // 引擎已释放(空闲 TTL / 任务移除):下面任何一步都不该再走 ——
                // 尤其 handlePlayStateForMusicSession 会去 updateSession,那会在没有引擎的情况下
                // 建出一条空通知并持有 wake/wifi 锁,而释放路径已经跑完、没人再来放锁(2026-09-14 审查)
                if (released) return;
                // 播放错误落一条盘:本机 ROM 吞 logcat,只有 App 文件日志能取证(白名单已含 echo-player)
                if (playState == VideoView.STATE_ERROR) {
                    LOG.i("echo-player error: kernel="
                            + (videoView.getMediaPlayer() == null ? "null" : videoView.getMediaPlayer().getClass().getSimpleName())
                            + " pos=" + videoView.getCurrentPosition()
                            + " started=" + controller.isPlaybackStarted()
                            + " url=" + controller.webPlayUrl());
                }
                // 遮黑帧的揭开与点播/直播无关:直播页共用同一块容器,漏揭就是"有声无画",
                // 故必须在下面的 liveMode 短路**之前**。纯音频没有画面可露、海报就是它的背景
                // (只有确认是影视才需要「收黑帧 + 撤封面」的互斥)
                if (playState == VideoView.STATE_PLAYING) {
                    if (controller.isConfirmedAudioOnly()) {
                        videoView.hideVideoFrameCover();
                    } else {
                        videoView.showVideoFrame();
                    }
                }
                // 直播模式(P4):直播页有自己的控制层与状态机(自动换源/时移),点播侧的
                // 预载排期、进度落盘、媒体会话、弹幕启动一概不参与 —— 否则会拿上一部点播的
                // vod 去更新通知/预载(错内容)或把直播画面当成点播起播
                if (liveMode) return;
                if (playState == VideoView.STATE_PLAYING) {
                    // 纯音频渲染兜底(2026-09-13):URL 预判漏网(无后缀音乐直链)时,轨道信息就绪后补切
                    controller.ensureAudioOnlyRender();
                    // 正片稳定播放 → 延迟评估下一集预载(预载方案第一期)
                    controller.onPlayerStateForPreload(playState);
                }
                if (playState == VideoView.STATE_BUFFERING || playState == VideoView.STATE_BUFFERED) {
                    // 缓冲让路 / 缓冲结束补一次评估(见原页面同名注释:dkplayer 的 STATE_PLAYING 只在首帧发一次)
                    controller.onPlayerStateForPreload(playState);
                }
                if (controller.webPlayUrl() != null && controller.isStartedPlayState(playState)) {
                    controller.markPlaybackStarted();
                    if (!released && !videoView.isVideoFrameCleared()) {
                        activeView().hideTipOnUiThread();
                    }
                }
                if (controller.handlePlayStateForMusicSession(playState)) {
                    return;
                }
                activeView().startDanmuIfReady();
            }
        });
        return view;
    }

    /**
     * 直播页接管(P4):直播与点播共用一个播放器实例 —— 直播页把整块 MyVideoView 放进自己的 Compose 树,
     * 这里只做"切换人格":撤掉点播进度管理器、结束点播媒体会话(撤通知/放锁)、清掉上一部点播的残留帧与封面。
     * 直播自身的控制层/自动换源/时移逻辑(LivePlayerManager)完全不变。
     */
    /**
     * 切回"直播人格":直播页从后台回到前台时用 —— 期间点播页可能接管过同一个实例
     * (attach 会把人格切回点播),回直播必须切回来。
     *
     * <p>能执行到本方法体(liveMode=false)就说明**必然被点播接管过**(liveMode 只有点播
     * attach 的 exitLiveState 会置 false):此时内核内容已不可信 —— 可能是点播正片
     * (点播页被 detach 停成 PAUSED,恰好是可 resume 的状态,直播页 onResume 的 resume()
     * 会把点播画面+声音恢复到直播页里),也可能是早已失效的旧直播流。因此这里必须把内核
     * **停死**(release),并由直播页重播当前频道(直播流地址无法续播)。
     *
     * @return true = 发生过点播接管,调用方(直播页)应重播当前频道,而不是 resume()
     */
    public boolean enterLiveState() {
        if (released || liveMode) return false;
        // 回直播前台时播放器可能已被点播页接管:必须先把点播页摘掉并撤掉点播的会话/锁,否则:
        // ① 渲染容器还挂在点播页槽位 → 直播页 Compose 树拿到空壳(无画面);
        // ② 点播音频与直播叠加;③ 点播通知与 wake/wifi 锁残留到直播期间(点通知还会把点播声音叠上来)。
        // ⚠️ detach 必须在 liveMode 置位**之前**(detach 对直播模式直接让路,见其 liveMode 守卫)
        PlaybackPage page = attachedPage();
        if (page != null) detach(page);
        controller.stopMusicSessionForFailedPlayback();
        PlaybackService.forceStopSession(appContext);
        liveMode = true;
        setLiveFlag(true);
        cancelIdleRelease();
        LOG.i(TAG + " re-enter live state (after vod takeover)");
        session = null;
        controller.clearStartedContent();
        videoView.setProgressManager(null);
        videoView.setExoDiskCacheEnabled(false);
        // 停死内核(在摘下点播进度管理器**之后**:release 内部会 saveProgress,若进度管理器还挂着,
        // 会把 mCurrentPosition —— 可能已是点播/直播的错位值 —— 写进残留的 mProgressKey;
        // 点播进度已由 detach 里的 saveCurrentProgress 落过盘,这里无需也无法再落)。
        // 只 pause/stop 的话,PAUSED 状态会被直播页的 resume() 恢复成"直播页播点播"
        videoView.release();
        return true;
    }

    public void enterLive() {
        if (released) return;
        // 直播不走会话通道(LivePlayerManager 直接操作播放器),引擎里上一次点播的会话与
        // "已起播内容"归属即刻失效:否则"点播→直播→回到同一部点播"会被 D6 判定为同片接管而跳过取流
        // (真机 bug:点播页播着直播)
        session = null;
        controller.clearStartedContent();
        LOG.i(TAG + " enter live mode");
        // 点播页面若还在栈里(未销毁):先把渲染容器收回来(否则它仍挂在那个页面的槽位里,
        // 直播页的 Compose 树拿到的只是一张空壳),并清掉对页面 View 的引用。
        // ⚠️ 必须在 liveMode 置位**之前**:detach 对直播模式直接让路(见 detach 的 liveMode 守卫)
        PlaybackPage page = attachedPage();
        if (page != null) detach(page);
        liveMode = true;
        setLiveFlag(true);
        // 直播接管期间播放器有人用(直播页不是 PlayContainer、不走 attach),取消空闲释放排期
        cancelIdleRelease();
        // 直播接管这一个播放器:旧内容一律停死(含"确认纯音频"的场景,避免与直播双声)。
        // 只 pause 不够:退页面后内核本就停在 PAUSED,pause() 是空操作,旧内容会留下被直播页 onResume 的 resume() 恢复出声。
        // 释放须在摘进度管理器之前 —— 那一刻进度键还是旧内容的,正好把它的观看位置落盘(直播无进度语义)
        releasePlayer();
        videoView.setProgressManager(null);
        // 边播边缓存是点播特性(直播流是 m3u8 直播片,缓存数据源无意义甚至影响起播):直播期间关掉
        videoView.setExoDiskCacheEnabled(false);
        videoView.clearArtwork();
        videoView.showVideoFrame();
        // 撤点播会话:先清控制器侧标记(含正常路径的 stop),再兜底强制收尾 ——
        // 归属守卫(stopSession)会因"当前 owner 还是那个点播页面"而拒停,直播接管必须绕过它,
        // 否则点播通知与播放锁会残留到直播期间(点通知还会把点播声音叠到直播上)
        controller.stopMusicSessionForFailedPlayback();
        PlaybackService.forceStopSession(appContext);
    }

    /**
     * 退出直播模式(直播页销毁):清直播控制器 + 还回点播设置 + **停掉直播流并释放内核**。
     *
     * <p>⚠️ 必须停流(2026-09-14 真机两个 bug 的根因):直播页销毁若只切人格而不停播放,
     * ① 退到首页仍有直播声音;② 点播页 attach 后容器里就是还在跑的直播画面,甚至被 D6 判定成
     * "同片接管"而不再取流 —— 表现是"看点播却播着直播"。
     * 这与改造前 `LivePlayActivity.onDestroy → mVideoView.release()` 的语义一致(直播无后台播放)。
     */
    public void exitLive() {
        if (released) return;
        // ⚠️ 归属判定必须在 `exitLiveState()` **之前**:exitLiveState 自己会把 liveMode 置 false,
        // 放到它后面再判 `!liveMode` 就恒为 true —— 直播退出将永不 release,
        // 直接退回"退出直播回首页仍有直播声 / 点播页播着直播"的真机 bug。
        if (!liveMode) {
            // 播放器已被点播页接管(直播 → 打开点播详情 → 直播页此刻才被销毁):
            // 直播页销毁若照旧 release,会把**点播正在播的画面**直接打掉(黑屏 + 停播)。
            // 播放器此刻不属于直播,什么都不做 —— 点播页自己的 detach 会负责收尾。
            LOG.i(TAG + " exit live skipped: player taken over by vod page");
            return;
        }
        // ⚠️ 先停死内核、**再**还点播人格(exitLiveState 会 setProgressManager 还回点播的进度管理器):
        // release() 内部会 saveProgress —— 若此刻 progressManager 已还回,而 VideoView 的
        // mProgressKey 还是上一部点播的、mCurrentPosition 已被直播期间的位置刷新,
        // 就会把【直播的 position】写进【点播的进度缓存】(看剧→进直播→返回,续播位置被污染)。
        // 直播无进度语义,先释放(此时 progressManager 仍为 null,saveProgress 是空操作)再还人格。
        videoView.release();
        exitLiveState();
        // 直播控制器属直播页(其 ComposeLiveController);页面销毁后必须摘掉,防引擎持有页面 View
        videoView.setVideoController(null);
        LOG.i(TAG + " live stream released");
        // 直播已停且没人接管(点播页可能还在栈里但没 attach)→ 排一次空闲释放,让宿主服务最终能退出
        scheduleIdleRelease();
    }

    // ==================== 空闲释放(2026-09-14) ====================

    /**
     * 排期"无人使用就释放"。只有**确实无人持有**时才真正生效(执行时再校验一次):
     * 无点播页面挂载 **且** 不在直播模式 —— 直播页不走 attach,只能靠 liveMode 判定有人在用。
     */
    private void scheduleIdleRelease() {
        main.removeCallbacks(idleRelease);
        if (released) return;
        main.postDelayed(idleRelease, IDLE_RELEASE_DELAY_MS);
    }

    private void cancelIdleRelease() {
        main.removeCallbacks(idleRelease);
    }

    private final Runnable idleRelease = new Runnable() {
        @Override
        public void run() {
            if (released || liveMode || attachedPage() != null) return;
            LOG.i(TAG + " idle release: no host for " + (IDLE_RELEASE_DELAY_MS / 1000) + "s");
            release();
            // 宿主服务没有引擎可托管了:清静态引用并停服务(下次 engine() 会重新建引擎 + 拉起服务)
            PlaybackService.onEngineReleased(PlaybackEngine.this);
        }
    };

    /**
     * 直播/点播标记(2026-09-15 修复):唯一写入点是**引擎的模式切换**,不再跟直播页的 onCreate/onDestroy 走。
     *
     * <p>{@code IjkMediaPlayer.setOptions()/setDataSource()} 与 {@code ApiConfig.proxyLocal()} 都会在
     * prepare/取流时读它(决定缓存窗口、解码线程数、M3U8/itv 代理与爬虫路由),而 Activity 的销毁时机
     * 与"引擎已切回点播"没有时序关系 —— 直播页还在栈里/销毁未完成时,点播起播会读到滞后的直播参数。
     * 引擎模式切换(enterLive/enterLiveState/exitLiveState/release)严格早于对应播放的起播 ⇒ 读到的值必然正确。
     */
    private static void setLiveFlag(boolean live) {
        KV.put(HawkConfig.PLAYER_IS_LIVE, live);
    }

    /** 只切"人格":还回点播的进度管理器与磁盘缓存标记(不动控制器 —— 调用方自己管) */
    private void exitLiveState() {
        if (released || !liveMode) return;
        liveMode = false;
        setLiveFlag(false);
        LOG.i(TAG + " exit live mode");
        videoView.setProgressManager(progressManager);
        videoView.setExoDiskCacheEnabled(true);
    }

    public boolean isLiveMode() {
        return liveMode;
    }

    /** 引擎是否已释放(宿主服务销毁/任务移除后);页面据此放弃对播放器视图的引用 */
    public boolean isReleased() {
        return released;
    }

    /** 页面挂载:搬渲染容器进页面宿主,并把视图桥切到页面(提示/弹幕/字幕/控制器动作都在页面) */
    public void attach(@NonNull PlaybackPage page) {
        if (released) return;
        // 只切人格:exitLive() 会清控制器,而页面构造期(initView)刚把自己的控制器设上去,
        // 这里清掉会导致返回点播页后失去控制器手势/按键(见 exitLiveState 注释)
        if (liveMode) exitLiveState();
        pageRef = new WeakReference<>(page);
        // 挂载时页面还不知道要播什么(会话要等详情数据回来),而旧内容若停在 PAUSED,渲染容器一进
        // 新页面就会带出上一部的最后一帧(media3 在 Surface 重建时重渲染)。先遮黑,起播由状态回调揭开;
        // 正在播的内容属于"本次接管"(音乐页交接/页面返回),遮了没人来揭,故不动
        if (!videoView.isPlaying()) videoView.coverVideoFrame();
        videoView.attachContainerTo(page.renderSlot());
        controller.setViewBridge(page.viewBridge());
        // 有人接手了,撤销空闲释放排期
        cancelIdleRelease();
        LOG.i(TAG + " attach page=" + page.hashCode() + " key=" + (session == null ? "-" : session.playbackKey()));
    }

    /**
     * 页面摘除(返回上一级 / 回首页):**一律停播 + 撤通知 + 放锁**,但**保留播放器实例**。
     *
     * <p>语义由用户 2026-09-14 选定(与 fongmi 默认一致):退出播放页不再继续播(影视与音乐都不后台继续),
     * 与改造前 `hostDestroy → release + MusicPlaybackService.stop` 的观感一致。
     * 只停"播放"不停"实例":实例留给下次进详情页直接复用(这是 P2 的核心收益)。
     *
     * <p>两个容易漏的点:① 不 release 就没人触发进度落盘 → 必须显式 `saveCurrentProgress()`;
     * ② 在途取流/解析若不停,退出后会在后台把这一集播起来(无声页面却在响)→ `stopPlaybackForPageExit()`。
     */
    public void detach(@NonNull PlaybackPage page) {
        detach(page, false);
    }

    /**
     * 把页面交给下一个页面(音乐播放页):摘视图但**不停播、不撤会话**。
     *
     * <p>用于"详情页发现是纯音频 → 拉起音乐页"的交接:老页面随后销毁时不能再走 {@link #detach}
     * (那样会把刚交接的音频停掉),故提前用本方法摘净视图并置空页面引用;若新页面最终没来接管,
     * 空闲释放(见 {@link #IDLE_RELEASE_DELAY_MS})仍会给实例一个上界。
     */
    public void detachForHandover(@NonNull PlaybackPage page) {
        detach(page, true);
    }

    private void detach(@NonNull PlaybackPage page, boolean keepPlayback) {
        if (released) return;
        // ⓪ 直播接管期间播放器属于**直播页**:此时被销毁的点播页(它可能只是被系统回收,或用户
        // 从详情跳直播后旧页才走 onDestroy)不得再动播放器 —— 否则会停掉直播流、撤掉直播通知、
        // 并把渲染容器从直播页的 Compose 树里摘走(直播黑屏)。
        // 直播页自己不走 attach(没有 PlaybackPage),所以归属守卫 `cur != page` 拦不住这种情况。
        if (liveMode) return;
        // ① 进度落盘**先于归属判定**(2026-09-14):"快速返回再进入"时新页面可能已经 attach 了引擎,
        // 归属守卫会让下面的收尾整段跳过;若不在这里先存,这一集的观看进度就随着页面销毁丢了
        // (播放器里还是旧内容/旧 progressKey,存下来正是旧集该存的那一份)。
        videoView.saveCurrentProgress();
        PlaybackPage cur = attachedPage();
        if (cur != null && cur != page) return;
        pageRef = null;
        if (!keepPlayback) {
            // ① 停播(一律,含"确认纯音频"的音乐):退页面即停
            videoView.pause();
            // ③ 刚点播放就退出(PREPARING/BUFFERING):pause() 无效,必须停内核,否则页面销毁后自己播起来
            videoView.stopPlaybackKeepPlayer();
            // ③ 收在途:撤取流/超时/解析 + 停会话(撤通知、放 wake/wifi 锁)
            controller.stopPlaybackForPageExit();
            // 归属守卫可能让 ③ 的停会话被跳过(owner 是页面 vs host 也是页面 —— 通常一致,这里再兜一次)
            PlaybackService.forceStopSession(appContext);
        }
        // ④ 摘视图与页面 View 引用(防引擎持有页面)
        videoView.setVideoController(null);
        videoView.setDanmuView(null);
        videoView.detachContainerFromHost();
        controller.setViewBridge(headlessView);
        // 页面已摘、播放已停,但实例仍留着(跨页复用的收益所在)—— 给它一个释放上界:
        // 到点还没人来取就释放内核(见 IDLE_RELEASE_DELAY_MS)
        scheduleIdleRelease();
        LOG.i(TAG + (keepPlayback ? " detach for handover page=" : " detach page=") + page.hashCode());
    }

    /**
     * 释放**播放内核**但保留引擎(换源点击即停 / 切内核重播 / 外部播放器接管 / 直播切台换解码器)。
     *
     * <p>所有权收口(2026-09-14 架构评审第 2 项):播放器归引擎,页面只能表达"我要换内核"这个意图,
     * **不能直接 `videoView.release()`** —— 那样引擎会在完全不知情的情况下失去内核
     * (预载协调器/会话归属/"已起播内容"标记全部停留在"还在播"的假象上)。
     * 本方法只释放内核与渲染视图,VideoView、引擎、容器挂载关系都不动,下次 `start()` 按工厂新建内核。
     */
    public void releasePlayer() {
        if (released) return;
        LOG.i(TAG + " release player kernel (engine kept)");
        videoView.release();
        // 内核没了 ⇒ 播放器里不再有"属于某个会话的内容":清掉 D6 的接管依据,
        // 否则"换源停播后重进同一部"会被判成同片接管而跳过取流(内容其实已经没了)
        controller.clearStartedContent();
    }

    /** 释放播放器(宿主服务销毁/任务移除;之后本引擎不可再用) */
    public void release() {
        if (released) return;
        released = true;
        // 引擎死亡必须复位直播标记(2026-09-15):否则"直播中被释放"会把 true 留给下一个引擎/后续点播
        setLiveFlag(false);
        LOG.i(TAG + " engine release");
        PlaybackPage page = attachedPage();
        pageRef = null;
        if (page != null) page.onServiceStopped();
        // 桥切回无页面桥:否则已释放的控制器仍指向那个页面,后续迟到的超时消息会把提示/错误弹到已销毁的页面上
        controller.setViewBridge(headlessView);
        controller.onHostDestroy();
        // 强制再收一次会话:`stopMusicSession()` 走的是带**归属守卫**的 stopSession,
        // 而此时 owner 往往还是那个页面(守卫拒停)⇒ 通知与 wake/wifi 锁会残留。释放路径必须绕过守卫。
        PlaybackService.forceStopSession(appContext);
        videoView.setVideoController(null);
        videoView.setDanmuView(null);
        videoView.release();
        controller.releaseFetch();
        controller.stopParse();
        controller.stopLoadWebView(true);
        main.removeCallbacksAndMessages(null);
    }

    /** 当前生效的视图桥:页面在时用页面桥,否则用无页面桥 */
    private PlaybackViewBridge activeView() {
        PlaybackPage page = attachedPage();
        if (page == null) return headlessView;
        return page.viewBridge();
    }

    // ==================== PlaybackHostApi(无页面时的宿主) ====================

    @Override
    public void setData(@NonNull PlaybackSession session) {
        this.session = session;
        controller.startSession(session);
    }

    @Override
    public void play(boolean reset) {
        controller.play(reset);
    }

    @Override
    public void playNext(boolean rmProgress) {
        // 集索引由页面(vod.playFlag/playIndex)维护,无页面时无"下一集"语义(P3 与服务合并后统一)
    }

    @Override
    public void playPrevious() {
        // 同上
    }

    @Override
    public boolean selectQuality(int position) {
        return false;
    }

    @Override
    public void setAutoSwitchLineEnabled(boolean enabled) {
    }

    @Override
    public void setPreviewMode(boolean previewMode) {
    }

    @Override
    public void toggleControllerControls() {
    }

    @Override
    public boolean onBackPressed() {
        return false;
    }

    @Override
    public void setExitingPreview(boolean exitingPreview) {
    }

    @Override
    public void setPlayTitle(boolean show) {
    }

    @Override
    public void stopForSourceSwitch(String tip) {
        controller.markStoppedForSourceSwitch();
        controller.stopMusicSessionForFailedPlayback();
    }

    @Override
    public void clearSourceSwitchTip() {
    }

    @Override
    public void showCast() {
    }

    @Override
    public void onLocalSubtitlePicked(Uri uri) {
    }

    @Override
    public void hostResume() {
        videoView.resume();
    }

    @Override
    public void hostPause() {
        if (!controller.isConfirmedAudioOnly()) videoView.pause();
    }

    @Override
    public void hostDestroy() {
        // 无页面可销毁;真正释放走 release()
    }

    @Override
    public void resumeFromMediaSession() {
        videoView.start();
        controller.updateMusicSession();
    }

    @Override
    public void pauseFromMediaSession() {
        videoView.pause();
        controller.updateMusicSession();
    }

    @Override
    public void stopFromMediaSession() {
        videoView.pause();
        controller.stopMusicSession();
    }

    @Override
    public void seekFromMediaSession(long position) {
        videoView.seekTo(position);
        controller.updateMusicSession();
    }

    /**
     * 无页面时的视图桥:让控制器在"页面不存在"时仍能推进播放器机械动作(起播/释放/状态读取/渲染切换),
     * 而 UI 动作(提示/弹幕/字幕/控制器覆盖层/嗅探宿主/外部播放器)一律空操作。
     *
     * <p>{@link #isPageAlive()} 返回 true:这里的语义是"**存在可服务的宿主**"(引擎自己),否则控制器会跳过
     * 媒体会话维护(如 {@code updateMusicSession}),退页面后通知就会冻结。
     */
    private final class HeadlessView implements PlaybackViewBridge {

        @Override
        public boolean isPageAlive() {
            return !released;
        }

        @Override
        public void runOnUi(Runnable action) {
            main.post(action);
        }

        @Override
        public Context context() {
            return appContext;
        }

        @Override
        public PlaybackHostApi playbackHost() {
            return PlaybackEngine.this;
        }

        @Override
        public void toast(CharSequence text) {
        }

        @Override
        public void showTip(String msg, boolean loading, boolean error) {
        }

        @Override
        public void hideTipOnUiThread() {
        }

        @Override
        public void showErrorWithRetry(String err, boolean finish) {
        }

        @Override
        public void requestNotificationPermission() {
        }

        @Override
        public int currentPlayState() {
            return released ? -1 : videoView.getCurrentPlayState();
        }

        @Override
        public long currentPosition() {
            return released ? 0 : videoView.getCurrentPosition();
        }

        @Override
        public long duration() {
            return released ? 0 : videoView.getDuration();
        }

        @Override
        public boolean isPlaying() {
            return !released && videoView.isPlaying();
        }

        @Override
        public AbstractPlayer mediaPlayer() {
            return released ? null : videoView.getMediaPlayer();
        }

        @Override
        public void releasePlayer() {
            // 与页面桥同一入口:走引擎的意图方法(所有权收口,见 PlaybackEngine.releasePlayer)
            PlaybackEngine.this.releasePlayer();
        }

        @Override
        public void setTitle(String title) {
        }

        @Override
        public void stopOtherPlayers() {
        }

        @Override
        public void resetDanmu() {
        }

        @Override
        public void clearLyric() {
        }

        @Override
        public void clearArtwork() {
            if (!released) videoView.clearArtwork();
        }

        @Override
        public void clearVideoFrame() {
            if (!released) videoView.clearVideoFrame();
        }

        @Override
        public void setSubtitleViewVisible(boolean visible) {
        }

        @Override
        public void onNewPlayStarted() {
        }

        @Override
        public void applyPlayerConfigToView(int forceKernel) {
            // 播放器配置由页面桥下发(需要 ComposeVideoController 参与);无页面时无新播放发起
        }

        @Override
        public void useTextureRenderForAudio() {
        }

        @Override
        public void switchRenderToTexture() {
            if (!released && videoView.isSurfaceRenderActive()) videoView.switchRenderToTexture();
        }

        @Override
        public void ensureRenderViewMatchesConfig() {
            if (!released) videoView.ensureRenderViewMatchesConfig();
        }

        @Override
        public boolean playExternalPlayer(int playerType, String url, String title, String subtitle,
                                          HashMap<String, String> headers, long progress) {
            return false;
        }

        @Override
        public void playM3u8(String url, HashMap<String, String> headers) {
            startVideoPlayback(url, headers, false);
        }

        @Override
        public void playM3u8(String url, HashMap<String, String> headers, int gen) {
            // 无页面桥不做净化(净化用例在页面),与 2 参实现一致直接起播,但同样要过代际
            if (!controller.isParseResultCurrent(gen)) return;
            startVideoPlayback(url, headers, false);
        }

        @Override
        public void startVideoPlayback(String url, HashMap<String, String> headers, boolean forceExoPlayer) {
            if (released) return;
            // 与页面桥一致的复用/释放防线:stopPlaybackKeepPlayer 可能留下"IDLE + 内核仍在"的组合,必须走非复用路径,
            // 且先显式 release 才能让引擎侧状态(已起播内容/进度管理器/预载归属)与内核真实情况一致。
            // 无页面时到达的取流结果窗口窄(退页面即 cancelInFlight),但迟到的嗅探/OkGo 回调仍可能撞上。
            boolean reusePlayer = !forceExoPlayer && videoView.getMediaPlayer() != null;
            if (!reusePlayer && videoView.getMediaPlayer() != null) releasePlayer();
            // 归属记录须在 releasePlayer 之后(它会 clearStartedContent)
            controller.markContentStarted();
            // 无页面 = 没有轨道菜单 = 没有用户选择:清键,免得上一部片的键留在内核上(页面起播前会重新下发)
            videoView.setTrackMemoryKey("");
            videoView.setUrl(url, headers);
            if (reusePlayer) {
                videoView.replay(false);
            } else {
                videoView.start();
            }
        }

        @Override
        public boolean switchPlayerKernel() {
            // true = 未真正换内核(无页面时不做内核回滚,避免与页面配置竞争)
            return true;
        }

        @Override
        public void applyPlayerConfig(JSONObject cfg) {
        }

        @Override
        public String firstUrlByArray(String url) {
            return PlayerSwitchUseCase.firstUrlByArray(url);
        }

        @Override
        public void setArtwork(String url) {
            if (!released) videoView.setArtwork(url);
        }

        @Override
        public void showParse(boolean show) {
        }

        @Override
        public void checkDanmu(String danmaku, Runnable onFailed) {
        }

        @Override
        public String encodeUrl(String url) {
            return PlayerSwitchUseCase.encodeUrl(url);
        }

        @Override
        public void evaluateScript(String url, WebView webView) {
        }

        @Override
        public WebView newSniffWebView() {
            // 无页面 = 没有可挂载嗅探 WebView 的内容视图(无页面的播放不会发起嗅探:新播放都由页面驱动)
            return null;
        }

        @Override
        public void attachSniffWebView(WebView webView) {
        }

        @Override
        public void startDanmuIfReady() {
            // 无页面 = 无弹幕视图
        }

        @Override
        public PreloadCoordinator.Snapshot buildPreloadSnapshot() {
            // 预载快照需要页面上下文/集信息;无页面时留空(控制器侧已做 null 保护)
            return null;
        }

        @Override
        public void showPreloadReadyTip() {
        }

        @Override
        public void hidePreloadReadyTip() {
        }

        @Override
        public boolean onLinesExhausted() {
            return false;
        }
    }
}
