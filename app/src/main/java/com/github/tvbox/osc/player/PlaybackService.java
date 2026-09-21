package com.github.tvbox.osc.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import coil3.SingletonImageLoader;
import coil3.request.ImageRequest;
import coil3.target.Target;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.ScreenUtils;

import java.lang.ref.WeakReference;

/**
 * 播放宿主服务(P2 播放服务化 + P3 通知/媒体会话并入,`skill/avbox-playback-service-spec.md` §2.1/§3)。
 *
 * <p>**职责**:
 * <ol>
 *   <li><b>托管播放引擎</b> {@link PlaybackEngine}(P2):播放器实例的生命周期长于页面 ——
 *       页面进出只做挂摘,不再重建 ExoPlayer/RenderView;任务被移除/服务销毁时释放。</li>
 *   <li><b>前台服务 + 媒体通知 + 媒体会话</b>(P3 起职责在本服务,原独立音乐服务的壳已在 P5 删除):
 *       有音频轨就维护会话(影视/音乐一视同仁),通知栏可播放/暂停/上一集/下一集/拖动;播放期间持
 *       wake/wifi 锁。由控制器({@code PlaybackController.updateMusicSession})驱动,宿主是页面或引擎。</li>
 * </ol>
 *
 * <p>**为什么引擎不是 Service 本体**:页面对引擎的取用必须与页面构造**同帧同步**(否则要处理
 * "服务未就绪 → 控制器事后替换 → 在途取流结果/观察者双投递"的竞态)。故引擎由页面同步取用
 * ({@link #engine(Context)}),本服务随后托管其生命周期。
 *
 * <p>**唯一形态**(P5 起):引擎 + 本服务即播放层;旧路径(页面自建播放器 + 独立音乐服务)已下线。
 */
public class PlaybackService extends Service {

    private static final String TAG = "echo-p2";
    private static final String CHANNEL_ID = "music_playback";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_UPDATE = "com.github.tvbox.osc.playback.UPDATE";
    private static final String ACTION_PLAY = "com.github.tvbox.osc.playback.PLAY";
    private static final String ACTION_PAUSE = "com.github.tvbox.osc.playback.PAUSE";
    private static final String ACTION_PREVIOUS = "com.github.tvbox.osc.playback.PREVIOUS";
    private static final String ACTION_NEXT = "com.github.tvbox.osc.playback.NEXT";
    private static final String ACTION_PLACEHOLDER = "com.github.tvbox.osc.playback.PLACEHOLDER";
    private static final String ACTION_STOP = "com.github.tvbox.osc.playback.STOP";
    private static final String ACTION_SEEK = "com.github.tvbox.osc.playback.SEEK";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_SUBTITLE = "subtitle";
    private static final String EXTRA_ARTWORK = "artwork";
    private static final String EXTRA_POSITION = "position";
    private static final String EXTRA_DURATION = "duration";
    private static final String EXTRA_PLAYING = "playing";
    private static final String EXTRA_SEEK = "seek";

    private static PlaybackService instance;
    private static PlaybackEngine engine;
    private static WeakReference<PlaybackHostApi> owner;
    /**
     * startForegroundService 已发出、服务尚未就绪(onCreate 未跑)。
     * ⚠️ 此窗口内绝不能 stopService:AOSP 竞态 —— create 已派发到进程,onStartCommand 可能
     * 不再交付,startForeground 永远不执行 → ForegroundServiceDidNotStartInTimeException 杀进程
     * (真机 2026-09-13:音乐起播失败重试期 start/stop 毫秒级抖动,连崩两次,vivo 超时窗 ~5s)。
     * 改为置 stopWhenStarted,让服务自己走「startForeground → stop」的合法时序。
     */
    private static volatile boolean pendingStart;
    private static volatile boolean stopWhenStarted;

    // ---- 会话资源(媒体会话/通知/锁) ----
    private MediaSessionCompat mediaSession;
    private PendingIntent sessionActivity;
    private String title = "TVBox";
    private String subtitle = "";
    private String artworkUrl = "";
    private Bitmap artwork;
    private long position;
    private long duration;
    private boolean playing;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    /**
     * 上一次 {@code startForeground} 被系统拒绝(2026-09-19)。
     *
     * <p>真机原文:{@code Service.startForeground() not allowed due to mAllowStartForeground false}
     * —— Android 12+ 禁止**后台应用**把服务提为前台。一旦被拒,服务会以"在跑但没进前台"的状态
     * 存活:唯一的前台通知已撤、前台身份丢失,进程随即可能被降级/冻结 —— 这就是"通知消失后再也
     * 回不来 + 回页面点击无反应"的起点。置位后由下一次会话更新自动重试(见 {@link #handleSessionIntent})。
     */
    private boolean foregroundDenied;
    private boolean foregroundRetryLogged;

    // ==================== 引擎入口(P2) ====================

    /**
     * 页面同步取用播放引擎(主线程调用;首次调用会顺带拉起宿主服务)。
     *
     * <p>同步返回是本设计的硬要求:页面构造期就要把 {@code scheduler}/{@code mVideoView} 指向引擎,
     * 不能"先本地建一套、服务就绪再替换"(在途取流/预载观察者会双投递)。
     */
    @NonNull
    public static PlaybackEngine engine(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (engine == null) engine = new PlaybackEngine(app);
        startHost(app, null);
        return engine;
    }

    /** 当前引擎(未创建时为 null) */
    @Nullable
    public static PlaybackEngine peek() {
        return engine;
    }

    private static void startHost(@NonNull Context app, @Nullable Intent intent) {
        if (instance != null) return;
        try {
            if (intent == null) {
                app.startService(new Intent(app, PlaybackService.class));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Throwable th) {
            // 后台启动服务受限等异常不影响播放(引擎在本进程内已可用)
            LOG.e(TAG + " startService failed: " + th.getMessage());
        }
    }

    // ==================== 媒体会话/通知入口(P3) ====================

    public static boolean isSupported(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        return !ScreenUtils.isTv(context);
    }

    /** 维护会话与通知(有音频轨就调用;影视/音乐一视同仁,见 PlaybackController.updateMusicSession) */
    public static void updateSession(Context context, PlaybackHostApi host, String title, String subtitle,
                                     String artwork, long position, long duration, boolean playing) {
        if (!isSupported(context)) return;
        // 引擎已不在(空闲 TTL 自释放 / 任务移除后)却来了迟到的会话更新:再建就会得到一条**空通知**
        // 加上无人释放的 wake/wifi 锁(释放路径已跑完)。这种调用一律丢弃。
        if (engine == null) return;
        owner = new WeakReference<>(host);
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_SUBTITLE, subtitle);
        intent.putExtra(EXTRA_ARTWORK, artwork);
        intent.putExtra(EXTRA_POSITION, position);
        intent.putExtra(EXTRA_DURATION, duration);
        intent.putExtra(EXTRA_PLAYING, playing);
        if (instance != null) {
            pendingStart = false;
            stopWhenStarted = false;
            instance.handleSessionIntent(intent);
        } else {
            // ⚠️ 这条是**后台受限启动**的入口(2026-09-19):服务不在时新建会话会走 startHost 的
            // startForegroundService 分支;若此刻 App 已在后台,系统会拒绝把服务提为前台
            // (Service.startForeground() not allowed due to mAllowStartForeground false),
            // 通知随之永远不出现。真机复现路径 = 本集自然播完撤会话后、在后台自动续播下一集。
            // 留痕以区分"服务已在前台只是没通知"与"这次压根没提到前台"。
            LOG.i(TAG + " session update with no live service → startForegroundService"
                    + " (may be denied if app is in background)");
            pendingStart = true;
            stopWhenStarted = false;
            startHost(context.getApplicationContext(), intent);
        }
    }

    /**
     * 强制结束会话(**不看 host 归属**):直播接管、引擎释放等"非页面驱动"的收尾用。
     * 归属守卫({@link #stopSession})是为"页面 B 不许停页面 A 的会话"而设,这些场景必须绕过它。
     */
    public static void forceStopSession(@Nullable Context context) {
        owner = null;
        if (instance != null) {
            pendingStart = false;
            instance.stopPlaybackSession();
        } else if (pendingStart) {
            stopWhenStarted = true;
        }
    }

    /** 结束会话:撤通知 + 释放锁与会话资源(**不释放引擎**:播放器仍要跨页面复用) */
    public static void stopSession(Context context, PlaybackHostApi host) {
        PlaybackHostApi current = owner == null ? null : owner.get();
        if (host != null && current != null && current != host) return;
        // 归属守卫:owner 是弱引用,页面被回收后 current 为 null ⇒ 守卫会放行任何调用者,留痕以便定位
        LOG.i(TAG + " stopSession: ownerMatch=" + (host != null && current == host)
                + " ownerAlive=" + (current != null) + " host=" + host);
        owner = null;
        if (instance != null) {
            pendingStart = false;
            instance.stopPlaybackSession();
        } else if (pendingStart) {
            // FGS 在途:不能 stopService(见 pendingStart 注释),登记"起来就停"
            stopWhenStarted = true;
        }
    }

    // ==================== 生命周期 ====================

    /** 通知文案走 Service 自身 Context,不包裹会是系统语言 */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageManager.INSTANCE.wrap(newBase));
    }

    /** Service 的 base 在创建时固化(切语言不会重挂)→ 文案改取 App 级(已按语言包裹过)的 Context */
    @NonNull
    private String text(int resId) {
        Context app = getApplicationContext();
        return (app == null ? this : LanguageManager.INSTANCE.localized(app)).getString(resId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        pendingStart = false;
        LOG.i(TAG + " host onCreate (engine=" + (engine == null ? "none" : "alive") + ")");
        createNotificationChannel();
        createMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_UPDATE.equals(action)) {
            // 会话请求:必须立刻进前台(否则 O+ 的 startForegroundService 超时杀进程)
            startForegroundSafely();
            handleSessionIntent(intent);
        } else if (action != null) {
            // 通知栏动作/AOSP 重建:已在 FGS 上则无事,否则补一次前台(持有通知才允许被后续 startForeground 覆盖)
            startForegroundSafely();
            handleSessionIntent(intent);
        }
        if (stopWhenStarted) {
            // 起播抖动期登记过"起来就停":先合法进前台,再收尾(见 pendingStart 注释)
            stopWhenStarted = false;
            stopPlaybackSession();
        }
        // 不自动重启:进程被回收后引擎已不存在,重启服务只会留下一个空壳
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        LOG.i(TAG + " host onTaskRemoved → release engine");
        stopPlaybackSession();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        LOG.i(TAG + " host onDestroy");
        instance = null;
        stopPlaybackSession();
        releaseEngine();
        super.onDestroy();
    }

    private static void releaseEngine() {
        PlaybackEngine current = engine;
        engine = null;
        if (current != null) current.release();
    }

    /**
     * 引擎**自己**释放后的回执(空闲 TTL 到期,见 `PlaybackEngine.IDLE_RELEASE_DELAY_MS`)。
     *
     * <p>与 {@link #releaseEngine()} 的区别:那条路径是服务主动释放(任务移除/服务销毁),静态引用已先清空;
     * 这条是引擎自下而上释放,服务必须把静态引用清掉,否则 `engine()` 会把一个已 released 的引擎继续发给新页面。
     * 引擎没了,服务也没有继续常驻的理由 —— 一并停掉(下次 `engine()` 会重新建引擎并拉起服务)。
     */
    static void onEngineReleased(@NonNull PlaybackEngine released) {
        if (engine == released) engine = null;
        owner = null;
        LOG.i(TAG + " engine self-released (idle)");
        if (instance != null) {
            instance.stopPlaybackSession();
            // ⚠️ **不 stopSelf**(2026-09-14 审查修复):stopSelf 到 onDestroy 之间有一段窗口,
            // 期间若用户打开详情页,`engine()` 会新建引擎 E2 而 `startHost` 因 `instance != null` 不拉服务;
            // 随后旧服务 onDestroy → `releaseEngine()` 会把静态 engine(此刻已是 **E2**)释放掉并置 null,
            // 页面拿到一个已释放的引擎 → 黑屏。服务本来就为托管引擎而常驻(P3 起会话结束也不 stopSelf),
            // 引擎可以重建,服务不必跟着销毁。
        }
    }

    // ==================== 会话内部实现 ====================

    private void createMediaSession() {
        mediaSession = new MediaSessionCompat(this, "TVBoxPlayback");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                PlaybackHostApi host = getOwner();
                if (host != null) host.resumeFromMediaSession();
            }

            @Override
            public void onPause() {
                PlaybackHostApi host = getOwner();
                if (host != null) host.pauseFromMediaSession();
            }

            @Override
            public void onSkipToPrevious() {
                PlaybackHostApi host = getOwner();
                if (host != null) {
                    pauseForSwitch();
                    host.playPrevious();
                }
            }

            @Override
            public void onSkipToNext() {
                PlaybackHostApi host = getOwner();
                if (host != null) {
                    pauseForSwitch();
                    host.playNext(false);
                }
            }

            @Override
            public void onStop() {
                PlaybackHostApi host = getOwner();
                if (host != null) host.stopFromMediaSession();
                stopPlaybackSession();
            }

            @Override
            public void onSeekTo(long pos) {
                PlaybackHostApi host = getOwner();
                if (host != null) host.seekFromMediaSession(pos);
            }
        }, new Handler(Looper.getMainLooper()));
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            sessionActivity = PendingIntent.getActivity(this, 0, launchIntent, flags);
            mediaSession.setSessionActivity(sessionActivity);
        }
    }

    private void startForegroundSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (mediaSession == null) return;
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            if (foregroundDenied) {
                // 之前被拒过、这次成了:前台身份已恢复(通知重新可见)
                foregroundDenied = false;
                foregroundRetryLogged = false;
                LOG.i(TAG + " startForeground recovered after denial");
            }
        } catch (Throwable th) {
            // ⚠️ 不能只打日志了事(2026-09-19):startForeground 被拒 ⇒ 服务**没进前台**,
            // 唯一的前台通知已被前一次 stopForeground 撤掉、且不会有新的 notification_enqueue,
            // 系统也不再给 FGS 的进程优先级。历史上这里静默吞掉,于是一路走到
            // 「回页面点击无反应」(引擎随进程被降级/回收而失效)都没有任何痕迹。
            // 现在置位并明确留痕,由后续会话更新自动重试。
            foregroundDenied = true;
            if (!foregroundRetryLogged) {
                foregroundRetryLogged = true;
                LOG.i(TAG + " startForeground DENIED (service stays non-foreground, will retry on"
                        + " next session update): " + th.getMessage());
            }
        }
    }

    /**
     * 通知栏 / 媒体键动作触发的"补一次前台"。
     *
     * <p>为什么单列一个入口(2026-09-19 审查补):修复的目标场景里**根本没有状态变化可供重试** ——
     * 已确认纯音频的会话退后台**不会被暂停**(`MusicPlayerActivity.hostPause` 对纯音频让路),
     * 所以回到前台时 `hostResume()` 也不产生任何播放状态事件 ⇒ 拿不到 ACTION_UPDATE ⇒
     * "回到前台即自动救回"是不成立的。真正的可达恢复点是**用户与通知/媒体键交互**这一刻:
     * 系统此刻一定允许提升前台(应用正在响应用户动作),且这一下正好是用户最可能做的操作。
     * 另有 ACTION_UPDATE 那条机会性重试(见其分支),二者互补。
     */
    private void promoteIfForegroundDenied() {
        if (!foregroundDenied) return;
        LOG.i(TAG + " media action while foreground denied → retry startForeground");
        startForegroundSafely();
    }

    private void handleSessionIntent(Intent intent) {
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            PlaybackHostApi host = getOwner();
            if (host != null) host.stopFromMediaSession();
            stopPlaybackSession();
            return;
        }
        if (ACTION_PLAY.equals(action)) {
            PlaybackHostApi host = getOwner();
            if (host != null) host.resumeFromMediaSession();
            promoteIfForegroundDenied();
            return;
        }
        if (ACTION_PAUSE.equals(action)) {
            PlaybackHostApi host = getOwner();
            if (host != null) host.pauseFromMediaSession();
            promoteIfForegroundDenied();
            return;
        }
        if (ACTION_PREVIOUS.equals(action)) {
            PlaybackHostApi host = getOwner();
            if (host != null) {
                pauseForSwitch();
                host.playPrevious();
            }
            promoteIfForegroundDenied();
            return;
        }
        if (ACTION_NEXT.equals(action)) {
            PlaybackHostApi host = getOwner();
            if (host != null) {
                pauseForSwitch();
                host.playNext(false);
            }
            promoteIfForegroundDenied();
            return;
        }
        if (ACTION_SEEK.equals(action)) {
            PlaybackHostApi host = getOwner();
            if (host != null) host.seekFromMediaSession(intent.getLongExtra(EXTRA_SEEK, 0));
            return;
        }
        if (ACTION_UPDATE.equals(action)) {
            // 会话已结束(通知已撤、mediaSession 已释放)但引擎/服务仍在:本次是**新会话** → 重建媒体会话。
            // (原实现靠 stopSelf 后由 onCreate 重建;并入后服务为托管引擎而常驻,必须自己重建)
            if (mediaSession == null) createMediaSession();
            // 重建失败(理论上不会)时不能再走下去:acquirePlaybackLocks 会重新持锁且无人释放(电量泄漏)、
            // buildNotification 曾在真机上直接 NPE 崩溃(2026-09-13 实锤路径)
            if (mediaSession == null) return;
            acquirePlaybackLocks();
            title = intent.getStringExtra(EXTRA_TITLE);
            subtitle = intent.getStringExtra(EXTRA_SUBTITLE);
            String newArtworkUrl = intent.getStringExtra(EXTRA_ARTWORK);
            position = intent.getLongExtra(EXTRA_POSITION, 0);
            duration = intent.getLongExtra(EXTRA_DURATION, 0);
            playing = intent.getBooleanExtra(EXTRA_PLAYING, false);
            updateArtwork(newArtworkUrl);
            updateSessionState();
            // 每次会话更新都顺手补一次前台:若上一次 startForeground 被系统拒(见 startForegroundSafely
            // 注释),服务此刻在跑但没有前台身份、也没有通知 —— 这次调用就是机会性重试,成功时
            // startForegroundSafely 内部会清标记并打 recovered 日志。**不在此打"正在重试"的日志**:
            // 被持续拒绝时那会每次更新刷一行,而本次修复的用意正是"别再静默、也别刷屏"。
            // 另一个更可靠的恢复点见 promoteIfForegroundDenied(用户动通知/媒体键那一刻)。
            startForegroundSafely();
        }
    }

    private void updateArtwork(String url) {
        if (TextUtils.equals(artworkUrl, url)) return;
        artworkUrl = url == null ? "" : url;
        artwork = null;
        if (TextUtils.isEmpty(artworkUrl)) return;
        // 封面地址的 @Headers= 等附加参数由 VodImages 的 OkHttp 拦截器剥离并注入请求头
        ImageRequest request = new ImageRequest.Builder(this)
                .data(artworkUrl)
                .size(256, 256)
                .target(new Target() {
                    @Override
                    public void onSuccess(coil3.Image image) {
                        // toBitmap 保证软件位图,通知 RemoteViews 不接受硬件位图
                        artwork = coil3.Image_androidKt.toBitmap(image);
                        // 图片下载期间会话可能已被结束(mediaSession 被置 null):此时再刷新通知会
                        // 在 buildNotification() 内对 null mediaSession 取 sessionToken 而崩溃(2026-09-13 修复)
                        if (mediaSession == null) return;
                        updateSessionState();
                        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
                    }
                })
                .build();
        SingletonImageLoader.get(this).enqueue(request);
    }

    private void updateSessionState() {
        if (mediaSession == null) return;
        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, subtitle);
        if (duration > 0) metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        if (!TextUtils.isEmpty(artworkUrl)) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUrl);
        }
        if (artwork != null) metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork);
        mediaSession.setMetadata(metadata.build());
        long action = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;
        int state = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(action)
                .setState(state, position, playing ? 1f : 0f)
                .build());
        mediaSession.setActive(true);
    }

    private void pauseForSwitch() {
        playing = false;
        position = 0;
        updateSessionState();
        startForegroundSafely();
    }

    private Notification buildNotification() {
        NotificationCompat.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new NotificationCompat.Builder(this, CHANNEL_ID)
                : new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification_music)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(sessionActivity)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setDeleteIntent(actionIntent(ACTION_STOP))
                // mediaSession 可能已被 stopPlaybackSession 置 null(封面异步回调晚于停止):
                // 这里判空兜底,防 getSessionToken() NPE(2026-09-13 修复)
                .setStyle(new MediaStyle().setMediaSession(mediaSession == null ? null : mediaSession.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3));
        if (artwork != null) builder.setLargeIcon(artwork);
        builder.addAction(new NotificationCompat.Action(R.drawable.media_action_placeholder, "", actionIntent(ACTION_PLACEHOLDER)));
        builder.addAction(new NotificationCompat.Action(R.drawable.exo_icon_previous, text(R.string.player_notification_previous), actionIntent(ACTION_PREVIOUS)));
        builder.addAction(new NotificationCompat.Action(playing ? R.drawable.exo_icon_pause : R.drawable.exo_icon_play,
                text(playing ? R.string.common_pause : R.string.common_play),
                actionIntent(playing ? ACTION_PAUSE : ACTION_PLAY)));
        builder.addAction(new NotificationCompat.Action(R.drawable.exo_icon_next, text(R.string.player_notification_next), actionIntent(ACTION_NEXT)));
        return builder.build();
    }

    private PendingIntent actionIntent(String action) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, action.hashCode(), intent, flags);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                text(R.string.player_notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(text(R.string.player_notification_channel_desc));
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void acquirePlaybackLocks() {
        try {
            if (wakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVBox:Playback");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
                LOG.i("echo-music wake lock acquired");
            }
        } catch (Throwable th) {
            LOG.i("echo-music wake lock acquire failed: " + th.getMessage());
        }
        try {
            if (wifiLock == null) {
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVBox:Playback");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
                LOG.i("echo-music wifi lock acquired");
            }
        } catch (Throwable th) {
            LOG.i("echo-music wifi lock acquire failed: " + th.getMessage());
        }
    }

    private void releasePlaybackLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                LOG.i("echo-music wifi lock released");
            }
        } catch (Throwable th) {
            LOG.i("echo-music wifi lock release failed: " + th.getMessage());
        } finally {
            wifiLock = null;
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                LOG.i("echo-music wake lock released");
            }
        } catch (Throwable th) {
            LOG.i("echo-music wake lock release failed: " + th.getMessage());
        } finally {
            wakeLock = null;
        }
    }

    private PlaybackHostApi getOwner() {
        return owner == null ? null : owner.get();
    }

    /**
     * 结束会话:撤通知 + 释放锁与媒体会话。
     * **不 stopSelf、不释放引擎** —— 播放器要继续跨页面复用(P2),任务移除/服务销毁时才释放(见 onTaskRemoved)。
     */
    private void stopPlaybackSession() {
        // 撤通知的唯一收尾点:留痕以便定位"通知自己消失"的路径
        LOG.i(TAG + " stopPlaybackSession (notification 1001 removed, playing=" + playing + ")");
        playing = false;
        // 会话结束 ⇒ "本会话曾进不了前台"这件事随之失效,两个标记一起复位
        // (只复位一个会让 foregroundRetryLogged 永远留在 true:整个服务生命周期只打一次
        //  DENIED 日志 —— 而"留痕"正是这两个标记存在的理由,再被拒就看不见了)
        foregroundDenied = false;
        foregroundRetryLogged = false;
        releasePlaybackLocks();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        stopForeground(true);
    }
}
