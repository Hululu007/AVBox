package com.github.tvbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.AppBootstrap
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun SelectCircle(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
internal fun ManageActionIcon(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .size(40.dp)
            .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { onConfirm(); onDismiss() } }) {
                Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            val dismissAnimated = LocalSheetDismiss.current
            TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private const val SWITCH_SUBSCRIBE_TIMEOUT_MS = 20_000L

internal suspend fun reopenViaSubscription(
    context: Context,
    cid: String,
    sourceKey: String?,
    vodId: String?,
    name: String?,
    pic: String?,
    collect: Boolean = false,
) {
    Toast.makeText(context, context.getString(R.string.detail_switching_source), Toast.LENGTH_SHORT).show()
    AppBootstrap.switchVodSubscription(cid)
    val ready = withTimeoutOrNull(SWITCH_SUBSCRIBE_TIMEOUT_MS) {
        AppBootstrap.state.first { it is AppBootstrap.Boot.Ready || it is AppBootstrap.Boot.Error }
    } is AppBootstrap.Boot.Ready
    if (ready && ApiConfig.get().getSource(sourceKey) != null) {
        context.jumpToDetail(vodId, sourceKey, name, pic, collect)
    } else {
        context.jumpToSearch(name.orEmpty())
    }
}
