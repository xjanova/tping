package com.xjanova.tping

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xjanova.tping.ui.screens.DataProfileScreen
import com.xjanova.tping.ui.screens.HomeScreen
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

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToData = { navController.navigate("data") },
                onNavigateToWorkflows = { navController.navigate("workflows") },
                onNavigateToPlay = { navController.navigate("play") }
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
