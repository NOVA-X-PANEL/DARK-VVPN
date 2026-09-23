package com.darkvvpn.app.data.update

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
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
        val url = release.apkUrl
            ?: return DownloadResult.Failure("This release has no APK to download.")

        val expected = release.apkSha256
        val target = File(cacheDir, "update-${release.tag}.apk")
        val partial = File(cacheDir, "$UPDATE_FILE_PREFIX${release.tag}.apk.part")

        cleanupPartials()
        _state.value = DownloadState.Running(0L, release.apkSizeBytes)

        var connection: HttpURLConnection? = null
        return withContext(Dispatchers.IO) {
            try {
                connection = (URL(url).openConnection() as? HttpURLConnection)
                    ?: return@withContext DownloadResult.Failure("The download URL is unusable.")

                // The release asset is on HTTPS; refuse anything downgraded.
                if (connection?.url?.protocol?.lowercase() != "https") {
                    return@withContext DownloadResult.Failure("The download was not served over HTTPS.")
                }

                connection?.apply {
                    instanceFollowRedirects = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "DARK-VVPN-updater")
                }

                val code = connection?.responseCode ?: -1
                if (code !in 200..299) {
                    val reason = "The download failed (HTTP $code)."
                    _state.value = DownloadState.Failed(reason)
                    return@withContext DownloadResult.Failure(reason)
                }

                val declaredLength = connection?.contentLengthLong?.takeIf { it > 0L }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L

                connection?.inputStream?.use { input ->
                    partial.outputStream().buffered().use { output ->
                        total = copyAndHash(input, output, digest, declaredLength, scope)
                    }
                }

                // An explicit cancel or scope teardown must not leave a file behind.
                scope.coroutineContext.ensureActive()

                if (declaredLength != null && total != declaredLength) {
                    partial.delete()
                    val reason = "The download was truncated ($total of $declaredLength bytes)."
                    _state.value = DownloadState.Failed(reason)
                    return@withContext DownloadResult.Failure(reason)
                }

                val actualHex = digest.digest().toHex()

                if (expected != null && actualHex != expected.lowercase()) {
                    // Wrong bytes: destroy them before anything else can use them.
                    partial.delete()
                    Log.e(TAG, "APK digest mismatch; discarded the download")
                    val reason = "The downloaded file failed its integrity check and was discarded."
                    _state.value = DownloadState.Failed(reason)
                    return@withContext DownloadResult.Failure(reason)
                }

                target.delete()
                if (!partial.renameTo(target)) {
                    partial.delete()
                    val reason = "The download could not be stored."
                    _state.value = DownloadState.Failed(reason)
                    return@withContext DownloadResult.Failure(reason)
                }

                val verified = expected != null
                Log.i(TAG, "APK downloaded and ${if (verified) "verified" else "unverified"} ($total bytes)")
                _state.value = DownloadState.Complete(target, verified)
                DownloadResult.Success(target, verified)
            } catch (e: IOException) {
                partial.delete()
                val reason = "The download was interrupted."
                _state.value = DownloadState.Failed(reason)
                DownloadResult.Failure(reason)
            } catch (e: Exception) {
                partial.delete()
                if (e is kotlinx.coroutines.CancellationException) {
                    _state.value = DownloadState.Idle
                    throw e
                }
                val reason = "The download failed."
                _state.value = DownloadState.Failed(reason)
                DownloadResult.Failure(reason)
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    /**
     * Streams [input] to [output] while feeding [digest], reporting progress.
     * The buffer is deliberately modest: an APK is tens of megabytes and there is
     * no reason to hold more than a chunk of it at a time.
     */
    private fun copyAndHash(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        declaredLength: Long?,
        scope: CoroutineScope,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var lastReported = 0L

        while (true) {
            if (!scope.coroutineContext.isActive) break
            val read = input.read(buffer)
            if (read <= 0) break

            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            total += read

            // Throttle UI updates to ~1% granularity: a StateFlow emission per
            // 8 KB chunk would spend more time recomposing than downloading.
            val step = declaredLength?.let { (it / 100).coerceAtLeast(1L) } ?: (256L * 1024L)
            if (total - lastReported >= step) {
                lastReported = total
                _state.update { current ->
                    if (current is DownloadState.Running) {
                        current.copy(bytesRead = total)
                    } else {
                        DownloadState.Running(total, declaredLength)
                    }
                }
            }
        }
        return total
    }

    private fun cleanupPartials() {
        runCatching {
            cacheDir.listFiles { f -> f.name.startsWith(UPDATE_FILE_PREFIX) }
                ?.forEach { it.delete() }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "UpdateDownloader"
        private const val UPDATE_FILE_PREFIX = "update-"
        private const val BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
