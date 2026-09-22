package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils.stringForTime

/** SeekBar max 照搬旧布局 android:max="1000" */
private const val SEEK_MAX = 1000

/** 预览态（竖屏详情页）进度行播放/暂停钮的触摸盒尺寸：与详情页右下角全屏入口同款 40dp 盒 / 22dp 图形 */
private val PreviewPlayPauseBox = 40.dp

/**
 * 底部菜单（图二布局：进度行在上、菜单行在下）：
 * - 菜单用 FlowRow 自动铺开（SpaceBetween），不再横向滚动；已裁剪 下一集/上一集/重播/重置/屏显
 *   （上/下一集移至中央控制组，重置经片头/片尾长按可达）；
 * - 左右边距按窗口宽度分档（compact 16dp / ≥600dp 24dp，`playerEdgePadding()`）；上下边距 10dp / 16dp；
 * - 预览态（竖屏详情页）进度行左侧多一颗播放/暂停钮（2026-09-13 用户要求），与进度条、
 *   详情页右下角全屏入口共用同一水平中心线；
 * - 按钮可见性全部由 PlayerUiState 衍生规则驱动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerBottomBar(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    if (!state.controlsVisible) return
    // 左右边距按窗口宽度分档（竖屏预览 16dp / 横屏全屏与平板 24dp，见 playerEdgePadding）
    val edge = playerEdgePadding()
    // 预览态进度行左侧多了播放/暂停钮（40dp 触摸盒，行高因此变高）：底距改成
    // `16dp + vs_30/2 - 40dp/2`（与详情页右下角全屏入口的 bottom 偏移同一式子，见 DetailActivity 注释），
    // 使「暂停钮 / 进度条 / 全屏钮」共用同一水平中心线，且进度条中心线位置与改动前一致。
    val bottomPad = if (state.previewMode) {
        // vs_30 太小时该式子会变负(Compose 的 padding 要求非负)，钳到 0
        (16.dp + playerDim(R.dimen.vs_30) / 2 - PreviewPlayPauseBox / 2).coerceAtLeast(0.dp)
    } else {
        16.dp
    }
    Column(
        modifier
            .fillMaxWidth()
            // 轻量化：实底面板改为自下而上的渐变 scrim
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.72f),
                )
            )
            .padding(start = edge, end = edge, top = 10.dp, bottom = bottomPad)
    ) {
        // —— 进度行（时间 - 进度条 - 总时长，横竖屏同款；预览态左侧多一颗播放/暂停钮） ——
        // 预览态右侧预留 44dp 给详情页右下角全屏入口图标，进度行与其融合不重叠
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = if (state.previewMode) 44.dp else 0.dp),
        ) {
            if (state.previewMode) {
                PreviewPlayPauseButton(state, actions)
            }
            // 时间按内容自适应完整显示（照搬哔哩哔哩），进度条 weight 占据剩余宽度
            // (2026-09-14 BugFix) 拆出只读 seekPreviewOrPosition 的 CurrentTimeText：
            // 拖拽/步进期间 onSeekPreview 每帧写 seekPreviewPositionMs，若在本体组合期
            // 读取，整条底栏（含 FlowRow 菜单行）会每帧重组
            CurrentTimeText(state)
            PlayerSeekRow(
                state = state,
                actions = actions,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                text = stringForTime(state.duration),
                color = Color.White,
                fontSize = playerTextSize(R.dimen.ts_20),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 48.dp),
            )
        }

        // —— 菜单行（FlowRow 自动铺开）；预览态不显示，避免抬高进度条 ——
        if (!state.previewMode) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlayerMenuButton(stringResource(R.string.common_refresh), onClick = actions::onRefreshClicked)
            PlayerMenuButton(
                state.scaleBtnText,
                onClick = actions::onScaleClicked,
                onLongClick = actions::onScaleLongClicked,
            )
            if (state.liveButtonsVisible) {
                PlayerMenuButton(
                    state.speedBtnText,
                    onClick = actions::onSpeedClicked,
                    onLongClick = actions::onSpeedLongClicked,
                )
            }
            PlayerMenuButton(
                state.playerBtnText,
                onClick = actions::onPlayerClicked,
                onLongClick = actions::onPlayerLongClicked,
            )
            if (state.ijkBtnVisible) {
                PlayerMenuButton(state.ijkBtnText, onClick = actions::onIjkClicked)
            }
            if (state.liveButtonsVisible) {
                PlayerMenuButton(
                    state.timeStartText,
                    onClick = actions::onTimeStartClicked,
                    onLongClick = actions::onTimeStartLongClicked,
                )
                PlayerMenuButton(
                    state.timeEndText,
                    onClick = actions::onTimeEndClicked,
                    onLongClick = actions::onTimeEndLongClicked,
                )
            }
            if (state.castBtnVisible) {
                PlayerMenuButton(stringResource(R.string.common_cast), onClick = actions::onCastClicked)
            }
            PlayerMenuButton(
                stringResource(R.string.player_menu_subtitle),
                onClick = actions::onSubtitleClicked,
                onLongClick = actions::onSubtitleLongClicked,
            )
            if (state.trackBtnVisible) {
                PlayerMenuButton(stringResource(R.string.player_menu_audio_track), onClick = actions::onAudioTrackClicked)
            }
            if (state.trackBtnVisible) {
                PlayerMenuButton(stringResource(R.string.player_menu_video_track), onClick = actions::onVideoTrackClicked)
            }
            if (state.danmuBtnVisible) {
                PlayerMenuButton(
                    stringResource(R.string.player_menu_danmu),
                    onClick = actions::onDanmuSettingClicked,
                    onLongClick = actions::onDanmuSettingLongClicked,
                )
            }
            if (state.danmuSearchBtnVisible) {
                PlayerMenuButton(
                    stringResource(R.string.player_menu_search_danmu),
                    onClick = actions::onDanmuSearchClicked,
                    onLongClick = actions::onDanmuSearchLongClicked,
                )
            }
        }
        }

        // —— 解析行（旧 parse_root + mGridParseView）；预览态不显示，与菜单行同规则 ——
        if (state.showParseRow && !state.previewMode) {
            val parseList = remember(state.parseListVersion) { ApiConfig.get().parseBeanList.toList() }
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_menu_parse),
                    color = Color.White,
                    fontSize = playerTextSize(R.dimen.ts_20),
                    maxLines = 1,
                    modifier = Modifier.padding(end = playerDim(R.dimen.vs_10)),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5))) {
                    items(parseList.size) { index ->
                        val item = parseList[index]
                        PlayerMenuButton(
                            item.name,
                            onClick = { actions.onParseSelected(index) },
                            textColor = if (item.isDefault) Color(0xFF02F8E1) else Color.White,
                            textSizeId = R.dimen.ts_20,
                        )
                    }
                }
            }
        }
    }
}


/**
 * 进度行左侧当前时间（2026-09-14 BugFix 自 PlayerBottomBar 拆出）：
 * 拖拽/按键步进中显示预览位置，否则显示真实播放位置。
 * seekPreviewPositionMs 为帧级写入（拖拽每帧）、position 为 1Hz 写入（mShowProgress），
 * 单独成 scope 后二者只重组本 Text，不再令整条 PlayerBottomBar 失效。
 */
@Composable
private fun CurrentTimeText(state: PlayerUiState, modifier: Modifier = Modifier) {
    Text(
        text = stringForTime(state.seekPreviewOrPosition),
        color = Color.White,
        fontSize = playerTextSize(R.dimen.ts_20),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = modifier.widthIn(min = 48.dp),
    )
}


/**
 * 预览态（竖屏详情页）进度行左侧的播放/暂停钮（2026-09-13 用户要求）：
 * - 触摸盒 40dp、图形 22dp、白色 90% —— 与详情页右下角全屏入口完全同款（该入口 = 40dp 盒 + 9dp padding + 90% 白 tint），
 *   二者分列进度条左右两端且同一水平中心线；左侧边距与全屏入口的右侧边距一致（都取 `playerEdgePadding()`）。
 * - 图标状态判定与中央控制组一致：`BUFFERING` / `BUFFERED` 也算“播放中”——dkplayer 缓冲结束停在
 *   `STATE_BUFFERED` 不回 `STATE_PLAYING`，只判 `STATE_PLAYING` 会让图标反显（实际在播却显示“播放”）。
 */
@Composable
private fun PreviewPlayPauseButton(state: PlayerUiState, actions: PlayerActions) {
    val playing = state.playState == VideoView.STATE_PLAYING ||
            state.playState == VideoView.STATE_BUFFERING ||
            state.playState == VideoView.STATE_BUFFERED
    Box(
        modifier = Modifier
            .size(PreviewPlayPauseBox)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { actions.onPlayPauseClicked() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(
                if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play
            ),
            contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.9f)),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 自绘进度条：视觉照搬 shape_player_control_vod_seek（轨道 #4DFFFFFF / 缓冲 #66FFFFFF /
 * 进度 #FF4081，圆角 2dp）与 CircleThumbDrawable（12dp 白圆 + #FF4081 2dp 描边，激活 16dp）。
 * 交互：触摸拖拽/点按、鼠标滚轮步进。
 * 性能（2026-09-14 BugFix）：progress/buffered 在 Canvas 绘制块内读取 state，
 * 拖拽每帧/播放每秒只重绘本进度条，不触发 PlayerSeekRow 重组。
 */
@Composable
private fun PlayerSeekRow(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier,
) {
    var draggingLocal by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }

    // thumbActive 随拖拽起止翻转，低频，组合期读取无妨
    val thumbActive = draggingLocal || state.dragging

    var seekModifier = modifier
        .height(playerDim(R.dimen.vs_30))
        .pointerInput(Unit) {
            // 鼠标滚轮步进（旧 onGenericMotionEvent ACTION_SCROLL）
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll && state.duration > 0) {
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                        val dir = when {
                            delta.y != 0f -> if (delta.y > 0) 1 else -1
                            delta.x != 0f -> if (delta.x > 0) 1 else -1
                            else -> continue
                        }
                        actions.onSeekStep(dir)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            // 点按跳转（旧 SeekBar 点按：start → change → stop）
            detectTapGestures { offset ->
                if (state.duration <= 0) return@detectTapGestures
                val target = (offset.x / size.width * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
                actions.onSeekStarted()
                actions.onSeekPreview(target)
                actions.onSeekFinished(target)
            }
        }
        .pointerInput(Unit) {
            // 横向拖拽（旧 onStartTrackingTouch/onProgressChanged/onStopTrackingTouch）
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    if (state.duration > 0) {
                        actions.onSeekStarted()
                        draggingLocal = true
                        dragProgress = (offset.x / size.width * SEEK_MAX).coerceIn(0f, SEEK_MAX.toFloat())
                        actions.onSeekPreview(dragProgress.toInt())
                    }
                },
                onDragEnd = {
                    if (draggingLocal) {
                        actions.onSeekFinished(dragProgress.toInt())
                    }
                    draggingLocal = false
                },
                onDragCancel = {
                    if (draggingLocal) {
                        actions.onSeekCancelled()
                    }
                    draggingLocal = false
                },
            ) { change, dragAmount ->
                if (draggingLocal) {
                    dragProgress = (dragProgress + dragAmount / size.width * SEEK_MAX)
                        .coerceIn(0f, SEEK_MAX.toFloat())
                    actions.onSeekPreview(dragProgress.toInt())
                    change.consume()
                }
            }
        }

    Canvas(seekModifier) {
        // (2026-09-14 BugFix) progress/buffered 计算移入绘制块：拖拽期间
        // seekPreviewPositionMs 每帧写入、播放期间 position 每秒写入，绘制期读取
        // 只触发本 Canvas 重绘；组合期求值会令 PlayerSeekRow 每帧/每秒重组
        val progress: Float = when {
            state.dragging && state.duration > 0 ->
                state.seekPreviewPositionMs.toFloat() / state.duration * SEEK_MAX
            state.duration > 0 -> state.position.toFloat() / state.duration * SEEK_MAX
            else -> 0f
        }
        val buffered: Float =
            if (state.duration > 0) state.bufferedPercent / 100f * SEEK_MAX else 0f
        val trackHeight = 3.dp.toPx()
        val centerY = size.height / 2
        val corner = CornerRadius(2.dp.toPx())
        // 背景
        drawRoundRect(
            color = Color(0x4DFFFFFF),
            topLeft = Offset(0f, centerY - trackHeight / 2),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            cornerRadius = corner,
        )
        // 缓冲
        if (buffered > 0f) {
            drawRoundRect(
                color = Color(0x66FFFFFF),
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = androidx.compose.ui.geometry.Size(size.width * (buffered / SEEK_MAX), trackHeight),
                cornerRadius = corner,
            )
        }
        // 进度
        if (progress > 0f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = androidx.compose.ui.geometry.Size(size.width * (progress / SEEK_MAX), trackHeight),
                cornerRadius = corner,
            )
        }
        // 圆形 thumb（激活时放大；轻量化配色：白圆 + 半透明白描边）
        val thumbRadius = (if (thumbActive) 8.dp else 6.dp).toPx()
        val thumbCenter = Offset(size.width * (progress / SEEK_MAX), centerY)
        drawCircle(Color.White, radius = thumbRadius, center = thumbCenter)
        drawCircle(
            Color.White.copy(alpha = 0.55f),
            radius = thumbRadius,
            center = thumbCenter,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
