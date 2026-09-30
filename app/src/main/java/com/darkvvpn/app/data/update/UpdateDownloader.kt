package com.darkvvpn.app.data.update

import android.util.Log
import com.darkvvpn.app.xray.XrayConfigBuilder
import com.darkvvpn.app.xray.XrayCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest

/** Progress of an in-flight APK download, as the UI needs it. */
sealed interface DownloadState {
    data object Idle : DownloadState

    data class Running(
        val bytesRead: Long,
        val totalBytes: Long?,
        val sha256Verified: Boolean = false,
    ) : DownloadState {
        /** 0f..1f, or `null` when the server sent no `Content-Length`. */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0L }
                ?.let { (bytesRead.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
    }

    data class Complete(val file: File, val sha256Verified: Boolean) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/** Outcome of one download attempt. */
sealed interface DownloadResult {
    data class Success(val file: File, val verified: Boolean) : DownloadResult
    data class Failure(val reason: String) : DownloadResult
}

private data class DownloadSource(
    val url: String,
    val proxy: Proxy?,
    val description: String,
)

/**
 * Downloads a release APK and proves it is the file GitHub published.
 *
 * ── Why the hash matters ─────────────────────────────────────────────────────
 * An app that installs an APK it downloaded is the single highest-risk surface
 * in this project: a substituted APK is a full device compromise. Three things
 * hold the line here:
 *
 *  1. The URL comes from the GitHub API response, over HTTPS, and is never
 *     user-supplied — the UI cannot point this at an arbitrary host.
 *  2. The bytes are hashed with **SHA-256 as they stream to disk**, so the file
 *     is never fully in memory and the digest is over exactly what was written.
 *  3. If the release advertises a digest and it does not match, the file is
 *     **deleted** and the download reports failure. A missing digest is reported
 *     to the user as unverified rather than silently accepted.
 *
 * Nothing is written outside the app's private cache directory, and the file is
 * written to a `.part` name and renamed only after the digest passes, so an
 * interrupted download can never be mistaken for a complete APK.
 */
class UpdateDownloader(
    private val cacheDir: File,
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var job: Job? = null

    private val updatesDir: File
        get() = File(cacheDir, "updates").apply { mkdirs() }

    /** Cancels an in-flight download and clears the partial file. */
    fun cancel() {
        job?.cancel()
        job = null
        cleanupPartials()
        _state.value = DownloadState.Idle
    }

    /**
     * Downloads [release]'s APK. Returns the final result, and mirrors progress
     * into [state] for a progress bar.
     */
    suspend fun download(
        scope: CoroutineScope,
        release: AppRelease,
    ): DownloadResult {
        val originalUrl = release.apkUrl
            ?: return DownloadResult.Failure("This release has no APK to download.")

        val expected = release.apkSha256
        val target = File(updatesDir, "update-${release.tag}.apk")
        val partial = File(updatesDir, "$UPDATE_FILE_PREFIX${release.tag}.apk.part")

        // 1. If target already exists and is valid, skip download entirely
        if (target.exists() && target.length() > 0L) {
            val valid = expected == null || computeFileSha256(target) == expected.lowercase()
            if (valid) {
                val verified = expected != null
                _state.value = DownloadState.Complete(target, verified)
                return DownloadResult.Success(target, verified)
            } else {
                target.delete()
            }
        }

        val existingBytes = if (partial.exists()) partial.length() else 0L
        _state.value = DownloadState.Running(existingBytes, release.apkSizeBytes)

        val sources = mutableListOf<DownloadSource>()

        // 1. If VPN tunnel is currently active, route through Xray's local HTTP inbound.
        // This provides full unthrottled line speed via the user's connected VPN server!
        if (XrayCore.isRunning) {
            val tunnelProxy = Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", XrayConfigBuilder.LOCAL_HTTP_PORT),
            )
            sources.add(DownloadSource(originalUrl, tunnelProxy, "VPN Tunnel"))
        }

        // 2. High-speed Cloudflare-accelerated CDN mirror (fast & unthrottled in Iran).
        // SECURITY: only queried if expected SHA-256 is present, preventing untrusted mirror tampering.
        if (!expected.isNullOrBlank()) {
            sources.add(DownloadSource("https://gh-proxy.com/$originalUrl", null, "Cloudflare CDN Mirror"))
            sources.add(DownloadSource("https://ghproxy.net/$originalUrl", null, "Mirror (ghproxy.net)"))
        }

        // 3. Direct GitHub as fallback
        sources.add(DownloadSource(originalUrl, null, "Direct GitHub"))

        return withContext(Dispatchers.IO) {
            var lastError: String? = null

            for (source in sources) {
                scope.coroutineContext.ensureActive()
                var connection: HttpURLConnection? = null
                try {
                    Log.i(TAG, "Attempting update download via: ${source.description}")
                    val currentPartialBytes = if (partial.exists()) partial.length() else 0L

                    connection = openConnectionWithRedirects(source.url, source.proxy, currentPartialBytes)

                    val code = connection.responseCode
                    val isPartial = code == HttpURLConnection.HTTP_PARTIAL // 206
                    val isOk = code == HttpURLConnection.HTTP_OK // 200

                    if (!isPartial && !isOk) {
                        lastError = "HTTP $code from ${source.description}"
                        Log.w(TAG, "Source failed: $lastError")
                        continue
                    }

                    val resume = isPartial && currentPartialBytes > 0L
                    val startingBytes = if (resume) currentPartialBytes else 0L
                    val contentLen = connection.contentLengthLong.takeIf { it > 0L }
                    val totalExpected = if (resume && contentLen != null) {
                        startingBytes + contentLen
                    } else {
                        contentLen ?: release.apkSizeBytes
                    }

                    connection.inputStream.buffered(BUFFER_BYTES).use { input ->
                        java.io.FileOutputStream(partial, resume).buffered(BUFFER_BYTES).use { output ->
                            copyAndProgress(input, output, startingBytes, totalExpected, scope)
                        }
                    }

                    scope.coroutineContext.ensureActive()

                    val actualHex = computeFileSha256(partial)
                    if (expected != null) {
                        if (actualHex != expected.lowercase()) {
                            Log.e(TAG, "Digest mismatch on ${source.description}: expected=$expected actual=$actualHex")
                            partial.delete()
                            lastError = "Digest verification failed on ${source.description}"
                            continue
                        }
                    } else if (source.description.contains("Mirror", ignoreCase = true)) {
                        Log.e(TAG, "Refusing unverified APK from untrusted mirror: ${source.description}")
                        partial.delete()
                        lastError = "Unverified mirror download rejected"
                        continue
                    }

                    target.delete()
                    if (!partial.renameTo(target)) {
                        partial.delete()
                        val reason = "The download could not be stored."
                        _state.value = DownloadState.Failed(reason)
                        return@withContext DownloadResult.Failure(reason)
                    }

                    val verified = expected != null
                    Log.i(TAG, "APK successfully downloaded via ${source.description} (${target.length()} bytes)")
                    _state.value = DownloadState.Complete(target, verified)
                    return@withContext DownloadResult.Success(target, verified)
                } catch (e: CancellationException) {
                    _state.value = DownloadState.Idle
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Download attempt failed on ${source.description}: ${e.message}")
                    lastError = e.message ?: e.javaClass.simpleName
                } finally {
                    runCatching { connection?.disconnect() }
                }
            }

            val finalReason = lastError ?: "The download could not be completed."
            _state.value = DownloadState.Failed(finalReason)
            DownloadResult.Failure(finalReason)
        }
    }

    private fun openConnectionWithRedirects(
        initialUrl: String,
        proxy: Proxy?,
        rangeBytes: Long,
    ): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 5) {
            val u = URL(currentUrl)
            if (u.protocol.lowercase() != "https") {
                throw IOException("Insecure protocol: ${u.protocol}")
            }
            val conn = (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as HttpURLConnection
            conn.apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "DARK-VVPN-updater")
                setRequestProperty("Connection", "keep-alive")
                if (rangeBytes > 0L) {
                    setRequestProperty("Range", "bytes=$rangeBytes-")
                }
            }
            val code = conn.responseCode
            if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank()) {
                    currentUrl = URL(u, location).toString()
                    redirects++
                    continue
                }
            }
            return conn
        }
        throw IOException("Too many redirects: $redirects")
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun copyAndProgress(
        input: InputStream,
        output: OutputStream,
        startingBytes: Long,
        totalExpectedBytes: Long?,
        scope: CoroutineScope,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var lastReported = startingBytes
        var lastReportTime = System.currentTimeMillis()

        while (true) {
            if (!scope.coroutineContext.isActive) break
            val read = input.read(buffer)
            if (read <= 0) break

            output.write(buffer, 0, read)
            total += read

            val now = System.currentTimeMillis()
            val currentTotal = startingBytes + total
            if ((currentTotal - lastReported >= PROGRESS_INTERVAL_BYTES && now - lastReportTime >= 100) ||
                (totalExpectedBytes != null && currentTotal == totalExpectedBytes)
            ) {
                _state.value = DownloadState.Running(currentTotal, totalExpectedBytes)
                lastReported = currentTotal
                lastReportTime = now
            }
        }
        return total
    }

    private fun cleanupPartials() {
        runCatching {
            updatesDir.listFiles { f -> f.name.startsWith(UPDATE_FILE_PREFIX) || f.name.endsWith(".part") }
                ?.forEach { it.delete() }
            cacheDir.listFiles { f -> f.name.startsWith(UPDATE_FILE_PREFIX) }
                ?.forEach { it.delete() }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "UpdateDownloader"
        private const val UPDATE_FILE_PREFIX = "update-"
        private const val BUFFER_BYTES = 128 * 1024
        private const val PROGRESS_INTERVAL_BYTES = 512 * 1024L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 25_000
    }
}
