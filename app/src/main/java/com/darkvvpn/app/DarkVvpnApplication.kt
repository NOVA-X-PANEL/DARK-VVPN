package com.darkvvpn.app

import android.app.Application
import android.content.Context
import com.darkvvpn.app.data.repository.ServerRepository
import com.darkvvpn.app.data.repository.SettingsRepository

/**
 * Hand-rolled service locator.
 *
 * DARK VVPN is small enough that a DI framework would be more ceremony than
 * value; everything hangs off [DarkVvpnApplication.container] and is reached
 * through ViewModel factories.
 */
class AppContainer(context: Context) {
    val serverRepository: ServerRepository = ServerRepository()
    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)
}

class DarkVvpnApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
