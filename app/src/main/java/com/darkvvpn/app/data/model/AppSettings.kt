package com.darkvvpn.app.data.model

/** User-tunable preferences, persisted with DataStore. */
data class AppSettings(
    val autoConnectOnLaunch: Boolean = false,
    val killSwitch: Boolean = true,
    val forceDarkTheme: Boolean = true,
    val dynamicColor: Boolean = false,
    val lastServerId: String? = null,
    val sortServersByPing: Boolean = true,
    val blockAdsAndTrackers: Boolean = true,
)
