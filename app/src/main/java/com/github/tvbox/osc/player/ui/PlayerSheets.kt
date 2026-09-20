package com.github.tvbox.osc.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R

/**
 * 播放器面板公共骨架:面板容器/标题/按钮/标签行/chips/步进/输入框/加载指示。
 * 具体面板见 DanmuSheets / SubtitleSheets / CastSheet / EpisodeSheet,同为播放器 Dialog 形态。
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

/** 对话框标题(弹幕设置/投屏居中,选集左对齐):`onSurface` + M3 Medium 字重 */
@Composable
internal fun SheetTitle(text: String, alignStart: Boolean = false) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = playerTextSize(R.dimen.ts_26),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = playerDim(R.dimen.vs_30)),
    )
}

/** 面板按钮:M3 选项样式 —— `surfaceBright` 底、选中 `primaryContainer`;触摸点按。*/
@Composable
internal fun SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceBright
    }
    val m = modifier
        .background(container, ItemShape)
        .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
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