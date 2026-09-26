package com.github.tvbox.osc.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.tvbox.osc.R
import kotlinx.coroutines.launch

/**
 * 播放器面板公共骨架:面板容器/标题/按钮/标签行/chips/步进/输入框/加载指示,以及弹窗壳 [PlayerDialog]。
 * 具体面板见 DanmuSheets / SubtitleSheets / CastSheet / PlayerSelectDialog,同为播放器 Dialog 形态。
 *
 * 视觉走 M3 语义色与形状:面板 `surfaceContainer` + 18dp 圆角 + 轻投影;
 * 选项 `surfaceBright`,选中 `primaryContainer`;提示文字 `onSurfaceVariant`。
 * 字号/尺寸仍用 AutoSize(mm) 档(playerDim/playerTextSize):覆盖层按屏宽等比缩放,
 * 换成 M3 固定 sp 会在小屏上明显偏小。
 * 交互:触摸点按。
 */

/** M3 形状档:对话框面板 18dp、内部选项/输入框 12dp(medium) */
private val PanelShape = RoundedCornerShape(18.dp)
private val ItemShape = RoundedCornerShape(12.dp)

/** 弹窗进出场:面板内容 0.92→1.0 缩放 + 淡入(时长与 app 内 AVBoxDialog 对齐) */
private const val PANEL_ENTER_DURATION_MS = 220
private const val PANEL_ENTER_SCALE = 0.92f

/** 面板内容里的关闭入口:播完退场动画再关(与 app 内 LocalSheetDismiss 同款语义) */
internal val LocalPlayerSheetDismiss = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * "先播退场,再执行动作,最后 onDismiss"的关闭入口 —— 给"关掉本面板并打开下一个面板"用
 * (例如弹幕设置 → 弹幕搜索),否则两个面板窗口会重叠出现。
 */
internal val LocalPlayerSheetDismissThen = staticCompositionLocalOf<(action: () -> Unit) -> Unit> { { it() } }

/**
 * 播放器弹窗壳:**保留平台 Dialog**(独立窗口 = 天然屏蔽播放器手势、返回键收起、独立于播放器容器坐标系),
 * 只在窗口内部给面板内容补"缩放 + 淡入",退场也先播完动画再回调。
 *
 * 用法:面板内容里的所有关闭入口(选项点击/取消按钮/异步回调)一律走
 * [LocalPlayerSheetDismiss](只关闭)或 [LocalPlayerSheetDismissThen](先执行动作再关闭),
 * 直接调 `onDismiss()` 会绕过退场动画。点面板外空白同样走退场动画(自己铺的遮罩收这个手势)。
 *
 * 注意:遮罩的**变暗**仍是平台 dialog 窗口的 dim(瞬现)—— Compose 拿不到窗口遮罩,这点与 app 内的 AVBoxDialog 不同。
 */
@Composable
internal fun PlayerDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(PANEL_ENTER_DURATION_MS))
    }

    // 兜底:退场这 220ms 里组合若被销毁(全屏 reparent 会重建 PlayerOverlay 的组合、Activity 重建同理),
    // 协程被取消 ⇒ onDismiss() 永远不执行,而弹窗状态还挂在 PlayerUiState 上 —— 重建后面板会自己弹回来。
    // 6 处 onDismiss 都是"置 null"的幂等操作,所以销毁时补一次是安全的。
    DisposableEffect(Unit) {
        onDispose { if (closing && !dismissed) onDismiss() }
    }

    val dismissAnimated: () -> Unit = {
        // closing 兼作防重入:退场动画期间的重复关闭(连点两个选项)直接吞掉
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, tween(PANEL_ENTER_DURATION_MS))
                dismissed = true
                onDismiss()
            }
        }
    }
    val dismissThen: (() -> Unit) -> Unit = { action ->
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, tween(PANEL_ENTER_DURATION_MS))
                action()
                dismissed = true
                onDismiss()
            }
        }
    }

    Dialog(onDismissRequest = dismissAnimated, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 面板居中 ⇒ 整屏内容做缩放等价于面板就地缩放(与 AVBoxDialog 同款观感)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    val scale = PANEL_ENTER_SCALE + (1f - PANEL_ENTER_SCALE) * p
                    scaleX = scale
                    scaleY = scale
                    alpha = p
                },
            contentAlignment = Alignment.Center,
        ) {
            // 点面板外空白关闭:平台 Dialog 的 dismissOnClickOutside 在这种"满屏内容"下永远不触发
            // (Compose 判定"是否在内容内"用的就是这块整屏 Box 的实测尺寸,见 DialogLayout.isInsideContent),
            // 所以自己铺一层遮罩收这个手势。面板本体是 M3 Surface(内部 pointerInput 挡穿透),
            // 面板上的点击不会被这层吃掉。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        enabled = !closing,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { dismissAnimated() },
                    ),
            )
            CompositionLocalProvider(
                LocalPlayerSheetDismiss provides dismissAnimated,
                LocalPlayerSheetDismissThen provides dismissThen,
                content = content,
            )
            // 退场这 220ms 里面板还挂着:不加一层吃触摸的盖子,连点两个选项会触发两次动作
            // (旧实现 onDismiss 当场摘状态,没这个窗口)
            if (closing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
            }
        }
    }
}

/** 聚焦描边宽度(M3 焦点提示:primary 描边 + 底色调档) */
private val FocusStroke = 2.dp

// ---------------------------------------------------------------------------
// 公共骨架组件
// ---------------------------------------------------------------------------

/** 对话框面板:M3 dialog 形态 —— 18dp 圆角、`surfaceContainer` 底、无描边 + 6dp 阴影 */
@Composable
internal fun SheetPanel(
    width: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.width(width),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
    ) {
        Column(content = content)
    }
}

/** 对话框标题(居中):`onSurface` + M3 Medium 字重 */
@Composable
internal fun SheetTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = playerTextSize(R.dimen.ts_26),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = playerDim(R.dimen.vs_30)),
    )
}

/** 面板按钮:M3 选项样式 —— `surfaceBright` 底、选中 `primaryContainer`;触摸点按。
 *  [contentPadding] 给"宽度随内容"的 chips 用(默认 0 = 沿用调用方的宽度/权重)。 */
@Composable
internal fun SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentPadding: Dp = 0.dp,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceBright
    }
    val m = modifier
        .background(container, ItemShape)
        .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
        .padding(horizontal = contentPadding)
        .height(playerDim(R.dimen.vs_50))
    Box(modifier = m, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontSize = playerTextSize(R.dimen.ts_20),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 左标签行:120mm 右对齐标签(`onSurfaceVariant`)+ 右侧 50mm 高控件区 */
@Composable
internal fun SheetLabelRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = playerDim(R.dimen.vs_5), horizontal = playerDim(R.dimen.vs_30)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = playerTextSize(R.dimen.ts_20),
            textAlign = TextAlign.End,
            modifier = Modifier.width(playerDim(R.dimen.vs_120)),
        )
        Row(
            Modifier
                .weight(1f)
                .padding(start = playerDim(R.dimen.vs_20))
                .height(playerDim(R.dimen.vs_50)),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/** 横排单选 chips,间距 vs_10 */
@Composable
internal fun SheetChipRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10))) {
        options.forEachIndexed { idx, label ->
            SheetButton(
                text = label,
                selected = idx == selected,
                onClick = { onSelect(idx) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 步进行(减 / 值 / 加;值居中 ts_26) */
@Composable
internal fun SheetStepper(
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SheetButton("-", onClick = onMinus, modifier = Modifier.size(playerDim(R.dimen.vs_50)))
        Text(
            text = valueText,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = playerTextSize(R.dimen.ts_20),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        SheetButton("+", onClick = onPlus, modifier = Modifier.size(playerDim(R.dimen.vs_50)))
    }
}

/** 面板输入框:M3 输入框样式(`surfaceContainerHighest` 底 + outline 描边,聚焦 primary 描边);IME 搜索键提交 */
@Composable
internal fun SheetInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, ItemShape)
            .border(
                if (focused) FocusStroke else 1.dp,
                if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                ItemShape,
            )
            .padding(horizontal = playerDim(R.dimen.vs_20), vertical = playerDim(R.dimen.vs_10)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = playerTextSize(R.dimen.ts_26),
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = playerTextSize(R.dimen.ts_26),
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** 对话框内加载指示 */
@Composable
internal fun SheetLoading(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(size),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = playerDim(R.dimen.vs_2),
        )
    }
}

internal fun Context.findActivityOrNull(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}