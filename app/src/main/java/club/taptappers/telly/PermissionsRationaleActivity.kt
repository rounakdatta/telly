package club.taptappers.telly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import club.taptappers.telly.ui.theme.Black
import club.taptappers.telly.ui.theme.TellyTheme
import club.taptappers.telly.ui.theme.White

/**
 * Privacy-policy / rationale screen for Health Connect.
 *
 * Required on Android 14+: the system Health Connect permission UI refuses to
 * launch unless the calling app declares an Activity that handles the
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` intent. On Android 13
 * and below, the Health Connect provider app uses the same intent filter to
 * link to the app's policy. See AndroidManifest.xml for the activity-alias
 * (`ViewPermissionUsageActivity`) that wires the same screen to the
 * Android 14+ `VIEW_PERMISSION_USAGE` contract.
 *
 * Telly is a personal task runner — we just state plainly what we read and
 * where it goes.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TellyTheme { RationaleScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RationaleScreen() {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = White,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Health Connect privacy",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = White,
                    titleContentColor = Black
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "What Telly reads",
                style = MaterialTheme.typography.titleMedium,
                color = Black
            )
            Text(
                text = "When you enable the \"Append health data\" reaction on a Strava-Hevy tale, Telly reads heart rate samples and active/total calorie records from Health Connect — only for the time window of the workout that the tale just fetched.",
                style = MaterialTheme.typography.bodyMedium,
                color = Black
            )

            Text(
                text = "Where it goes",
                style = MaterialTheme.typography.titleMedium,
                color = Black
            )
            Text(
                text = "The samples are merged into the JSON payload on this device and forwarded to the webhook URL you configure in the same tale. Telly itself has no servers and sends nothing to anyone you haven't pointed it at.",
                style = MaterialTheme.typography.bodyMedium,
                color = Black
            )

            Text(
                text = "Scope",
                style = MaterialTheme.typography.titleMedium,
                color = Black
            )
            Text(
                text = "Read-only. Telly never writes to Health Connect. It also never reads outside the workout window of the activity it just fetched.",
                style = MaterialTheme.typography.bodyMedium,
                color = Black
            )
        }
    }
}
