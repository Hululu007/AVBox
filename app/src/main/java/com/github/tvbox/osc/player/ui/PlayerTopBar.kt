package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState

/**
 * 顶部应用栏（照搬旧 tv_top_l_container / tv_top_r_container 布局与显隐规则）。
 * 阶段 3 直接修复：补 scrim 渐变，亮画面下白字不再糊在视频上。
 *
 * 显隐规则（§4.3，逐条逆向自旧 msg 1002/1003）：
 * - 左块（片名+分辨率）= 底栏可见 OR 竖屏切集临时标题(3s)；暂停时强制隐藏
 * - 右块（网速/进度/系统时间）= 屏显开关 OR 底栏可见（一旦显示过就保持可见）
 */
@Composable
fun PlayerTopBar(state: PlayerUiState, actions: PlayerActions) {
    val anyVisible = state.topLeftVisible || state.topRightVisible
    // 左右边距按窗口宽度分档（竖屏预览 16dp / 横屏全屏与平板 24dp，见 playerEdgePadding）
    val edge = playerEdgePadding()
    // 顶部安全区避让（2026-09-14 用户反馈：竖屏全屏/贴顶预览态下固定 12dp 的顶栏被摄像头挖孔遮挡）：
    // 顶栏贴近窗口顶部时，把 safeDrawing 顶部（状态栏 + 挖孔）尚未被自身位置覆盖的差值补进 top；
    // 不贴顶（详情页非贴顶预览态）或横屏全屏（系统栏隐藏后顶部安全区为 0、挖孔在侧边）时差值为 0，布局不变
    val density = LocalDensity.current
    val safeTopPx = WindowInsets.safeDrawing.getTop(density)
    var barTopPx by remember { mutableStateOf(Float.NaN) }
    val extraTop = if (barTopPx.isNaN()) {
        0.dp
    } else {
        with(density) { (safeTopPx - barTopPx).coerceAtLeast(0f).toDp() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { barTopPx = it.positionInWindow().y }
    ) {
        if (anyVisible) {
            // scrim 渐变（黑 55% → 透明），替代旧实现"无背景白字压画面"
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                        )
                    )
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = edge,
                    end = edge,
                    top = 12.dp + extraTop,
                    bottom = playerDim(R.dimen.vs_5),
                )
        ) {
            // —— 左块：返回箭头 + 片名 + 分辨率 ——
            if (state.topLeftVisible) {
                Row(Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically) {
                    // 返回箭头：点击等价于遥控器返回键（onBackClicked）
                    Box(
                        Modifier
                            .size(36.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { actions.onBackClicked() })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.player_ic_back),
                            contentDescription = "返回",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Column {
                        Text(
                            text = state.title,
                            color = Color.White,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = playerDim(R.dimen.vs_10),
                                top = playerDim(R.dimen.vs_5),
                            )
                        )
                        Text(
                            text = state.videoSize,
                            color = Color.White,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = playerDim(R.dimen.vs_10),
                                top = playerDim(R.dimen.vs_5),
                            )
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(3f))
            }
            // —— 右块：网速/进度时间/系统时间 ——
            if (state.topRightVisible) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.netSpeedSideVisible) {
                            TopBarText(state.netSpeedTopRight)
                        }
                        if (state.seekTimeVisible) {
                            TopBarText(state.seekTimeText)
                        }
                        if (state.sysTimeVisible && state.batteryPercent in 0..100) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // 时间文字自带 top=vs_5，此处不补会与时间错位
                                modifier = Modifier.padding(
                                    end = playerDim(R.dimen.vs_10),
                                    top = playerDim(R.dimen.vs_5),
                                ),
                            ) {
                                Text(
                                    text = "${state.batteryPercent}%",
                                    color = Color.White,
                                    fontSize = playerTextSize(R.dimen.ts_20),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Image(
                                    painter = painterResource(batteryIcon(state)),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        if (state.sysTimeVisible) {
                            TopBarText(state.sysTime)
                        }
                    }
                    if (state.netSpeedTopRightVisible) {
                        TopBarText(state.netSpeedTopRight)
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 电池图标档位：充电/充满 → 闪电帧；否则按百分比映射 1~6 档（每档约 16.7%） */
private fun batteryIcon(state: PlayerUiState): Int = when {
    state.batteryCharging -> R.drawable.ic_battery_charging
    state.batteryPercent <= 16 -> R.drawable.ic_battery_1
    state.batteryPercent <= 33 -> R.drawable.ic_battery_2
    state.batteryPercent <= 50 -> R.drawable.ic_battery_3
    state.batteryPercent <= 66 -> R.drawable.ic_battery_4
    state.batteryPercent <= 83 -> R.drawable.ic_battery_5
    else -> R.drawable.ic_battery_6
}

@Composable
private fun TopBarText(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = playerTextSize(R.dimen.ts_20),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(
            end = playerDim(R.dimen.vs_10),
            top = playerDim(R.dimen.vs_5),
        )
    )
}
