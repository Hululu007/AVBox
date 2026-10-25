package com.github.avbox.osc.ui.player

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.api.DanmakuApi
import com.github.avbox.osc.bean.DanmuSearchResult
import com.github.avbox.osc.player.state.DanmuSearchSheetState
import com.github.avbox.osc.player.state.DanmuSettingSheetState
import com.github.avbox.osc.util.DanmuSettingsState
import kotlinx.coroutines.launch

private val DANMU_SPEEDS = listOf(2.4f, 1.8f, 1.5f, 1.0f)

@Composable
fun DanmuSettingSheet(sheet: DanmuSettingSheetState, onDismiss: () -> Unit) {
    PlayerDialog(onDismiss = onDismiss) {
        val dismissThen = LocalPlayerSheetDismissThen.current
        SheetPanel(width = playerDim(R.dimen.vs_520)) {
            var colorIdx by remember { mutableIntStateOf(if (DanmuSettingsState.config.randomColor) 1 else 0) }
            var speedIdx by remember {
                mutableIntStateOf(DANMU_SPEEDS.indexOf(DanmuSettingsState.config.speed).coerceAtLeast(0))
            }
            var size by remember { mutableIntStateOf(Math.round(DanmuSettingsState.config.sizeScale * 10)) }
            var line by remember { mutableIntStateOf(DanmuSettingsState.config.maxLine) }
            var alpha by remember { mutableIntStateOf(Math.round(DanmuSettingsState.config.alpha * 100)) }
            Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
            SheetHeaderRow(title = stringResource(R.string.danmu_settings)) {
                SheetTextAction(
                    text = stringResource(R.string.theme_reset),
                    padding = 0.dp,
                    fontSizeRes = R.dimen.ts_22,
                    onClick = {
                        sheet.onReset()
                        colorIdx = if (DanmuSettingsState.config.randomColor) 1 else 0
                        speedIdx = DANMU_SPEEDS.indexOf(DanmuSettingsState.config.speed).coerceAtLeast(0)
                        size = Math.round(DanmuSettingsState.config.sizeScale * 10)
                        line = DanmuSettingsState.config.maxLine
                        alpha = Math.round(DanmuSettingsState.config.alpha * 100)
                    },
                )
            }
            Spacer(Modifier.height(playerDim(R.dimen.vs_12)))
            val postSettings: (Boolean) -> Unit = { forColor ->
                sheet.onSettingsApplied(forColor)
            }

            SheetLabelRow(stringResource(R.string.danmu_online)) {
                SheetButton(
                    text = stringResource(R.string.common_search),
                    onClick = {
                        dismissThen { sheet.onOpenSearch() }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
                SheetLabelRow(stringResource(R.string.danmu_color)) {
                    SheetChipRow(
                        listOf(
                            stringResource(R.string.common_default),
                            stringResource(R.string.danmu_color_random),
                        ),
                        colorIdx,
                        onSelect = { idx ->
                        colorIdx = idx
                        DanmuSettingsState.randomColor = idx == 1
                        postSettings(true)
                    })
                }
                SheetLabelRow(stringResource(R.string.danmu_speed)) {
                    SheetChipRow(
                        listOf(
                            stringResource(R.string.danmu_speed_very_slow),
                            stringResource(R.string.danmu_speed_slow),
                            stringResource(R.string.danmu_speed_normal),
                            stringResource(R.string.danmu_speed_fast),
                        ),
                        speedIdx,
                        onSelect = { idx ->
                        speedIdx = idx
                        DanmuSettingsState.speed = DANMU_SPEEDS[idx]
                        postSettings(false)
                    })
                }
                SheetLabelRow(stringResource(R.string.danmu_size)) {
                    SheetStepper(
                        stringResource(R.string.danmu_size_value, size),
                        onMinus = {
                            if (size > 6) {
                                size--
                                DanmuSettingsState.sizeScale = size / 10f
                                postSettings(false)
                            }
                        },
                        onPlus = {
                            if (size < 20) {
                                size++
                                DanmuSettingsState.sizeScale = size / 10f
                                postSettings(false)
                            }
                        },
                    )
                }
                SheetLabelRow(stringResource(R.string.danmu_lines)) {
                    SheetStepper(
                        stringResource(R.string.danmu_lines_value, line),
                        onMinus = {
                            if (line > 1) {
                                line--
                                DanmuSettingsState.maxLine = line
                                postSettings(false)
                            }
                        },
                        onPlus = {
                            if (line < 15) {
                                line++
                                DanmuSettingsState.maxLine = line
                                postSettings(false)
                            }
                        },
                    )
                }
                SheetLabelRow(stringResource(R.string.danmu_alpha)) {
                    SheetStepper(
                        "$alpha%",
                        onMinus = {
                            if (alpha > 10) {
                                alpha -= 10
                                DanmuSettingsState.alpha = alpha / 100f
                                postSettings(false)
                            }
                        },
                        onPlus = {
                            if (alpha < 100) {
                                alpha += 10
                                DanmuSettingsState.alpha = alpha / 100f
                                postSettings(false)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
        }
    }
}

@Composable
fun DanmuSearchSheet(sheet: DanmuSearchSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var word by remember { mutableStateOf(sheet.searchWord) }
    var results by remember { mutableStateOf(emptyList<DanmuSearchResult>()) }
    var loading by remember { mutableStateOf(false) }

    val search: (String) -> Unit = { raw ->
        val w = raw.trim()
        if (w.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_input_empty), Toast.LENGTH_SHORT).show()
        } else {
            loading = true
            results = emptyList()
            scope.launch {
                val list = try {
                    DanmakuApi.searchList(w, sheet.episode)
                } catch (e: DanmakuApi.DanmakuException) {
                    loading = false
                    results = emptyList()
                    Toast.makeText(context, e.message ?: "", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                loading = false
                results = list
                if (list.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.toast_danmu_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (sheet.searchWord.isNotBlank()) search(sheet.searchWord)
    }
    DisposableEffect(Unit) {
        onDispose { DanmakuApi.cancel() }
    }

    PlayerDialog(onDismiss = onDismiss) {
        val dismiss = LocalPlayerSheetDismiss.current
        SheetPanel(
                width = playerDim(R.dimen.vs_960),
                modifier = Modifier.height(playerDim(R.dimen.vs_480)),
            ) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
                Row(
                    Modifier
                        .padding(horizontal = playerDim(R.dimen.vs_30))
                        .height(playerDim(R.dimen.vs_50)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetInput(
                        value = word,
                        onValueChange = { word = it },
                        hint = stringResource(R.string.danmu_search_hint),
                        modifier = Modifier.weight(1f),
                        onSubmit = { search(word) },
                    )
                    Spacer(Modifier.width(playerDim(R.dimen.vs_5)))
                    SheetButton(text = stringResource(R.string.common_search), onClick = { search(word) })
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = playerDim(R.dimen.vs_30)),
                ) {
                    if (loading) {
                        SheetLoading(size = playerDim(R.dimen.vs_50))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5))) {
                            itemsIndexed(results) { _, item ->
                                SheetButton(text = item.name, onClick = {
                                    loading = true
                                    scope.launch {
                                        val danmu = try {
                                            DanmakuApi.loadSearchResult(item)
                                        } catch (e: DanmakuApi.DanmakuException) {
                                            loading = false
                                            Toast.makeText(context, e.message ?: "", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        dismiss()
                                        sheet.onLoad(danmu)
                                    }
                                })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
            }
    }
}
