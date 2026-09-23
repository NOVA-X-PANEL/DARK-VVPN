package com.darkvvpn.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.darkvvpn.app.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "darkvvpn_settings")

/**
 * Persists [AppSettings] with Preferences DataStore.
 *
 * Only non-sensitive preferences live here. Connection credentials and
 * subscription URLs (which embed tokens) belong to the stores that exclude them
 * from backup — see `res/xml/backup_rules.xml`.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect_on_launch")
        val KILL_SWITCH = booleanPreferencesKey("kill_switch")
        val FORCE_DARK = booleanPreferencesKey("force_dark_theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LAST_SERVER = stringPreferencesKey("last_server_id")
        val SORT_BY_PING = booleanPreferencesKey("sort_by_ping")
        val BLOCK_ADS = booleanPreferencesKey("block_ads")

        val SUB_REFRESH_HOURS = intPreferencesKey("subscription_refresh_hours")
        val SUB_WIFI_ONLY = booleanPreferencesKey("subscription_wifi_only")
        val SUB_USER_AGENT = stringPreferencesKey("subscription_user_agent")
        val MERGE_DUPLICATES = booleanPreferencesKey("merge_duplicate_nodes")

        val UPDATE_ON_LAUNCH = booleanPreferencesKey("check_updates_on_launch")
        val UPDATE_PRERELEASE = booleanPreferencesKey("allow_prerelease_updates")
        val SKIPPED_UPDATE_TAG = stringPreferencesKey("skipped_update_tag")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val KNOWN_RELEASE_TAG = stringPreferencesKey("known_release_tag")
        val KNOWN_RELEASE_VERSION = stringPreferencesKey("known_release_version")
        val KNOWN_RELEASE_PRERELEASE = booleanPreferencesKey("known_release_prerelease")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            autoConnectOnLaunch = prefs[Keys.AUTO_CONNECT] ?: defaults.autoConnectOnLaunch,
            killSwitch = prefs[Keys.KILL_SWITCH] ?: defaults.killSwitch,
            blockAdsAndTrackers = prefs[Keys.BLOCK_ADS] ?: defaults.blockAdsAndTrackers,
            sortServersByPing = prefs[Keys.SORT_BY_PING] ?: defaults.sortServersByPing,
            lastServerId = prefs[Keys.LAST_SERVER],
            forceDarkTheme = prefs[Keys.FORCE_DARK] ?: defaults.forceDarkTheme,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            subscriptionRefreshHours = prefs[Keys.SUB_REFRESH_HOURS] ?: defaults.subscriptionRefreshHours,
            subscriptionRefreshOverWifiOnly = prefs[Keys.SUB_WIFI_ONLY] ?: defaults.subscriptionRefreshOverWifiOnly,
            subscriptionUserAgent = prefs[Keys.SUB_USER_AGENT] ?: defaults.subscriptionUserAgent,
            mergeDuplicateNodes = prefs[Keys.MERGE_DUPLICATES] ?: defaults.mergeDuplicateNodes,
            checkForUpdatesOnLaunch = prefs[Keys.UPDATE_ON_LAUNCH] ?: defaults.checkForUpdatesOnLaunch,
            allowPrereleaseUpdates = prefs[Keys.UPDATE_PRERELEASE] ?: defaults.allowPrereleaseUpdates,
            skippedUpdateTag = prefs[Keys.SKIPPED_UPDATE_TAG],
            lastUpdateCheckEpochMillis = prefs[Keys.LAST_UPDATE_CHECK],
            knownReleaseTag = prefs[Keys.KNOWN_RELEASE_TAG],
            knownReleaseVersion = prefs[Keys.KNOWN_RELEASE_VERSION],
            knownReleaseIsPrerelease = prefs[Keys.KNOWN_RELEASE_PRERELEASE] ?: false,
        )
    }

    // ---- connection ----------------------------------------------------
    suspend fun setAutoConnect(enabled: Boolean) = put(Keys.AUTO_CONNECT, enabled)
    suspend fun setKillSwitch(enabled: Boolean) = put(Keys.KILL_SWITCH, enabled)
    suspend fun setSortByPing(enabled: Boolean) = put(Keys.SORT_BY_PING, enabled)
    suspend fun setBlockAds(enabled: Boolean) = put(Keys.BLOCK_ADS, enabled)

    // ---- appearance ----------------------------------------------------
    suspend fun setForceDarkTheme(enabled: Boolean) = put(Keys.FORCE_DARK, enabled)
    suspend fun setDynamicColor(enabled: Boolean) = put(Keys.DYNAMIC_COLOR, enabled)

    // ---- subscriptions -------------------------------------------------
    suspend fun setSubscriptionRefreshHours(hours: Int) {
        context.settingsStore.edit { it[Keys.SUB_REFRESH_HOURS] = hours.coerceIn(0, 168) }
    }

    suspend fun setSubscriptionWifiOnly(enabled: Boolean) = put(Keys.SUB_WIFI_ONLY, enabled)

    suspend fun setSubscriptionUserAgent(value: String) {
        val trimmed = value.trim().take(200)
        context.settingsStore.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(Keys.SUB_USER_AGENT)
            else prefs[Keys.SUB_USER_AGENT] = trimmed
        }
    }

    suspend fun setMergeDuplicateNodes(enabled: Boolean) = put(Keys.MERGE_DUPLICATES, enabled)

    // ---- updates -------------------------------------------------------
    suspend fun setCheckForUpdatesOnLaunch(enabled: Boolean) = put(Keys.UPDATE_ON_LAUNCH, enabled)
    suspend fun setAllowPrereleaseUpdates(enabled: Boolean) = put(Keys.UPDATE_PRERELEASE, enabled)

    suspend fun setLastUpdateCheck(epochMillis: Long) {
        context.settingsStore.edit { it[Keys.LAST_UPDATE_CHECK] = epochMillis }
    }

    /**
     * Records the newest release the app has seen, so the update badge can be
     * drawn on the next launch before any network call completes.
     *
     * Only ever moves forward: a manual check that returns an older or equal tag
     * (a stale cache, or a release deleted upstream) must not make the badge
     * disappear.
     */
    suspend fun setKnownRelease(tag: String, version: String, isPrerelease: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.KNOWN_RELEASE_VERSION]
            if (current != null &&
                com.darkvvpn.app.data.update.VersionComparator.compare(version, current) < 0
            ) {
                return@edit
            }
            prefs[Keys.KNOWN_RELEASE_TAG] = tag
            prefs[Keys.KNOWN_RELEASE_VERSION] = version
            prefs[Keys.KNOWN_RELEASE_PRERELEASE] = isPrerelease
        }
    }

    suspend fun setSkippedUpdateTag(tag: String?) {
        context.settingsStore.edit { prefs ->
            if (tag.isNullOrBlank()) prefs.remove(Keys.SKIPPED_UPDATE_TAG)
            else prefs[Keys.SKIPPED_UPDATE_TAG] = tag
        }
    }

    suspend fun setLastServerId(serverId: String?) {
        context.settingsStore.edit { prefs ->
            if (serverId == null) prefs.remove(Keys.LAST_SERVER) else prefs[Keys.LAST_SERVER] = serverId
        }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.settingsStore.edit { it[key] = value }
    }
}
