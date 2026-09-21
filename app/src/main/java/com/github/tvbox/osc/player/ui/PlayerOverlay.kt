package com.github.tvbox.osc.player.ui

import android.content.res.Configuration
import androidx.annotation.DimenRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.ui.components.ScallopShape
import kotlinx.coroutines.delay
import xyz.doikki.videoplayer.player.VideoView

/**
 * 播放器控制层根 Composable（Compose 化改造 §3.3 方案 C1）。
 * 层级顺序照搬 player_vod_control_view.xml 的 z-order（自底向上）：
 * 顶部栏 → 底部菜单 → 暂停浮层 → 亮度/音量提示 → seek 提示 → loading → 中央网速 →
 * 返回键 → 锁屏 → 长按倍速。原生字幕视图是控制器的直接子 View（位于 Compose 层之下），
 * 与旧布局一致。
 */
@Composable
fun PlayerOverlay(
    state: PlayerUiState,
    actions: PlayerActions,
) {
    Box(Modifier.fillMaxSize()) {
        PlayerTopBar(state, actions)
        // 旧 XML bottom_container 为 layout_gravity="bottom"（BoxScope 内显式贴底）
        PlayerBottomBar(state, actions, Modifier.align(Alignment.BottomCenter))
        // 中央控制组（图二样式）：点击显示底栏时出现 上一集/播放暂停/下一集
        PlayerCenterControls(state, actions, Modifier.align(Alignment.Center))
        PlayerPauseLayer(state, actions)
        PlayerSlideHint(state)
        PlayerSeekHint(state)
        PlayerLoadingLayer(state)
        PlayerNetSpeedCenter(state)
        PlayerLockButton(state, actions)
        PlayerSpeedBoostHint(state)

        // 尺寸/倍速/播放器选择弹窗（阶段 7）
        state.selectDialog?.let { dialogState ->
            PlayerSelectDialog(
                dialogState = dialogState,
                onDismiss = { state.selectDialog = null },
            )
        }

        // Step 6 对话框 Compose 化（替代 View 版 Danmu/SearchDanmu/Subtitle/SearchSubtitle/Cast/Episode Dialog）
        state.episodeSheet?.let { sheet ->
            EpisodeSheet(sheet) { state.episodeSheet = null }
        }
        state.danmuSettingSheet?.let { sheet ->
            DanmuSettingSheet(sheet) { state.danmuSettingSheet = null }
        }
        state.danmuSearchSheet?.let { sheet ->
            DanmuSearchSheet(sheet) { state.danmuSearchSheet = null }
        }
        state.subtitleSheet?.let { sheet ->
            SubtitleSheet(sheet) { state.subtitleSheet = null }
        }
        state.subtitleSearchSheet?.let { sheet ->
            SubtitleSearchSheet(sheet) { state.subtitleSearchSheet = null }
        }
        state.castSheet?.let { sheet ->
            CastSheet(sheet) { state.castSheet = null }
        }
    }

    // seek 提示 1s 自动隐藏（替代 msg 1000/1001；key 含文本保证连续滑动时重新计时）
    LaunchedEffect(state.seekHintVisible, state.seekHintText) {
        if (state.seekHintVisible) {
            delay(1000)
            actions.hideSeekHint()
        }
    }
    // 亮度/音量提示 1s 自动隐藏（替代 msg 100/101）
    LaunchedEffect(state.slideHintVisible, state.slideHintText) {
        if (state.slideHintVisible) {
            delay(1000)
            actions.hideSlideHint()
        }
    }
    // 1s 轮询（替代 myRunnable2：系统时间/网速/分辨率）
    LaunchedEffect(Unit) {
        while (true) {
            actions.refreshSystemInfo()
            delay(1000)
        }
    }
}

// ---------------------------------------------------------------------------
// 共享辅助：AutoSize(mm) 尺寸换算 / 菜单按钮
// ---------------------------------------------------------------------------

/**
 * 项目 dimens 以 mm 为单位（AutoSize），换算为 Compose Dp 保持物理缩放一致 */
@Composable
internal fun playerDim(@DimenRes id: Int): Dp {
    val px = LocalContext.current.resources.getDimensionPixelSize(id)
    return with(LocalDensity.current) { (px * portraitCompensation()).toDp() }
}

@Composable
internal fun playerTextSize(@DimenRes id: Int): TextUnit {
    val px = LocalContext.current.resources.getDimension(id)
    return with(LocalDensity.current) { (px * portraitCompensation()).toSp() }
}

/**
 * 播放器覆盖层控件距屏幕边缘的距离（2026-09-13 用户定稿，spec §4.4）：
 * 按窗口宽度分档 —— compact（screenWidthDp < 600，竖屏详情页预览态）16dp；
 * medium/expanded（横屏全屏、平板、折叠展开）24dp（对齐 M3 窗口分档惯例：compact 16dp / medium 及以上 24dp）。
 * 备注：边距与手势带无关 —— dkplayer 的 `PlayerUtils.isEdge()` 已忽略四边各 40dp 内的视频手势。
 */
@Composable
internal fun playerEdgePadding(): Dp =
    if (LocalConfiguration.current.screenWidthDp >= 600) 24.dp else 16.dp

/**
 * 竖屏补偿：AutoSize 按屏宽适配（BaseActivity design 1280dp），竖屏时“宽度”变短边，
 * density 缩为横屏的 宽/高 倍，所有 mm 尺寸物理上同步缩水（旧 XML 竖屏同样如此）。
 * 这里乘以 屏高px/屏宽px（= 横屏 density / 竖屏 density），使竖屏与横屏物理观感一致。
 */
@Composable
private fun portraitCompensation(): Float {
    val conf = LocalConfiguration.current
    return if (conf.orientation == Configuration.ORIENTATION_PORTRAIT) {
        // Configuration.screenWidthPx 在新 SDK stub 已移除，用 displayMetrics 取物理像素
        val dm = LocalContext.current.resources.displayMetrics
        if (dm.widthPixels > 0) dm.heightPixels.toFloat() / dm.widthPixels else 1f
    } else {
        1f
    }
}

/**
 * 中央控制组（图二样式）：显示底栏时屏幕中央出现三个半透明圆形按钮：
 * 左＝上一集、中＝播放/暂停（图标随播放态切换）、右＝下一集。
 * 触摸点按；锁定时不显示。固定尺寸（等比例缩放已回退）。
 * loading（PREPARING/BUFFERING）时中央让位给转圈；预览态（竖屏详情页）同样显示（可播控/切集）。
 */
@Composable
private fun PlayerCenterControls(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    // 预览态（竖屏详情页）也可用：单击唤出后中央三键可播控/切集
    if (!state.controlsVisible || state.locked || state.loadingVisible) return
    // BUFFERING/BUFFERED 也算“播放中”：dkplayer 缓冲结束后停在 STATE_BUFFERED 不回 STATE_PLAYING，
    // 只判 STATE_PLAYING 会导致每次卡缓冲后图标长期反显（实际在播却显示“播放”，点下是暂停）
    val playing = state.playState == VideoView.STATE_PLAYING ||
            state.playState == VideoView.STATE_BUFFERING ||
            state.playState == VideoView.STATE_BUFFERED
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CenterControlCircle(
            diameter = 48.dp,
            icon = painterResource(R.drawable.player_ic_prev),
            label = stringResource(R.string.player_prev_episode),
            onClick = actions::onPreClicked,
            shape = ScallopShape(),
        )
        CenterControlCircle(
            diameter = 60.dp,
            icon = painterResource(if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play),
            label = stringResource(if (playing) R.string.common_pause else R.string.common_play),
            onClick = actions::onPlayPauseClicked,
        )
        CenterControlCircle(
            diameter = 48.dp,
            icon = painterResource(R.drawable.player_ic_next),
            label = stringResource(R.string.player_next_episode),
            onClick = actions::onNextClicked,
            shape = ScallopShape(),
        )
    }
}

@Composable
private fun CenterControlCircle(
    diameter: Dp,
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    shape: Shape = CircleShape,
) {
    Box(
        Modifier
            .size(diameter)
            .background(Color.Black.copy(alpha = 0.35f), shape)
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = icon,
            contentDescription = label,
            modifier = Modifier.size(diameter * 0.55f),
        )
    }
}

/**
 * 菜单按钮：轻量化文字条目（视觉参考极简播放器底栏，替代旧 button_dialog_main 药丸）：
 * 常态纯文字全白（与顶栏标题一致），按压仅淡色底 + 文字加粗；选中色由调用方传入（02F8E1）。
 */
@Composable
internal fun PlayerMenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    textColor: Color = Color.White,
    @DimenRes textSizeId: Int = R.dimen.ts_19,
) {
    var pressed by remember { mutableStateOf(false) }
    val buttonModifier = modifier
        .pointerInput(onClick, onLongClick) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { onClick() },
                onLongPress = onLongClick?.let { cb -> { cb() } },
            )
        }
        .background(
            if (pressed) Color.White.copy(alpha = 0.16f) else Color.Transparent,
            RoundedCornerShape(playerDim(R.dimen.vs_5)),
        )
        .padding(horizontal = playerDim(R.dimen.vs_10), vertical = playerDim(R.dimen.vs_5))
    Text(
        text = text,
        color = textColor,
        fontSize = playerTextSize(textSizeId),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (pressed) FontWeight.Bold else FontWeight.Medium,
        modifier = buttonModifier,
    )
}
