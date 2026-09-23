package com.darkvvpn.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.darkvvpn.app.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "darkvvpn_settings")

/**
 * Persists [AppSettings] with Preferences DataStore.
 *
 * Only non-sensitive preferences live here. Connection credentials are the
 * responsibility of the tunnel core, not of the settings store.
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
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            autoConnectOnLaunch = prefs[Keys.AUTO_CONNECT] ?: defaults.autoConnectOnLaunch,
            killSwitch = prefs[Keys.KILL_SWITCH] ?: defaults.killSwitch,
            forceDarkTheme = prefs[Keys.FORCE_DARK] ?: defaults.forceDarkTheme,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            lastServerId = prefs[Keys.LAST_SERVER],
            sortServersByPing = prefs[Keys.SORT_BY_PING] ?: defaults.sortServersByPing,
            blockAdsAndTrackers = prefs[Keys.BLOCK_ADS] ?: defaults.blockAdsAndTrackers,
        )
    }

    suspend fun setAutoConnect(enabled: Boolean) = put(Keys.AUTO_CONNECT, enabled)
    suspend fun setKillSwitch(enabled: Boolean) = put(Keys.KILL_SWITCH, enabled)
    suspend fun setForceDarkTheme(enabled: Boolean) = put(Keys.FORCE_DARK, enabled)
    suspend fun setDynamicColor(enabled: Boolean) = put(Keys.DYNAMIC_COLOR, enabled)
    suspend fun setSortByPing(enabled: Boolean) = put(Keys.SORT_BY_PING, enabled)
    suspend fun setBlockAds(enabled: Boolean) = put(Keys.BLOCK_ADS, enabled)

    suspend fun setLastServerId(serverId: String?) {
        context.settingsStore.edit { prefs ->
            if (serverId == null) prefs.remove(Keys.LAST_SERVER) else prefs[Keys.LAST_SERVER] = serverId
        }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.settingsStore.edit { it[key] = value }
    }
}
