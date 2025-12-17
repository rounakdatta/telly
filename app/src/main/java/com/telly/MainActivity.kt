package com.telly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.telly.ui.screens.CreateTaleScreen
import com.telly.ui.screens.HomeScreen
import com.telly.ui.screens.TaleDetailScreen
import com.telly.ui.theme.TellyTheme
import com.telly.viewmodel.TaleViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TellyTheme {
                TellyApp()
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
fun TellyApp() {
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
                onBack = { navController.popBackStack() }
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
                    onBack = { navController.popBackStack() }
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
