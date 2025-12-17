package club.taptappers.telly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.data.model.TaleLog
import club.taptappers.telly.ui.theme.Black
import club.taptappers.telly.ui.theme.Gray100
import club.taptappers.telly.ui.theme.Gray200
import club.taptappers.telly.ui.theme.Gray500
import club.taptappers.telly.ui.theme.Green500
import club.taptappers.telly.ui.theme.Red500
import club.taptappers.telly.ui.theme.White
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaleDetailScreen(
    tale: Tale,
    logs: List<TaleLog>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRunNow: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Tale") },
            text = { Text("Are you sure you want to delete \"${tale.name}\"? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = Red500)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = Black)
                }
            },
            containerColor = White
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = White,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = tale.name,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Red500)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = White,
                    titleContentColor = Black,
                    navigationIconContentColor = Black,
                    actionIconContentColor = Black
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tale info card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Enabled toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enabled",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Black
                    )
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

                // Schedule info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Schedule",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Black
                    )
                    Text(
                        text = getScheduleDescription(tale),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Gray500
                    )
                }

                // Action type
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Action",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Black
                    )
                    Text(
                        text = "Fetch Time",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Gray500
                    )
                }

                // Webhook URL
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Webhook",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Black
                    )
                    Text(
                        text = tale.webhookUrl ?: "Not configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Gray500,
                        maxLines = 2
                    )
                }

                // Run now button
                Button(
                    onClick = onRunNow,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Gray100,
                        contentColor = Black
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Run Now")
                }
            }

            // Logs section
            Text(
                text = "Execution Logs",
                style = MaterialTheme.typography.titleMedium,
                color = Black,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No logs yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Gray500
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs, key = { it.id }) { log ->
                        LogItem(log = log)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItem(
    log: TaleLog,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Gray100, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (log.success) Green500 else Red500)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.result,
                style = MaterialTheme.typography.bodyMedium,
                color = Black
            )
            Text(
                text = formatTimestamp(log.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = Gray500
            )
        }
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

private fun formatTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
