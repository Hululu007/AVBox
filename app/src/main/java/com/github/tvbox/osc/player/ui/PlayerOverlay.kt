package com.github.tvbox.osc.player.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.platform.LocalWindowInfo
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
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 播放器控制层根 Composable（Compose 化改造 §3.3 方案 C1）。
 * 层级顺序照搬 player_vod_control_view.xml 的 z-order（自底向上）：
 * 加载/错误遮罩 → 顶部栏 → 底部菜单 → 暂停浮层 → 亮度/音量提示 → seek 提示 → loading → 中央网速 →
 * 返回键 → 右侧竖排（旋转/锁）→ 长按倍速。原生字幕视图是控制器的直接子 View（位于 Compose 层之下），
 * 与旧布局一致。
 *
 * ⚠️ 遮罩（[PlayerTipLayer]）必须留在最底、顶栏/底栏之前，否则加载期唤不出控件（见该层注释）。
 */
@Composable
fun PlayerOverlay(
    state: PlayerUiState,
    actions: PlayerActions,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 图标盒只有一处算,胶囊与右侧竖排共用 ⇒ 两处图标必然等大(竖屏全屏下胶囊会被钳小)
        val iconBox = playerIconBox(maxWidth - playerEdgePadding() * 2)
        PlayerTipLayer(state)
        PlayerTopBar(state, actions)
        // 旧 XML bottom_container 为 layout_gravity="bottom"（BoxScope 内显式贴底）
        PlayerBottomBar(state, actions, iconBox, Modifier.align(Alignment.BottomCenter))
        PlayerCenterControls(state, actions, Modifier.align(Alignment.Center))
        PlayerPauseLayer(state, actions)
        PlayerSlideHint(state)
        PlayerSeekHint(state)
        // 遮罩自带指示器：同时在屏会叠出两层转圈（遮罩的居中指示器 + 缓冲转圈），故遮罩在屏时不画
        if (!state.tipVisible) PlayerLoadingLayer(state)
        PlayerNetSpeedCenter(state)
        PlayerSideButtons(state, actions, iconBox)
        PlayerSpeedBoostHint(state)

        // 尺寸/倍速/播放器选择弹窗（阶段 7）
        state.selectDialog?.let { dialogState ->
            PlayerSelectDialog(
                dialogState = dialogState,
                onDismiss = { state.selectDialog = null },
            )
        }

        // 播放参数抽屉：横屏贴右滑出、竖屏贴底滑出（与选集面板同形态）
        state.paramsSheet?.let { sheet ->
            PlayerParamsSheet(
                sheet = sheet,
                slideFromEnd = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
                onDismiss = { state.paramsSheet = null },
            )
        }

        // Step 6 对话框 Compose 化（替代 View 版 Danmu/SearchDanmu/Subtitle/SearchSubtitle/Cast Dialog）
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
// 共享辅助：mm 尺寸换算 / 菜单按钮
// ---------------------------------------------------------------------------

/** 覆盖层设计基准宽（= `BaseActivity.getSizeInDp()` 的常态值） */
private const val PLAYER_DESIGN_WIDTH = 1280f

/** 覆盖层尺寸的唯一事实来源（窗口长边像素 / 设计宽）。⚠️ 别改回 `getDimension*()` + 方向补偿：
 *  AutoSize 的 screenWidth 由库（显示宽）与 `refreshAutoSize`（窗口宽）两处写入、变更又不触发重组，旧方向的尺寸会烙进 TextUnit ⇒ 小窗↔全屏切换时字号突变。 */
@Composable
private fun playerMmScale(): Float {
    // containerSize 是真实容器像素且随布局即时更新（自由窗口/桌面模式/分屏下比 Configuration 准）；首帧未量出时回落 Configuration
    val container = LocalWindowInfo.current.containerSize
    val longEdgePx = if (container.width > 0 && container.height > 0) {
        maxOf(container.width, container.height).toFloat()
    } else {
        val conf = LocalConfiguration.current
        maxOf(conf.screenWidthDp, conf.screenHeightDp) * LocalDensity.current.density
    }
    return if (longEdgePx > 0f) longEdgePx / PLAYER_DESIGN_WIDTH else 1f
}

/** dimen 的原始数值（不带任何单位换算）；非 mm 单位返回 null。
 *  只认字面量：覆盖层用到的 dimen 必须保持配置无关（别加 `values-sw600dp` 之类），否则这里的 Resources 读不到新配置。 */
private fun rawMm(resources: Resources, @DimenRes id: Int): Float? {
    val tv = TypedValue()
    try {
        resources.getValue(id, tv, true)
    } catch (e: Resources.NotFoundException) {
        return null
    }
    if (tv.type != TypedValue.TYPE_DIMENSION) return null
    if ((tv.data and TypedValue.COMPLEX_UNIT_MASK) != TypedValue.COMPLEX_UNIT_MM) return null
    // ⚠️ 必须用 complexToFloat：维度值的 data 是定点编码(高 24 位尾数 + 低位单位/基数)，
    // getFloat() 会把这段位模式当 IEEE 浮点重解释(30mm → 1e-41)，尺寸静默变成 0
    return TypedValue.complexToFloat(tv.data)
}

/** mm dimens 按当前窗口换算为 Compose Dp；取整规则同旧 `getDimensionPixelSize`，非 mm 走系统换算。 */
@Composable
internal fun playerDim(@DimenRes id: Int): Dp {
    val res = LocalContext.current.resources
    val raw = rawMm(res, id)
    val px = if (raw != null) raw * playerMmScale() else res.getDimension(id)
    val pxInt = if (px == 0f) 0 else px.roundToInt().coerceAtLeast(1)
    return with(LocalDensity.current) { pxInt.toFloat().toDp() }
}

/** 与 [playerDim] 同一套换算，保留浮点（旧 `getDimension` 不取整）；菜单按钮字号用。 */
@Composable
internal fun playerTextSize(@DimenRes id: Int): TextUnit {
    val res = LocalContext.current.resources
    val raw = rawMm(res, id)
    val px = if (raw != null) raw * playerMmScale() else res.getDimension(id)
    // 非负保证：TextUnit/尺寸一旦为负或非有限，下游(布局/排版)会直接抛异常
    val safePx = if (px.isFinite()) px.coerceAtLeast(0f) else 0f
    return with(LocalDensity.current) { safePx.toSp() }
}

/**
 * 播放器覆盖层控件距屏幕边缘的距离：compact（screenWidthDp < 600，竖屏详情页预览态）16dp；
 * medium/expanded（横屏全屏、平板、折叠展开）48dp（2026-09-26 用户要求由 24dp 提至 48dp）。
 * 备注：边距与手势带无关 —— dkplayer 的 `PlayerUtils.isEdge()` 已忽略四边各 40dp 内的视频手势。
 */
@Composable
internal fun playerEdgePadding(): Dp =
    if (LocalConfiguration.current.screenWidthDp >= 600) 48.dp else 16.dp

/**
 * 中央控制组（图二样式）：显示底栏时屏幕中央出现三个半透明圆形按钮：
 * 左＝上一集、中＝播放/暂停（图标随播放态切换）、右＝下一集。
 * 触摸点按；锁定时不显示。固定尺寸（等比例缩放已回退）。
 * 预览态（竖屏详情页）同样显示；加载/解析期隐藏。
 */
@Composable
private fun PlayerCenterControls(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    if (!state.centerControlsVisible) return
    val playing = state.playbackActive
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

/** 解析行用的文字条目：常态纯文字全白，按压仅淡色底 + 文字加粗；选中色由调用方传入（02F8E1）。 */
@Composable
internal fun PlayerMenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    textColor: Color = Color.White,
    @DimenRes textSizeId: Int = R.dimen.ts_20,
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

/** 动作胶囊内的图标按钮：白色图标 + 下方文字标签 + 按压淡底（无障碍朗读由文字承担）。
 *  [box] 定图标盒尺寸 —— 竖屏全屏下不能直接用 `playerDim(vs_40)`（它按长边=屏高缩放，会撑爆宽度）。 */
@Composable
internal fun PlayerPillIconButton(
    @DrawableRes iconRes: Int,
    label: String,
    box: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.pointerInput(onClick, onLongClick) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { onClick() },
                onLongPress = onLongClick?.let { cb -> { cb() } },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .wrapContentWidth(Alignment.CenterHorizontally)
                .background(
                    if (pressed) Color.White.copy(alpha = 0.22f) else Color.Transparent,
                    RoundedCornerShape(50),
                )
                .padding(horizontal = playerDim(R.dimen.vs_10), vertical = playerDim(R.dimen.vs_2)),
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(box * ICON_TO_BOX_RATIO),
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = playerTextSize(R.dimen.ts_18),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 图标相对触摸盒的比例（照搬原 vs_24 / vs_40）；`PlayerLayers` 的右侧竖排也用它来与胶囊图标同尺寸 */
internal const val ICON_TO_BOX_RATIO = 0.6f

/** 动作胶囊最多同时可见的图标数（图标盒的宽度上限按"全可见"的最坏情况算） */
private const val PILL_MAX_ICONS = 8

/**
 * 覆盖层图标的触摸盒尺寸（动作胶囊与右侧竖排**共用同一结果**，两处图标因此必然等大）。
 * 按可用宽度钳制：`playerDim` 按窗口**长边**缩放，而竖屏全屏的长边 = 屏高、可用宽却由**短边**决定，
 * 不钳则胶囊铺满时每颗摊到的槽位会小于图标盒，图标相互挤压甚至溢出屏宽。
 */
@Composable
internal fun playerIconBox(availableWidth: Dp): Dp {
    val gap = playerDim(R.dimen.vs_8)
    return minOf(
        playerDim(R.dimen.vs_70),
        (availableWidth - gap * (PILL_MAX_ICONS + 1)) / PILL_MAX_ICONS,
    ).coerceAtLeast(1.dp)
}
