package com.darkvvpn.app.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.darkvvpn.app.xray.XrayConfigBuilder
import com.darkvvpn.app.xray.XrayCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DARK VVPN's [VpnService]: this is where the tunnel becomes real.
 *
 * ── How the traffic actually flows ───────────────────────────────────────────
 *
 *   app traffic
 *        │  the system routes it here because of the tun routes below
 *        ▼
 *   tun interface  ──fd──►  XrayCore.startLoop(config, fd)
 *        │                        │
 *        │                        ▼
 *        │                 Xray's gVisor netstack terminates the TCP/IP flows
 *        │                        │
 *        │                        ▼
 *        └──────────────    the protocol outbound dials the node
 *
 * There is no tun2socks process and no packet pump in Kotlin: the core owns the
 * tun descriptor and the whole network stack behind it. This service's job is
 * therefore narrow and important — open the interface, hand over the descriptor,
 * keep the process alive, and tear all of it down cleanly in the right order.
 *
 * ── The one setting that makes or breaks it ──────────────────────────────────
 * `addDisallowedApplication(packageName)`. Without it the core's *outbound*
 * sockets are themselves routed into the tun, the traffic loops back into the
 * netstack, and the tunnel connects while passing nothing. Excluding our own
 * package is what breaks that cycle.
 *
 * ── Shutdown order ───────────────────────────────────────────────────────────
 * Stop the core, then close the interface, then stop the service. Closing the
 * tun fd first leaves the core reading a dead descriptor; stopping the service
 * first can leave the core holding the fd with no owner. The order in
 * [shutdownTunnel] is deliberate.
 */
class DarkVvpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var configJob: Job? = null
    private var statsJob: Job? = null
    private var tunnelJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectedAtMillis: Long = 0L

    /** Running totals, accumulated across the per-tick deltas the core reports. */
    private val totals = MutableStateFlow(VpnStats.Empty)

    override fun onCreate() {
        super.onCreate()
        VpnNotifications.ensureChannel(this)
        // Surface core warnings (dial failures, TLS errors) in the UI log stream.
        XrayCore.statusListener = { status ->
            VpnConnectionManager.onCoreStatus(status)
        }
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                startTunnel(serverId, serverName, config)
            }

            ACTION_STOP -> stopTunnel()
            else -> Log.w(TAG, "onStartCommand with unknown action: ${intent?.action}")
        }
        // START_STICKY would resurrect a tunnel the user explicitly stopped, so
        // the service is not sticky: the UI is the only thing allowed to start it.
        return Service.START_NOT_STICKY
    }

    // ==================================================================
    // Start
    // ==================================================================

    private fun startTunnel(serverId: String, serverName: String, config: String) {
        if (tunInterface != null || tunnelJob?.isActive == true) {
            Log.w(TAG, "startTunnel ignored: already running")
            return
        }
        if (config.isBlank()) {
            VpnConnectionManager.onError("No tunnel configuration was supplied.")
            return
        }

        VpnConnectionManager.onConnecting()
        goForeground()

        tunnelJob = scope.launch {
            val interfaceDescriptor = try {
                establishTunInterface()
            } catch (t: Throwable) {
                Log.e(TAG, "failed to establish the tun interface", t)
                null
            }

            if (interfaceDescriptor == null) {
                VpnConnectionManager.onError(
                    "Android refused the VPN interface. Another VPN may be active.",
                )
                teardownForeground()
                stopSelf()
                return@launch
            }
            tunInterface = interfaceDescriptor

            // Bring the core up off the main thread: startLoop parses the config
            // and builds the netstack, which is not a main-thread operation.
            val failure = withContext(Dispatchers.IO) {
                XrayCore.ensureInitialized(applicationContext)
                XrayCore.start(config, interfaceDescriptor.fd)
            }

            if (failure != null) {
                VpnConnectionManager.onError(failure)
                shutdownTunnel()
                stopSelf()
                return@launch
            }

            connectedAtMillis = System.currentTimeMillis()
            VpnConnectionManager.onConnected(serverId, serverName, connectedAtMillis)
            Log.i(TAG, "tunnel established on $serverName")

            startStatsLoop()
        }
    }

    /**
     * Opens the tun interface with the routes, resolver and MTU the core's config
     * expects.
     *
     * Every value here has a counterpart in [XrayConfigBuilder] — the address,
     * the prefix and the MTU are shared constants on purpose, because a tunnel
     * whose two halves disagree establishes successfully and then silently drops
     * every packet, which is the hardest possible failure to diagnose.
     */
    private fun establishTunInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(XrayConfigBuilder.TUN_MTU)
            .addAddress(XrayConfigBuilder.TUN_CLIENT_ADDRESS, XrayConfigBuilder.TUN_PREFIX_LENGTH)

        // Full tunnel: every destination, v4 and v6, goes through the tun.
        builder.addRoute("0.0.0.0", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { builder.addRoute("::", 0) }
        }

        XrayConfigBuilder.TUN_DNS_SERVERS.forEach { server ->
            runCatching { builder.addDnsServer(server) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // THE critical line: our own package must stay outside the tunnel, or the
        // core's outbound sockets are routed back into it and nothing flows.
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Log.e(TAG, "could not exclude our own package from the tunnel", it) }

        return try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish() failed", t)
            null
        }
    }

    // ==================================================================
    // Stop
    // ==================================================================

    private fun stopTunnel() {
        VpnConnectionManager.onDisconnecting()
        // The core is stopped outside the coroutine scope so the teardown still
        // happens if the scope is being cancelled (a normal race on stop).
        shutdownTunnel()
        VpnConnectionManager.onDisconnected()
        stopSelf()
    }

    /**
     * Tears everything down in the only order that is safe: core first, then the
     * interface, then the foreground notification. Safe to call repeatedly —
     * [onRevoke] and [onDestroy] both do.
     */
    private fun shutdownTunnel() {
        tunnelJob?.cancel()
        tunnelJob = null
        statsJob?.cancel()
        statsJob = null
        configJob?.cancel()
        configJob = null

        if (XrayCore.isRunning) {
            XrayCore.stop()
        }

        runCatching { tunInterface?.close() }
            .onFailure { Log.w(TAG, "closing the tun interface reported an error", it) }
        tunInterface = null

        totals.value = VpnStats.Empty
        teardownForeground()
    }

    // ==================================================================
    // Stats
    // ==================================================================

    /**
     * Samples the core's own traffic counters once a second.
     *
     * `queryAllOutboundTrafficStats` returns and resets the counters, so each
     * reading is a delta over the previous tick — exactly what a throughput
     * figure is. The totals are accumulated here because the core forgets them.
     */
    private fun startStatsLoop() {
        statsJob = scope.launch {
            var downTotal = 0L
            var upTotal = 0L
            var tick = 0L

            while (isActive && tunInterface != null && XrayCore.isRunning) {
                delay(1_000)
                tick++

                val counters = XrayCore.drainTrafficCounters()
                var downDelta = 0L
                var upDelta = 0L
                counters.forEach { (key, value) ->
                    val (_, direction) = key
                    when (direction) {
                        "downlink" -> downDelta += value
                        "uplink" -> upDelta += value
                    }
                }

                downTotal += downDelta
                upTotal += upDelta

                VpnConnectionManager.pushStats(
                    VpnStats(
                        downloadBytesPerSec = downDelta,
                        uploadBytesPerSec = upDelta,
                        totalDownloadBytes = downTotal,
                        totalUploadBytes = upTotal,
                        sessionSeconds = tick,
                    ),
                )
            }
        }
    }

    // ==================================================================
    // Foreground
    // ==================================================================

    private fun goForeground() {
        // The two-argument form is deliberate: from API 34 the system reads the
        // foregroundServiceType from the manifest declaration, and on 29–33 it is
        // a no-op, so one call site stays correct on every supported release.
        val notification = VpnNotifications.connected(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                VpnNotifications.ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(VpnNotifications.ID, notification)
        }
    }

    private fun teardownForeground() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    // ==================================================================
    // Platform callbacks
    // ==================================================================

    override fun onRevoke() {
        // Another VPN app took over, or the user revoked consent in Settings.
        Log.w(TAG, "onRevoke(): the system revoked our VPN consent")
        shutdownTunnel()
        VpnConnectionManager.onDisconnected("VPN access was revoked by the system.")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdownTunnel()
        scope.cancel()
        XrayCore.statusListener = null
        if (!XrayCore.isRunning) {
            VpnConnectionManager.onDisconnected()
        }
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DarkVvpnService"
        private const val SESSION_NAME = "DARK VVPN"

        const val ACTION_START = "com.darkvvpn.app.action.START"
        const val ACTION_STOP = "com.darkvvpn.app.action.STOP"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_SERVER_NAME = "extra_server_name"

        /**
         * The rendered Xray config travels in the intent rather than being rebuilt
         * in the service: the service must not reach into repositories, and one
         * builder call site means the config the UI previewed is the config that
         * runs.
         */
        const val EXTRA_CONFIG = "extra_config"

        fun start(context: Context, serverId: String, serverName: String, config: String) {
            val intent = Intent(context, DarkVvpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_ID, serverId)
                putExtra(EXTRA_SERVER_NAME, serverName)
                putExtra(EXTRA_CONFIG, config)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DarkVvpnService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
                .onFailure { context.stopService(Intent(context, DarkVvpnService::class.java)) }
        }
    }
}
