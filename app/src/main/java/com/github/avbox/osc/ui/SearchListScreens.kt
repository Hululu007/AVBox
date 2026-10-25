package com.github.avbox.osc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.ui.components.VodCard
import com.github.avbox.osc.ui.components.VodCardStyle
import com.github.avbox.osc.ui.components.clearFocusOnDrag
import com.github.avbox.osc.ui.theme.filterChipColors

@Composable
internal fun SearchListResults(
    done: List<SearchViewModel.SourceResult>,
    running: Boolean,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    listState: LazyListState,
    topPad: Dp,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val shown = if (selectedSource == null) done else done.filter { it.sourceKey == selectedSource }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .clearFocusOnDrag(focusManager),
        contentPadding = PaddingValues(top = topPad - 4.dp, bottom = 12.dp),
    ) {
        if (running || done.size > 1) {
            item(key = "search_leading") {
                Column {
                    if (running) {
                        LinearWavyProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    if (done.size > 1) {
                        LazyRow(
                            modifier = Modifier.padding(top = if (running) 0.dp else 12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "filter_all") {
                                FilterChip(
                                    selected = selectedSource == null,
                                    onClick = { onSelectSource(null) },
                                    label = { Text(stringResource(R.string.common_all)) },
                                    shape = RoundedCornerShape(20.dp),
                                    colors = MaterialTheme.colorScheme.filterChipColors(),
                                )
                            }
                            items(done, key = { "filter_${it.sourceKey}" }) { result ->
                                FilterChip(
                                    selected = selectedSource == result.sourceKey,
                                    onClick = {
                                        onSelectSource(
                                            if (selectedSource == result.sourceKey) null else result.sourceKey,
                                        )
                                    },
                                    label = { Text(result.sourceName) },
                                    shape = RoundedCornerShape(20.dp),
                                    colors = MaterialTheme.colorScheme.filterChipColors(),
                                )
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(shown, key = { _, r -> r.sourceKey }) { index, result ->
            Column(modifier = Modifier.padding(top = if (index == 0) 12.dp else 24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = result.sourceName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .clickable {
                                PartitionListActivity.startForSearch(context, result.videos, result.sourceName)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.common_all),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(result.videos) { _, video ->
                        VodCard(
                            video = video,
                            onClick = { onCardClick(video) },
                            onLongClick = { onCardLongClick(video) },
                            modifier = Modifier.width(110.dp),
                            style = VodCardStyle.Stacked,
                        )
                    }
                }
            }
        }
    }
}
