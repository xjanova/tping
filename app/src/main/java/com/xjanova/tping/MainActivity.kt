package com.xjanova.tping

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xjanova.tping.data.license.LicenseManager
import com.xjanova.tping.overlay.FloatingOverlayService
import com.xjanova.tping.ui.screens.CloudScreen
import com.xjanova.tping.ui.screens.DataProfileScreen
import com.xjanova.tping.ui.screens.ExportImportScreen
import com.xjanova.tping.ui.screens.HomeScreen
import com.xjanova.tping.ui.screens.LicenseGateScreen
import com.xjanova.tping.ui.screens.PlayScreen
import com.xjanova.tping.ui.screens.WorkflowScreen
import com.xjanova.tping.ui.theme.TpingTheme
import com.xjanova.tping.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TpingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TpingApp()
                }
            }
        }
    }
}

@Composable
fun TpingApp() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()

    // Initialize license on first composition
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        try {
            FloatingOverlayService.playbackEngine = viewModel.playbackEngine
            LicenseManager.initialize(context)
        } catch (e: Exception) {
            android.util.Log.e("TpingApp", "Initialization failed", e)
        }
    }

    // Observe license state — auto-navigate to gate when expired/none
    val licenseState by LicenseManager.state.collectAsState()

    LaunchedEffect(licenseState.status, licenseState.isLoading) {
        if (!licenseState.isLoading && LicenseManager.shouldShowGate()) {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != "license_gate") {
                navController.navigate("license_gate") {
                    popUpTo("home") { inclusive = true }
                }
            }
        }
    }

    // Start at home — license check runs in background
    NavHost(
        navController = navController,
        startDestination = "home",
        enterTransition = { slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)) },
        exitTransition = { slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(tween(300)) { -it / 3 } + fadeIn(tween(300)) },
        popExitTransition = { slideOutHorizontally(tween(300)) { it } + fadeOut(tween(200)) }
    ) {
        composable("license_gate") {
            LicenseGateScreen(
                onLicenseActivated = {
                    navController.navigate("home") {
                        popUpTo("license_gate") { inclusive = true }
                    }
                }
            )
        }
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToData = { navController.navigate("data") { launchSingleTop = true } },
                onNavigateToWorkflows = { navController.navigate("workflows") { launchSingleTop = true } },
                onNavigateToPlay = { navController.navigate("play") { launchSingleTop = true } },
                onNavigateToCloud = { navController.navigate("cloud") { launchSingleTop = true } }
            )
        }
        composable("data") {
            DataProfileScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("workflows") {
            WorkflowScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("play") {
            PlayScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("export") {
            ExportImportScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("cloud") {
            CloudScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
