package com.darkvvpn.app.data.model

/** User-tunable preferences, persisted with DataStore. */
data class AppSettings(
    // ---- connection -----------------------------------------------------
    val autoConnectOnLaunch: Boolean = false,
    val killSwitch: Boolean = true,
    val blockAdsAndTrackers: Boolean = true,
    val sortServersByPing: Boolean = true,
    val lastServerId: String? = null,

    // ---- appearance -----------------------------------------------------
    val forceDarkTheme: Boolean = true,
    val dynamicColor: Boolean = false,

    // ---- subscriptions --------------------------------------------------
    /** Hours between automatic subscription refreshes; 0 disables them. */
    val subscriptionRefreshHours: Int = 12,
    /** Only refresh while on an unmetered network. */
    val subscriptionRefreshOverWifiOnly: Boolean = true,
    val subscriptionUserAgent: String = "DARK-VVPN/1.0",
    /** Replace a node in place when a refresh serves the same endpoint. */
    val mergeDuplicateNodes: Boolean = true,

    // ---- in-app update --------------------------------------------------
    val checkForUpdatesOnLaunch: Boolean = true,
    /** Include pre-releases such as `v1.0.0-rc1` in the update check. */
    val allowPrereleaseUpdates: Boolean = false,
    /** The release tag the user chose to skip, so the prompt is not repeated. */
    val skippedUpdateTag: String? = null,
    val lastUpdateCheckEpochMillis: Long? = null,
)
