@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.player.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState

/**
 * 浮层组（照搬旧 tv_slide_progress_text / tv_progress_container /
 * loading / tv_play_load_net_speed / tv_back / tv_lock / play_speed_3_container）。
 * 视觉：提示类浮层（seek 提示 / 亮度音量提示）统一为 M3 surface 药丸 —— 半透明
 * `surfaceContainer`(90%) + 4dp 轻投影、无描边、内容自适应（2026-09-13 用户定稿，
 * 废弃旧 shape_user_focus 的深灰底 #6C3D3D3D + 白描边 + 固定 200x100mm）。
 */

private val PillShape = RoundedCornerShape(50)

@Composable
private fun HintPill(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            // M3 surface 样式：surfaceContainer 50% 透明度 + 轻投影,无描边
            .shadow(4.dp, PillShape)
            .background(
                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
                PillShape
            )
            .padding(horizontal = playerDim(R.dimen.vs_20), vertical = playerDim(R.dimen.vs_10)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * 加载/错误遮罩：盖住视频面，但**必须**画在顶栏/底栏之前 —— 盖到控制条上时，加载期单击只会
 * 静默翻转 `controlsVisible`（遮罩不拦触摸），用户一个控件也看不到。
 */
@Composable
fun PlayerTipLayer(state: PlayerUiState) {
    if (!state.tipVisible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.tipLoading) {
                ContainedLoadingIndicator(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    indicatorColor = Color.White.copy(alpha = 0.75f),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.icon_error),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(48.dp),
                )
            }
            if (state.tipMsg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.tipMsg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

/**
 * 暂停浮层：仅中央播放键（60dp 半透明圆底，点按即续播，免二次点击）。
 * 退后台触发的暂停不显示（pauseOverlayVisible 排除 lifecyclePaused）——避免被系统任务快照拍出"已暂停"假象。
 * 遮罩在屏时同样不显示：那时暂停的是上一次会话的残留内核，键压在遮罩上会误导（点它启停的不是即将播放的内容）。
 */
@Composable
fun PlayerPauseLayer(state: PlayerUiState, actions: PlayerActions) {
    if (!state.pauseOverlayVisible || state.tipVisible) return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(60.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { actions.onPlayPauseClicked() })
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.player_ic_play),
                contentDescription = stringResource(R.string.common_play),
                modifier = Modifier.size(60.dp * 0.55f),
            )
        }
    }
}

/**
 * 亮度/音量提示（中央药丸，替代旧 msg 100/101 + tv_slide_progress_text）。
 * 2026-09-13 用户定稿：**样式与 seek 提示（[PlayerSeekHint]）完全同款** —— 复用 [HintPill]
 * （半透明 `surfaceContainer` 90% + 轻投影、无描边），尺寸由内容自适应（不再固定 200x100mm），
 * 文字色 `onSurface`。
 */
@Composable
fun PlayerSlideHint(state: PlayerUiState) {
    if (!state.slideHintVisible) return
    Box(Modifier.fillMaxSize()) {
        HintPill(modifier = Modifier.align(Alignment.Center)) {
            Text(
                text = state.slideHintText,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = playerTextSize(R.dimen.ts_30),
            )
        }
    }
}

/** seek 提示（顶部居中 60mm，快进/快退图标 + 时间，替代 msg 1000/1001） */
@Composable
fun PlayerSeekHint(state: PlayerUiState) {
    if (!state.seekHintVisible) return
    Box(Modifier.fillMaxSize()) {
        HintPill(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = playerDim(R.dimen.vs_60))
        ) {
            Image(
                painter = painterResource(
                    if (state.seekHintForward) R.drawable.exo_icon_fastforward else R.drawable.exo_icon_rewind
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.size(playerDim(R.dimen.vs_40)),
            )
            Spacer(Modifier.width(playerDim(R.dimen.vs_20)))
            Text(
                text = state.seekHintText,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = playerTextSize(R.dimen.ts_30),
            )
        }
    }
}

/** loading（PREPARING/BUFFERING 显示，替代旧 vod_control_loading ProgressBar）；
 *  指示器下方实时网速（2026-09-12 用户需求）：复用 1s 轮询刷新的 netSpeedTopRight，
 *  拖动进度条/缓冲时用户可直观看到取流速度 */
@Composable
fun PlayerLoadingLayer(state: PlayerUiState) {
    if (!state.loadingVisible) return
    Box(Modifier.fillMaxSize()) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .size(playerDim(R.dimen.vs_50)),
            color = Color.White,
        )
        Text(
            text = state.netSpeedTopRight,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = playerDim(R.dimen.vs_50) / 2 + playerDim(R.dimen.vs_10)),
        )
    }
}

/** 中央网速（旧 tv_play_load_net_speed：center + marginTop 40mm，仅 IDLE 可见）。
 *  遮罩在屏时不显示：解析期播放态正是 IDLE，网速会压在遮罩上。 */
@Composable
fun PlayerNetSpeedCenter(state: PlayerUiState) {
    if (!state.netSpeedCenterVisible || state.tipVisible) return
    Box(Modifier.fillMaxSize()) {
        Text(
            text = state.netSpeedCenter,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = playerDim(R.dimen.vs_40)),
        )
    }
}

/**
 * 左右两侧各一颗、垂直居中（左：旋转 / 右：锁）。
 * 锁屏三态照搬 showLockView：非预览态非 TV 才出现，锁定 3s 后隐藏。
 * [iconBox] 由 [PlayerOverlay] 统一算出并与动作胶囊共用 ⇒ 两处图标必然等大。
 */
@Composable
fun PlayerSideButtons(state: PlayerUiState, actions: PlayerActions, iconBox: Dp) {
    if (state.lockState == LockVisibility.GONE) return
    val shown = state.lockState == LockVisibility.SHOWN
    // 边距跟随 window 分档（竖屏预览 16dp / 横屏全屏与平板 48dp，见 playerEdgePadding）
    val edge = playerEdgePadding()
    val iconSize = iconBox * ICON_TO_BOX_RATIO
    Box(Modifier.fillMaxSize()) {
        SideButton(
            iconRes = R.drawable.ic_player_rotate,
            contentDescription = stringResource(
                if (state.isPortrait) R.string.player_rotate_landscape else R.string.player_rotate_portrait
            ),
            startSide = true,
            edge = edge,
            iconSize = iconSize,
            // 锁定态隐藏（绕锁旋转无意义）
            visible = shown && !state.locked,
            onClick = actions::onRotateClicked,
        )
        SideButton(
            iconRes = if (state.locked) R.drawable.icon_lock else R.drawable.icon_unlock,
            contentDescription = stringResource(R.string.player_lock),
            startSide = false,
            edge = edge,
            iconSize = iconSize,
            visible = shown,
            onClick = actions::onLockClicked,
        )
    }
}

@Composable
private fun BoxScope.SideButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    startSide: Boolean,
    edge: Dp,
    iconSize: Dp,
    visible: Boolean,
    onClick: () -> Unit,
) {
    Image(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        alpha = if (visible) 1f else 0f,
        modifier = Modifier
            .align(if (startSide) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(start = if (startSide) edge else 0.dp, end = if (startSide) 0.dp else edge)
            .size(iconSize)
            .then(
                if (visible) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onClick() })
                    }
                } else {
                    Modifier
                }
            ),
    )
}

/**
 * 长按倍速浮层(替代 play_speed_3_container / fromLongPress;倍率设置页可调 2x~10x)。
 *
 * 样式与其他提示浮层统一(2026-09-13 用户要求):复用 [HintPill] —— 与控制条进度提示
 * ([PlayerSeekHint])完全同款的半透明 surface 药丸,不再用旧的纯黑圆角底 `#66000000` + 白字;
 * 文字色随主题 `onSurface`,字号用 play 模块的 ts_26 档(与中央提示同级)。
 * 遮罩在屏时不显示:长按倍速作用的是上一次会话的残留内核,提示不该出现在加载画面上。
 */
@Composable
fun PlayerSpeedBoostHint(state: PlayerUiState) {
    if (!state.speedBoostVisible || state.tipVisible) return
    Box(Modifier.fillMaxSize()) {
        HintPill(modifier = Modifier.align(Alignment.Center)) {
            Text(
                text = "%.1f X".format(state.speedBoostValue),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = playerTextSize(R.dimen.ts_26),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
