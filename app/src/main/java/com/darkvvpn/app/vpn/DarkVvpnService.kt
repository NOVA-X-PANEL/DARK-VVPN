package com.darkvvpn.app.vpn

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.darkvvpn.app.data.model.VpnStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * DARK VVPN's [VpnService] implementation.
 *
 * ── What this file actually does ─────────────────────────────────────────────
 * It performs the *platform* half of a VPN client: it becomes a foreground
 * service, asks the system for a tun interface with `Builder.establish()`,
 * routes all traffic through it, and tears everything down on disconnect.
 *
 * ── What it deliberately does NOT do ─────────────────────────────────────────
 * It does not forward packets. The skeleton measures the session and reports
 * counters so the UI is fully exercisable, but the read/write loop that pumps
 * frames between the tun fd and the upstream core is the integration point for
 * your tunnel core (Xray / sing-box / WireGuard) — see [startPumpLoop].
 *
 * SECURITY NOTE: when you add the real core, remember that the panel/API
 * credentials must be injected from encrypted storage and must never be written
 * to logcat; use [com.darkvvpn.app.util.Redact].
 */
class DarkVvpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statsJob: Job? = null
    private var pumpJob: Job? = null

    /** Address handed to the device inside the tunnel. */
    private val tunnelAddress = "10.8.0.2"
    private val tunnelPrefix = 32
    private val dnsServers = listOf("1.1.1.1", "1.0.0.1")

    private var connectedAtMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        VpnNotifications.ensureChannel(this)
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTunnel(
                serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty(),
                serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty(),
            )

            ACTION_STOP -> stopTunnel()
            else -> Log.w(TAG, "onStartCommand with unknown action: ${intent?.action}")
        }
        // START_STICKY would resurrect a tunnel the user explicitly stopped, so
        // the service is not sticky: the UI is the only thing allowed to start it.
        return Service.START_NOT_STICKY
    }

    // ---- tunnel lifecycle --------------------------------------------------

    private fun startTunnel(serverId: String, serverName: String) {
        if (tunInterface != null) {
            Log.w(TAG, "startTunnel ignored: already running")
            return
        }

        VpnConnectionManager.onConnecting()
        goForeground()

        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(MTU)
            .addAddress(tunnelAddress, tunnelPrefix)

        // Full-tunnel configuration: every destination goes through the tun.
        // A split-tunnel build narrows these routes (or excludes them with
        // addDisallowedApplication) instead.
        builder.addRoute("0.0.0.0", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { builder.addRoute("::", 0) }
        }
        dnsServers.forEach { runCatching { builder.addDnsServer(it) } }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            runCatching { builder.setBlocking(true) }
        }

        val descriptor = try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish() failed", t)
            null
        }

        if (descriptor == null) {
            Log.e(TAG, "establish() returned null")
            VpnConnectionManager.onError("The system refused the VPN interface (another VPN may be active).")
            teardownForeground()
            return
        }

        tunInterface = descriptor
        connectedAtMillis = System.currentTimeMillis()
        VpnConnectionManager.onConnected(serverId, serverName, connectedAtMillis)
        Log.i(TAG, "tunnel established for ${if (serverName.isBlank()) "unknown" else serverName}")

        startPumpLoop(descriptor)
        startStatsLoop()
    }

    private fun stopTunnel() {
        VpnConnectionManager.onDisconnecting()
        shutdown()
        VpnConnectionManager.onDisconnected()
        // Tear the service down itself: a START_NOT_STICKY service that has been
        // asked to stop must not linger waiting for the next command.
        stopSelf()
    }

    /** Releases the tun fd and stops both loops. Safe to call repeatedly. */
    private fun shutdown() {
        pumpJob?.cancel()
        statsJob?.cancel()
        pumpJob = null
        statsJob = null

        runCatching { tunInterface?.close() }
        tunInterface = null
        teardownForeground()
    }

    private fun goForeground() {
        // The two-argument form is deliberate: from API 34 the system reads the
        // foregroundServiceType from the manifest declaration, and on 29–33 it
        // is a no-op, so one call site stays correct on every supported release.
        startForeground(VpnNotifications.ID, VpnNotifications.connected(this))
    }

    private fun teardownForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- data plane --------------------------------------------------------

    /**
     * INTEGRATION POINT.
     *
     * A production core replaces the body of this loop with the real packet
     * pump: read frames from [descriptor] with `FileInputStream`, hand them to
     * the core, and write what comes back with `FileOutputStream`. The loop must
     * stay off the main thread and must stop promptly when the job is cancelled,
     * otherwise a reconnect leaks a second pump on the same fd.
     */
    private fun startPumpLoop(descriptor: ParcelFileDescriptor) {
        pumpJob = scope.launch {
            if (descriptor.fileDescriptor == null) return@launch
            // No forwarding in the skeleton: the fd is held open and the loop
            // parks. Replace with the read/write pump described above.
            while (isActive && tunInterface != null) {
                delay(1_000)
            }
        }
    }

    /**
     * Fills the Home-screen counters. In the skeleton the numbers are synthetic
     * so the UI has something to animate; a real core reports them from its own
     * accounting instead.
     */
    private fun startStatsLoop() {
        statsJob = scope.launch {
            var downTotal = 0L
            var upTotal = 0L
            var lastDown = 0L
            var lastUp = 0L
            var tick = 0L

            while (isActive && tunInterface != null) {
                delay(1_000)
                tick++

                val downRate = abs(Random.nextLong(180_000, 9_400_000))
                val upRate = abs(Random.nextLong(40_000, 2_100_000))
                downTotal += downRate
                upTotal += upRate
                lastDown = downRate
                lastUp = upRate

                val stats = VpnStats(
                    downloadBytesPerSec = lastDown,
                    uploadBytesPerSec = lastUp,
                    totalDownloadBytes = downTotal,
                    totalUploadBytes = upTotal,
                    sessionSeconds = tick,
                )
                VpnConnectionManager.pushStats(stats)
            }
        }
    }

    // ---- teardown ----------------------------------------------------------

    override fun onRevoke() {
        // Another VPN app took over, or the user revoked consent in Settings.
        Log.w(TAG, "onRevoke(): the system revoked our VPN consent")
        shutdown()
        VpnConnectionManager.onDisconnected("VPN access was revoked by the system.")
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        if (tunInterface == null) {
            VpnConnectionManager.onDisconnected()
        }
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DarkVvpnService"
        private const val SESSION_NAME = "DARK VVPN"
        private const val MTU = 1500

        const val ACTION_START = "com.darkvvpn.app.action.START"
        const val ACTION_STOP = "com.darkvvpn.app.action.STOP"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_SERVER_NAME = "extra_server_name"

        fun start(context: android.content.Context, serverId: String, serverName: String) {
            val intent = Intent(context, DarkVvpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_ID, serverId)
                putExtra(EXTRA_SERVER_NAME, serverName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, DarkVvpnService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
                .onFailure { context.stopService(Intent(context, DarkVvpnService::class.java)) }
        }
    }
}
