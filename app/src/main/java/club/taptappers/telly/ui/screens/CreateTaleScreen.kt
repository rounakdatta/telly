package club.taptappers.telly.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import club.taptappers.telly.data.model.ActionType
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.gmail.GmailAuthState
import club.taptappers.telly.gmail.GmailAuthStatus
import club.taptappers.telly.ui.theme.Black
import club.taptappers.telly.ui.theme.Gray100
import club.taptappers.telly.ui.theme.Gray200
import club.taptappers.telly.ui.theme.Gray500
import club.taptappers.telly.ui.theme.Green500
import club.taptappers.telly.ui.theme.White

data class IntervalOption(
    val label: String,
    val valueMs: Long,
    val isCustom: Boolean = false
)

enum class IntervalUnit(val label: String, val multiplierMs: Long) {
    SECONDS("seconds", 1_000L),
    MINUTES("minutes", 60_000L),
    HOURS("hours", 3_600_000L),
    DAYS("days", 86_400_000L),
    WEEKS("weeks", 604_800_000L)
}

// Quick preset options
val quickIntervalPresets = listOf(
    IntervalOption("1m", 60_000),
    IntervalOption("5m", 300_000),
    IntervalOption("15m", 900_000),
    IntervalOption("30m", 1_800_000),
    IntervalOption("1h", 3_600_000),
    IntervalOption("6h", 21_600_000),
    IntervalOption("12h", 43_200_000),
    IntervalOption("1d", 86_400_000)
)

// For backwards compatibility and dropdown
val intervalOptions = listOf(
    IntervalOption("10 seconds", 10_000),
    IntervalOption("30 seconds", 30_000),
    IntervalOption("1 minute", 60_000),
    IntervalOption("5 minutes", 300_000),
    IntervalOption("15 minutes", 900_000),
    IntervalOption("30 minutes", 1_800_000),
    IntervalOption("1 hour", 3_600_000),
    IntervalOption("3 hours", 10_800_000),
    IntervalOption("5 hours", 18_000_000),
    IntervalOption("6 hours", 21_600_000),
    IntervalOption("12 hours", 43_200_000),
    IntervalOption("24 hours", 86_400_000)
)

fun createCustomInterval(value: Int, unit: IntervalUnit): IntervalOption {
    val ms = value * unit.multiplierMs
    val label = "$value ${if (value == 1) unit.label.dropLast(1) else unit.label}"
    return IntervalOption(label, ms, isCustom = true)
}

fun parseIntervalToCustom(ms: Long): Pair<Int, IntervalUnit> {
    return when {
        ms >= IntervalUnit.WEEKS.multiplierMs && ms % IntervalUnit.WEEKS.multiplierMs == 0L ->
            Pair((ms / IntervalUnit.WEEKS.multiplierMs).toInt(), IntervalUnit.WEEKS)
        ms >= IntervalUnit.DAYS.multiplierMs && ms % IntervalUnit.DAYS.multiplierMs == 0L ->
            Pair((ms / IntervalUnit.DAYS.multiplierMs).toInt(), IntervalUnit.DAYS)
        ms >= IntervalUnit.HOURS.multiplierMs && ms % IntervalUnit.HOURS.multiplierMs == 0L ->
            Pair((ms / IntervalUnit.HOURS.multiplierMs).toInt(), IntervalUnit.HOURS)
        ms >= IntervalUnit.MINUTES.multiplierMs && ms % IntervalUnit.MINUTES.multiplierMs == 0L ->
            Pair((ms / IntervalUnit.MINUTES.multiplierMs).toInt(), IntervalUnit.MINUTES)
        else -> Pair((ms / IntervalUnit.SECONDS.multiplierMs).toInt(), IntervalUnit.SECONDS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaleScreen(
    existingTale: Tale? = null,
    gmailAuthState: GmailAuthState?,
    onSave: (Tale) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(existingTale?.name ?: "") }
    var actionType by remember { mutableStateOf(existingTale?.actionType ?: ActionType.TIME) }
    var scheduleType by remember { mutableStateOf(existingTale?.scheduleType ?: ScheduleType.INTERVAL) }
    var selectedInterval by remember {
        mutableStateOf(
            existingTale?.scheduleValue?.toLongOrNull()?.let { ms ->
                intervalOptions.find { it.valueMs == ms }
            } ?: intervalOptions[2] // Default to 1 minute
        )
    }
    var dailyTime by remember {
        mutableStateOf(
            if (existingTale?.scheduleType == ScheduleType.DAILY_AT)
                existingTale.scheduleValue ?: "07:00"
            else "07:00"
        )
    }
    var webhookUrl by remember { mutableStateOf(existingTale?.webhookUrl ?: "") }
    var searchQuery by remember { mutableStateOf(existingTale?.searchQuery ?: "") }

    val gmailAuthStatus by gmailAuthState?.authStatus?.collectAsState()
        ?: remember { mutableStateOf(GmailAuthStatus.NotSignedIn) }

    val context = LocalContext.current

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Toast.makeText(context, "Sign-in result code: ${result.resultCode}", Toast.LENGTH_SHORT).show()
        if (result.resultCode == Activity.RESULT_OK) {
            gmailAuthState?.handleSignInResult(result.data)
        } else {
            Toast.makeText(context, "Sign-in cancelled or failed (code: ${result.resultCode})", Toast.LENGTH_LONG).show()
            gmailAuthState?.checkCurrentAuthStatus()
        }
    }

    // Show toast when auth status changes to error
    LaunchedEffect(gmailAuthStatus) {
        if (gmailAuthStatus is GmailAuthStatus.Error) {
            Toast.makeText(context, (gmailAuthStatus as GmailAuthStatus.Error).message, Toast.LENGTH_LONG).show()
        } else if (gmailAuthStatus is GmailAuthStatus.SignedIn) {
            Toast.makeText(context, "Signed in as ${(gmailAuthStatus as GmailAuthStatus.SignedIn).email}", Toast.LENGTH_SHORT).show()
        }
    }

    val isEditing = existingTale != null
    val canSave = name.isNotBlank() && when (actionType) {
        ActionType.TIME -> true
        ActionType.EMAIL_JUGGLE -> {
            searchQuery.isNotBlank() && gmailAuthStatus is GmailAuthStatus.SignedIn
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = White,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isEditing) "Edit Tale" else "Create Tale",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = White,
                    titleContentColor = Black,
                    navigationIconContentColor = Black
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Name input
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelLarge,
                    color = Black
                )
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (name.isEmpty()) {
                                Text(
                                    text = "Enter tale name",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Gray500
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            // Action type selection
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Action",
                    style = MaterialTheme.typography.labelLarge,
                    color = Black
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionTypeChip(
                        label = "Fetch Time",
                        selected = actionType == ActionType.TIME,
                        onClick = { actionType = ActionType.TIME },
                        modifier = Modifier.weight(1f)
                    )
                    ActionTypeChip(
                        label = "Email Juggle",
                        selected = actionType == ActionType.EMAIL_JUGGLE,
                        onClick = { actionType = ActionType.EMAIL_JUGGLE },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Email Juggle specific options
            if (actionType == ActionType.EMAIL_JUGGLE) {
                // Gmail Sign-in status
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Gmail Account",
                        style = MaterialTheme.typography.labelLarge,
                        color = Black
                    )
                    when (val status = gmailAuthStatus) {
                        is GmailAuthStatus.SignedIn -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Green500, RoundedCornerShape(8.dp))
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Green500,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = status.email,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Black
                                    )
                                }
                                Text(
                                    text = "Change",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Gray500,
                                    modifier = Modifier.clickable {
                                        gmailAuthState?.signOut()
                                    }
                                )
                            }
                        }
                        is GmailAuthStatus.SigningIn -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Black
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Signing in...", color = Gray500)
                            }
                        }
                        is GmailAuthStatus.NotSignedIn, is GmailAuthStatus.Error -> {
                            OutlinedButton(
                                onClick = {
                                    gmailAuthState?.getSignInIntent()?.let { intent ->
                                        signInLauncher.launch(intent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Email,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Sign in with Google")
                            }
                            if (status is GmailAuthStatus.Error) {
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                // Search query input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Gmail Search Query",
                        style = MaterialTheme.typography.labelLarge,
                        color = Black
                    )
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                            .padding(16.dp),
                        decorationBox = { innerTextField ->
                            Box {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "e.g., from:bank OR label:important",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Gray500
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    Text(
                        text = "Uses Gmail search syntax. Time window auto-added based on schedule interval.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray500
                    )
                }
            }

            // Schedule type
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Schedule",
                    style = MaterialTheme.typography.labelLarge,
                    color = Black
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScheduleType.entries.forEach { type ->
                        ScheduleTypeChip(
                            label = when (type) {
                                ScheduleType.ONCE -> "Once"
                                ScheduleType.INTERVAL -> "Interval"
                                ScheduleType.DAILY_AT -> "Daily"
                            },
                            selected = scheduleType == type,
                            onClick = { scheduleType = type },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Schedule value input based on type
            when (scheduleType) {
                ScheduleType.ONCE -> {
                    Text(
                        text = if (actionType == ActionType.EMAIL_JUGGLE)
                            "Will search emails from the last hour"
                        else
                            "Tale will run immediately when enabled",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Gray500
                    )
                }

                ScheduleType.INTERVAL -> {
                    IntervalPicker(
                        selectedOption = selectedInterval,
                        onOptionSelected = { selectedInterval = it }
                    )
                    if (actionType == ActionType.EMAIL_JUGGLE) {
                        Text(
                            text = "Email search window matches interval (e.g., 30 min interval = last 30 min of emails)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray500
                        )
                    }
                }

                ScheduleType.DAILY_AT -> {
                    TimeInput(
                        time = dailyTime,
                        onTimeChange = { dailyTime = it }
                    )
                    if (actionType == ActionType.EMAIL_JUGGLE) {
                        Text(
                            text = "Will search emails from the last 24 hours",
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray500
                        )
                    }
                }
            }

            // Webhook URL input
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Webhook URL",
                    style = MaterialTheme.typography.labelLarge,
                    color = Black
                )
                BasicTextField(
                    value = webhookUrl,
                    onValueChange = { webhookUrl = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (webhookUrl.isEmpty()) {
                                Text(
                                    text = "https://your-server.com/webhook",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Gray500
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Text(
                    text = "Results will be POSTed to this URL as JSON",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray500
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Save button
            Button(
                onClick = {
                    val scheduleValue = when (scheduleType) {
                        ScheduleType.ONCE -> null
                        ScheduleType.INTERVAL -> selectedInterval.valueMs.toString()
                        ScheduleType.DAILY_AT -> dailyTime
                    }

                    val tale = existingTale?.copy(
                        name = name,
                        actionType = actionType,
                        scheduleType = scheduleType,
                        scheduleValue = scheduleValue,
                        webhookUrl = webhookUrl.ifBlank { null },
                        searchQuery = if (actionType == ActionType.EMAIL_JUGGLE) searchQuery.ifBlank { null } else null
                    ) ?: Tale(
                        name = name,
                        actionType = actionType,
                        scheduleType = scheduleType,
                        scheduleValue = scheduleValue,
                        webhookUrl = webhookUrl.ifBlank { null },
                        searchQuery = if (actionType == ActionType.EMAIL_JUGGLE) searchQuery.ifBlank { null } else null
                    )

                    onSave(tale)
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Black,
                    contentColor = White,
                    disabledContainerColor = Gray200,
                    disabledContentColor = Gray500
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isEditing) "Save Changes" else "Create Tale",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ActionTypeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = if (selected) Black else Gray200,
                shape = RoundedCornerShape(8.dp)
            )
            .background(if (selected) Black else White)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) White else Gray500
        )
    }
}

@Composable
private fun ScheduleTypeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = if (selected) Black else Gray200,
                shape = RoundedCornerShape(8.dp)
            )
            .background(if (selected) Black else White)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) White else Gray500
        )
    }
}

@Composable
private fun IntervalPicker(
    selectedOption: IntervalOption,
    onOptionSelected: (IntervalOption) -> Unit,
    modifier: Modifier = Modifier
) {
    // Check if current selection matches a preset
    val isPresetSelected = quickIntervalPresets.any { it.valueMs == selectedOption.valueMs }
    var showCustom by remember { mutableStateOf(!isPresetSelected) }

    // Parse existing value for custom inputs
    val (initialValue, initialUnit) = remember(selectedOption.valueMs) {
        parseIntervalToCustom(selectedOption.valueMs)
    }
    var customValue by remember { mutableStateOf(initialValue.toString()) }
    var customUnit by remember { mutableStateOf(initialUnit) }
    var unitExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Repeat every",
            style = MaterialTheme.typography.labelLarge,
            color = Black
        )

        // Quick presets as chips - 2 rows of 4
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickIntervalPresets.take(4).forEach { preset ->
                    IntervalChip(
                        label = preset.label,
                        selected = !showCustom && selectedOption.valueMs == preset.valueMs,
                        onClick = {
                            showCustom = false
                            onOptionSelected(preset)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickIntervalPresets.drop(4).forEach { preset ->
                    IntervalChip(
                        label = preset.label,
                        selected = !showCustom && selectedOption.valueMs == preset.valueMs,
                        onClick = {
                            showCustom = false
                            onOptionSelected(preset)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Custom toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = if (showCustom) Black else Gray200,
                    shape = RoundedCornerShape(8.dp)
                )
                .background(if (showCustom) Gray100 else White)
                .clickable { showCustom = true }
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Custom:",
                style = MaterialTheme.typography.labelMedium,
                color = if (showCustom) Black else Gray500
            )

            if (showCustom) {
                // Number input
                BasicTextField(
                    value = customValue,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                            customValue = newValue
                            val intValue = newValue.toIntOrNull() ?: 1
                            if (intValue > 0) {
                                onOptionSelected(createCustomInterval(intValue, customUnit))
                            }
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .width(50.dp)
                        .border(1.dp, Gray200, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    singleLine = true
                )

                // Unit dropdown
                Box {
                    Row(
                        modifier = Modifier
                            .border(1.dp, Gray200, RoundedCornerShape(4.dp))
                            .clickable { unitExpanded = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = customUnit.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Black
                        )
                        Text(
                            text = " ▼",
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray500
                        )
                    }

                    DropdownMenu(
                        expanded = unitExpanded,
                        onDismissRequest = { unitExpanded = false },
                        modifier = Modifier.background(White)
                    ) {
                        IntervalUnit.entries.forEach { unit ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = unit.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (unit == customUnit) Black else Gray500
                                    )
                                },
                                onClick = {
                                    customUnit = unit
                                    unitExpanded = false
                                    val intValue = customValue.toIntOrNull() ?: 1
                                    if (intValue > 0) {
                                        onOptionSelected(createCustomInterval(intValue, unit))
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Set your own interval",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Gray500
                )
            }
        }

        // Show current selection summary
        if (showCustom) {
            val intValue = customValue.toIntOrNull() ?: 0
            if (intValue > 0) {
                Text(
                    text = "Runs every ${createCustomInterval(intValue, customUnit).label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray500
                )
            }
        }
    }
}

@Composable
private fun IntervalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = if (selected) Black else Gray200,
                shape = RoundedCornerShape(8.dp)
            )
            .background(if (selected) Black else White)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) White else Gray500
        )
    }
}

@Composable
private fun TimeInput(
    time: String,
    onTimeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Time (HH:MM)",
            style = MaterialTheme.typography.labelLarge,
            color = Black
        )
        BasicTextField(
            value = time,
            onValueChange = { newValue ->
                // Simple validation for HH:MM format
                if (newValue.length <= 5 && newValue.all { it.isDigit() || it == ':' }) {
                    onTimeChange(newValue)
                }
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                .padding(16.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (time.isEmpty()) {
                        Text(
                            text = "07:00",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Gray500
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}
