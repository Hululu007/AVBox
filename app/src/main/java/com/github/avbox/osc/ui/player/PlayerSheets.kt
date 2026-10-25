package com.github.avbox.osc.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.theme.ContinuousPlayerPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PanelShape = ContinuousPlayerPanel
private val ItemShape = RoundedCornerShape(12.dp)

private const val PANEL_ENTER_DURATION_MS = 220
private const val PANEL_ENTER_SCALE = 0.90f
private const val PANEL_SCRIM_ALPHA = 0.6f

internal val LocalPlayerSheetDismiss = staticCompositionLocalOf<() -> Unit> { {} }

internal val LocalPlayerSheetDismissThen = staticCompositionLocalOf<(action: () -> Unit) -> Unit> { { it() } }

@OptIn(ExperimentalMaterial3Api::class)
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

    DisposableEffect(Unit) {
        onDispose { if (closing && !dismissed) onDismiss() }
    }

    val dismissAnimated: () -> Unit = {
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

    BackHandler { if (!closing) dismissAnimated() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(BottomSheetDefaults.ScrimColor.copy(alpha = PANEL_SCRIM_ALPHA))
                .clickable(
                    enabled = !closing,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissAnimated() },
                ),
        )
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val p = progress.value
                    val scale = PANEL_ENTER_SCALE + (1f - PANEL_ENTER_SCALE) * p
                    scaleX = scale
                    scaleY = scale
                    alpha = p
                }
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            CompositionLocalProvider(
                LocalPlayerSheetDismiss provides dismissAnimated,
                LocalPlayerSheetDismissThen provides dismissThen,
                content = content,
            )
        }
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

private val FocusStroke = 2.dp

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

@Composable
internal fun SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentPadding: Dp = 0.dp,
    @DrawableRes iconRes: Int? = null,
    onPressChange: ((Boolean) -> Unit)? = null,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceBright
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val rowModifier = modifier
        .background(container, ItemShape)
        .pointerInput(onClick, onPressChange) {
            detectTapGestures(
                onTap = { onClick() },
                onPress = press@{
                    val change = onPressChange ?: return@press
                    change(true)
                    tryAwaitRelease()
                    change(false)
                },
            )
        }
        .padding(horizontal = contentPadding)
        .height(playerDim(R.dimen.vs_40))
    Box(modifier = rowModifier, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(playerDim(R.dimen.vs_24)),
                )
                Spacer(Modifier.width(playerDim(R.dimen.vs_8)))
            }
            Text(
                text = text,
                color = contentColor,
                fontSize = playerTextSize(R.dimen.ts_20),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val ACTION_FLASH_MS = 100L

@Composable
internal fun SheetTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    padding: Dp? = null,
    @DimenRes fontSizeRes: Int = R.dimen.ts_20,
) {
    Box(
        modifier = modifier.pointerInput(onClick) {
            detectTapGestures(onTap = { onClick() })
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            fontSize = playerTextSize(fontSizeRes),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = padding ?: playerDim(R.dimen.vs_10),
                vertical = playerDim(R.dimen.vs_10),
            ),
        )
    }
}

@Composable
internal fun SheetHeaderRow(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = playerDim(R.dimen.vs_30)),
        contentAlignment = Alignment.Center,
    ) {
        SheetTitle(title)
        if (action != null) {
            Box(Modifier.align(Alignment.CenterEnd)) { action() }
        }
    }
}

@Composable
internal fun SheetActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
) {
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(flashing) {
        if (flashing) {
            delay(ACTION_FLASH_MS)
            flashing = false
        }
    }
    SheetButton(
        text = text,
        iconRes = iconRes,
        selected = flashing,
        onClick = {
            flashing = true
            onClick()
        },
        modifier = modifier,
    )
}

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
            fontWeight = FontWeight.Medium,
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
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        SheetButton("+", onClick = onPlus, modifier = Modifier.size(playerDim(R.dimen.vs_50)))
    }
}

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
                fontWeight = FontWeight.Medium,
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
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

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