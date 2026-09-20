package com.github.tvbox.osc.player.ui

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.EpisodeSheetState

/** 选集面板 */

// ---------------------------------------------------------------------------
// 选集(右半屏面板 + 左半屏关闭区;列数按最长集名估宽 1~4)
// ---------------------------------------------------------------------------

@Composable
fun EpisodeSheet(sheet: EpisodeSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.fillMaxSize()) {
            // 左半屏:点击收起(旧 episode_dismiss)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 侧边面板形态:面板贴屏幕上下边,只圆起始侧;圆角档与对话框一致
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
                    )
                    .padding(start = playerDim(R.dimen.vs_30), top = playerDim(R.dimen.vs_24),
                        end = playerDim(R.dimen.vs_30), bottom = playerDim(R.dimen.vs_24)),
            ) {
                Text(
                    text = sheet.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = playerTextSize(R.dimen.ts_26),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(playerDim(R.dimen.vs_50)),
                )
                Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
                val gridState = rememberLazyGridState()
                var widthPx by remember { mutableStateOf(0) }
                val spanCount = remember(sheet.episodes, widthPx) {
                    if (widthPx <= 0) 2 else {
                        val res = context.resources
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                        paint.textSize = res.getDimension(R.dimen.ts_20)
                        val bounds = Rect()
                        var maxTextWidth = 1
                        for (episode in sheet.episodes) {
                            val name = episode.name
                            if (name.isEmpty()) continue
                            paint.getTextBounds(name, 0, name.length, bounds)
                            if (bounds.width() > maxTextWidth) maxTextWidth = bounds.width()
                        }
                        val itemPadding = res.getDimensionPixelSize(R.dimen.vs_10) * 2
                        val itemMargin = res.getDimensionPixelSize(R.dimen.vs_5) * 2
                        val itemWidth = maxTextWidth + itemPadding + itemMargin
                        (widthPx / itemWidth.coerceAtLeast(1)).coerceIn(1, 4)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(spanCount),
                    state = gridState,
                    verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
                    horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { widthPx = it.width },
                ) {
                    itemsIndexed(sheet.episodes) { idx, episode ->
                        SheetButton(
                            text = episode.name,
                            selected = idx == sheet.currentIndex,
                            onClick = {
                                onDismiss()
                                sheet.onSelect(idx)
                            },
                        )
                    }
                }
                // 定位当前集(旧 setSelectionWithSmooth)
                LaunchedEffect(sheet.currentIndex) {
                    runCatching { gridState.scrollToItem(sheet.currentIndex) }
                }
            }
        }
    }
}
