package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.net.SearchSettings
import com.github.tvbox.osc.util.HomeSettings

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

private val ExactCardShape = RoundedCornerShape(28.dp)

@Composable
fun SearchSettingsSheet(
    sources: List<SourceBean>,
    selected: Set<String>,
    onApplySelection: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val allKeys = remember(sources) { sources.map { it.key.orEmpty() }.toSet() }
    val homeLayout by HomeSettings.layoutFlow.collectAsStateWithLifecycle()
    var exactMatch by remember { mutableStateOf(SearchSettings.isExactMatchEnabled()) }

    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp

    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.search_settings),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.heightIn(max = maxSheetHeight),
        headerContent = {
            Text(
                text = stringResource(R.string.search_home_poster),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption(
                        label = stringResource(R.string.search_layout_horizontal),
                        value = HomeSettings.HomeLayout.Horizontal,
                        iconPainter = painterResource(R.drawable.ic_layout_horizontal),
                    ),
                    SegmentOption(
                        label = stringResource(R.string.search_layout_vertical),
                        value = HomeSettings.HomeLayout.Vertical,
                        iconPainter = painterResource(R.drawable.ic_layout_vertical),
                    ),
                ),
                selectedValue = homeLayout,
                onOptionSelected = { HomeSettings.setLayout(it) },
                style = SegmentStyle.Separated,
                containerColor = MaterialTheme.colorScheme.surfaceBright,
                pressMorph = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SettingsGroup(title = null) {
                SettingsCard(
                    position = SettingsCardPosition.SINGLE,
                    color = MaterialTheme.colorScheme.surfaceBright,
                    shape = ExactCardShape,
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.search_exact),
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
                        text = stringResource(R.string.search_sites),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (selected.isEmpty()) {
                            stringResource(R.string.search_sites_none_hint)
                        } else {
                            stringResource(R.string.search_sites_selected_hint, selected.size, allKeys.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onApplySelection(allKeys) }) { Text(stringResource(R.string.common_select_all)) }
                TextButton(onClick = { onApplySelection(allKeys - selected) }) { Text(stringResource(R.string.common_invert_selection)) }
            }
            if (sources.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_sites_empty),
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
                            val leftKey = left.key.orEmpty()
                            SourceCard(
                                name = left.name?.takeIf { it.isNotEmpty() } ?: leftKey,
                                selected = leftKey in selected,
                                dotOnLeft = true,
                                shape = SourceCardShapeLeft,
                                onClick = {
                                    onApplySelection(
                                        if (leftKey in selected) selected - leftKey else selected + leftKey,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                            val right = pair.getOrNull(1)
                            if (right != null) {
                                val rightKey = right.key.orEmpty()
                                SourceCard(
                                    name = right.name?.takeIf { it.isNotEmpty() } ?: rightKey,
                                    selected = rightKey in selected,
                                    dotOnLeft = false,
                                    shape = SourceCardShapeRight,
                                    onClick = {
                                        onApplySelection(
                                            if (rightKey in selected) selected - rightKey else selected + rightKey,
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
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
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
