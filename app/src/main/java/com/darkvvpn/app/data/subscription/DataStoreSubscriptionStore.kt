package com.darkvvpn.app.data.subscription

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.subscriptionStore: DataStore<Preferences> by
    preferencesDataStore(name = "darkvvpn_subscriptions")

/**
 * Persists the subscription list and its cached nodes.
 *
 * Two keys in one DataStore, each written as a single JSON blob. A blob rather
 * than one row per subscription because the list is small, always read and
 * written whole, and this keeps each write atomic — an interrupted write must
 * not leave an unparseable store.
 *
 * The cached nodes are stored alongside rather than fetched on demand so that
 * opening the app shows the node list immediately instead of an empty screen
 * until a network round-trip finishes. That empty screen is what a user
 * describes as "my subscription imported but no configs appeared".
 *
 * Both keys hold material that must not leave the device: URLs routinely embed
 * an access token, and a node carries its connection credentials. Both stores are
 * therefore excluded from cloud backup and device transfer in
 * `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`.
 */
class DataStoreSubscriptionStore(context: Context) : SubscriptionStore {

    private val store = context.applicationContext.subscriptionStore

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun read(): List<StoredSubscription> {
        val raw = store.data.first()[KEY_SUBSCRIPTIONS] ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(StoredSubscription.serializer()), raw)
        } catch (_: Throwable) {
            // A corrupt blob is treated as "no subscriptions" rather than crashing
            // the app on launch; the user can re-add the URL.
            emptyList()
        }
    }

    override suspend fun write(subscriptions: List<StoredSubscription>) {
        val encoded = json.encodeToString(ListSerializer(StoredSubscription.serializer()), subscriptions)
        store.edit { it[KEY_SUBSCRIPTIONS] = encoded }
    }

    override suspend fun readNodesRaw(): String? = store.data.first()[KEY_NODES]

    override suspend fun writeNodesRaw(payload: String) {
        store.edit { it[KEY_NODES] = payload }
    }

    private companion object {
        val KEY_SUBSCRIPTIONS = stringPreferencesKey("subscriptions_json")
        val KEY_NODES = stringPreferencesKey("subscription_nodes_json")
    }
}
