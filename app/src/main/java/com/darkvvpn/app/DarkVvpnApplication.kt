package com.darkvvpn.app

import android.app.Application
import android.content.Context
import com.darkvvpn.app.data.repository.ServerRepository
import com.darkvvpn.app.data.repository.SettingsRepository
import com.darkvvpn.app.data.subscription.DataStoreSubscriptionStore
import com.darkvvpn.app.data.subscription.HttpFetcher
import com.darkvvpn.app.data.subscription.SubscriptionParser
import com.darkvvpn.app.data.subscription.SubscriptionRepository
import com.darkvvpn.app.data.update.UpdateChecker
import com.darkvvpn.app.data.update.UpdateDownloader
import com.darkvvpn.app.data.update.UpdateHttp
import com.darkvvpn.app.data.update.UpdateInstaller
import com.darkvvpn.app.util.NetworkState
import java.io.File

/**
 * Hand-rolled service locator.
 *
 * DARK VVPN is small enough that a DI framework would be more ceremony than
 * value; everything hangs off [DarkVvpnApplication.container] and is reached
 * through ViewModel factories.
 *
 * Everything here is a lazy `val`, so a screen that never opens the update sheet
 * never constructs an [UpdateChecker], and the subscription store is not read
 * until the subscriptions screen asks for it.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    // ---- data ----------------------------------------------------------
    val serverRepository: ServerRepository = ServerRepository()
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)

    val subscriptionRepository: SubscriptionRepository = SubscriptionRepository(
        parser = SubscriptionParser(),
        store = DataStoreSubscriptionStore(appContext),
        http = HttpFetcher(),
    )

    val subscriptionParser: SubscriptionParser = SubscriptionParser()

    // ---- updates -------------------------------------------------------
    val updateChecker: UpdateChecker = UpdateChecker(
        currentVersionName = BuildConfig.VERSION_NAME,
    )

    val updateDownloader: UpdateDownloader = UpdateDownloader(
        cacheDir = appContext.cacheDir.also { ensureCacheDir(it) },
    )

    val updateInstaller: UpdateInstaller = UpdateInstaller(appContext)

    /** Exposed for diagnostics and tests. */
    val updateHttp: UpdateHttp = UpdateHttp()

    private fun ensureCacheDir(dir: File) {
        if (!dir.exists()) dir.mkdirs()
    }
}

class DarkVvpnApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Prime the connectivity flag the subscription scheduler reads. It is
        // re-read on resume; this only avoids a bogus "metered" answer at startup.
        NetworkState.prime(this)
    }
}
