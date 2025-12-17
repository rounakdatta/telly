package club.taptappers.telly

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import club.taptappers.telly.gmail.GmailAuthState
import club.taptappers.telly.service.ServiceManager
import club.taptappers.telly.ui.screens.CreateTaleScreen
import club.taptappers.telly.ui.screens.HomeScreen
import club.taptappers.telly.ui.screens.TaleDetailScreen
import club.taptappers.telly.ui.theme.TellyTheme
import club.taptappers.telly.viewmodel.TaleViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var gmailAuthState: GmailAuthState

    @Inject
    lateinit var serviceManager: ServiceManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, check if service needs to start
            lifecycleScope.launch {
                serviceManager.checkAndManageService()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()

        // Check and start service if there are enabled tales
        lifecycleScope.launch {
            serviceManager.checkAndManageService()
        }

        setContent {
            TellyTheme {
                TellyApp(gmailAuthState)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object CreateTale : Screen("create_tale")
    data object EditTale : Screen("edit_tale/{taleId}") {
        fun createRoute(taleId: String) = "edit_tale/$taleId"
    }
    data object TaleDetail : Screen("tale_detail/{taleId}") {
        fun createRoute(taleId: String) = "tale_detail/$taleId"
    }
}

@Composable
fun TellyApp(gmailAuthState: GmailAuthState) {
    val navController = rememberNavController()
    val viewModel: TaleViewModel = hiltViewModel()
    val tales by viewModel.tales.collectAsState()
    val selectedTale by viewModel.selectedTale.collectAsState()
    val selectedTaleLogs by viewModel.selectedTaleLogs.collectAsState()

    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(
                tales = tales,
                onCreateTale = { navController.navigate(Screen.CreateTale.route) },
                onTaleClick = { tale ->
                    viewModel.selectTale(tale.id)
                    navController.navigate(Screen.TaleDetail.createRoute(tale.id))
                },
                onToggleTale = { tale, enabled ->
                    viewModel.toggleTaleEnabled(tale, enabled)
                }
            )
        }

        composable(Screen.CreateTale.route) {
            CreateTaleScreen(
                onSave = { tale ->
                    viewModel.createTale(tale)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                gmailAuthState = gmailAuthState
            )
        }

        composable(
            route = Screen.EditTale.route,
            arguments = listOf(navArgument("taleId") { type = NavType.StringType })
        ) {
            selectedTale?.let { tale ->
                CreateTaleScreen(
                    existingTale = tale,
                    onSave = { updatedTale ->
                        viewModel.updateTale(updatedTale)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                    gmailAuthState = gmailAuthState
                )
            }
        }

        composable(
            route = Screen.TaleDetail.route,
            arguments = listOf(navArgument("taleId") { type = NavType.StringType })
        ) {
            selectedTale?.let { tale ->
                TaleDetailScreen(
                    tale = tale,
                    logs = selectedTaleLogs,
                    onBack = {
                        viewModel.clearSelectedTale()
                        navController.popBackStack()
                    },
                    onEdit = {
                        navController.navigate(Screen.EditTale.createRoute(tale.id))
                    },
                    onDelete = {
                        viewModel.deleteTale(tale)
                        navController.popBackStack()
                    },
                    onRunNow = { viewModel.runTaleNow(tale) },
                    onToggleEnabled = { enabled ->
                        viewModel.toggleTaleEnabled(tale, enabled)
                    }
                )
            }
        }
    }
}
