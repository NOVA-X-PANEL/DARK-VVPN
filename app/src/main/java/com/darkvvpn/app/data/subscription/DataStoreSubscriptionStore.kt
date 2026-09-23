package com.darkvvpn.app.data.subscription

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.subscriptionStore: DataStore<Preferences> by
    preferencesDataStore(name = "darkvvpn_subscriptions")

/**
 * Persists the subscription list as one JSON blob inside Preferences DataStore.
 *
 * A single key rather than one row per subscription: the list is small, always
 * read and written whole, and this keeps the write atomic — a partial write can
 * lose an entry, and an interrupted write must not leave an unparseable store.
 *
 * The store holds URLs, which usually embed an access token. It is therefore
 * excluded from cloud backup and device transfer in `res/xml/backup_rules.xml`
 * and `res/xml/data_extraction_rules.xml`.
 */
class DataStoreSubscriptionStore(context: Context) : SubscriptionStore {

    private val store = context.applicationContext.subscriptionStore

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun read(): List<StoredSubscription> {
        val raw = store.data.first()[KEY] ?: return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(StoredSubscription.serializer()),
                raw,
            )
        } catch (_: Throwable) {
            // A corrupt blob is treated as "no subscriptions" rather than
            // crashing the app on launch; the user can re-add the URL.
            emptyList()
        }
    }

    override suspend fun write(subscriptions: List<StoredSubscription>) {
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(StoredSubscription.serializer()),
            subscriptions,
        )
        store.edit { it[KEY] = encoded }
    }

    private companion object {
        val KEY = stringPreferencesKey("subscriptions_json")
    }
}
