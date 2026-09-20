package xyz.doikki.videoplayer.player;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.player.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.controller.MediaPlayerControl;
import xyz.doikki.videoplayer.render.IRenderView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.util.L;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * 播放器
 * Created by Doikki on 2017/4/7.
 */

public class VideoView<P extends AbstractPlayer> extends FrameLayout
        implements MediaPlayerControl, AbstractPlayer.PlayerEventListener {

    protected P mMediaPlayer;//播放器
    protected PlayerFactory<P> mPlayerFactory;//工厂类，用于实例化播放核心
    @Nullable
    protected BaseVideoController mVideoController;//控制器

    /**
     * 真正承载播放器视图的容器
     */
    protected FrameLayout mPlayerContainer;

    protected IRenderView mRenderView;
    protected RenderViewFactory mRenderViewFactory;

    public static final int SCREEN_SCALE_DEFAULT = 0;
    public static final int SCREEN_SCALE_16_9 = 1;
    public static final int SCREEN_SCALE_4_3 = 2;
    public static final int SCREEN_SCALE_MATCH_PARENT = 3;
    public static final int SCREEN_SCALE_ORIGINAL = 4;
    public static final int SCREEN_SCALE_CENTER_CROP = 5;
    protected int mCurrentScreenScaleType;

    protected int[] mVideoSize = {0, 0};

    protected boolean mIsMute;//是否静音

    //--------- data sources ---------//
    protected String mUrl;//当前播放视频的地址
    protected String mProgressKey = null;
    protected Map<String, String> mHeaders;//当前视频地址的请求头
    protected AssetFileDescriptor mAssetFileDescriptor;//assets文件

    protected long mCurrentPosition;//当前正在播放视频的位置

    //播放器的各种状态
    public static final int STATE_ERROR = -1;
    public static final int STATE_IDLE = 0;
    public static final int STATE_PREPARING = 1;
    public static final int STATE_PREPARED = 2;
    public static final int STATE_PLAYING = 3;
    public static final int STATE_PAUSED = 4;
    public static final int STATE_PLAYBACK_COMPLETED = 5;
    public static final int STATE_BUFFERING = 6;
    public static final int STATE_BUFFERED = 7;
    public static final int STATE_START_ABORT = 8;//开始播放中止
    protected int mCurrentPlayState = STATE_IDLE;//当前播放器的状态

    /**
     * seek 前处于暂停:内核围绕 seek 会发缓冲/首帧回调把播放状态顶离 PAUSED,而 setPlayState(STATE_PAUSED)
     * 只在 pause() 里 ⇒ 暂停语义丢失(暂停浮层不再出现、中央播放暂停图标与实际画面不一致)。改变播放意图的动作清除。
     */
    private boolean mPausedBeforeSeek;

    public static final int PLAYER_NORMAL = 10;        // 普通播放器
    public static final int PLAYER_FULL_SCREEN = 11;   // 全屏播放器
    public static final int PLAYER_TINY_SCREEN = 12;   // 小屏播放器
    protected int mCurrentPlayerState = PLAYER_NORMAL;

    protected boolean mIsFullScreen;//是否处于全屏状态

    protected boolean mIsTinyScreen;//是否处于小屏状态
    protected int[] mTinyScreenSize = {0, 0};

    /**
     * 监听系统中音频焦点改变，见{@link #setEnableAudioFocus(boolean)}
     */
    protected boolean mEnableAudioFocus;
    @Nullable
    protected AudioFocusHelper mAudioFocusHelper;

    /**
     * OnStateChangeListener集合，保存了所有开发者设置的监听器
     */
    protected List<OnStateChangeListener> mOnStateChangeListeners;

    /**
     * 进度管理器，设置之后播放器会记录播放进度，以便下次播放恢复进度
     */
    @Nullable
    protected ProgressManager mProgressManager;

    /**
     * 循环播放
     */
    protected boolean mIsLooping;

    /**
     * {@link #mPlayerContainer}背景色，默认黑色
     */
    private int mPlayerBackgroundColor;

    public VideoView(@NonNull Context context) {
        this(context, null);
    }

    public VideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        //读取全局配置
        VideoViewConfig config = VideoViewManager.getConfig();
        mEnableAudioFocus = config.mEnableAudioFocus;
        mProgressManager = config.mProgressManager;
        mPlayerFactory = config.mPlayerFactory;
        mCurrentScreenScaleType = config.mScreenScaleType;
        mRenderViewFactory = config.mRenderViewFactory;

        //读取xml中的配置，并综合全局配置
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.VideoView);
        mEnableAudioFocus = a.getBoolean(R.styleable.VideoView_enableAudioFocus, mEnableAudioFocus);
        mIsLooping = a.getBoolean(R.styleable.VideoView_looping, false);
        mCurrentScreenScaleType = a.getInt(R.styleable.VideoView_screenScaleType, mCurrentScreenScaleType);
        mPlayerBackgroundColor = a.getColor(R.styleable.VideoView_playerBackgroundColor, Color.BLACK);
        a.recycle();

        initView();
    }

    /**
     * 初始化播放器视图
     */
    protected void initView() {
        mPlayerContainer = new FrameLayout(getContext());
        mPlayerContainer.setBackgroundColor(mPlayerBackgroundColor);
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        this.addView(mPlayerContainer, params);
    }

    /**
     * 设置{@link #mPlayerContainer}的背景色
     */
    public void setPlayerBackgroundColor(int color) {
        mPlayerContainer.setBackgroundColor(color);
    }

    /**
     * 开始播放，注意：调用此方法后必须调用{@link #release()}释放播放器，否则会导致内存泄漏
     */
    @Override
    public void start() {
        if (isInIdleState()
                || isInStartAbortState()) {
            startPlay();
        } else if (isInPlaybackState()) {
            startInPlaybackState();
        }
    }

    /**
     * 第一次播放
     *
     * @return 是否成功开始播放
     */
    protected boolean startPlay() {
        //如果要显示移动网络提示则不继续播放
        if (showNetWarning()) {
            //中止播放
            setPlayState(STATE_START_ABORT);
            return false;
        }
        //监听音频焦点改变(只建一次:覆盖引用会留下永不释放的旧 listener,它们仍会响应焦点事件去 pause/start)
        if (mEnableAudioFocus) {
            if (mAudioFocusHelper == null) {
                mAudioFocusHelper = new AudioFocusHelper(this);
            }
            mAudioFocusHelper.onNewPlayback();
        }
        //读取播放进度
        if (mProgressManager != null) {
            mCurrentPosition = mProgressManager.getSavedProgress(mProgressKey == null ? mUrl : mProgressKey);
        }
        initPlayer();
        addDisplay();
        startPrepare(false);
        return true;
    }

    /**
     * 是否显示移动网络提示，可在Controller中配置
     */
    protected boolean showNetWarning() {
        //播放本地数据源时不检测网络
        if (isLocalDataSource()) return false;
        return mVideoController != null && mVideoController.showNetWarning();
    }

    /**
     * 判断是否为本地数据源，包括 本地文件、Asset、raw
     */
    protected boolean isLocalDataSource() {
        if (mAssetFileDescriptor != null) {
            return true;
        } else if (!TextUtils.isEmpty(mUrl)) {
            Uri uri = Uri.parse(mUrl);
            return ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())
                    || ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                    || "rawresource".equals(uri.getScheme());
        }
        return false;
    }

    /**
     * 初始化播放器
     */
    protected void initPlayer() {
        mMediaPlayer = mPlayerFactory.createPlayer(getContext());
        mMediaPlayer.setPlayerEventListener(this);
        setInitOptions();
        mMediaPlayer.initPlayer();
        setOptions();
    }

    /**
     * 初始化之前的配置项
     */
    protected void setInitOptions() {
    }

    /**
     * 初始化之后的配置项
     */
    protected void setOptions() {
        mMediaPlayer.setLooping(mIsLooping);
        float volume = mIsMute ? 0.0f : 1.0f;
        mMediaPlayer.setVolume(volume, volume);
    }

    /**
     * 初始化视频渲染View
     */
    protected void addDisplay() {
        if (mRenderView != null) {
            mPlayerContainer.removeView(mRenderView.getView());
            mRenderView.release();
        }
        mRenderView = mRenderViewFactory.createRenderView(getContext());
        mRenderView.attachToPlayer(mMediaPlayer);
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        mPlayerContainer.addView(mRenderView.getView(), 0, params);
    }

    /**
     * 开始准备播放（直接播放）
     */
    protected void startPrepare(boolean reset) {
        startPrepare(reset, false);
    }

    protected void startPrepare(boolean reset, boolean rebindRenderView) {
        // 新一次起播(replay 也走这里):seek 前的暂停记忆随之作废,否则会把在播的新内容按回暂停
        mPausedBeforeSeek = false;
        if (reset) {
            mMediaPlayer.reset();
            //重新设置option，media player reset之后，option会失效
            setOptions();
            if (rebindRenderView && mRenderView != null) {
                mRenderView.attachToPlayer(mMediaPlayer);
            }
        }
        if (prepareDataSource()) {
            mMediaPlayer.setStartPosition(mCurrentPosition);
            mMediaPlayer.prepareAsync();
            setPlayState(STATE_PREPARING);
            setPlayerState(isFullScreen() ? PLAYER_FULL_SCREEN : isTinyScreen() ? PLAYER_TINY_SCREEN : PLAYER_NORMAL);
        }
    }

    /**
     * 设置播放数据
     *
     * @return 播放数据是否设置成功
     */
    protected boolean prepareDataSource() {
        if (mAssetFileDescriptor != null) {
            mMediaPlayer.setDataSource(mAssetFileDescriptor);
            return true;
        } else if (!TextUtils.isEmpty(mUrl)) {
            mMediaPlayer.setDataSource(mUrl, mHeaders);
            return true;
        }
        return false;
    }

    /**
     * 播放状态下开始播放
     */
    protected void startInPlaybackState() {
        mPausedBeforeSeek = false;
        mMediaPlayer.start();
        setPlayState(STATE_PLAYING);
        if (mAudioFocusHelper != null && !isMute()) {
            mAudioFocusHelper.requestFocus();
        }
        mPlayerContainer.setKeepScreenOn(true);
    }

    /**
     * 暂停播放
     */
    @Override
    public void pause() {
        if (isInPlaybackState()
                && mMediaPlayer.isPlaying()) {
            // 只在暂停真正生效时清:无效的 pause()(内核本就没在播)不能把"暂停记忆"提前丢掉
            mPausedBeforeSeek = false;
            mMediaPlayer.pause();
            setPlayState(STATE_PAUSED);
            if (mAudioFocusHelper != null && !isMute()) {
                mAudioFocusHelper.abandonFocus();
            }
            mPlayerContainer.setKeepScreenOn(false);
        }
    }

    /**
     * 继续播放
     */
    public void resume() {
        if (isInPlaybackState() && !mMediaPlayer.isPlaying()) {
            assert mRenderView != null;
            View renderView = mRenderView.getView();
            if (renderView instanceof SurfaceView) {
                final SurfaceView surfaceView = (SurfaceView) renderView;
                final SurfaceHolder holder = surfaceView.getHolder();
                if (holder.getSurface() != null && holder.getSurface().isValid()) {
                    mMediaPlayer.setDisplay(holder);
                    resumePlay();
                } else {
                    holder.addCallback(new SurfaceHolder.Callback() {
                        @Override
                        public void surfaceCreated(SurfaceHolder holder) {
                            if (mRenderView != null) {
                                mRenderView.setScaleType(mCurrentScreenScaleType);
                                mRenderView.setVideoSize(mVideoSize[0], mVideoSize[1]);
                            }
                            mMediaPlayer.setDisplay(holder);
                            resumePlay();
                            // 移除回调，避免重复调用
                            holder.removeCallback(this);
                        }

                        @Override
                        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                        }

                        @Override
                        public void surfaceDestroyed(SurfaceHolder holder) {
                        }
                    });
                }
            } else {
                resumePlay();
            }
        }
    }

    private void resumePlay(){
        mPausedBeforeSeek = false;
        mMediaPlayer.start();
        setPlayState(STATE_PLAYING);
        if (mAudioFocusHelper != null && !isMute()) {
            mAudioFocusHelper.requestFocus();
        }
        mPlayerContainer.setKeepScreenOn(true);
    }
    /**
     * 停止播放但**保留播放器实例**(P2 服务化后新增)。
     *
     * <p>与 {@link #release()} 的区别:只 stop 内核(会打断 PREPARING/BUFFERING 中的起播),
     * 不销毁实例、不卸载渲染视图 —— 因此实例可跨页面复用(下一次起播走 reusePlayer 的 reset 路径)。
     *
     * <p>存在的理由:{@link #pause()} 只在已进入 playback 态时生效;而"刚点播放就退出页面"时状态是
     * PREPARING/BUFFERING,只 pause 的话这一集会在页面销毁后自己播起来(页面没了却在响)。
     */
    public void stopPlaybackKeepPlayer() {
        if (mMediaPlayer == null) return;
        // 已被 pause() 停住的保持 PAUSED:**PAUSED + 实例仍在 = 可复用状态**;
        // 反之若置成 IDLE,则 IDLE + 实例仍在 是个危险组合 —— 此后任何 start() 都会走
        // startPlay() → initPlayer() 新建一个内核并覆盖旧的(旧的不会被 release),既泄漏又毁掉跨页复用
        if (mCurrentPlayState == STATE_PAUSED) return;
        mMediaPlayer.stop();
        setPlayState(STATE_IDLE);
    }

    /**
     * 显式落盘当前进度(P2 服务化后新增)。
     *
     * <p>背景:改造前"退出页面即 {@link #release()}",进度由 release() 内部的 saveProgress 落盘。
     * 服务化后播放器归引擎、页面退出不再 release(否则跨页复用就没了),因此必须提供这个显式入口 ——
     * 否则"退出详情页 → 再进"会丢失上次位置(不能再续播)。
     *
     * <p>无进度管理器(直播模式)或位置为 0 时是空操作(与 saveProgress 自身守卫一致)。
     */
    public void saveCurrentProgress() {
        saveProgress();
    }

    /**
     * 释放播放器
     */
    public void release() {
        mPausedBeforeSeek = false;
        //焦点监听与 IDLE 无关:stopPlaybackKeepPlayer 置 IDLE 后再 release 也必须清,否则 listener 残留在系统里
        if (mAudioFocusHelper != null) {
            mAudioFocusHelper.abandonFocus();
            mAudioFocusHelper = null;
        }
        if (!isInIdleState()) {
            //释放播放器
            if (mMediaPlayer != null) {
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
            //释放renderView
            if (mRenderView != null) {
                mPlayerContainer.removeView(mRenderView.getView());
                mRenderView.release();
                mRenderView = null;
            }
            //释放Assets资源
            if (mAssetFileDescriptor != null) {
                try {
                    mAssetFileDescriptor.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            //关闭屏幕常亮
            mPlayerContainer.setKeepScreenOn(false);
            //保存播放进度
            saveProgress();
            //重置播放进度
            mCurrentPosition = 0;
            //切换转态
            setPlayState(STATE_IDLE);
        }
        mVideoSize[0] = 0;
        mVideoSize[1] = 0;
    }

    /**
     * 保存播放进度
     */
    protected void saveProgress() {
        if (mProgressManager != null && mCurrentPosition > 0) {
            L.d("saveProgress: " + mCurrentPosition);
            mProgressManager.saveProgress(mProgressKey == null ? mUrl : mProgressKey, mCurrentPosition);
        }
    }

    /**
     * 是否处于播放状态
     */
    protected boolean isInPlaybackState() {
        return mMediaPlayer != null
                && mCurrentPlayState != STATE_ERROR
                && mCurrentPlayState != STATE_IDLE
                && mCurrentPlayState != STATE_PREPARING
                && mCurrentPlayState != STATE_START_ABORT
                && mCurrentPlayState != STATE_PLAYBACK_COMPLETED;
    }

    /**
     * 是否处于未播放状态
     */
    protected boolean isInIdleState() {
        return mCurrentPlayState == STATE_IDLE;
    }

    /**
     * 播放中止状态
     */
    private boolean isInStartAbortState() {
        return mCurrentPlayState == STATE_START_ABORT;
    }

    /**
     * 重新播放
     *
     * @param resetPosition 是否从头开始播放
     */
    @Override
    public void replay(boolean resetPosition) {
        if (resetPosition) {
            mCurrentPosition = 0;
        }
        if (mMediaPlayer == null) {
            start();
            return;
        }
        if (mMediaPlayer.keepRenderViewOnReset()) {
            mMediaPlayer.reset();
            setOptions();
            mMediaPlayer.setOptions();
            startPrepare(false);
        } else {
            startPrepare(true, true);
        }
    }

    /**
     * 获取视频总时长
     */
    @Override
    public long getDuration() {
        if (isInPlaybackState()) {
            return mMediaPlayer.getDuration();
        }
        return 0;
    }

    /**
     * 获取当前播放的位置
     */
    @Override
    public long getCurrentPosition() {
        if (isInPlaybackState()) {
            mCurrentPosition = mMediaPlayer.getCurrentPosition();
            return mCurrentPosition;
        }
        return 0;
    }

    /**
     * 调整播放进度
     */
    @Override
    public void seekTo(long pos) {
        if (isInPlaybackState()) {
            // 暂停中的 seek:内核不会因此续播,但随后的缓冲回调会把状态顶离 PAUSED,先记下
            if (mCurrentPlayState == STATE_PAUSED) {
                mPausedBeforeSeek = true;
            }
            mMediaPlayer.seekTo(pos);
        }
    }

    /**
     * seek 期间的缓冲/首帧回调是否仍按"暂停"呈现:返回 true 时调用方不得再改播放状态。
     * 用户 seek 后自己点了播放(start/resume)会先清掉标记,不会被误按回暂停。
     */
    private boolean keepPausedStateAfterSeek() {
        if (!mPausedBeforeSeek) return false;
        if (mCurrentPlayState != STATE_PAUSED) {
            setPlayState(STATE_PAUSED);
        }
        return true;
    }

    /**
     * 是否处于播放状态
     */
    @Override
    public boolean isPlaying() {
        return isInPlaybackState() && mMediaPlayer.isPlaying();
    }

    /**
     * 获取当前缓冲百分比
     */
    @Override
    public int getBufferedPercentage() {
        return mMediaPlayer != null ? mMediaPlayer.getBufferedPercentage() : 0;
    }

    /**
     * 设置静音
     */
    @Override
    public void setMute(boolean isMute) {
        this.mIsMute = isMute;
        if (mMediaPlayer != null) {
            float volume = isMute ? 0.0f : 1.0f;
            mMediaPlayer.setVolume(volume, volume);
        }
    }

    /**
     * 是否处于静音状态
     */
    @Override
    public boolean isMute() {
        return mIsMute;
    }

    /**
     * 视频缓冲完毕，准备开始播放时回调
     */
    @Override
    public void onPrepared() {
        // Custom players may not implement the common start-position contract.
        if (mCurrentPosition > 0 && !mMediaPlayer.isStartPositionApplied()) {
            mMediaPlayer.seekTo(mCurrentPosition);
        }
        setPlayState(STATE_PREPARED);
        if (!isMute() && mAudioFocusHelper != null) {
            mAudioFocusHelper.requestFocus();
        }
    }

    /**
     * 播放信息回调，播放中的缓冲开始与结束，开始渲染视频第一帧，视频旋转信息
     */
    @Override
    public void onInfo(int what, int extra) {
        switch (what) {
            case AbstractPlayer.MEDIA_INFO_BUFFERING_START:
                if (!keepPausedStateAfterSeek()) setPlayState(STATE_BUFFERING);
                break;
            case AbstractPlayer.MEDIA_INFO_BUFFERING_END:
                if (!keepPausedStateAfterSeek()) setPlayState(STATE_BUFFERED);
                break;
            case AbstractPlayer.MEDIA_INFO_RENDERING_START: // 视频/音频开始渲染
                // 暂停中的 seek 也会渲染出新位置的帧,不能据此判成"在播"
                if (!keepPausedStateAfterSeek()) {
                    setPlayState(STATE_PLAYING);
                    mPlayerContainer.setKeepScreenOn(true);
                }
                break;
            case AbstractPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED:
                if (mRenderView != null) mRenderView.setVideoRotation(extra);
                break;
        }
    }

    /**
     * 视频播放出错回调
     */
    @Override
    public void onError() {
        mPlayerContainer.setKeepScreenOn(false);
        setPlayState(STATE_ERROR);
    }

    /**
     * 视频播放完成回调
     */
    @Override
    public void onCompletion() {
        mPlayerContainer.setKeepScreenOn(false);
        mCurrentPosition = 0;
        if (mProgressManager != null) {
            //播放完成，清除进度
            mProgressManager.saveProgress(mProgressKey == null ? mUrl : mProgressKey, 0);
        }
        setPlayState(STATE_PLAYBACK_COMPLETED);
    }

    /**
     * 获取当前播放器的状态
     */
    public int getCurrentPlayerState() {
        return mCurrentPlayerState;
    }

    /**
     * 获取当前的播放状态
     */
    public int getCurrentPlayState() {
        return mCurrentPlayState;
    }

    /**
     * 获取缓冲速度
     */
    @Override
    public long getTcpSpeed() {
        return mMediaPlayer != null ? mMediaPlayer.getTcpSpeed() : 0;
    }

    /**
     * 设置播放速度
     */
    @Override
    public void setSpeed(float speed) {
        if (isInPlaybackState()) {
            mMediaPlayer.setSpeed(speed);
        }
    }

    @Override
    public float getSpeed() {
        if (isInPlaybackState()) {
            return mMediaPlayer.getSpeed();
        }
        return 1f;
    }

    /**
     * 设置视频地址
     */
    public void setUrl(String url) {
        setUrl(url, null);
    }

    /**
     * 设置包含请求头信息的视频地址
     *
     * @param url     视频地址
     * @param headers 请求头
     */
    public void setUrl(String url, Map<String, String> headers) {
        mPausedBeforeSeek = false;
        mAssetFileDescriptor = null;
        mUrl = url;
        mHeaders = headers;
        mVideoSize[0] = 0;
        mVideoSize[1] = 0;
    }

    /**
     * 用于播放assets里面的视频文件
     */
    public void setAssetFileDescriptor(AssetFileDescriptor fd) {
        mUrl = null;
        this.mAssetFileDescriptor = fd;
    }

    public void setProgressKey(String key) {
        mProgressKey = key;
    }

    /**
     * 一开始播放就seek到预先设置好的位置
     */
    public void skipPositionWhenPlay(int position) {
        this.mCurrentPosition = position;
    }

    /**
     * 设置音量 0.0f-1.0f 之间
     *
     * @param v1 左声道音量
     * @param v2 右声道音量
     */
    public void setVolume(float v1, float v2) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolume(v1, v2);
        }
    }

    /**
     * 设置进度管理器，用于保存播放进度
     */
    public void setProgressManager(@Nullable ProgressManager progressManager) {
        this.mProgressManager = progressManager;
    }

    /**
     * 循环播放， 默认不循环播放
     */
    public void setLooping(boolean looping) {
        mIsLooping = looping;
        if (mMediaPlayer != null) {
            mMediaPlayer.setLooping(looping);
        }
    }

    /**
     * 是否开启AudioFocus监听， 默认开启，用于监听其它地方是否获取音频焦点，如果有其它地方获取了
     * 音频焦点，此播放器将做出相应反应，具体实现见{@link AudioFocusHelper}
     */
    public void setEnableAudioFocus(boolean enableAudioFocus) {
        mEnableAudioFocus = enableAudioFocus;
    }

    /**
     * 自定义播放核心，继承{@link PlayerFactory}实现自己的播放核心
     */
    public void setPlayerFactory(PlayerFactory<P> playerFactory) {
        if (playerFactory == null) {
            throw new IllegalArgumentException("PlayerFactory can not be null!");
        }
        mPlayerFactory = playerFactory;
    }

    /**
     * 自定义RenderView，继承{@link RenderViewFactory}实现自己的RenderView
     */
    public void setRenderViewFactory(RenderViewFactory renderViewFactory) {
        if (renderViewFactory == null) {
            throw new IllegalArgumentException("RenderViewFactory can not be null!");
        }
        mRenderViewFactory = renderViewFactory;
    }

    /**
     * 进入全屏
     */
    @Override
    public void startFullScreen() {
        if (mIsFullScreen)
            return;

        ViewGroup decorView = getDecorView();
        if (decorView == null)
            return;

        mIsFullScreen = true;

        //隐藏NavigationBar和StatusBar
        hideSysBar(decorView);

        //从当前FrameLayout中移除播放器视图
        this.removeView(mPlayerContainer);
        //将播放器视图添加到DecorView中即实现了全屏
        decorView.addView(mPlayerContainer);

        setPlayerState(PLAYER_FULL_SCREEN);
    }

    private void hideSysBar(ViewGroup decorView) {
        int uiOptions = decorView.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        }
        decorView.setSystemUiVisibility(uiOptions);
        getActivity().getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && mIsFullScreen) {
            //重新获得焦点时保持全屏状态
            hideSysBar(getDecorView());
        }
    }

    /**
     * 退出全屏
     */
    @Override
    public void stopFullScreen() {
        if (!mIsFullScreen)
            return;

        ViewGroup decorView = getDecorView();
        if (decorView == null)
            return;

        mIsFullScreen = false;

        //显示NavigationBar和StatusBar
        showSysBar(decorView);

        //把播放器视图从DecorView中移除并添加到当前FrameLayout中即退出了全屏
        decorView.removeView(mPlayerContainer);
        this.addView(mPlayerContainer);

        setPlayerState(PLAYER_NORMAL);
    }

    /**
     * 把承载播放画面的容器挂到外部宿主(P2 播放服务化:服务持有播放器与渲染视图,页面只提供显示宿主)。
     *
     * <p>与 {@link #startFullScreen()} 的区别:只搬运 {@link #mPlayerContainer},**不碰系统栏、不改
     * {@link #mIsFullScreen}**,因此可与"页面内全屏"(横屏沉浸,容器在 DecorView)共存 —— 全屏/退出全屏是
     * 页面内的搬运,本方法只负责"页面 ⇄ 服务"的搬运;两处都先判 parent 再 addView,幂等且不会重复挂载。
     *
     * <p>搬运会触发 SurfaceView 的 surfaceDestroyed/surfaceCreated,dkplayer 既有链路会 setDisplay(null)
     * 再重挂;IJK/Exo 侧安全性结论见 MEMORY.md「IJK 异步 release × Surface 回调」与播放服务化 Spec §2.3/R1。
     *
     * @param host 页面侧显示宿主(插到 index 0:宿主内的弹幕/覆盖层都在其之上)
     */
    public void attachContainerTo(@NonNull ViewGroup host) {
        if (mPlayerContainer == null || host == null) return;
        ViewGroup parent = (ViewGroup) mPlayerContainer.getParent();
        if (parent == host) return;
        if (parent != null) parent.removeView(mPlayerContainer);
        ViewGroup.LayoutParams lp = mPlayerContainer.getLayoutParams();
        if (lp == null) {
            lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        host.addView(mPlayerContainer, 0, lp);
    }

    /**
     * 把渲染容器从外部宿主摘回自身(服务侧),供页面 detach/销毁调用;幂等(未挂出时为空操作)。
     * 摘回后渲染视图失去 Surface(画面不可见),音频不受影响。
     */
    public void detachContainerFromHost() {
        if (mPlayerContainer == null) return;
        ViewGroup parent = (ViewGroup) mPlayerContainer.getParent();
        if (parent == null || parent == this) return;
        parent.removeView(mPlayerContainer);
        this.addView(mPlayerContainer, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 渲染容器当前是否挂在指定宿主上(P2 挂摘幂等判定;宿主已销毁时为 false) */
    public boolean isContainerAttachedTo(ViewGroup host) {
        return mPlayerContainer != null && host != null && mPlayerContainer.getParent() == host;
    }

    private void showSysBar(ViewGroup decorView) {
        int uiOptions = decorView.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            uiOptions &= ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        }
        decorView.setSystemUiVisibility(uiOptions);
        getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    /**
     * 获取DecorView
     */
    protected ViewGroup getDecorView() {
        Activity activity = getActivity();
        if (activity == null) return null;
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    /**
     * 获取activity中的content view,其id为android.R.id.content
     */
    protected ViewGroup getContentView() {
        Activity activity = getActivity();
        if (activity == null) return null;
        return activity.findViewById(android.R.id.content);
    }

    /**
     * 获取Activity，优先通过Controller去获取Activity
     */
    protected Activity getActivity() {
        Activity activity;
        if (mVideoController != null) {
            activity = PlayerUtils.scanForActivity(mVideoController.getContext());
            if (activity == null) {
                activity = PlayerUtils.scanForActivity(getContext());
            }
        } else {
            activity = PlayerUtils.scanForActivity(getContext());
        }
        return activity;
    }

    /**
     * 判断是否处于全屏状态
     */
    @Override
    public boolean isFullScreen() {
        return mIsFullScreen;
    }

    /**
     * 开启小屏
     */
    public void startTinyScreen() {
        if (mIsTinyScreen) return;
        ViewGroup contentView = getContentView();
        if (contentView == null) return;
        this.removeView(mPlayerContainer);
        int width = mTinyScreenSize[0];
        if (width <= 0) {
            width = PlayerUtils.getScreenWidth(getContext(), false) / 2;
        }

        int height = mTinyScreenSize[1];
        if (height <= 0) {
            height = width * 9 / 16;
        }

        LayoutParams params = new LayoutParams(width, height);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        contentView.addView(mPlayerContainer, params);
        mIsTinyScreen = true;
        setPlayerState(PLAYER_TINY_SCREEN);
    }

    /**
     * 退出小屏
     */
    public void stopTinyScreen() {
        if (!mIsTinyScreen) return;

        ViewGroup contentView = getContentView();
        if (contentView == null) return;
        contentView.removeView(mPlayerContainer);
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        this.addView(mPlayerContainer, params);

        mIsTinyScreen = false;
        setPlayerState(PLAYER_NORMAL);
    }

    public boolean isTinyScreen() {
        return mIsTinyScreen;
    }

    @Override
    public void onVideoSizeChanged(int videoWidth, int videoHeight) {
        mVideoSize[0] = videoWidth;
        mVideoSize[1] = videoHeight;

        if (mRenderView != null) {
            mRenderView.setScaleType(mCurrentScreenScaleType);
            mRenderView.setVideoSize(videoWidth, videoHeight);
        }
    }

    /**
     * 设置控制器，传null表示移除控制器
     */
    public void setVideoController(@Nullable BaseVideoController mediaController) {
        mPlayerContainer.removeView(mVideoController);
        mVideoController = mediaController;
        if (mediaController != null) {
            mediaController.setMediaPlayer(this);
            LayoutParams params = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            mPlayerContainer.addView(mVideoController, params);
            // 状态回灌(P2 挂摘,2026-09-14 修复):挂上来的控制器**可能是全新的**(新页面 / 摘下后重挂),
            // 而播放器此时也许已经在播或已暂停。dkplayer 只在状态**发生变化**时下发事件,新控制器会一直停在
            // 初始 IDLE:duration/position = 0(进度条显示 0 且不可拖)、播放/暂停图标与实际反相。
            // 因此必须把当前状态补发一次;非播放态再补一跳进度,让 duration/position 立刻正确。
            mediaController.setPlayState(mCurrentPlayState);
            mediaController.setPlayerState(mCurrentPlayerState);
            if (mCurrentPlayState != STATE_IDLE && mCurrentPlayState != STATE_ERROR) {
                mediaController.startProgress();
            }
        }
    }

    /**
     * 当前挂载的控制器(P4:直播页据此判断自己的控制层是否被点播页顶掉,见 LivePlayActivity)。
     */
    @Nullable
    public BaseVideoController getVideoController() {
        return mVideoController;
    }

    /**
     * 设置视频比例
     */
    @Override
    public void setScreenScaleType(int screenScaleType) {
        mCurrentScreenScaleType = screenScaleType;
        if (mRenderView != null) {
            mRenderView.setScaleType(screenScaleType);
        }
    }

    /**
     * 设置镜像旋转，暂不支持SurfaceView
     */
    @Override
    public void setMirrorRotation(boolean enable) {
        if (mRenderView != null) {
            mRenderView.getView().setScaleX(enable ? -1 : 1);
        }
    }

    /**
     * 截图，暂不支持SurfaceView
     */
    @Override
    public Bitmap doScreenShot() {
        if (mRenderView != null) {
            return mRenderView.doScreenShot();
        }
        return null;
    }

    /**
     * 获取视频宽高,其中width: mVideoSize[0], height: mVideoSize[1]
     */
    @Override
    public int[] getVideoSize() {
        return mVideoSize;
    }

    /**
     * 旋转视频画面
     *
     * @param rotation 角度
     */
    @Override
    public void setRotation(float rotation) {
        if (mRenderView != null) {
            mRenderView.setVideoRotation((int) rotation);
        }
    }

    /**
     * 设置小屏的宽高
     *
     * @param tinyScreenSize 其中tinyScreenSize[0]是宽，tinyScreenSize[1]是高
     */
    public void setTinyScreenSize(int[] tinyScreenSize) {
        this.mTinyScreenSize = tinyScreenSize;
    }

    /**
     * 向Controller设置播放状态，用于控制Controller的ui展示
     */
    protected void setPlayState(int playState) {
        mCurrentPlayState = playState;
        if (mVideoController != null) {
            mVideoController.setPlayState(playState);
        }
        if (mOnStateChangeListeners != null) {
            for (OnStateChangeListener l : PlayerUtils.getSnapshot(mOnStateChangeListeners)) {
                if (l != null) {
                    l.onPlayStateChanged(playState);
                }
            }
        }
    }

    /**
     * 向Controller设置播放器状态，包含全屏状态和非全屏状态
     */
    protected void setPlayerState(int playerState) {
        mCurrentPlayerState = playerState;
        if (mVideoController != null) {
            mVideoController.setPlayerState(playerState);
        }
        if (mOnStateChangeListeners != null) {
            for (OnStateChangeListener l : PlayerUtils.getSnapshot(mOnStateChangeListeners)) {
                if (l != null) {
                    l.onPlayerStateChanged(playerState);
                }
            }
        }
    }

    /**
     * 播放状态改变监听器
     */
    public interface OnStateChangeListener {
        void onPlayerStateChanged(int playerState);

        void onPlayStateChanged(int playState);
    }

    /**
     * OnStateChangeListener的空实现。用的时候只需要重写需要的方法
     */
    public static class SimpleOnStateChangeListener implements OnStateChangeListener {
        @Override
        public void onPlayerStateChanged(int playerState) {
        }

        @Override
        public void onPlayStateChanged(int playState) {
        }
    }

    /**
     * 添加一个播放状态监听器，播放状态发生变化时将会调用。
     */
    public void addOnStateChangeListener(@NonNull OnStateChangeListener listener) {
        if (mOnStateChangeListeners == null) {
            mOnStateChangeListeners = new ArrayList<>();
        }
        mOnStateChangeListeners.add(listener);
    }

    /**
     * 移除某个播放状态监听
     */
    public void removeOnStateChangeListener(@NonNull OnStateChangeListener listener) {
        if (mOnStateChangeListeners != null) {
            mOnStateChangeListeners.remove(listener);
        }
    }

    /**
     * 设置一个播放状态监听器，播放状态发生变化时将会调用，
     * 如果你想同时设置多个监听器，推荐 {@link #addOnStateChangeListener(OnStateChangeListener)}。
     */
    public void setOnStateChangeListener(@NonNull OnStateChangeListener listener) {
        if (mOnStateChangeListeners == null) {
            mOnStateChangeListeners = new ArrayList<>();
        } else {
            mOnStateChangeListeners.clear();
        }
        mOnStateChangeListeners.add(listener);
    }

    /**
     * 移除所有播放状态监听
     */
    public void clearOnStateChangeListeners() {
        if (mOnStateChangeListeners != null) {
            mOnStateChangeListeners.clear();
        }
    }

    /**
     * 改变返回键逻辑，用于activity
     */
    public boolean onBackPressed() {
        return mVideoController != null && mVideoController.onBackPressed();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        L.d("onSaveInstanceState: " + mCurrentPosition);
        //activity切到后台后可能被系统回收，故在此处进行进度保存
        saveProgress();
        return super.onSaveInstanceState();
    }
}
