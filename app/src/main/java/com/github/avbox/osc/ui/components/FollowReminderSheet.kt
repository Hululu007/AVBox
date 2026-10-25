package com.github.avbox.osc.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.theme.cardContainer

private val ReminderHours = 0..23

private val HourMenuMaxHeight = 280.dp

private const val ReminderSheetHeightFraction = 0.6f

@Composable
fun FollowReminderSheet(
    initialDays: Set<Int>,
    initialHour: Int,
    onDismissRequest: () -> Unit,
    onSave: (days: Set<Int>, hour: Int) -> Unit,
) {
    val dismissAnimated = LocalSheetDismiss.current
    var selectedDays by rememberSaveable { mutableStateOf(initialDays) }
    var selectedHour by rememberSaveable { mutableStateOf(initialHour) }
    var timeMenuOpen by rememberSaveable { mutableStateOf(false) }

    val separator = stringResource(R.string.follow_days_separator)
    val timeText = formatReminderTime(selectedHour)
    val hourOptions = remember { ReminderHours.map { formatReminderTime(it) } }
    val dayLabels = WeekdayRes.map { stringResource(it) }
    val daysText = joinedDaysText(dayLabels, selectedDays, separator)

    AVBoxBottomSheet(
        onDismissRequest = onDismissRequest,
        title = null,
        modifier = Modifier.fillMaxHeight(ReminderSheetHeightFraction),
        headerContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_tab_following),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.follow_reminder_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) {
        ReminderSectionTitle(titleRes = R.string.follow_update_day)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            WeekdayRes.forEachIndexed { index, nameRes ->
                WeekdayChip(
                    label = stringResource(nameRes),
                    selected = index in selectedDays,
                    onClick = {
                        selectedDays = if (index in selectedDays) {
                            if (selectedDays.size > 1) selectedDays - index else selectedDays
                        } else {
                            selectedDays + index
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ReminderGroupDivider()
        ReminderSectionTitle(titleRes = R.string.follow_reminder_time)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Surface(
                onClick = { timeMenuOpen = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.cardContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_follow_update_time),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(if (timeMenuOpen) 90f else 0f),
                    )
                }
            }
            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                AVBoxOptionMenu(
                    expanded = timeMenuOpen,
                    options = hourOptions,
                    selectedIndex = selectedHour,
                    onSelect = { index ->
                        timeMenuOpen = false
                        selectedHour = index
                    },
                    onDismissRequest = { timeMenuOpen = false },
                    modifier = Modifier.heightIn(max = HourMenuMaxHeight),
                )
            }
        }

        ReminderGroupDivider()
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.cardContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_follow_update_week),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.follow_summary, daysText, timeText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Button(
            onClick = {
                onSave(selectedDays, selectedHour)
                dismissAnimated()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.follow_save_settings))
        }
    }
}

@Composable
private fun ReminderSectionTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp),
    )
}

@Composable
private fun ReminderGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
