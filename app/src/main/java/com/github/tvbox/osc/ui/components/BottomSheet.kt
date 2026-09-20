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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalConfiguration
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
            content = content,
        )
    } else {
        val id = remember { Any() }
        SideEffect {
            host.submit(
                SheetRequest(id, onDismissRequest, modifier, title, containerColor, isScrollable, headerContent, content),
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

private const val SHEET_DRAG_DISMISS_FRACTION = 0.25f
private const val SHEET_DRAG_DISMISS_VELOCITY = 1400f

internal class SheetRequest(
    val id: Any,
    val onDismissRequest: () -> Unit,
    val modifier: Modifier,
    val title: String?,
    val containerColor: Color?,
    val isScrollable: Boolean,
    val headerContent: (@Composable () -> Unit)?,
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

@Composable
fun SheetHost(state: SheetHostState) {
    state.request?.let { req ->
        SheetOverlay(
            onDismissRequest = req.onDismissRequest,
            modifier = req.modifier,
            title = req.title,
            containerColor = req.containerColor,
            isScrollable = req.isScrollable,
            headerContent = req.headerContent,
            content = req.content,
        )
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val collapse = remember { Animatable(1f) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var entered by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        collapse.animateTo(0f, tween(SHEET_SLIDE_DURATION_MS))
        entered = true
    }

    fun dismissWithAnimation() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            collapse.animateTo(1f, tween(SHEET_SLIDE_DURATION_MS))
            onDismissRequest()
        }
    }

    BackHandler(enabled = entered) { dismissWithAnimation() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - collapse.value }
                .background(BottomSheetDefaults.ScrimColor)
                .clickable(
                    enabled = entered,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissWithAnimation() },
                ),
        )
        val panelMaxHeight = (LocalConfiguration.current.screenHeightDp * SheetMaxHeightFraction).dp
        Surface(
            modifier = Modifier
                .then(modifier)
                .widthIn(max = SheetMaxWidth)
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                .graphicsLayer { translationY = collapse.value * size.height }
                .onGloballyPositioned { panelHeightPx = it.size.height },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = containerColor ?: BottomSheetDefaults.ContainerColor,
        ) {
            CompositionLocalProvider(LocalSheetDismiss provides { dismissWithAnimation() }) {
                Column {
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
                                    scope.launch { collapse.animateTo(0f, tween(SHEET_SLIDE_DURATION_MS)) }
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
