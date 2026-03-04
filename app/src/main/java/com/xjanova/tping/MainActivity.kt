package com.xjanova.tping

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xjanova.tping.data.license.LicenseManager
import com.xjanova.tping.data.license.LicenseStatus
import com.xjanova.tping.overlay.FloatingOverlayService
import com.xjanova.tping.ui.screens.DataProfileScreen
import com.xjanova.tping.ui.screens.HomeScreen
import com.xjanova.tping.ui.screens.LicenseGateScreen
import com.xjanova.tping.ui.screens.PlayScreen
import com.xjanova.tping.ui.screens.WorkflowScreen
import com.xjanova.tping.ui.theme.TpingTheme
import com.xjanova.tping.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

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
    val licenseState by LicenseManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Initialize license on first composition
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        FloatingOverlayService.playbackEngine = viewModel.playbackEngine
        LicenseManager.initialize(context)
    }

    // Show loading while checking license
    if (licenseState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Determine start destination based on license
    val startDest = if (licenseState.status == LicenseStatus.EXPIRED || licenseState.status == LicenseStatus.NONE) {
        "license_gate"
    } else {
        "home"
    }

    NavHost(
        navController = navController,
        startDestination = startDest,
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
                onNavigateToPlay = { navController.navigate("play") { launchSingleTop = true } }
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
    }
}
