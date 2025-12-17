package club.taptappers.telly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.ui.theme.Black
import club.taptappers.telly.ui.theme.Gray200
import club.taptappers.telly.ui.theme.Gray500
import club.taptappers.telly.ui.theme.White
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TaleCard(
    tale: Tale,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Gray200, RoundedCornerShape(8.dp))
            .background(White)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tale.name,
                style = MaterialTheme.typography.titleMedium,
                color = Black
            )
            Text(
                text = getScheduleDescription(tale),
                style = MaterialTheme.typography.bodySmall,
                color = Gray500
            )
            tale.lastRunAt?.let { lastRun ->
                Text(
                    text = "Last: ${formatTime(lastRun)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray500
                )
            }
        }

        Switch(
            checked = tale.isEnabled,
            onCheckedChange = onToggleEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = White,
                checkedTrackColor = Black,
                uncheckedThumbColor = White,
                uncheckedTrackColor = Gray200,
                uncheckedBorderColor = Gray200
            )
        )
    }
}

private fun getScheduleDescription(tale: Tale): String {
    return when (tale.scheduleType) {
        ScheduleType.ONCE -> "One-time"
        ScheduleType.INTERVAL -> {
            val ms = tale.scheduleValue?.toLongOrNull() ?: return "Interval"
            formatInterval(ms)
        }
        ScheduleType.DAILY_AT -> "Daily at ${tale.scheduleValue ?: "?"}"
    }
}

private fun formatInterval(ms: Long): String {
    return when {
        ms < 60_000 -> "Every ${ms / 1000}s"
        ms < 3_600_000 -> "Every ${ms / 60_000}m"
        else -> "Every ${ms / 3_600_000}h"
    }
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
