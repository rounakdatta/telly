package club.taptappers.telly.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import club.taptappers.telly.data.model.ActionType
import club.taptappers.telly.data.model.Reaction
import club.taptappers.telly.data.model.ReactionCodec
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.gmail.GmailAuthState
import club.taptappers.telly.gmail.GmailAuthStatus
import club.taptappers.telly.health.HealthConnectAuthState
import club.taptappers.telly.health.HealthConnectStatus
import club.taptappers.telly.hevy.HevyAuthState
import club.taptappers.telly.hevy.HevyAuthStatus
import club.taptappers.telly.strava.StravaAuthState
import club.taptappers.telly.strava.StravaAuthStatus
import club.taptappers.telly.ui.theme.Black
import kotlinx.coroutines.launch
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
    stravaAuthState: StravaAuthState? = null,
    healthConnectAuthState: HealthConnectAuthState? = null,
    hevyAuthState: HevyAuthState? = null,
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

    // Hydrate the "Append health data" toggle from the existing tale's
    // reactions chain (if it had one). Falling back to false is safe — old
    // tales without reactionsJson never had this option configured.
    val initialReactions = remember(existingTale) {
        ReactionCodec.decodeOrNull(existingTale?.reactionsJson) ?: emptyList()
    }
    var appendHealthData by remember(initialReactions) {
        mutableStateOf(initialReactions.any { it is Reaction.GetHealthDataForWorkout })
    }
    var stravaTransform by remember(initialReactions) {
        mutableStateOf(initialReactions.any { it is Reaction.StravaTransform })
    }
    var syncBiometricsToHevy by remember(initialReactions) {
        mutableStateOf(initialReactions.any { it is Reaction.SyncBiometricsToHevy })
    }

    val gmailAuthStatus by gmailAuthState?.authStatus?.collectAsState()
        ?: remember { mutableStateOf(GmailAuthStatus.NotSignedIn) }

    val stravaAuthStatus by stravaAuthState?.status?.collectAsState()
        ?: remember { mutableStateOf<StravaAuthStatus>(StravaAuthStatus.NotConfigured) }

    val healthConnectStatus by healthConnectAuthState?.status?.collectAsState()
        ?: remember { mutableStateOf<HealthConnectStatus>(HealthConnectStatus.Unavailable) }

    val hevyAuthStatus by hevyAuthState?.status?.collectAsState()
        ?: remember { mutableStateOf<HevyAuthStatus>(HevyAuthStatus.NotConfigured) }

    val context = LocalContext.current

    // Re-derive Strava status from disk on entry so the UI reflects any
    // background-cleared tokens (e.g., a refresh failed in a tale run while
    // this screen wasn't visible).
    LaunchedEffect(stravaAuthState) {
        stravaAuthState?.refresh()
    }

    LaunchedEffect(healthConnectAuthState) {
        healthConnectAuthState?.refresh()
    }

    LaunchedEffect(hevyAuthState) {
        hevyAuthState?.refresh()
    }

    // Health Connect's permission grant is its own activity-result contract,
    // not the standard Android runtime-permission API. The contract takes a
    // Set<String> in (the requested permissions) and returns a Set<String>
    // out (those granted). We just refresh after to update the displayed
    // status — the StateFlow is read elsewhere.
    val scope = rememberCoroutineScope()
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { _: Set<String> ->
        scope.launch { healthConnectAuthState?.refresh() }
    }

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
        ActionType.STRAVA_LAST_HEVY -> {
            stravaAuthStatus is StravaAuthStatus.SignedIn
        }
        ActionType.HEVY_LAST_WORKOUT -> {
            hevyAuthStatus is HevyAuthStatus.Authorized
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
                // 2×2 grid — four labels too cramped at weight=1f each, and
                // wrapping to two rows reads cleaner than a horizontal scroll.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ActionTypeChip(
                            label = "Strava Hevy",
                            selected = actionType == ActionType.STRAVA_LAST_HEVY,
                            onClick = { actionType = ActionType.STRAVA_LAST_HEVY },
                            modifier = Modifier.weight(1f)
                        )
                        ActionTypeChip(
                            label = "Hevy Workout",
                            selected = actionType == ActionType.HEVY_LAST_WORKOUT,
                            onClick = { actionType = ActionType.HEVY_LAST_WORKOUT },
                            modifier = Modifier.weight(1f)
                        )
                    }
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

            // Strava specific options
            if (actionType == ActionType.STRAVA_LAST_HEVY) {
                StravaAccountSection(
                    stravaAuthState = stravaAuthState,
                    stravaAuthStatus = stravaAuthStatus
                )
            }

            // Hevy specific options
            if (actionType == ActionType.HEVY_LAST_WORKOUT) {
                HevyAccountSection(
                    hevyAuthState = hevyAuthState,
                    hevyAuthStatus = hevyAuthStatus
                )
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

            // Reactions — what runs after the action.
            //
            // The action produces a payload; reactions augment or transmit it
            // in order. Today the available reactions are "Append health data
            // for the workout" (Strava-action only) and the webhook POST.
            // Order on save: augmenters first, transmitters last.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Reactions",
                    style = MaterialTheme.typography.labelLarge,
                    color = Black
                )
                Text(
                    text = "Run after the action. Order: augment first, send last.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray500
                )

                // Health-data augmenter is shared by both workout-action types.
                if (actionType == ActionType.STRAVA_LAST_HEVY ||
                    actionType == ActionType.HEVY_LAST_WORKOUT
                ) {
                    HealthDataReactionRow(
                        enabled = appendHealthData,
                        onToggle = { appendHealthData = it },
                        status = healthConnectStatus,
                        onRequestPermission = {
                            val perms = healthConnectAuthState?.requiredPermissions
                                ?: return@HealthDataReactionRow
                            healthPermissionLauncher.launch(perms)
                        }
                    )
                }

                if (actionType == ActionType.STRAVA_LAST_HEVY) {
                    StravaTransformReactionRow(
                        enabled = stravaTransform,
                        onToggle = { stravaTransform = it },
                        healthDataEnabled = appendHealthData
                    )
                }

                if (actionType == ActionType.HEVY_LAST_WORKOUT) {
                    SyncBiometricsToHevyReactionRow(
                        enabled = syncBiometricsToHevy,
                        onToggle = { syncBiometricsToHevy = it },
                        healthDataEnabled = appendHealthData
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Webhook URL",
                        style = MaterialTheme.typography.bodyMedium,
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
                        text = "Final payload (after any augmenters) is POSTed here as JSON. Leave blank to skip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray500
                    )
                }
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

                    val finalWebhookUrl = webhookUrl.ifBlank { null }
                    val finalSearchQuery = if (actionType == ActionType.EMAIL_JUGGLE) {
                        searchQuery.ifBlank { null }
                    } else null

                    // Build the reaction chain in canonical order: augmenters
                    // first (so transmitters see the augmented payload), then
                    // transport. Always serialize — even an empty list — so we
                    // don't fall through to the legacy webhookUrl synthesis.
                    val isWorkoutAction = actionType == ActionType.STRAVA_LAST_HEVY ||
                        actionType == ActionType.HEVY_LAST_WORKOUT
                    val reactions = buildList {
                        if (isWorkoutAction && appendHealthData) {
                            add(Reaction.GetHealthDataForWorkout)
                        }
                        if (actionType == ActionType.STRAVA_LAST_HEVY && stravaTransform) {
                            add(Reaction.StravaTransform)
                        }
                        if (actionType == ActionType.HEVY_LAST_WORKOUT && syncBiometricsToHevy) {
                            add(Reaction.SyncBiometricsToHevy)
                        }
                        if (finalWebhookUrl != null) {
                            add(Reaction.Webhook(finalWebhookUrl))
                        }
                    }
                    val finalReactionsJson = ReactionCodec.encode(reactions)

                    val tale = existingTale?.copy(
                        name = name,
                        actionType = actionType,
                        scheduleType = scheduleType,
                        scheduleValue = scheduleValue,
                        webhookUrl = finalWebhookUrl,
                        searchQuery = finalSearchQuery,
                        reactionsJson = finalReactionsJson
                    ) ?: Tale(
                        name = name,
                        actionType = actionType,
                        scheduleType = scheduleType,
                        scheduleValue = scheduleValue,
                        webhookUrl = finalWebhookUrl,
                        searchQuery = finalSearchQuery,
                        reactionsJson = finalReactionsJson
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

@Composable
private fun StravaAccountSection(
    stravaAuthState: StravaAuthState?,
    stravaAuthStatus: StravaAuthStatus
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val openAuthUrl: () -> Unit = {
        stravaAuthState?.authorizationUrl()?.let { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } ?: Toast.makeText(
            context,
            "Save your client credentials first",
            Toast.LENGTH_SHORT
        ).show()
    }

    val submitCode: (String) -> Unit = { input ->
        scope.launch { stravaAuthState?.submitAuthorizationCode(input) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Strava Account",
            style = MaterialTheme.typography.labelLarge,
            color = Black
        )

        when (stravaAuthStatus) {
            is StravaAuthStatus.NotConfigured -> {
                StravaCredentialsForm(
                    onSave = { id, secret -> stravaAuthState?.saveCredentials(id, secret) }
                )
            }
            is StravaAuthStatus.NotSignedIn -> {
                StravaAuthorizeStep(
                    onOpenAuthUrl = openAuthUrl,
                    onSubmitCode = submitCode,
                    onChangeCredentials = { stravaAuthState?.clearAll() },
                    error = null
                )
            }
            is StravaAuthStatus.Authorizing -> {
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
                    Text("Verifying with Strava...", color = Gray500)
                }
            }
            is StravaAuthStatus.SignedIn -> {
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
                            text = stravaAuthStatus.athleteName ?: "Connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Black
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Sign out",
                            style = MaterialTheme.typography.labelMedium,
                            color = Gray500,
                            modifier = Modifier.clickable { stravaAuthState?.signOut() }
                        )
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelMedium,
                            color = Gray500,
                            modifier = Modifier.clickable { stravaAuthState?.clearAll() }
                        )
                    }
                }
            }
            is StravaAuthStatus.Error -> {
                StravaAuthorizeStep(
                    onOpenAuthUrl = openAuthUrl,
                    onSubmitCode = submitCode,
                    onChangeCredentials = { stravaAuthState?.clearAll() },
                    error = stravaAuthStatus.message
                )
            }
        }
    }
}

@Composable
private fun StravaCredentialsForm(
    onSave: (String, String) -> Unit
) {
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Register a personal app at strava.com/settings/api with " +
                "Authorization Callback Domain set to localhost. Paste its " +
                "Client ID and Client Secret below — both are stored encrypted " +
                "on this device only.",
            style = MaterialTheme.typography.bodySmall,
            color = Gray500
        )
        BasicTextField(
            value = clientId,
            onValueChange = { clientId = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                .padding(16.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (clientId.isEmpty()) {
                        Text(
                            text = "Client ID",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Gray500
                        )
                    }
                    innerTextField()
                }
            }
        )
        BasicTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                .padding(16.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (clientSecret.isEmpty()) {
                        Text(
                            text = "Client Secret",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Gray500
                        )
                    }
                    innerTextField()
                }
            }
        )
        OutlinedButton(
            onClick = { onSave(clientId, clientSecret) },
            enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Save credentials")
        }
    }
}

@Composable
private fun StravaAuthorizeStep(
    onOpenAuthUrl: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onChangeCredentials: () -> Unit,
    error: String?
) {
    var code by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onOpenAuthUrl,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Open Strava authorization")
        }
        Text(
            text = "Authorize in your browser. It will land on a localhost page " +
                "that fails to load — that's expected. Copy the full URL from " +
                "the address bar (or just the code parameter) and paste it below.",
            style = MaterialTheme.typography.bodySmall,
            color = Gray500
        )
        BasicTextField(
            value = code,
            onValueChange = { code = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                .padding(16.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (code.isEmpty()) {
                        Text(
                            text = "Paste URL or code",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Gray500
                        )
                    }
                    innerTextField()
                }
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    onSubmitCode(code)
                    code = ""
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Submit")
            }
            OutlinedButton(
                onClick = onChangeCredentials,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Reset credentials")
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun HealthDataReactionRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    status: HealthConnectStatus,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Gray200, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Append health data",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Black
                )
                Text(
                    text = "Heart rate + calories from Health Connect, scoped to the workout window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray500
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = White,
                    checkedTrackColor = Black,
                    uncheckedThumbColor = White,
                    uncheckedTrackColor = Gray200,
                    uncheckedBorderColor = Gray200
                )
            )
        }

        // Only surface the permission/availability state when the user has
        // actually opted in — otherwise it's noise.
        if (enabled) {
            when (status) {
                is HealthConnectStatus.Granted -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Green500,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Health Connect permissions granted",
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray500
                        )
                    }
                }
                is HealthConnectStatus.NeedsPermission -> {
                    OutlinedButton(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Grant Health Connect permissions")
                    }
                }
                is HealthConnectStatus.Unavailable -> {
                    Text(
                        text = "Health Connect isn't available on this device. Install the Health Connect app from the Play Store (Android <14) or enable it in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StravaTransformReactionRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    healthDataEnabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Gray200, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Strava Transform",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Black
                )
                Text(
                    text = "Re-uploads the workout as a new Strava activity with HR, calories, and the Hevy description baked into a TCX file. The original Hevy activity stays alongside, untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray500
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = White,
                    checkedTrackColor = Black,
                    uncheckedThumbColor = White,
                    uncheckedTrackColor = Gray200,
                    uncheckedBorderColor = Gray200
                )
            )
        }
        if (enabled && !healthDataEnabled) {
            Text(
                text = "Tip: enable \"Append health data\" above so the new activity has a real HR graph and calorie total. Without it the activity uploads but Strava reports has_heartrate: false.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray500
            )
        }
        if (enabled) {
            Text(
                text = "Requires Strava activity:write scope — sign out and re-authorize if your existing connection is read-only.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray500
            )
        }
    }
}

@Composable
private fun HevyAccountSection(
    hevyAuthState: HevyAuthState?,
    hevyAuthStatus: HevyAuthStatus
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Hevy Account",
            style = MaterialTheme.typography.labelLarge,
            color = Black
        )

        when (hevyAuthStatus) {
            is HevyAuthStatus.NotConfigured, is HevyAuthStatus.Error -> {
                HevyCredentialsForm(
                    onSave = { devKey, cookieJson ->
                        hevyAuthState?.saveDevApiKey(devKey)
                        scope.launch {
                            val err = hevyAuthState?.saveAuthCookieAndVerify(cookieJson)
                            if (err != null) {
                                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    error = (hevyAuthStatus as? HevyAuthStatus.Error)?.message
                )
            }
            is HevyAuthStatus.Verifying -> {
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
                    Text("Verifying with Hevy...", color = Gray500)
                }
            }
            is HevyAuthStatus.Authorized -> {
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
                            text = hevyAuthStatus.username ?: "Connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Black
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Sign out",
                            style = MaterialTheme.typography.labelMedium,
                            color = Gray500,
                            modifier = Modifier.clickable { hevyAuthState?.signOut() }
                        )
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelMedium,
                            color = Gray500,
                            modifier = Modifier.clickable { hevyAuthState?.clearAll() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HevyCredentialsForm(
    onSave: (devKey: String, cookieJson: String) -> Unit,
    error: String?
) {
    var devKey by remember { mutableStateOf("") }
    var cookieJson by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Hevy needs two pieces, both stored encrypted on this device only:\n\n" +
                "1. Developer API key — get one from your Hevy account → Developer page (used for V1 listing).\n" +
                "2. auth2.0-token cookie — log in at app.hevyapp.com, open DevTools → Application → Cookies → hevy.com → copy the value of \"auth2.0-token\" (it's URL-encoded JSON; paste verbatim).",
            style = MaterialTheme.typography.bodySmall,
            color = Gray500
        )
        BasicTextField(
            value = devKey,
            onValueChange = { devKey = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                .padding(16.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (devKey.isEmpty()) {
                        Text(
                            text = "Hevy developer API key",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Gray500
                        )
                    }
                    innerTextField()
                }
            }
        )
        BasicTextField(
            value = cookieJson,
            onValueChange = { cookieJson = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Black),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Gray200, RoundedCornerShape(8.dp))
                .padding(16.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (cookieJson.isEmpty()) {
                        Text(
                            text = "auth2.0-token cookie value",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Gray500
                        )
                    }
                    innerTextField()
                }
            }
        )
        OutlinedButton(
            onClick = { onSave(devKey, cookieJson) },
            enabled = devKey.isNotBlank() && cookieJson.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Save & verify")
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SyncBiometricsToHevyReactionRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    healthDataEnabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Gray200, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Sync biometrics to Hevy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Black
                )
                Text(
                    text = "Recreates the Hevy workout with HR + calories + share_to_strava=true. Hevy auto-pushes the result to Strava with native HR graph and photo. The old Hevy workout is deleted; the orphaned old Strava activity will be cleaned up by a follow-up reaction (TBD).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray500
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = White,
                    checkedTrackColor = Black,
                    uncheckedThumbColor = White,
                    uncheckedTrackColor = Gray200,
                    uncheckedBorderColor = Gray200
                )
            )
        }
        if (enabled && !healthDataEnabled) {
            Text(
                text = "Tip: enable \"Append health data\" above so the recreated workout actually has biometrics — otherwise this is a no-op.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray500
            )
        }
    }
}
