package com.darkvvpn.app.xray

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The process's handle on Xray-core.
 *
 * ── What this class is ───────────────────────────────────────────────────────
 * A thin, thread-safe wrapper over the Go library (`libv2ray`, i.e. Xray-core
 * built with gomobile). It owns the three things the core needs and nothing
 * else: a one-time environment initialisation, a single controller for the
 * lifetime of the process, and the start/stop pair.
 *
 * ── Why one controller, not one per connection ───────────────────────────────
 * `newCoreController` allocates Go state that the core does not tolerate being
 * created twice concurrently; v2rayNG hit exactly this and documents it. A
 * single process-wide controller with a serialised start/stop is both simpler
 * and the shape the library is proven to work in.
 *
 * ── Why the tun fd is an int ─────────────────────────────────────────────────
 * `startLoop(config, tunFd)` takes the descriptor of the tun interface the app
 * already opened. The core wraps it in its own gVisor netstack and terminates
 * the TCP/IP flows arriving there, forwarding each one through the configured
 * outbound. There is no tun2socks process in this app; that work lives inside
 * the core, which is why the fd is the only thing that crosses the boundary.
 *
 * SECURITY: the config passed to [start] contains the node's credentials. It is
 * never logged here — only the outcome is.
 */
object XrayCore {

    private const val TAG = "XrayCore"

    /** Serialises start/stop, which the core does not allow to overlap. */
    private val lifecycleLock = Any()

    @Volatile
    private var controller: CoreController? = null

    @Volatile
    private var environmentReady = false

    private val isRunningFlag = AtomicBoolean(false)

    /** Receives status lines the core emits, including dial failures. */
    @Volatile
    var statusListener: ((String) -> Unit)? = null

    val isRunning: Boolean get() = isRunningFlag.get()

    /**
     * Prepares the Go runtime and Xray's environment. Idempotent and safe to
     * call from any thread; the work happens once per process.
     *
     * The asset path is a private directory the core may write to. The XUDP base
     * key is derived from `ANDROID_ID` — it seeds cross-user ID obfuscation, so
     * it must be stable per device and must never be a constant.
     */
    fun ensureInitialized(context: Context) {
        if (environmentReady) return
        synchronized(lifecycleLock) {
            if (environmentReady) return
            ensureController()
            val appContext = context.applicationContext
            // gomobile needs a Context to reach back into Java (assets, logging).
            Seq.setContext(appContext)
            Libv2ray.initCoreEnv(assetPath(appContext), xudpKey(appContext))
            environmentReady = true
            Log.i(TAG, "core environment ready")
        }
    }

    /**
     * Starts the core against [config], taking ownership of [tunFd] for as long
     * as it runs.
     *
     * @return `null` on success, or a message suitable for the user.
     */
    fun start(config: String, tunFd: Int): String? {
        synchronized(lifecycleLock) {
            val core = controller ?: return "The tunnel core is not initialised."

            if (isRunningFlag.get()) {
                // Reconnecting means tearing the old core down first; leaving it
                // running would bind the same tun fd twice.
                Log.w(TAG, "core already running; stopping it first")
                stopInternal()
            }

            return try {
                core.startLoop(config, tunFd)
                if (!core.isRunning) {
                    // startLoop returned without throwing but the core is not up,
                    // which in practice means the config was rejected.
                    "The core rejected the configuration."
                } else {
                    isRunningFlag.set(true)
                    Log.i(TAG, "core started on tun fd $tunFd")
                    null
                }
            } catch (t: Throwable) {
                // The Go layer surfaces config and bind failures as exceptions;
                // the message is the only useful diagnostic we get.
                val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                Log.e(TAG, "core failed to start: $message")
                isRunningFlag.set(false)
                message
            }
        }
    }

    /** Stops the core. Safe to call when it is already stopped. */
    fun stop() {
        synchronized(lifecycleLock) { stopInternal() }
    }

    private fun stopInternal() {
        val core = controller ?: return
        try {
            if (core.isRunning) core.stopLoop()
        } catch (t: Throwable) {
            // A failure here means the core is already gone; nothing to recover.
            Log.w(TAG, "stopLoop reported ${t.javaClass.simpleName}")
        } finally {
            isRunningFlag.set(false)
        }
    }

    /** `Xray 25.x.x` — shown in Settings so the user knows what is dialling. */
    fun version(): String = try {
        Libv2ray.checkVersionX()
    } catch (t: Throwable) {
        "unknown"
    }

    /**
     * Real traffic counters, straight from the core's stats manager, in the form
     * `tag,direction,value;` per counter. Reading them **resets** them, which is
     * exactly what a per-tick sampler wants.
     */
    fun drainTrafficCounters(): Map<Pair<String, String>, Long> {
        val raw = try {
            controller?.queryAllOutboundTrafficStats()
        } catch (t: Throwable) {
            Log.w(TAG, "traffic stats unavailable")
            null
        }
        if (raw.isNullOrBlank()) return emptyMap()

        val out = HashMap<Pair<String, String>, Long>()
        raw.split(';').forEach { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return@forEach
            val value = parts[2].trim().toLongOrNull() ?: return@forEach
            out[parts[0].trim() to parts[1].trim().lowercase()] = value
        }
        return out
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun ensureController() {
        if (controller == null) {
            controller = Libv2ray.newCoreController(StatusCallback())
        }
    }

    /** Where the core may write its own files (geo data overrides, cache). */
    private fun assetPath(context: Context): String = try {
        context.getDir("xray-assets", Context.MODE_PRIVATE).absolutePath
    } catch (t: Throwable) {
        ""
    }

    /**
     * Stable per-device seed for XUDP's per-user key.
     *
     * `ANDROID_ID` is reset on factory reset and differs per app signing key, so
     * it identifies the install rather than the person — the right granularity
     * for a seed. The bytes are padded to the 32 the core expects and
     * URL-safe-base64 encoded so no padding or `+`/`/` reaches the Go side.
     */
    private fun xudpKey(context: Context): String = try {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty().ifBlank { "darkvvpn" }
        val bytes = androidId.toByteArray(Charsets.UTF_8)
        val sized = ByteArray(32)
        System.arraycopy(bytes, 0, sized, 0, minOf(bytes.size, sized.size))
        Base64.encodeToString(sized, Base64.NO_PADDING or Base64.URL_SAFE)
    } catch (t: Throwable) {
        ""
    }

    /**
     * Callbacks the core invokes back into Java.
     *
     * All three return `0` to mean "continue". A non-zero return from `startup`
     * or `shutdown` tells the core to abort, which is never what we want.
     */
    private class StatusCallback : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "core reported startup")
            return 0L
        }

        override fun shutdown(): Long {
            Log.i(TAG, "core reported shutdown")
            isRunningFlag.set(false)
            return 0L
        }

        override fun onEmitStatus(p0: Long, p1: String): Long {
            // The core routes dial failures and warnings through here. The text
            // never contains credentials, so it is safe to forward and log.
            if (p1.isNotBlank()) {
                Log.i(TAG, "core status: $p1")
                statusListener?.invoke(p1)
            }
            return 0L
        }
    }
}
