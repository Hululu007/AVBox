package com.github.avbox.osc.ui.player

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.SourceSubtitle
import com.github.avbox.osc.player.state.SubtitleSearchSheetState
import com.github.avbox.osc.player.state.SubtitleSheetState
import com.github.avbox.osc.util.SubtitleHelper
import com.github.avbox.osc.sourcedata.SubtitleViewModel

@Composable
fun SubtitleSheet(sheet: SubtitleSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exo = sheet.exoInternal
    var sizeText by remember {
        mutableStateOf(if (exo) SubtitleHelper.exoSubtitleScale.toString() + "%"
        else SubtitleHelper.getTextSize(context.findActivityOrNull()).toString())
    }
    var posText by remember {
        mutableStateOf(if (exo) {
            val p = SubtitleHelper.exoSubtitlePosition
            if (p == 0.0f) "0" else "$p%"
        } else "")
    }
    var delayText by remember {
        val d = SubtitleHelper.timeDelay
        mutableStateOf(if (d == 0) "0" else (d / 1000.0).toString())
    }

    PlayerDialog(onDismiss = onDismiss) {
        val dismiss = LocalPlayerSheetDismiss.current
        val dismissThen = LocalPlayerSheetDismissThen.current
        SheetPanel(width = playerDim(R.dimen.vs_640)) {
                Column(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(playerDim(R.dimen.vs_480))
                        .padding(vertical = playerDim(R.dimen.vs_30)),
                ) {
                    if (sheet.hasInternal) {
                        SheetButton(stringResource(R.string.subtitle_builtin), onClick = {
                            dismissThen { sheet.onSelectInternal() }
                        }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    }
                    SheetButton(stringResource(R.string.subtitle_local), onClick = {
                        dismissThen { sheet.onSelectLocal() }
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    SheetButton(stringResource(R.string.subtitle_online), onClick = {
                        dismissThen { sheet.onSelectRemote() }
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton(stringResource(R.string.subtitle_size_minus), onClick = {
                            if (exo) {
                                val scale = (SubtitleHelper.exoSubtitleScale - 5).coerceAtLeast(50)
                                sizeText = "$scale%"
                                SubtitleHelper.exoSubtitleScale = scale
                            } else {
                                val cur = (sizeText.toIntOrNull() ?: 16) - 2
                                val next = cur.coerceAtLeast(12)
                                sizeText = next.toString()
                                SubtitleHelper.setTextSize(next)
                                sheet.onTextSizeChange()
                            }
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                        Text(
                            text = sizeText,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = playerTextSize(R.dimen.ts_26),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                        )
                        SheetButton(stringResource(R.string.subtitle_size_plus), onClick = {
                            if (exo) {
                                val scale = (SubtitleHelper.exoSubtitleScale + 5).coerceAtMost(200)
                                sizeText = "$scale%"
                                SubtitleHelper.exoSubtitleScale = scale
                            } else {
                                val cur = (sizeText.toIntOrNull() ?: 16) + 2
                                val next = cur.coerceAtMost(60)
                                sizeText = next.toString()
                                SubtitleHelper.setTextSize(next)
                                sheet.onTextSizeChange()
                            }
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                    }
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton(
                            stringResource(if (exo) R.string.subtitle_move_up else R.string.subtitle_style_one),
                            onClick = {
                                if (exo) {
                                    val position = (SubtitleHelper.exoSubtitlePosition + 0.5f).coerceAtMost(80.0f)
                                    SubtitleHelper.exoSubtitlePosition = position
                                    posText = if (position == 0.0f) "0" else "$position%"
                                } else {
                                    sheet.onSelectStyle(0)
                                    dismiss()
                                    Toast.makeText(context, context.getString(R.string.toast_subtitle_style_ok), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.width(playerDim(R.dimen.vs_140)),
                        )
                        SheetTextAction(
                            text = stringResource(R.string.theme_reset),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                            onClick = {
                                sheet.onReset()
                                sizeText = if (exo) SubtitleHelper.exoSubtitleScale.toString() + "%"
                                else SubtitleHelper.getTextSize(context.findActivityOrNull()).toString()
                                val position = SubtitleHelper.exoSubtitlePosition
                                posText = if (position == 0.0f) "0" else "$position%"
                                delayText = SubtitleHelper.timeDelay.let { if (it == 0) "0" else (it / 1000.0).toString() }
                            },
                        )
                        SheetButton(
                            stringResource(if (exo) R.string.subtitle_move_down else R.string.subtitle_style_two),
                            onClick = {
                                if (exo) {
                                    val position = (SubtitleHelper.exoSubtitlePosition - 0.5f).coerceAtLeast(-80.0f)
                                    SubtitleHelper.exoSubtitlePosition = position
                                    posText = if (position == 0.0f) "0" else "$position%"
                                } else {
                                    sheet.onSelectStyle(1)
                                    dismiss()
                                    Toast.makeText(context, context.getString(R.string.toast_subtitle_style_ok), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.width(playerDim(R.dimen.vs_140)),
                        )
                    }
                    if (exo) {
                        Text(
                            text = stringResource(R.string.subtitle_position_value, posText),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = playerDim(R.dimen.vs_5)),
                        )
                    }
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    Text(
                        text = stringResource(if (exo) R.string.subtitle_delay_exo_hint else R.string.subtitle_delay_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = playerTextSize(R.dimen.ts_20),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton(stringResource(R.string.subtitle_advance), onClick = {
                            var time = (delayText.toDoubleOrNull() ?: 0.0) - 0.5
                            SubtitleHelper.timeDelay = (time * 1000).toInt()
                            delayText = if (time == 0.0) "0" else time.toString()
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                        Text(
                            text = delayText,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = playerTextSize(R.dimen.ts_26),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                        )
                        SheetButton(stringResource(R.string.subtitle_delay), onClick = {
                            var time = (delayText.toDoubleOrNull() ?: 0.0) + 0.5
                            SubtitleHelper.timeDelay = (time * 1000).toInt()
                            delayText = if (time == 0.0) "0" else time.toString()
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                    }
                }
            }
    }
}

@Composable
fun SubtitleSearchSheet(sheet: SubtitleSearchSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val viewModel: SubtitleViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var word by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<SourceSubtitle>()) }
    var loading by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("search") }
    var page by remember { mutableIntStateOf(1) }
    var canLoadMore by remember { mutableStateOf(false) }
    val zipCache = remember { mutableListOf<SourceSubtitle>() }
    var release by remember { mutableStateOf<SourceSubtitle?>(null) }
    val maxPage = 5
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val search: (String) -> Unit = { raw ->
        val w = raw.trim()
        if (w.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_input_empty), Toast.LENGTH_SHORT).show()
        } else {
            mode = "search"
            release = null
            items = emptyList()
            loading = true
            word = w
            page = 1
            viewModel.searchResult(w, 1)
        }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.searchResult.flow.collect { data ->
                mainHandler.post {
                    loading = false
                    val result = data
                    val list = result?.subtitleList
                    if (result == null || list == null) {
                        Toast.makeText(context, context.getString(R.string.toast_subtitle_not_found), Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    if (list.isNotEmpty()) {
                        if (result.isZip == true) {
                            if (result.isNew == true) {
                                items = list
                                zipCache.clear()
                                zipCache.addAll(list)
                            } else {
                                items = items + list
                                zipCache.addAll(list)
                            }
                            page++
                            if (page > maxPage) {
                                canLoadMore = false
                            } else {
                                canLoadMore = true
                            }
                        } else {
                            items = list
                            canLoadMore = false
                        }
                    } else {
                        canLoadMore = false
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        var wd = sheet.searchWord
        wd = wd.replace(Regex("(?:（|\\(|\\[|【|\\.mp4|\\.mkv|\\.avi|\\.MP4|\\.MKV|\\.AVI)"), "")
        wd = wd.replace(Regex("(?:：|\\:|）|\\)|\\]|】|\\.)"), " ")
        wd = wd.take(36).trim()
        word = wd
        if (wd.isNotEmpty()) search(wd)
    }

    BackHandler(enabled = mode == "zipfiles") {
        mode = "search"
        release = null
        items = zipCache.toList()
        canLoadMore = page < maxPage
        loading = false
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, canLoadMore, mode, items) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (canLoadMore && mode == "search" && items.isNotEmpty() &&
                    items.firstOrNull()?.isZip == true && last != null && last >= items.size - 3
                ) {
                    canLoadMore = false
                    viewModel.searchResult(word, page)
                }
            }
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
                        hint = stringResource(R.string.subtitle_search_hint),
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
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5)),
                        ) {
                            itemsIndexed(items) { _, item ->
                                SheetButton(
                                    text = "${item.name}(${if (item.isZip) "压缩包" else "文件"})",
                                    onClick = {
                                        if (item.isZip) {
                                            mode = "zipfiles"
                                            release = item
                                            loading = true
                                            viewModel.searchResultSubtitleUrls(item)
                                        } else {
                                            val releaseUrl = release?.url.orEmpty()
                                            viewModel.getSubtitleUrl(item) { subtitle ->
                                                mainHandler.post {
                                                    if (subtitle.url != null) sheet.onLoadSubtitle(subtitle, releaseUrl)
                                                }
                                            }
                                            dismiss()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
            }
    }
}
