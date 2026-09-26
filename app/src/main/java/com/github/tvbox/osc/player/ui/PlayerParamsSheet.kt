package com.github.tvbox.osc.player.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.ParamsChoice
import com.github.tvbox.osc.player.state.ParamsSheetState
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import kotlin.math.roundToInt

@Composable
internal fun PlayerParamsSheet(
    sheet: ParamsSheetState,
    slideFromEnd: Boolean,
    onDismiss: () -> Unit,
) {
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        slideFromEnd = slideFromEnd,
        title = stringResource(R.string.player_menu_params),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = playerDim(R.dimen.vs_30),
                    end = playerDim(R.dimen.vs_30),
                    bottom = playerDim(R.dimen.vs_30),
                ),
        ) {
            ParamsChoiceGroup(R.string.player_params_player, sheet.player)
            ParamsChoiceGroup(R.string.settings_play_decode, sheet.decode)
            ParamsSliderGroup(R.string.player_params_speed, sheet.speed)
            ParamsTimeGroup(sheet)
            ParamsChoiceGroup(R.string.live_group_scale, sheet.scale)
            sheet.onSearchDanmu?.let { onSearch ->
                // 走 dismissThen：先收起本面板再开弹幕搜索，否则两个面板会重叠
                val dismissThen = LocalSheetDismissThen.current
                SheetButton(
                    text = stringResource(R.string.player_menu_search_danmu),
                    onClick = {
                        dismissThen {
                            onSearch()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamsChoiceGroup(@StringRes labelRes: Int, choice: ParamsChoice) {
    ParamsGroupLabel(labelRes)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
        verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
    ) {
        choice.options.forEachIndexed { index, option ->
            SheetButton(
                text = option,
                selected = index == choice.selected,
                onClick = { choice.onSelect(index) },
                contentPadding = playerDim(R.dimen.vs_20),
            )
        }
    }
    Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
}

@Composable
private fun ParamsGroupLabel(@StringRes labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = playerTextSize(R.dimen.ts_20),
    )
    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
}

/** 带当前值的标签行：组名在左、当前值贴右（倍速与片头片尾两组共用，保证两组同款） */
@Composable
private fun ParamsLabelRow(@StringRes labelRes: Int, valueText: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = playerTextSize(R.dimen.ts_20),
        )
    }
    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
}

/** 档位滑块组:值 = 档位下标(档位间距在轨道上等分,倍速本身非等步长);拖动实时跟随、松手才提交 */
@Composable
private fun ParamsSliderGroup(@StringRes labelRes: Int, choice: ParamsChoice) {
    var index by remember(choice) { mutableFloatStateOf(choice.selected.toFloat()) }
    val stop = index.roundToInt().coerceIn(choice.options.indices)
    ParamsLabelRow(labelRes, choice.options[stop])
    Slider(
        value = index,
        onValueChange = { index = it },
        // 读 index 而不是上面的 stop:末次拖动与抬手可能落在同一帧(还没重组),闭包里的 stop 会差一档
        onValueChangeFinished = { choice.onSelect(index.roundToInt().coerceIn(choice.options.indices)) },
        valueRange = 0f..(choice.options.size - 1).toFloat(),
        steps = choice.options.size - 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
}

@Composable
private fun ParamsTimeGroup(sheet: ParamsSheetState) {
    val unset = stringResource(R.string.common_not_set)
    ParamsLabelRow(
        labelRes = R.string.player_params_time,
        valueText = "${stringResource(R.string.player_time_start)} ${sheet.timeStartText.ifEmpty { unset }}" +
                "  ·  " +
                "${stringResource(R.string.player_time_end)} ${sheet.timeEndText.ifEmpty { unset }}",
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
    ) {
        SheetButton(
            text = stringResource(R.string.player_params_set_start),
            onClick = sheet.onSetTimeStart,
            modifier = Modifier.weight(1f),
        )
        SheetButton(
            text = stringResource(R.string.player_params_set_end),
            onClick = sheet.onSetTimeEnd,
            modifier = Modifier.weight(1f),
        )
        SheetButton(
            text = stringResource(R.string.common_clear),
            onClick = sheet.onResetTime,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
}
