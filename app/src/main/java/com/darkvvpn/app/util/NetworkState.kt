package com.darkvvpn.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * A tiny process-wide flag for "is the active network unmetered?".
 *
 * The subscriptions screen needs this to honour the user's
 * "refresh over Wi-Fi only" preference, and the check is needed from a
 * ViewModel that must not hold a `Context`. [NetworkState] is primed once from
 * the application, so the ViewModel reads a plain boolean.
 */
object NetworkState {

    @Volatile
    private var unmetered: Boolean = false

    @Volatile
    private var primed: Boolean = false

    /** Defaults to `false` (treat as metered) until [prime] has run. */
    val isUnmetered: Boolean get() = unmetered

    val hasBeenPrimed: Boolean get() = primed

    /** Reads connectivity once; safe to call repeatedly. */
    fun prime(context: Context) {
        unmetered = queryUnmetered(context)
        primed = true
    }

    /** Re-reads connectivity, e.g. after the app returns to the foreground. */
    fun refresh(context: Context) {
        unmetered = queryUnmetered(context)
    }

    /**
     * Reads connectivity, treating any failure as "metered".
     *
     * Every call is wrapped: this runs from a lifecycle observer, and a throw there
     * reaches the caller's scope. "Metered" is also the safe answer — it means an
     * automatic refresh is skipped rather than run on a connection that might be
     * mobile data.
     */
    private fun queryUnmetered(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false

        // A VPN transport with the underlying Wi-Fi still unmetered counts; the
        // capability list carries both, so check for the absence of metering
        // rather than the presence of a specific transport.
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        hasInternet && notMetered
    } catch (t: Throwable) {
        false
    }
}
