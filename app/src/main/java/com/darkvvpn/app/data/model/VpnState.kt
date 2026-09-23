package com.darkvvpn.app.data.model

/**
 * The finite states of the tunnel, as observed by the UI.
 *
 * The transition graph is deliberately small:
 *
 *   Disconnected → Connecting → Connected → Disconnecting → Disconnected
 *                       ↘ Error ↗
 *
 * Anything else (e.g. Connected → Connected) is a no-op, which lets the UI call
 * [connect] / [disconnect] freely without guarding every call site.
 */
sealed interface VpnState {

    /** No tunnel. [lastError] carries the reason for the most recent failure. */
    data class Disconnected(val lastError: String? = null) : VpnState

    /** `VpnService.prepare()` returned an Intent and we are waiting for consent. */
    data object AwaitingPermission : VpnState

    /** The system granted consent; the core is starting. */
    data object Connecting : VpnState

    data class Connected(
        val serverId: String,
        val serverName: String,
        val sinceEpochMillis: Long,
    ) : VpnState

    data object Disconnecting : VpnState

    data class Error(val message: String) : VpnState

    val isBusy: Boolean
        get() = this is Connecting || this is Disconnecting || this is AwaitingPermission

    val isConnected: Boolean
        get() = this is Connected
}

/** Live counters for the session banner. */
data class VpnStats(
    val downloadBytesPerSec: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val sessionSeconds: Long = 0L,
) {
    companion object {
        val Empty = VpnStats()
    }
}
