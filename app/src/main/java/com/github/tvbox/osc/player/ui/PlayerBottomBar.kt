package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import xyz.doikki.videoplayer.util.PlayerUtils.stringForTime

/** SeekBar max 照搬旧布局 android:max="1000" */
private const val SEEK_MAX = 1000

/** 预览态（竖屏详情页）进度行播放/暂停钮的触摸盒尺寸：与详情页右下角全屏入口同款 40dp 盒 / 22dp 图形 */
private val PreviewPlayPauseBox = 40.dp

@Composable
fun PlayerBottomBar(
    state: PlayerUiState,
    actions: PlayerActions,
    iconBox: Dp,
    modifier: Modifier = Modifier,
) {
    if (!state.controlsVisible) return
    // 左右边距按窗口宽度分档（竖屏预览 16dp / 横屏全屏与平板 48dp，见 playerEdgePadding）
    val edge = playerEdgePadding()
    // 预览态进度行左侧多了播放/暂停钮（40dp 触摸盒，行高因此变高）：底距改成
    // `16dp + vs_30/2 - 40dp/2`（与详情页右下角全屏入口的 bottom 偏移同一式子，见 DetailActivity 注释），
    // 使「暂停钮 / 进度条 / 全屏钮」共用同一水平中心线，且进度条中心线位置与改动前一致。
    val bottomPad = if (state.previewMode) {
        // vs_30 太小时该式子会变负(Compose 的 padding 要求非负)，钳到 0
        (16.dp + playerDim(R.dimen.vs_30) / 2 - PreviewPlayPauseBox / 2).coerceAtLeast(0.dp)
    } else {
        6.dp
    }
    Column(
        modifier
            .fillMaxWidth()
            // 轻量化：实底面板改为自下而上的渐变 scrim
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.5f),
                )
            )
            .padding(start = edge, end = edge, top = 10.dp, bottom = bottomPad)
    ) {
        // —— 时间胶囊（进度条左上角）；预览态不显示（预览态时间仍在进度条两侧） ——
        if (!state.previewMode) {
            PlayerTimePill(
                state = state,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // —— 进度行（预览态 = 播放/暂停钮 + 时间 - 进度条 - 总时长；全屏态 = 进度条整行） ——
        // 预览态右侧预留 44dp 给详情页右下角全屏入口图标，进度行与其融合不重叠
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = if (state.previewMode) 44.dp else 0.dp),
        ) {
            if (state.previewMode) {
                PreviewPlayPauseButton(state, actions)
            }
           
            if (state.previewMode) {
                CurrentTimeText(state)
            }
            PlayerSeekRow(
                state = state,
                actions = actions,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = if (state.previewMode) 8.dp else 0.dp),
            )
            if (state.previewMode) {
                Text(
                    text = stringForTime(state.duration),
                    color = Color.White,
                    fontSize = playerTextSize(R.dimen.ts_20),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
            }
        }

        // —— 菜单行；预览态不显示，避免抬高进度条 ——
        if (!state.previewMode) {
            PlayerActionPill(
                actions = actions,
                iconBox = iconBox,
                modifier = Modifier.padding(top = 6.dp),
            )
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

/** 时间胶囊底色透明度 */
private const val OVERLAY_PILL_ALPHA = 0.2f

private const val PILL_DIVIDER_ALPHA = 0.3f

@Composable
private fun PlayerActionPill(
    actions: PlayerActions,
    iconBox: Dp,
    modifier: Modifier = Modifier,
) {
    val gap = playerDim(R.dimen.vs_8)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_refresh,
            label = stringResource(R.string.common_refresh),
            box = iconBox,
            onClick = actions::onRefreshClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.ic_detail_cast,
            label = stringResource(R.string.common_cast),
            box = iconBox,
            onClick = actions::onCastClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_subtitle,
            label = stringResource(R.string.player_menu_subtitle),
            box = iconBox,
            onClick = actions::onSubtitleClicked,
            onLongClick = actions::onSubtitleLongClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_audio,
            label = stringResource(R.string.player_menu_audio_track),
            box = iconBox,
            onClick = actions::onAudioTrackClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_video,
            label = stringResource(R.string.player_menu_video_track),
            box = iconBox,
            onClick = actions::onVideoTrackClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_danmu,
            label = stringResource(R.string.player_menu_danmu),
            box = iconBox,
            onClick = actions::onDanmuSettingClicked,
            onLongClick = actions::onDanmuSettingLongClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillDivider(iconBox)
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_menu_episodes,
            label = stringResource(R.string.detail_episodes),
            box = iconBox,
            onClick = actions::onEpisodeClicked,
            modifier = Modifier.weight(1f),
        )
        PlayerPillIconButton(
            iconRes = R.drawable.player_ic_params,
            label = stringResource(R.string.player_menu_params),
            box = iconBox,
            onClick = actions::onParamsClicked,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlayerPillDivider(iconBox: Dp) {
    Box(
        Modifier
            .padding(horizontal = playerDim(R.dimen.vs_8))
            .width(1.dp)
            .height(iconBox * ICON_TO_BOX_RATIO)
            .background(Color.White.copy(alpha = PILL_DIVIDER_ALPHA), RoundedCornerShape(50)),
    )
}

/**
 * 进度条左上角的时间胶囊（`当前 / 总时长`）：仅全屏态显示，与动作胶囊同色同圆角。
 */
@Composable
private fun PlayerTimePill(state: PlayerUiState, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = OVERLAY_PILL_ALPHA), RoundedCornerShape(50))
            .padding(
                horizontal = playerDim(R.dimen.vs_10),
                vertical = playerDim(R.dimen.vs_5),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeRangeText(state)
    }
}

/** 与 [CurrentTimeText] 同理单独成 scope：拖拽期 seekPreviewPositionMs 每帧写入时只重组本 Text */
@Composable
private fun TimeRangeText(state: PlayerUiState) {
    Text(
        text = stringForTime(state.seekPreviewOrPosition) + " / " + stringForTime(state.duration),
        color = Color.White,
        fontSize = playerTextSize(R.dimen.ts_20),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

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

@Composable
private fun PreviewPlayPauseButton(state: PlayerUiState, actions: PlayerActions) {
    val playing = state.playbackActive
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
