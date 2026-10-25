package com.github.avbox.osc.ui.player

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import com.github.avbox.osc.player.controller.VideoPlayerController
import com.github.avbox.osc.player.state.VideoRect
import com.github.avbox.osc.ui.theme.AVBoxTheme

/**
 * 播放器 UI 的根节点。
 *
 * 三个必须保留的原生 View（弹幕式字幕 / media3 字幕 / 歌词）通过 [AndroidView] 桥接，
 * 层级顺序与改造前 ComposeVideoController 的 addView 顺序保持一致：
 * 字幕 → media3 字幕 → 歌词 → Compose 控件层，后者盖在前者之上。
 */
@Composable
internal fun PlayerSurfaceHost(controller: VideoPlayerController) {
    val cfg = controller.playerConfig
    val codecFlow = controller.videoCodecFlow
    val decodeFlow = remember(cfg, codecFlow) { controller.config.decodeChoiceFlow(cfg) }
    val decodeChoice by decodeFlow.collectAsState(
        initial = remember(cfg) { controller.config.decodeChoiceNow(cfg) },
    )
    AVBoxTheme(manageStatusBarIcons = false) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { controller.onHostSizeChanged(it.width, it.height) },
        ) {
            Box(Modifier.videoRectLayout(controller.state.videoRect)) {
                AndroidView(
                    factory = { adopt(controller.subtitleView) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )
            }
            AndroidView(
                factory = { adopt(controller.exoSubtitleView) },
                modifier = Modifier.videoRectLayout(controller.state.videoRect),
            )
            AndroidView(
                factory = { adopt(controller.lyricView) },
                modifier = Modifier.fillMaxSize(),
            )
            PlayerOverlay(
                state = controller.state,
                actions = controller.actions,
                decodeChoice = decodeChoice,
                gestureHandler = controller.gestureHandler,
                gestureSession = { w, h, sw, y ->
                    controller.gestureActions.beginSession(w, h, sw, y)
                },
                onTapPending = { controller.onGestureTapPending() },
            )
        }
    }
}

/**
 * AndroidView 会复用同一批 View 实例，重新挂载前先脱离旧 parent，
 * 否则抛 "The specified child already has a parent"。
 */
private fun <T : View> adopt(view: T): T {
    (view.parent as? ViewGroup)?.removeView(view)
    return view
}

private fun Modifier.videoRectLayout(rect: VideoRect): Modifier = layout { measurable, constraints ->
    val hostWidth = constraints.maxWidth
    val hostHeight = constraints.maxHeight
    if (rect.empty) {
        val placeable = measurable.measure(constraints)
        return@layout layout(hostWidth, hostHeight) { placeable.place(0, 0) }
    }
    val placeable = measurable.measure(Constraints.fixed(rect.width, rect.height))
    layout(hostWidth, hostHeight) { placeable.place(rect.left, rect.top) }
}
