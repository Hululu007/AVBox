@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

sealed interface LoadState {
    data object Loading : LoadState
    data object Empty : LoadState
    data class Error(val message: String? = null) : LoadState
}

@Composable
fun LoadStateBox(
    state: LoadState,
    emptyText: String,
    errorText: String,
    retryText: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    emptyIconRes: Int? = null,
    loadingContent: @Composable () -> Unit = { ContainedLoadingIndicator(Modifier.size(64.dp)) },
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (state) {
            LoadState.Loading -> loadingContent()

            LoadState.Empty -> if (emptyIconRes == null) {
                StateText(emptyText)
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(emptyIconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(64.dp),
                    )
                    StateText(emptyText)
                }
            }

            is LoadState.Error -> Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StateText(state.message ?: errorText)
                if (onRetry != null) {
                    FilledTonalButton(onClick = onRetry) {
                        Text(text = retryText)
                    }
                }
            }
        }
    }
}

@Composable
private fun StateText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
