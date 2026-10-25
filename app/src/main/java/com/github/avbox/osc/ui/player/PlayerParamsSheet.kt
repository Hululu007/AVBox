package com.github.avbox.osc.ui.player

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.github.avbox.osc.R
import com.github.avbox.osc.player.state.ParamsChoice
import com.github.avbox.osc.player.state.ParamsSheetState
import com.github.avbox.osc.player.state.ParamsTab
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.ui.components.CapsuleSegmentedButton
import com.github.avbox.osc.ui.components.LocalSheetDismissThen
import com.github.avbox.osc.ui.components.SegmentOption
import com.github.avbox.osc.ui.components.SegmentStyle
import kotlin.math.roundToInt

@Composable
internal fun PlayerParamsSheet(
    sheet: ParamsSheetState,
    decodeChoice: ParamsChoice,
    tab: ParamsTab,
    onTabSelected: (ParamsTab) -> Unit,
    osdVisible: Boolean,
    onToggleOsd: () -> Unit,
    slideFromEnd: Boolean,
    onDismiss: () -> Unit,
) {
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        slideFromEnd = slideFromEnd,
        title = stringResource(R.string.player_menu_more),
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
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption(
                        label = stringResource(R.string.player_menu_params),
                        value = ParamsTab.Playback,
                        iconPainter = painterResource(R.drawable.player_ic_params_playback),
                    ),
                    SegmentOption(
                        label = stringResource(R.string.player_menu_picture),
                        value = ParamsTab.Picture,
                        iconPainter = painterResource(R.drawable.player_ic_params_picture),
                    ),
                ),
                selectedValue = tab,
                onOptionSelected = onTabSelected,
                modifier = Modifier.fillMaxWidth(),
                style = SegmentStyle.Separated,
                containerColor = MaterialTheme.colorScheme.surfaceBright,
                pressMorph = true,
            )
            Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
            when (tab) {
                ParamsTab.Playback -> PlaybackParams(sheet, decodeChoice, osdVisible, onToggleOsd, onDismiss)
                ParamsTab.Picture -> PictureParams(sheet.picture)
            }
        }
    }
}

@Composable
private fun PlaybackParams(
    sheet: ParamsSheetState,
    decodeChoice: ParamsChoice,
    osdVisible: Boolean,
    onToggleOsd: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ParamsChoiceGroup(R.string.player_params_player, sheet.player, R.drawable.player_ic_params_player)
        ParamsGroupDivider()
        ParamsChoiceGroup(R.string.settings_play_decode, decodeChoice, R.drawable.player_ic_params_decode)
        ParamsGroupDivider()
        ParamsSliderGroup(R.string.player_params_speed, sheet.speed, R.drawable.player_ic_params_speed)
        ParamsGroupDivider()
        ParamsTimeGroup(sheet)
        ParamsGroupDivider()
        ParamsChoiceGroup(R.string.live_group_scale, sheet.scale, R.drawable.player_ic_params_scale)
        ParamsGroupDivider()
        SheetButton(
            text = stringResource(R.string.player_info),
            iconRes = R.drawable.ic_settings_about,
            selected = osdVisible,
            onClick = onToggleOsd,
            modifier = Modifier.fillMaxWidth(),
        )
        sheet.onSearchDanmu?.let { onSearch ->
            Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
            val dismissThen = LocalSheetDismissThen.current
            SheetButton(
                text = stringResource(R.string.player_menu_search_danmu),
                iconRes = R.drawable.player_ic_menu_danmu,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamsChoiceGroup(
    @StringRes labelRes: Int,
    choice: ParamsChoice,
    @DrawableRes iconRes: Int? = null,
) {
    ParamsGroupHeader(labelRes, iconRes)
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
}

@Composable
internal fun ParamsGroupHeader(
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int? = null,
    valueText: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(playerDim(R.dimen.vs_24)),
            )
            Spacer(Modifier.width(playerDim(R.dimen.vs_10)))
        }
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = playerTextSize(R.dimen.ts_22),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        valueText?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = playerTextSize(R.dimen.ts_22),
                fontWeight = FontWeight.Medium,
            )
        }
    }
    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
}

@Composable
internal fun ParamsGroupDivider() {
    Spacer(Modifier.height(playerDim(R.dimen.vs_15)))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(playerDim(R.dimen.vs_15)))
}

@Composable
private fun ParamsSliderGroup(
    @StringRes labelRes: Int,
    choice: ParamsChoice,
    @DrawableRes iconRes: Int? = null,
) {
    var index by remember(choice) { mutableFloatStateOf(choice.selected.toFloat()) }
    val stop = index.roundToInt().coerceIn(choice.options.indices)
    ParamsGroupHeader(labelRes, iconRes, choice.options[stop])
    Slider(
        value = index,
        onValueChange = { index = it },
        onValueChangeFinished = { choice.onSelect(index.roundToInt().coerceIn(choice.options.indices)) },
        valueRange = 0f..(choice.options.size - 1).toFloat(),
        steps = choice.options.size - 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ParamsTimeGroup(sheet: ParamsSheetState) {
    ParamsGroupHeader(R.string.player_params_time, R.drawable.player_ic_params_time)
    val startActive = sheet.timeStartText.isNotEmpty()
    val endActive = sheet.timeEndText.isNotEmpty()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
    ) {
        SheetButton(
            text = timeMarkText(sheet.timeStartText, R.string.player_time_start, R.string.player_params_set_start),
            iconRes = R.drawable.player_ic_params_time_start,
            selected = startActive,
            onClick = sheet.onSetTimeStart,
            modifier = Modifier.weight(1f),
        )
        SheetButton(
            text = timeMarkText(sheet.timeEndText, R.string.player_time_end, R.string.player_params_set_end),
            iconRes = R.drawable.player_ic_params_time_end,
            selected = endActive,
            onClick = sheet.onSetTimeEnd,
            modifier = Modifier.weight(1f),
        )
        SheetActionButton(
            text = stringResource(R.string.common_clear),
            iconRes = R.drawable.ic_delete,
            onClick = sheet.onResetTime,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun timeMarkText(time: String, @StringRes labelRes: Int, @StringRes setRes: Int): String =
    if (time.isEmpty()) stringResource(setRes) else "${stringResource(labelRes)} $time"
