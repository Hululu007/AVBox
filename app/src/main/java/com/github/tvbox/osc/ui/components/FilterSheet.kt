package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.MovieSort

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    sort: MovieSort.SortData,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    var selection by remember(sort.id) { mutableStateOf(sort.filterSelect.toMap()) }
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.filter_title, sort.name ?: ""),
    ) {
        val dismissAnimated = LocalSheetDismiss.current
        var accepted by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            sort.filters.forEach { filter ->
                Text(
                    text = filter.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    filter.values.forEach { (valueKey, valueName) ->
                        FilterChip(
                            selected = selection[filter.key] == valueKey,
                            onClick = {
                                selection = if (selection[filter.key] == valueKey) {
                                    selection - filter.key
                                } else {
                                    selection + (filter.key to valueKey)
                                }
                            },
                            label = { Text(valueName) },
                            shape = RoundedCornerShape(18.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    if (!accepted) {
                        accepted = true
                        selection = emptyMap()
                        onConfirm(emptyMap())
                        dismissAnimated()
                    }
                }) { Text(stringResource(R.string.filter_clear)) }
                TextButton(onClick = {
                    if (!accepted) {
                        accepted = true
                        onConfirm(selection)
                        dismissAnimated()
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            }
        }
    }
}
