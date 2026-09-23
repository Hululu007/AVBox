package com.github.tvbox.osc.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AVBoxBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color? = null,
    isScrollable: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = OverlayRequest(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    title = title,
    containerColor = containerColor,
    isScrollable = isScrollable,
    headerContent = headerContent,
    variant = SheetVariant.BOTTOM,
    content = content,
)

/**
 * 弹层的宿主路由:窗口根有 [SheetHost] 槽位就投递上去(页面在被裁剪容器里时也能盖住底栏与系统栏),
 * 否则就地渲染。[AVBoxBottomSheet] 与 [AVBoxDialog] 共用这一条路径,只有 [variant] 不同。
 */
@Composable
internal fun OverlayRequest(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color? = null,
    isScrollable: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    variant: SheetVariant = SheetVariant.BOTTOM,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalSheetHost.current
    if (host == null) {
        SheetOverlay(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            title = title,
            containerColor = containerColor,
            isScrollable = isScrollable,
            headerContent = headerContent,
            variant = variant,
            dismissible = dismissible,
            content = content,
        )
    } else {
        val id = remember { Any() }
        SideEffect {
            host.submit(
                SheetRequest(
                    id, onDismissRequest, modifier, title, containerColor, isScrollable, headerContent, variant,
                    dismissible, content,
                ),
            )
        }
        DisposableEffect(id) {
            onDispose { host.clear(id) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AVBoxOptionSheet(
    onDismissRequest: () -> Unit,
    title: String?,
    options: List<String>,
    selected: String?,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    AVBoxBottomSheet(
        onDismissRequest = onDismissRequest,
        title = title,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        val dismissAnimated = LocalSheetDismiss.current
        var accepted by remember { mutableStateOf(false) }
        SettingsGroup(
            title = null,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            options.forEachIndexed { index, option ->
                SettingsCard(
                    position = optionCardPosition(index, options.size),
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    SettingsOptionRow(
                        title = option,
                        selected = option == selected,
                        onClick = {
                            if (!accepted) {
                                accepted = true
                                onSelect(option)
                                dismissAnimated()
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun optionCardPosition(index: Int, size: Int): SettingsCardPosition = when {
    size <= 1 -> SettingsCardPosition.SINGLE
    index == 0 -> SettingsCardPosition.FIRST
    index == size - 1 -> SettingsCardPosition.LAST
    else -> SettingsCardPosition.MIDDLE
}

private val SheetMaxWidth = 640.dp

private const val SheetMaxHeightFraction = 0.9f

private const val SHEET_SLIDE_DURATION_MS = 280

/** 居中对话框:进出场更短(缩放+淡入),面板是四角圆角、限宽 280~560dp */
private const val DIALOG_FADE_DURATION_MS = 220
private const val DIALOG_ENTER_SCALE = 0.90f
private const val DIALOG_SCRIM_ALPHA = 0.6f
private val DialogMinWidth = 280.dp
private val DialogMaxWidth = 560.dp
private val DialogShape = RoundedCornerShape(28.dp)
private val DIALOG_HORIZONTAL_MARGIN = 24.dp

private const val SHEET_DRAG_DISMISS_FRACTION = 0.25f
private const val SHEET_DRAG_DISMISS_VELOCITY = 1400f

/** [SheetVariant.BOTTOM] = 贴底弹层(上滑入场、可拖拽关闭);[SheetVariant.CENTER] = 居中对话框(缩放淡入) */
internal enum class SheetVariant { BOTTOM, CENTER }

internal class SheetRequest(
    val id: Any,
    val onDismissRequest: () -> Unit,
    val modifier: Modifier,
    val title: String?,
    val containerColor: Color?,
    val isScrollable: Boolean,
    val headerContent: (@Composable () -> Unit)?,
    val variant: SheetVariant,
    val dismissible: Boolean,
    val content: @Composable ColumnScope.() -> Unit,
)

@Stable
class SheetHostState {
    internal var request by mutableStateOf<SheetRequest?>(null)
        private set

    internal fun submit(newRequest: SheetRequest) {
        request = newRequest
    }

    internal fun clear(id: Any) {
        if (request?.id === id) request = null
    }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

val LocalSheetDismiss = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * "先播退场动画,再执行 [action]"的关闭入口(供确认类按钮用):
 * 动画跑完才执行 action,由 action 自己清掉宿主可见性状态(它通常会顺带把弹层从组合里摘掉)。
 * 注意:与 [LocalSheetDismiss] 不同,这条路径**不会**调用 `onDismissRequest` —— 确认动作与"取消/点遮罩"
 * 的收尾逻辑往往不是一回事(例如语言切换对话框:确认要重启、取消要回滚)。
 */
val LocalSheetDismissThen = staticCompositionLocalOf<(action: () -> Unit) -> Unit> { { it() } }

@Composable
fun SheetHost(state: SheetHostState) {
    state.request?.let { req ->
        // 槽位只有一个:请求被另一个调用点顶替时(例如面板里点出确认对话框)必须重建覆盖层,
        // 否则会复用上一个请求的 Animatable/dismissing 状态 —— 退场动画会把它一起带走。
        key(req.id) {
            SheetOverlay(
                onDismissRequest = req.onDismissRequest,
                modifier = req.modifier,
                title = req.title,
                containerColor = req.containerColor,
                isScrollable = req.isScrollable,
                headerContent = req.headerContent,
                variant = req.variant,
                dismissible = req.dismissible,
                content = req.content,
            )
        }
    }
}

/**
 * 页面级弹层宿主:内容 + 窗口根槽位同层(照 `MainScreen.MainContent` 的写法封装)。
 *
 * **独立 Activity 的页面必须套这一层** —— 弹层的契约是"有槽位就投到窗口根,否则就地渲染":
 * 就地渲染时 `Box(fillMaxSize)` 会被调用点的容器吃掉,弹层会被塞进列表项里(实测事故:
 * `PreferenceSettingsActivity` 的"切换语言"对话框被渲染在设置列表内部的卡片里、还没有遮罩)。
 * 挂在 `MainScreen` pager 里的页面不需要它(`MainContent` 已经提供了槽位)。
 */
@Composable
fun SheetHostScaffold(content: @Composable () -> Unit) {
    val host = remember { SheetHostState() }
    CompositionLocalProvider(LocalSheetHost provides host) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            SheetHost(host)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetOverlay(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color? = null,
    isScrollable: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    variant: SheetVariant = SheetVariant.BOTTOM,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val collapse = remember { Animatable(1f) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var entered by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    // 走"点遮罩/返回键"这条路径时:退场动画跑完才会调 onDismissRequest。记一个标志,好在组合中途被销毁时补调用。
    var plainDismissPending by remember { mutableStateOf(false) }
    val centered = variant == SheetVariant.CENTER
    val durationMs = if (centered) DIALOG_FADE_DURATION_MS else SHEET_SLIDE_DURATION_MS

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        collapse.animateTo(0f, tween(durationMs))
        entered = true
    }

    /**
     * [after] 为 null = 播完退场后走 `onDismissRequest`(点遮罩/返回键/取消按钮);
     * [after] 非 null = 播完退场后执行它、**不**再调 `onDismissRequest`,由它自己把弹层从组合里摘掉
     * (确认类动作与取消的收尾逻辑不同,见 [LocalSheetDismissThen])。
     */
    fun dismissWithAnimation(after: (() -> Unit)? = null) {
        if (dismissing) return
        // 阻断式弹窗(如"启动失败"必须重试/离线二选一):只拦"点遮罩/返回键"这类无动作关闭 ——
        // 播了退场却没人清状态的话,面板会隐身留场(dismissing=true 还会吞掉后续关闭入口),
        // 覆盖层继续吃掉整屏触摸 = 用户卡死。带动作的关闭(重试/离线按钮)必须照常走,否则按钮变死键。
        if (!dismissible && after == null) {
            onDismissRequest()
            return
        }
        dismissing = true
        plainDismissPending = after == null
        // 覆盖层在应用窗口内,收弹窗时没人顺手替我们收键盘 —— 平台 dialog 是"窗口没了键盘跟着没",
        // 这里必须显式收:先清焦点(否则输入框一离场焦点又跳回来),再 hide。
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            collapse.animateTo(1f, tween(durationMs))
            if (after == null) {
                plainDismissPending = false
                onDismissRequest()
            } else {
                after()
            }
        }
    }

    // 兜底:退场动画期间组合若被销毁(页面/Activity 重建、被移出组合),协程被取消 ⇒ onDismissRequest()
    // 永远不执行,而弹层可见性状态还挂在外层 —— 重建后弹层会自己弹回来。
    // ⚠️ 只补"点遮罩/返回键"这条路径(它本来就要调 onDismissRequest);
    // 带动作的关闭(after != null)绝不能补:它的收尾不是 onDismissRequest(例如语言切换对话框"取消=回滚"),
    // 补调会把用户刚确认的动作反过来。
    DisposableEffect(Unit) {
        onDispose { if (plainDismissPending) onDismissRequest() }
    }

    BackHandler(enabled = entered) { dismissWithAnimation() }

    val scrimColor = if (centered) {
        // 对话框沿用平台 dialog 的遮罩浓度(Theme.Material 的 backgroundDimAmount = 0.6),
        // 免得"弹窗变亮了";弹层维持 BottomSheetDefaults.ScrimColor(0.32)不变。
        BottomSheetDefaults.ScrimColor.copy(alpha = DIALOG_SCRIM_ALPHA)
    } else {
        BottomSheetDefaults.ScrimColor
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelMaxHeight = maxHeight * SheetMaxHeightFraction
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - collapse.value }
                .background(scrimColor)
                .clickable(
                    // 不可关闭时仍要吃掉触摸(保持模态),只是什么都不做
                    enabled = entered,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissWithAnimation() },
                ),
        )
        // 遮罩恒满屏;键盘让位改挂在面板内容上(见下方 Column)⇒ 面板底边恒贴屏底,收起时不会露出下方页面
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (centered) Modifier.imePadding() else Modifier),
            contentAlignment = if (centered) Alignment.Center else Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .then(modifier)
                    .then(
                        if (centered) {
                            Modifier
                                .padding(horizontal = DIALOG_HORIZONTAL_MARGIN)
                                .widthIn(min = DialogMinWidth, max = DialogMaxWidth)
                        } else {
                            Modifier.widthIn(max = SheetMaxWidth)
                        },
                    )
                    .fillMaxWidth()
                    .heightIn(max = panelMaxHeight)
                    .graphicsLayer {
                        if (centered) {
                            val progress = 1f - collapse.value
                            val scale = DIALOG_ENTER_SCALE + (1f - DIALOG_ENTER_SCALE) * progress
                            scaleX = scale
                            scaleY = scale
                            alpha = progress
                        } else {
                            translationY = collapse.value * size.height
                        }
                    }
                    .onGloballyPositioned { panelHeightPx = it.size.height },
                shape = if (centered) DialogShape else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = containerColor ?: BottomSheetDefaults.ContainerColor,
            ) {
                CompositionLocalProvider(
                    LocalSheetDismiss provides { dismissWithAnimation() },
                    LocalSheetDismissThen provides { action -> dismissWithAnimation(action) },
                ) {
                    // 贴底弹层:键盘高度留在面板内部,面板底边不动 ⇒ 收起键盘时不会在底部漏出下方页面
                    Column(
                        modifier = if (centered) Modifier else Modifier.imePadding(),
                    ) {
                        // 居中对话框没有把手、也不吃下滑关闭手势(内容要能正常滚/选文字),
                        // 标题由调用方画在内容里(见 AVBoxAlertDialog)。
                        if (!centered) {
                            Column(
                                modifier = Modifier.draggable(
                                    state = sheetDragState(collapse, entered, panelHeightPx),
                                    orientation = Orientation.Vertical,
                                    onDragStopped = { velocity ->
                                        val dismiss = collapse.value > SHEET_DRAG_DISMISS_FRACTION ||
                                                velocity > SHEET_DRAG_DISMISS_VELOCITY
                                        if (dismiss) {
                                            dismissWithAnimation()
                                        } else {
                                            scope.launch { collapse.animateTo(0f, tween(durationMs)) }
                                        }
                                    },
                                ),
                            ) {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    BottomSheetDefaults.DragHandle()
                                }
                                headerContent?.invoke()
                                title?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                        if (isScrollable) {
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                                content = content,
                            )
                        } else {
                            Column(modifier = Modifier.weight(1f, fill = false), content = content)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sheetDragState(
    collapse: Animatable<Float, AnimationVector1D>,
    entered: Boolean,
    panelHeightPx: Int,
): DraggableState {
    val scope = rememberCoroutineScope()
    return rememberDraggableState { delta ->
        if (entered && panelHeightPx > 0) {
            scope.launch {
                collapse.snapTo(
                    (collapse.value + delta / panelHeightPx).coerceIn(0f, 1f),
                )
            }
        }
    }
}
