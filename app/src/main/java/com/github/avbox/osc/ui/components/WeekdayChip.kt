package com.github.avbox.osc.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.theme.cardContainer

internal val WeekdayRes = listOf(
    R.string.weekday_mon,
    R.string.weekday_tue,
    R.string.weekday_wed,
    R.string.weekday_thu,
    R.string.weekday_fri,
    R.string.weekday_sat,
    R.string.weekday_sun,
)

internal fun formatReminderTime(hour: Int): String =
    hour.toString().padStart(2, '0') + ":00"

internal fun joinedDaysText(labels: List<String>, days: Set<Int>, separator: String): String =
    labels.filterIndexed { index, _ -> index in days }.joinToString(separator)

@Composable
internal fun WeekdayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    todayOutline: Boolean = false,
) {
    Box(modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.cardContainer
            },
            border = if (todayOutline) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}
