package com.darkvvpn.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.darkvvpn.app.ui.screens.HomeScreen
import com.darkvvpn.app.ui.screens.ServersScreen
import com.darkvvpn.app.ui.screens.SettingsScreen
import com.darkvvpn.app.ui.screens.SplashScreen
import com.darkvvpn.app.ui.screens.SubscriptionsScreen
import com.darkvvpn.app.viewmodel.UpdateViewModel

/**
 * @param updateViewModel the single update ViewModel for the app. It is passed in
 *   rather than created per screen because the badge, the Settings banner and the
 *   dialog must all be looking at the *same* state: the banner's tap opens the
 *   dialog, so a second instance would set a flag nobody reads and the tap would
 *   appear to do nothing.
 */
@Composable
fun DarkVvpnNavHost(
    navController: NavHostController,
    updateViewModel: UpdateViewModel,
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

        composable(Routes.SERVERS) {
            ServersScreen(
                // The empty state's call to action: the fix for "no servers" is
                // importing one, so that is where the button goes.
                onNavigateToSubscriptions = { navController.navigate(Routes.SUBSCRIPTIONS) },
            )
        }

        composable(
            route = "${Routes.SUBSCRIPTIONS}?payload={payload}",
            arguments = listOf(
                navArgument("payload") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            // A share link or subscription URL opened from another app arrives
            // here as an encoded argument and is prefilled into the import sheet.
            val payload = entry.arguments?.getString("payload")
            SubscriptionsScreen(prefillPayload = payload)
        }

        composable(Routes.SETTINGS) { SettingsScreen(updateViewModel = updateViewModel) }
    }
}
