package com.github.tvbox.osc.player.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.bean.Subtitle
import com.github.tvbox.osc.dlna.CastVideo
import xyz.doikki.videoplayer.player.VideoView

/**
 * 播放器控制层集中状态容器（Compose 化改造 §4.2）。
 *
 * dkplayer 侧只读状态由事件桥写入；控制层自身 UI 状态由 ComposeVideoController 的
 * 意图方法写入。Compose 通过 mutableStateOf 直接观察，替代旧实现中的
 * Handler(msg 100/1000-1004) + myHandle 两套异步与 30+ 个分散字段。
 */
class PlayerUiState {

    // —— 来自 dkplayer（只读，事件桥写入） ——
    var playState: Int by mutableStateOf(VideoView.STATE_IDLE)
    var playerState: Int by mutableStateOf(VideoView.PLAYER_NORMAL)
    var duration: Int by mutableStateOf(0)
    var position: Int by mutableStateOf(0)
    var bufferedPercent: Int by mutableStateOf(0)
    var locked: Boolean by mutableStateOf(false)
    /** dkplayer 侧 show/hide（§3.3 桥接；锁定状态下不下发） */
    var showing: Boolean by mutableStateOf(false)

    // —— 控制层自身 UI 状态 ——

    /** 底部菜单可见（替代 mBottomRoot 显隐 + msg 1002/1003） */
    var controlsVisible: Boolean by mutableStateOf(false)
    /** SeekBar 拖拽/按键步进中（替代 mIsDragging，拖拽中不回写进度） */
    var dragging: Boolean by mutableStateOf(false)
    /** 拖拽预览位置 ms（仅 dragging 时用于当前时间展示） */
    var seekPreviewPositionMs: Long by mutableStateOf(0L)

    /** seek 提示（替代 msg 1000/1001）：方向与文本 */
    var seekHintVisible: Boolean by mutableStateOf(false)
    var seekHintForward: Boolean by mutableStateOf(true)
    var seekHintText: String by mutableStateOf("")

    /** 亮度/音量提示（替代 BaseController msg 100/101） */
    var slideHintVisible: Boolean by mutableStateOf(false)
    var slideHintText: String by mutableStateOf("")

    /** 长按倍速浮层（替代 play_speed_3_container / fromLongPress） */
    var speedBoostVisible: Boolean by mutableStateOf(false)
    /** 浮层显示的倍率值(设置页可调 2x~10x,长按触发时写入) */
    var speedBoostValue: Float by mutableStateOf(3.0f)

    // —— 1 秒轮询（替代 myRunnable2） ——
    var title: String by mutableStateOf("")
    var videoSize: String by mutableStateOf("")
    var sysTime: String by mutableStateOf("")
    /** 电量百分比（0~100；读不到为 -1 不显示），随 1s 轮询刷新 */
    var batteryPercent: Int by mutableStateOf(-1)
    /** 充电中/已充满：电池图标用闪电帧 */
    var batteryCharging: Boolean by mutableStateOf(false)
    var netSpeedTopRight: String by mutableStateOf("")
    var netSpeedCenter: String by mutableStateOf("")

    // —— 顶部栏元素可见性（逐元素照搬 msg 1002/1003 的规则，§7.8） ——
    /** mTopRoot1：片名 + 分辨率 */
    var topLeftVisible: Boolean by mutableStateOf(false)
    /** mTopRoot2 容器：init 后一旦显示过就保持可见（旧实现如此） */
    var topRightVisible: Boolean by mutableStateOf(false)
    /** tv_sys_time */
    var sysTimeVisible: Boolean by mutableStateOf(false)
    /** net_play_speed（右块行内网速） */
    var netSpeedSideVisible: Boolean by mutableStateOf(false)
    /** tv_seek_time */
    var seekTimeVisible: Boolean by mutableStateOf(false)
    /** tv_play_load_net_speed_right_top */
    var netSpeedTopRightVisible: Boolean by mutableStateOf(false)
    /** tv_seek_time 文本（1s 轮询 + setProgress 更新） */
    var seekTimeText: String by mutableStateOf("")

    /** 返回键：true = VISIBLE（仅横屏非 TV），false = INVISIBLE（占位不显示） */
    var backVisible: Boolean by mutableStateOf(false)
    /** 锁屏按钮可见性（照搬 showLockView 的三态） */
    var lockState: LockVisibility by mutableStateOf(LockVisibility.GONE)

    // —— 底部菜单（照搬 updatePortraitMenu / updatePlayerCfgView / hideLiveAboutBtn） ——
    var screenDisplayOn: Boolean by mutableStateOf(false)
    var showParseRow: Boolean by mutableStateOf(false)
    var isPortrait: Boolean by mutableStateOf(true)
    var playerType: Int by mutableStateOf(2)
    /** 直播源（duration==0）时隐藏倍速与片头尾按钮 */
    var liveButtonsVisible: Boolean by mutableStateOf(true)
    var danmuOpen: Boolean by mutableStateOf(false)
    var danmuSearchAvailable: Boolean by mutableStateOf(false)
    /** 详情页竖屏预览态（setPreviewMode 写入）：呼出控件栏时只显示进度行，不显示菜单行 */
    var previewMode: Boolean by mutableStateOf(false)
    var playerBtnText: String by mutableStateOf("")
    var scaleBtnText: String by mutableStateOf("")
    var speedBtnText: String by mutableStateOf("")
    var ijkBtnText: String by mutableStateOf("")
    /** 片头/片尾按钮文案:初始为空,由控制器写入资源文案 */
    var timeStartText: String by mutableStateOf("")
    var timeEndText: String by mutableStateOf("")
    /** 解析列表版本号：setDefaultParse 后自增以驱动重绘 */
    var parseListVersion: Int by mutableStateOf(0)

    /** 尺寸/倍速/播放器选择弹窗（阶段 7：替代 View 版 SelectDialog），null = 不显示 */
    var selectDialog: SelectDialogState? by mutableStateOf(null)

    // —— Step 6 对话框 sheet 化（替代 View 版 DanmuSetting/SearchDanmu/Subtitle/SearchSubtitle/Cast/Episode Dialog） ——
    /** 弹幕设置面板；内部配置直接读写 DanmuHelper + EventBus，无需业务回调 */
    var danmuSettingSheet: DanmuSettingSheetState? by mutableStateOf(null)
    /** 弹幕搜索面板 */
    var danmuSearchSheet: DanmuSearchSheetState? by mutableStateOf(null)
    /** 字幕设置面板 */
    var subtitleSheet: SubtitleSheetState? by mutableStateOf(null)
    /** 字幕搜索面板 */
    var subtitleSearchSheet: SubtitleSearchSheetState? by mutableStateOf(null)
    /** 投屏设备面板 */
    var castSheet: CastSheetState? by mutableStateOf(null)

    // —— 衍生可见性（照搬 updatePortraitMenu 的逐按钮规则；与方向无关，预览态由菜单行/解析行的 previewMode 守卫） ——

    /** 解码按钮:IJK(内核 options)与 EXO(media3 视频解码选择器)都有软解路径(2026-09-17 放开 EXO) */
    val ijkBtnVisible: Boolean get() = playerType == 1 || playerType == 2
    val trackBtnVisible: Boolean get() = playerType == 1 || playerType == 2
    val danmuBtnVisible: Boolean get() = danmuOpen
    val danmuSearchBtnVisible: Boolean get() = danmuSearchAvailable
    val castBtnVisible: Boolean get() = android.os.Build.VERSION.SDK_INT >= 30

    /** 退后台暂停标记(PlayerControlApi.setLifecyclePaused):此暂停不画中央播放键 */
    var lifecyclePaused: Boolean by mutableStateOf(false)

    /** 暂停浮层可见:暂停中且底栏已收起;生命周期暂停不算(避免任务快照拍到"已暂停"假象) */
    val pauseOverlayVisible: Boolean
        get() = playState == VideoView.STATE_PAUSED && !controlsVisible && !lifecyclePaused

    /** 当前时间行文本位置：拖拽/按键步进中显示预览位置，否则显示真实播放位置 */
    val seekPreviewOrPosition: Int
        get() = if (dragging && duration > 0) seekPreviewPositionMs.toInt().coerceIn(0, duration) else position

    /** loading 可见（照搬 BaseController.onPlayStateChanged） */
    val loadingVisible: Boolean
        get() = playState == VideoView.STATE_PREPARING || playState == VideoView.STATE_BUFFERING

    /** 中央网速文本可见（旧实现仅 IDLE 阶段可见） */
    val netSpeedCenterVisible: Boolean
        get() = playState == VideoView.STATE_IDLE
}

/** 锁屏按钮三态（照搬旧实现 GONE/INVISIBLE/VISIBLE 的区别） */
enum class LockVisibility { GONE, HIDDEN, SHOWN }

/**
 * 选择弹窗状态（照搬 SelectDialog.setAdapter 的四参数：tip/数据/默认选中/回调）。
 * onSelected(index) 由控制器提供：应用选择后由 UI 侧自动收起弹窗。
 */
class SelectDialogState(
    val tip: String,
    val items: List<String>,
    val defaultIndex: Int,
    val onSelected: (Int) -> Unit,
)

/** 弹幕设置面板状态（Step 6 替代 View 版 DanmuSettingDialog） */
class DanmuSettingSheetState(
    val onOpenSearch: () -> Unit,
)

/** 弹幕搜索面板状态（替代 View 版 SearchDanmuDialog）；onLoad = 命中弹幕 XML 回调（PlayContainer.checkDanmu） */
class DanmuSearchSheetState(
    val episode: String,
    val searchWord: String,
    val onLoad: (String) -> Unit,
)

/** 字幕设置面板状态（替代 View 版 SubtitleDialog）；exoInternal = Exo 内置字幕模式（字号百分比/字幕上下移） */
class SubtitleSheetState(
    val exoInternal: Boolean,
    val hasInternal: Boolean,
    val onSelectInternal: () -> Unit,
    val onSelectLocal: () -> Unit,
    val onSelectRemote: () -> Unit,
    /** 外挂字幕文字样式(2026-09-12 补回丢失逻辑):0=样式一 白色,1=样式二 粉色(#FFB6C1) */
    val onSelectStyle: (Int) -> Unit = {},
    /** 字号按钮只写了设置,需播放层立即按当前形态(预览 0.6×/全屏 1×)应用到字幕视图 */
    val onTextSizeChange: () -> Unit = {},
)

/** 字幕搜索面板状态（替代 View 版 SearchSubtitleDialog）；onLoadSubtitle = 拿到字幕直链后回调 */
class SubtitleSearchSheetState(
    val searchWord: String,
    val onLoadSubtitle: (Subtitle) -> Unit,
)

/** 投屏设备面板状态（替代 View 版 CastDeviceDialog） */
class CastSheetState(
    val video: CastVideo,
    val onCastSuccess: () -> Unit,
)

/**
 * 控制层意图集（UI → 控制器）。由 ComposeVideoController 实现。
 * 命名与 §5.3 按钮清单一一对应。
 */
interface PlayerActions {
    // 底栏显隐
    fun toggleControls()
    fun keepControlsAlive()

    // 播控按钮（onXxxLongClicked 为遥控器确认键/触摸长按）
    fun onNextClicked()
    fun onPreClicked()
    /** 播放/暂停切换（底栏「播放」按钮 + 中央控制组中间按钮） */
    fun onPlayPauseClicked()
    fun onRefreshClicked()
    fun onScaleClicked()
    fun onScaleLongClicked()
    fun onSpeedClicked()
    fun onSpeedLongClicked()
    fun onPlayerClicked()
    fun onPlayerLongClicked()
    fun onIjkClicked()
    fun onTimeStartClicked()
    fun onTimeStartLongClicked()
    fun onTimeEndClicked()
    fun onTimeEndLongClicked()
    fun onTimeResetClicked()
    fun onCastClicked()
    fun onSubtitleClicked()
    fun onSubtitleLongClicked()
    fun onAudioTrackClicked()
    fun onVideoTrackClicked()
    fun onDanmuSettingClicked()
    fun onDanmuSettingLongClicked()
    fun onDanmuSearchClicked()
    fun onDanmuSearchLongClicked()
    fun onRotateClicked()
    fun onScreenDisplayClicked()
    fun onBackClicked()
    fun onLockClicked()

    // 解析
    fun onParseSelected(position: Int)

    // 进度条（§5.3：拖拽中不回写；滚轮/方向键步进）
    fun onSeekStarted()
    fun onSeekPreview(progress: Int)
    fun onSeekFinished(progress: Int)
    /** 手势被系统取消（等效旧 ACTION_CANCEL：不提交 seek） */
    fun onSeekCancelled()
    fun onSeekStep(dir: Int)

    // 1s 轮询（替代 myRunnable2）
    fun refreshSystemInfo()

    // 提示浮层自动隐藏（替代 msg 1001/101）
    fun hideSeekHint()
    fun hideSlideHint()
}
