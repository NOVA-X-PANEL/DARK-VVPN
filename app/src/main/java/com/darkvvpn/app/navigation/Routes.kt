package com.darkvvpn.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.graphics.vector.ImageVector
import com.darkvvpn.app.R

/** Every destination in the app, as a string route. */
object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
}

/** The destinations that appear in the bottom navigation bar. */
enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Filled.Shield),
    SERVERS(Routes.SERVERS, R.string.nav_servers, Icons.Filled.Public),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
}
