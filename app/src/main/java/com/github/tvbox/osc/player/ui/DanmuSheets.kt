package com.github.tvbox.osc.player.ui

import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.DanmakuApi
import com.github.tvbox.osc.bean.DanmuSearchResult
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.state.DanmuSearchSheetState
import com.github.tvbox.osc.player.state.DanmuSettingSheetState
import com.github.tvbox.osc.util.DanmuHelper
import org.greenrobot.eventbus.EventBus

/** 弹幕面板:设置 + 搜索(入口 DanmuSettingSheet / DanmuSearchSheet) */

// ---------------------------------------------------------------------------
// 弹幕设置
// ---------------------------------------------------------------------------

private val DANMU_SPEEDS = listOf(2.4f, 1.8f, 1.5f, 1.0f)

@Composable
fun DanmuSettingSheet(sheet: DanmuSettingSheetState, onDismiss: () -> Unit) {
    PlayerDialog(onDismiss = onDismiss) {
        val dismissThen = LocalPlayerSheetDismissThen.current
        SheetPanel(width = playerDim(R.dimen.vs_520)) {
            Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
            SheetTitle(stringResource(R.string.danmu_settings))
            Spacer(Modifier.height(playerDim(R.dimen.vs_12)))
            // TYPE_SET_DANMU_SETTINGS 第二参数:仅颜色行传 true
            val postSettings: (Boolean) -> Unit = { forColor ->
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, forColor))
            }
            var colorIdx by remember { mutableIntStateOf(if (DanmuHelper.useRandomColor()) 1 else 0) }
            var speedIdx by remember {
                mutableIntStateOf(DANMU_SPEEDS.indexOf(DanmuHelper.getSpeed()).coerceAtLeast(0))
            }
            var size by remember { mutableIntStateOf(Math.round(DanmuHelper.getSizeScale() * 10)) }
            var line by remember { mutableIntStateOf(DanmuHelper.getMaxLine()) }
            var alpha by remember { mutableIntStateOf(Math.round(DanmuHelper.getAlpha() * 100)) }

            SheetLabelRow(stringResource(R.string.danmu_online)) {
                SheetButton(
                    text = stringResource(R.string.common_search),
                    onClick = {
                        // 先播退场再换面板,两个面板窗口不重叠
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
                        DanmuHelper.setRandomColor(idx == 1)
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
                        DanmuHelper.setSpeed(DANMU_SPEEDS[idx])
                        postSettings(false)
                    })
                }
                SheetLabelRow(stringResource(R.string.danmu_size)) {
                    SheetStepper(
                        stringResource(R.string.danmu_size_value, size),
                        onMinus = {
                            if (size > 6) {
                                size--
                                DanmuHelper.setSizeScale(size / 10f)
                                postSettings(false)
                            }
                        },
                        onPlus = {
                            if (size < 20) {
                                size++
                                DanmuHelper.setSizeScale(size / 10f)
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
                                DanmuHelper.setMaxLine(line)
                                postSettings(false)
                            }
                        },
                        onPlus = {
                            if (line < 15) {
                                line++
                                DanmuHelper.setMaxLine(line)
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
                                DanmuHelper.setAlpha(alpha / 100f)
                                postSettings(false)
                            }
                        },
                        onPlus = {
                            if (alpha < 100) {
                                alpha += 10
                                DanmuHelper.setAlpha(alpha / 100f)
                                postSettings(false)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
        }
    }
}

// ---------------------------------------------------------------------------
// 弹幕搜索
// ---------------------------------------------------------------------------

@Composable
fun DanmuSearchSheet(sheet: DanmuSearchSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
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
            DanmakuApi.searchList(w, sheet.episode, object : DanmakuApi.SearchListCallback {
                override fun onSuccess(list: List<DanmuSearchResult>?) {
                    mainHandler.post {
                        loading = false
                        results = list ?: emptyList()
                        if (list.isNullOrEmpty()) {
                            Toast.makeText(context, context.getString(R.string.toast_danmu_not_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(message: String?) {
                    mainHandler.post {
                        loading = false
                        results = emptyList()
                        Toast.makeText(context, message ?: "", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    // 进入即按初始词搜索(旧 setSearchWord);离开时取消在途请求(旧 onBackPressed)
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
                                    DanmakuApi.loadSearchResult(item, object : DanmakuApi.SearchResultCallback {
                                        override fun onSuccess(danmu: String?) {
                                            mainHandler.post {
                                                dismiss()
                                                if (danmu != null) sheet.onLoad(danmu)
                                            }
                                        }

                                        override fun onError(message: String?) {
                                            mainHandler.post {
                                                loading = false
                                                Toast.makeText(context, message ?: "", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    })
                                })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
            }
    }
}
