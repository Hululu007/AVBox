package com.github.tvbox.osc.player;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.PlayerHelper;

import java.util.Map;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.ui.widget.DanmakuView;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class MyVideoView extends VideoView implements DrawHandler.Callback {
    private DanmakuView danmuView;
    private ImageView artworkView;
    /** 封面的在途图片请求句柄:换图/隐藏前必须先取消,否则过期海报可能盖到画面上(见 clearArtwork) */
    private coil3.request.Disposable artworkDisposable;
    private View frameCover;

    // updateCfg 保存的用户配置引擎;播放 rtmp 源时临时切换 ijk,切回非 rtmp 源时还原
    private PlayerFactory<? extends AbstractPlayer> mConfiguredFactory;
    private boolean mRtmpForced;
    /** 点播磁盘缓存标记(第二期扩展「边播边缓存」):默认 false(直播页不设置),点播容器 PlayContainer 启用 */
    private boolean mExoDiskCacheEnabled;
    /** "本次起播必须重建内核"标记(EXO 解码方式变更,见 PlayerHelper.updateCfg) */
    private boolean mKernelRebuildRequired;
    /**
     * 本次播放的有效 IJK 解码名("本剧配置 → 缺省全局",由 {@code PlayerHelper.updateCfg} 下发)。
     * rtmp 强制 IJK 的工厂/推送在 {@link #setUrl} 时才建,那时拿不到 playerCfg —— 靠这里带上
     * (见 PlayerHelper.applyRtmpSchemeOverride)。
     */
    private String mEffectiveIjkCodec;

    /**
     * 点播磁盘缓存标记:true 时 Exo 播放器对普通集也使用 cache 数据源(边播边缓存)。
     * 标志存于 VideoView(而非播放器实例),内核切换/自动重试重建播放器后仍自动生效(见 initPlayer 覆写)。
     */
    public void setExoDiskCacheEnabled(boolean enabled) {
        mExoDiskCacheEnabled = enabled;
        applyExoDiskCacheFlag();
    }

    /** 本片记忆键(见 TrackMemory);存于 VideoView 是因为内核重建后要把键推给新实例 */
    private String mTrackMemoryKey = "";

    public void setTrackMemoryKey(String key) {
        mTrackMemoryKey = key == null ? "" : key;
        applyTrackMemoryKey();
    }

    @Override
    protected void initPlayer() {
        super.initPlayer();
        applyExoDiskCacheFlag();
        applyTrackMemoryKey();
    }

    private void applyTrackMemoryKey() {
        if (mMediaPlayer instanceof ExoPlayer) {
            ((ExoPlayer) mMediaPlayer).setContentKey(mTrackMemoryKey);
        } else if (mMediaPlayer instanceof IjkMediaPlayer) {
            ((IjkMediaPlayer) mMediaPlayer).setContentKey(mTrackMemoryKey);
        }
    }

    private void applyExoDiskCacheFlag() {
        if (mMediaPlayer instanceof ExoPlayer) {
            ((ExoPlayer) mMediaPlayer).setUseDiskCache(mExoDiskCacheEnabled);
        }
    }

    public MyVideoView(@NonNull Context context) {
        super(context, null);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AbstractPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

    public void saveConfiguredFactory(PlayerFactory<? extends AbstractPlayer> factory) {
        mConfiguredFactory = factory;
        mRtmpForced = false;
    }

    public boolean isRtmpForced() {
        return mRtmpForced;
    }

    /**
     * 标记"本次起播必须重建内核"(2026-09-17,EXO 解码方式变更时由 PlayerHelper.updateCfg 写入)。
     *
     * <p>为什么 EXO 必须重建:media3 跨 period 复用同一 MediaCodec(disable 时只 flush 不 release),
     * 选择器不会再被查询 —— 换集走复用路径时只改选择器的静态下发位不生效,必须让内核重建。
     */
    public void requireKernelRebuild() {
        mKernelRebuildRequired = true;
    }

    /** 取出并复位"必须重建内核"标记(起播处消费;true 时走非复用路径,先释放再新建) */
    public boolean consumeKernelRebuildRequired() {
        boolean required = mKernelRebuildRequired;
        mKernelRebuildRequired = false;
        return required;
    }

    /** 记录本次播放的有效 IJK 解码名(见 PlayerHelper.updateCfg / applyRtmpSchemeOverride) */
    public void setEffectiveIjkCodec(String name) {
        mEffectiveIjkCodec = name;
    }

    /** 本次播放的有效 IJK 解码名;未下发过(从未走过 updateCfg)返回 null,调用方回落全局设置 */
    public String effectiveIjkCodec() {
        return mEffectiveIjkCodec;
    }

    public void forceIjkFactory(PlayerFactory<? extends AbstractPlayer> factory) {
        mRtmpForced = true;
        setPlayerFactory(factory);
    }

    public void restoreConfiguredFactory() {
        if (!mRtmpForced) return;
        mRtmpForced = false;
        if (mConfiguredFactory != null) {
            setPlayerFactory(mConfiguredFactory);
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setUrl(String url, Map headers) {
        PlayerHelper.applyRtmpSchemeOverride(this, url);
        super.setUrl(url, headers);
    }

    /** 当前渲染视图是否为 SurfaceView(见 [switchRenderToTexture] 的纯音频兜底) */
    public boolean isSurfaceRenderActive() {
        return mRenderView != null && mRenderView.getView() instanceof SurfaceView;
    }

    /**
     * 纯音频(音乐)渲染热切换:Surface → Texture(2026-09-13)。
     * SurfaceView 的画面在独立于应用窗口的合成层上(且本渲染视图用 RGBA_8888 可透明格式),
     * 应用窗口在播放器矩形被"打洞":无视频帧的内容(音乐)全靠空 Surface 垫底呈黑 ——
     * 退后台任务快照里 Surface 垫底消失,播放器区域只剩窗口底色(多任务卡片变白);
     * 回前台 Surface 重建前过渡动画还会透视到桌面(闪烁变透明)。
     * TextureView 画在应用窗口图层内,无帧呈黑、快照与过渡全部正常(实测)。
     * 纯音频确认后切换零渲染开销、音频不中断;旧 SurfaceView 摘除后 surfaceDestroyed
     * 异步回调的 setDisplay(null) 落在无视频轨的播放器上是无操作,不影响新 Texture 挂载。
     */
    public void switchRenderToTexture() {
        setRenderViewFactory(TextureRenderViewFactory.create());
        addDisplay();
    }

    /**
     * 渲染视图与当前 RenderViewFactory 配置不一致时按工厂重建(2026-09-13 修复)。
     *
     * <p>背景(与 [switchRenderToTexture] 配套):纯音频会热切成 TextureView;但换集走 reusePlayer
     * 路径(fork 的 {@code VideoView.replay(false)} → {@code startPrepare},两个分支都不会调用
     * addDisplay),而 {@code PlayerHelper.updateCfg} 只改工厂、不重建视图 —— 于是之后有视频的
     * 集数会继续留在 TextureView 上渲染,与"画面渲染"设置不符。addDisplay() 会移除旧视图并按
     * 当前工厂新建(即热切换);类型一致(绝大多数场景)时本方法直接返回,零开销、无闪烁。
     */
    public void ensureRenderViewMatchesConfig() {
        // mRenderView 空 = 尚未挂载(下次 start() 会按工厂创建);
        // mMediaPlayer 空 = 无播放器可挂载 —— addDisplay 内 attachToPlayer(null) 属未定义调用,
        // 直接返回更稳(与 clearVideoFrame 的判空风格一致)
        if (mRenderView == null || mMediaPlayer == null) return;
        boolean expectedSurface = !(mRenderViewFactory instanceof TextureRenderViewFactory);
        if (expectedSurface == isSurfaceRenderActive()) return;
        addDisplay();
    }

    public void setArtwork(String url) {
        if (TextUtils.isEmpty(url)) {
            clearArtwork();
            return;
        }
        if (artworkView == null) {
            artworkView = new ImageView(getContext());
            artworkView.setBackgroundColor(android.graphics.Color.BLACK);
            artworkView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            artworkView.setClickable(false);
            artworkView.setFocusable(false);
            int index = mRenderView == null ? 0 : Math.min(1, mPlayerContainer.getChildCount());
            mPlayerContainer.addView(artworkView, index, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        artworkView.setVisibility(VISIBLE);
        // 先撤旧请求再发新请求:封面每换一集/一线都要换图,上一个请求的迟到回调会把过期海报盖上来
        cancelArtworkRequest();
        artworkDisposable = ImgUtil.loadPlayerArtwork(url, artworkView);
    }

    public void clearArtwork() {
        // 取消在途请求,再隐藏:Coil 的 onSuccess 不检查视图可见性,只置 GONE 挡不住晚到的位图
        cancelArtworkRequest();
        if (artworkView != null) {
            artworkView.setVisibility(GONE);
            artworkView.setImageDrawable(null);
        }
    }

    /** 取消播放器封面的在途图片请求(Coil dispose 会同步置 isDisposed 并取消 job,晚到回调不再落地) */
    private void cancelArtworkRequest() {
        if (artworkDisposable != null) {
            artworkDisposable.dispose();
            artworkDisposable = null;
        }
    }

    public int[] getVideoSize() {
        return mVideoSize;
    }

    public boolean isPortraitVideo() {
        return VideoOrientation.isPortrait(mVideoSize[0], mVideoSize[1]);
    }

    public void clearVideoFrame() {
        if (mMediaPlayer != null) mMediaPlayer.stop();
        showFrameCover();
    }

    /**
     * 只遮黑、不动内核:页面挂载时用 —— 旧内容停在 PAUSED 时,media3 会在新 Surface 重建时
     * 把上一帧重渲染出来。不能复用 {@link #clearVideoFrame()}:它内部 stop 内核,
     * 而"同片接管"要靠内核里留着的内容续播(见 PlayContainer.isSamePlaybackOwned)。
     */
    public void coverVideoFrame() {
        showFrameCover();
    }

    private void showFrameCover() {
        if (frameCover == null) {
            frameCover = new View(getContext());
            frameCover.setBackgroundColor(android.graphics.Color.BLACK);
            mPlayerContainer.addView(frameCover, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        frameCover.setVisibility(VISIBLE);
        // 遮罩是追加的,会把控制器(顶栏/手势层/字幕/直播控制层)一起盖住;控制器属 UI 层必须压在最上。
        // 用 bringToFront 而不是按 index 插:addDisplay() 永远把渲染视图插到 index 0,index 方案在
        // "渲染视图尚未创建"时会算错位(此时容器里可能只有控制器)
        if (mVideoController != null) mVideoController.bringToFront();
    }

    public void showVideoFrame() {
        hideVideoFrameCover();
        // 画面已出 → 顺手撤掉封面:artworkView 与渲染 Surface 同层且盖在其上,
        // 任何「画面已就绪却仍显示封面」的时序都会把视频压成一张海报(有声无画)。
        // 这里做终极兜底,保证「有画面」与「显示封面」互斥。
        clearArtwork();
    }

    /** 纯音频没有画面可露:只收黑帧、**保留封面**(海报就是它的背景,播放中不能只剩黑底) */
    public void hideVideoFrameCover() {
        if (frameCover != null) frameCover.setVisibility(GONE);
    }

    public boolean isVideoFrameCleared() {
        return frameCover != null && frameCover.getVisibility() == VISIBLE;
    }

    @Override
    public void seekTo(long pos) {
        super.seekTo(pos);
        if (haveDanmu()) danmuView.seekTo(pos);
    }

    @Override
    public void resume() {
        super.resume();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void start() {
        super.start();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void pause() {
        super.pause();
        if (haveDanmu()) danmuView.pause();
    }

    @Override
    public void release() {
        super.release();
        // 键随内核一起作废:直播页/音乐页直接 setUrl 起播从不推键,留着上一部片的键会吃掉它们的默认字幕
        mTrackMemoryKey = "";
        if (haveDanmu()) danmuView.release();
    }

    private boolean haveDanmu() {
        return danmuView != null && danmuView.isPrepared();
    }

    public void setDanmuView(DanmakuView view) {
        danmuView = view;
        if (danmuView != null) danmuView.setCallback(this);
    }

    public DanmakuView getDanmuView() {
        return danmuView;
    }

    @Override
    public void prepared() {
        post(() -> {
            if (danmuView == null) return;
            if (isPlaying() && danmuView.isPrepared()) {
                danmuView.start(getCurrentPosition());
            }
        });
    }

    @Override
    public void updateTimer(DanmakuTimer timer) {
    }

    @Override
    public void danmakuShown(BaseDanmaku danmaku) {
    }

    @Override
    public void drawingFinished() {
    }
}
