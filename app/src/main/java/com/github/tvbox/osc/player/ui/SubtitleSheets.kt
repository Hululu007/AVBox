package com.github.tvbox.osc.player.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Subtitle
import com.github.tvbox.osc.player.state.SubtitleSearchSheetState
import com.github.tvbox.osc.player.state.SubtitleSheetState
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.viewmodel.SubtitleViewModel

/** 字幕面板:本地/在线字幕选择与搜索(入口 SubtitleSheet / SubtitleSearchSheet) */

// ---------------------------------------------------------------------------
// 字幕设置
// ---------------------------------------------------------------------------

@Composable
fun SubtitleSheet(sheet: SubtitleSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exo = sheet.exoInternal
    var sizeText by remember {
        mutableStateOf(if (exo) SubtitleHelper.getExoSubtitleScale().toString() + "%"
        else SubtitleHelper.getTextSize(context.findActivityOrNull()).toString())
    }
    var posText by remember {
        mutableStateOf(if (exo) {
            val p = SubtitleHelper.getExoSubtitlePosition()
            if (p == 0.0f) "0" else "$p%"
        } else "")
    }
    var delayText by remember {
        val d = SubtitleHelper.getTimeDelay()
        mutableStateOf(if (d == 0) "0" else (d / 1000.0).toString())
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SheetPanel(width = playerDim(R.dimen.vs_640)) {
                Column(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(playerDim(R.dimen.vs_480))
                        .padding(vertical = playerDim(R.dimen.vs_30)),
                ) {
                    if (sheet.hasInternal) {
                        SheetButton(stringResource(R.string.subtitle_builtin), onClick = {
                            onDismiss()
                            sheet.onSelectInternal()
                        }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    }
                    SheetButton(stringResource(R.string.subtitle_local), onClick = {
                        onDismiss()
                        sheet.onSelectLocal()
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    SheetButton(stringResource(R.string.subtitle_online), onClick = {
                        onDismiss()
                        sheet.onSelectRemote()
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    // 字号行:exo 模式百分比 50~200 步 5;外挂字号 12~60 步 2
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton(stringResource(R.string.subtitle_size_minus), onClick = {
                            if (exo) {
                                val scale = (SubtitleHelper.getExoSubtitleScale() - 5).coerceAtLeast(50)
                                sizeText = "$scale%"
                                SubtitleHelper.setExoSubtitleScale(scale)
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
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                        )
                        SheetButton(stringResource(R.string.subtitle_size_plus), onClick = {
                            if (exo) {
                                val scale = (SubtitleHelper.getExoSubtitleScale() + 5).coerceAtMost(200)
                                sizeText = "$scale%"
                                SubtitleHelper.setExoSubtitleScale(scale)
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
                    // 样式行:exo = 上移/位置/下移;外挂 = 样式一/样式二
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
                                    val position = (SubtitleHelper.getExoSubtitlePosition() + 0.5f).coerceAtMost(80.0f)
                                    SubtitleHelper.setExoSubtitlePosition(position)
                                    posText = if (position == 0.0f) "0" else "$position%"
                                } else {
                                    // 样式一 = 外挂字幕白色
                                    sheet.onSelectStyle(0)
                                    onDismiss()
                                    Toast.makeText(context, context.getString(R.string.toast_subtitle_style_ok), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.width(playerDim(R.dimen.vs_140)),
                        )
                        Text(
                            text = posText,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = playerTextSize(R.dimen.ts_26),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                        )
                        SheetButton(
                            stringResource(if (exo) R.string.subtitle_move_down else R.string.subtitle_style_two),
                            onClick = {
                                if (exo) {
                                    val position = (SubtitleHelper.getExoSubtitlePosition() - 0.5f).coerceAtLeast(-80.0f)
                                    SubtitleHelper.setExoSubtitlePosition(position)
                                    posText = if (position == 0.0f) "0" else "$position%"
                                } else {
                                    // 样式二 = 外挂字幕粉色 #FFB6C1
                                    sheet.onSelectStyle(1)
                                    onDismiss()
                                    Toast.makeText(context, context.getString(R.string.toast_subtitle_style_ok), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.width(playerDim(R.dimen.vs_140)),
                        )
                    }
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    Text(
                        text = stringResource(if (exo) R.string.subtitle_delay_exo_hint else R.string.subtitle_delay_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = playerTextSize(R.dimen.ts_20),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    // 延时行:±0.5s 步进,增量回调按 ±0.5*1000ms(旧 mseconds 语义)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton(stringResource(R.string.subtitle_advance), onClick = {
                            var time = (delayText.toDoubleOrNull() ?: 0.0) - 0.5
                            SubtitleHelper.setTimeDelay((time * 1000).toInt())
                            delayText = if (time == 0.0) "0" else time.toString()
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                        Text(
                            text = delayText,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = playerTextSize(R.dimen.ts_26),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                        )
                        SheetButton(stringResource(R.string.subtitle_delay), onClick = {
                            var time = (delayText.toDoubleOrNull() ?: 0.0) + 0.5
                            SubtitleHelper.setTimeDelay((time * 1000).toInt())
                            delayText = if (time == 0.0) "0" else time.toString()
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 字幕搜索(数据链沿用 SubtitleViewModel:assrt 搜索 / zip 展开 / 分页)
// ---------------------------------------------------------------------------

@Composable
fun SubtitleSearchSheet(sheet: SubtitleSearchSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val viewModel: SubtitleViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var word by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<Subtitle>()) }
    var loading by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("search") } // search | zipfiles
    var page by remember { mutableIntStateOf(1) }
    var canLoadMore by remember { mutableStateOf(false) }
    val zipCache = remember { mutableListOf<Subtitle>() }
    val maxPage = 5
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val search: (String) -> Unit = { raw ->
        val w = raw.trim()
        if (w.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_input_empty), Toast.LENGTH_SHORT).show()
        } else {
            mode = "search"
            items = emptyList()
            loading = true
            word = w
            page = 1
            viewModel.searchResult(w, 1)
        }
    }

    // 观察 SubtitleViewModel.searchResult
    DisposableEffect(viewModel) {
        val observer = androidx.lifecycle.Observer<com.github.tvbox.osc.bean.SubtitleData> { data ->
            mainHandler.post {
                loading = false
                val list = data.subtitleList
                if (list == null) {
                    Toast.makeText(context, context.getString(R.string.toast_subtitle_not_found), Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (list.isNotEmpty()) {
                    if (data.isZip) {
                        if (data.isNew) {
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
        viewModel.searchResult.observe(lifecycleOwner, observer)
        onDispose { viewModel.searchResult.removeObserver(observer) }
    }

    // 进入即清洗片名并自动搜索(旧 setSearchWord 的清洗链)
    LaunchedEffect(Unit) {
        var wd = sheet.searchWord
        wd = wd.replace(Regex("(?:（|\\(|\\[|【|\\.mp4|\\.mkv|\\.avi|\\.MP4|\\.MKV|\\.AVI)"), "")
        wd = wd.replace(Regex("(?:：|\\:|）|\\)|\\]|】|\\.)"), " ")
        wd = wd.take(36).trim()
        word = wd
        if (wd.isNotEmpty()) search(wd)
    }

    // zip 展开态按返回回搜索列表(旧 onBackPressed)
    BackHandler(enabled = mode == "zipfiles") {
        mode = "search"
        items = zipCache.toList()
        canLoadMore = page < maxPage
        loading = false
    }

    // 触底加载更多(zip 搜索态)
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                                            loading = true
                                            viewModel.getSearchResultSubtitleUrls(item)
                                        } else {
                                            // 旧行为:发起直链解析后立即收起,回调在容器侧落地
                                            viewModel.getSubtitleUrl(item) { subtitle ->
                                                mainHandler.post {
                                                    if (subtitle.url != null) sheet.onLoadSubtitle(subtitle)
                                                }
                                            }
                                            onDismiss()
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
}
