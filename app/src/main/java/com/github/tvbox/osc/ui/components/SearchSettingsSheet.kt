package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.ui.activity.SearchViewModel
import com.github.tvbox.osc.util.SearchSettings

private val SourceCardShapeLeft = RoundedCornerShape(
    topStart = 28.dp,
    bottomStart = 28.dp,
    topEnd = 4.dp,
    bottomEnd = 4.dp,
)

private val SourceCardShapeRight = RoundedCornerShape(
    topStart = 4.dp,
    bottomStart = 4.dp,
    topEnd = 28.dp,
    bottomEnd = 28.dp,
)

@Composable
fun SearchSettingsSheet(onDismiss: () -> Unit) {
    val sources = remember { ApiConfig.get().getSourceBeanList().filter(SourceBean::isSearchable) }
    val allKeys = remember(sources) { sources.map { it.key }.toSet() }
    var exactMatch by remember { mutableStateOf(SearchSettings.isExactMatchEnabled()) }
    var selected by remember {
        val selection = SearchSettings.currentSelection()
        mutableStateOf(
            when {
                selection == null -> allKeys
                selection.isEmpty() -> emptySet()
                else -> selection.filter { it in allKeys }.toSet().ifEmpty { allKeys }
            },
        )
    }

    fun applySelection(next: Set<String>) {
        selected = next
        SearchSettings.putSourcesForSearch(next)
        SearchViewModel.loadCheckedSources()
    }

    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = "搜索设置",
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SettingsGroup(title = null) {
                SettingsCard(
                    position = SettingsCardPosition.SINGLE,
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    SettingsSwitchRow(
                        title = "精准搜索",
                        checked = exactMatch,
                        onCheckedChange = {
                            exactMatch = it
                            SearchSettings.setExactMatchEnabled(it)
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "搜索站点",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (selected.isEmpty()) {
                            "未选择站点，将搜不到结果"
                        } else {
                            "已选 ${selected.size}/${allKeys.size}，全选即搜索全部站点"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { applySelection(allKeys) }) { Text("全选") }
                TextButton(onClick = { applySelection(allKeys - selected) }) { Text("反选") }
            }
            if (sources.isEmpty()) {
                Text(
                    text = "暂无可用站点",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    sources.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val left = pair[0]
                            SourceCard(
                                name = left.name?.takeIf { it.isNotEmpty() } ?: left.key,
                                selected = left.key in selected,
                                dotOnLeft = true,
                                shape = SourceCardShapeLeft,
                                onClick = {
                                    applySelection(
                                        if (left.key in selected) selected - left.key else selected + left.key,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                            val right = pair.getOrNull(1)
                            if (right != null) {
                                SourceCard(
                                    name = right.name?.takeIf { it.isNotEmpty() } ?: right.key,
                                    selected = right.key in selected,
                                    dotOnLeft = false,
                                    shape = SourceCardShapeRight,
                                    onClick = {
                                        applySelection(
                                            if (right.key in selected) selected - right.key else selected + right.key,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    name: String,
    selected: Boolean,
    dotOnLeft: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(if (dotOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 12.dp)
                        .size(14.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
}
