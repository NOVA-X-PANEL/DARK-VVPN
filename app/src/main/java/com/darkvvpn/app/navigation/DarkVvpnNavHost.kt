package com.darkvvpn.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.darkvvpn.app.ui.screens.HomeScreen
import com.darkvvpn.app.ui.screens.ServersScreen
import com.darkvvpn.app.ui.screens.SettingsScreen
import com.darkvvpn.app.ui.screens.SplashScreen

@Composable
fun DarkVvpnNavHost(
    navController: NavHostController,
    startDestination: String = Routes.SPLASH,
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToServers = { navController.navigate(Routes.SERVERS) },
            )
        }

        composable(Routes.SERVERS) { ServersScreen() }

        composable(Routes.SETTINGS) { SettingsScreen() }
    }
}
