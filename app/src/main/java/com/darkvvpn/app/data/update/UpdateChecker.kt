package com.darkvvpn.app.data.update

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Asks GitHub for this project's latest release and decides whether to offer it.
 *
 * ── Trust model ──────────────────────────────────────────────────────────────
 * The update is fetched from **one** hard-coded repository over HTTPS and
 * nowhere else. A release is only offered when it carries an `.apk` asset, and
 * the asset's `sha256:` digest is carried through to
 * [UpdateDownloader] so the bytes that land on the device can be proved to be
 * the bytes GitHub published. Nothing here trusts a URL supplied by a user.
 *
 * ── Failing quietly ──────────────────────────────────────────────────────────
 * A failed check must never block the app or nag the user, so every error path
 * returns [UpdateCheckResult.Failed] or [UpdateCheckResult.UpToDate] and the
 * caller decides whether to surface it — the launch-time check ignores failures
 * entirely, while a manual "Check for updates" shows the reason.
 */
class UpdateChecker(
    private val currentVersionName: String,
    private val http: UpdateHttp = UpdateHttp(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param allowPrerelease also consider `-rc`/`-beta` releases.
     * @param skippedTag a tag the user asked not to be told about again.
     */
    fun check(
        allowPrerelease: Boolean = false,
        skippedTag: String? = null,
    ): UpdateCheckResult {
        val response = http.get(RELEASES_URL)
        if (response.error != null) {
            return UpdateCheckResult.Failed(response.error)
        }

        val releases = parseReleases(response.body.orEmpty())
        if (releases.isEmpty()) {
            return UpdateCheckResult.Failed("No published release was found.")
        }

        // Newest first, by our own comparator rather than by GitHub's ordering,
        // so a mis-tagged release cannot win on publish date alone.
        val newest = releases
            .filter { allowPrerelease || !it.isPrerelease }
            .maxWithOrNull { a, b -> VersionComparator.compare(a.versionName, b.versionName) }

        if (newest == null) {
            return UpdateCheckResult.UpToDate(latestTag = null)
        }

        val isNewer = VersionComparator.isNewer(newest.versionName, currentVersionName)
        if (!isNewer || newest.tag == skippedTag) {
            return UpdateCheckResult.UpToDate(latestTag = newest.tag)
        }

        if (newest.apkUrl == null) {
            // A newer tag with no APK is a packaging mistake, not an update.
            Log.w(TAG, "release ${newest.tag} has no APK asset; not offering it")
            return UpdateCheckResult.UpToDate(latestTag = newest.tag)
        }

        return UpdateCheckResult.UpdateAvailable(newest)
    }

    /** Exposed for tests: parses a releases array body without any network. */
    fun parseReleases(body: String): List<AppRelease> {
        // Annotated so the type is `List<JsonElement>` rather than the union of
        // the two branches, which the compiler widens to a nullable array.
        val array: List<kotlinx.serialization.json.JsonElement> = try {
            (json.parseToJsonElement(body) as? JsonArray) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        return array.mapNotNull { parseRelease(it as? JsonObject ?: return@mapNotNull null) }
    }

    private fun parseRelease(node: JsonObject): AppRelease? {
        val tag = node.str("tag_name") ?: return null
        val assets = node["assets"] as? JsonArray ?: JsonArray(emptyList())
        val apk = assets
            .mapNotNull { it as? JsonObject }
            .firstOrNull { obj ->
                val name = obj.str("name") ?: return@firstOrNull false
                name.endsWith(".apk", ignoreCase = true)
            }

        return AppRelease(
            tag = tag,
            versionName = tag.removePrefix("v").removePrefix("V"),
            name = node.str("name") ?: tag,
            notes = node.str("body").orEmpty(),
            publishedAtEpochMillis = node.str("published_at")?.let { parseIso(it) },
            isPrerelease = node["prerelease"]?.let {
                (it as? JsonPrimitive)?.booleanOrNull
            } ?: false,
            htmlUrl = node.str("html_url") ?: RELEASES_PAGE,
            apkUrl = apk?.str("browser_download_url"),
            apkAssetName = apk?.str("name"),
            apkSizeBytes = apk?.let { (it["size"] as? JsonPrimitive)?.longOrNull },
            apkSha256 = VersionComparator.normaliseDigest(apk?.str("digest")),
        )
    }

    private fun parseIso(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    companion object {
        /** The owner/repo this app updates itself from. */
        const val REPO = "NOVA-X-PANEL/DARK-VVPN"
        const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=20"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"
        private const val TAG = "UpdateChecker"
    }
}

/** One HTTP GET returning a body string, used by the checker. */
data class UpdateHttpResponse(
    val body: String?,
    val httpCode: Int? = null,
    val error: String? = null,
)

/**
 * Thin HTTPS GET client for the update check.
 *
 * Only `https` is dialled. The response is capped, because the endpoint is
 * remote and a broken proxy must not be able to stream until the app dies.
 */
class UpdateHttp(
    private val connectTimeoutMillis: Int = 12_000,
    private val readTimeoutMillis: Int = 15_000,
) {
    fun get(url: String): UpdateHttpResponse {
        val parsed = try {
            URL(url)
        } catch (_: Exception) {
            return UpdateHttpResponse(null, error = "Invalid update URL.")
        }
        if (parsed.protocol?.lowercase() != "https") {
            return UpdateHttpResponse(null, error = "The update endpoint must be HTTPS.")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (parsed.openConnection() as? HttpURLConnection)
                ?: return UpdateHttpResponse(null, error = "The update endpoint could not be reached.")
            connection.instanceFollowRedirects = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "DARK-VVPN-updater")

            val code = connection.responseCode
            if (code == 404) {
                return UpdateHttpResponse(null, code, "No release has been published yet.")
            }
            if (code == 403) {
                // Unauthenticated GitHub allows 60 requests/hour per IP.
                return UpdateHttpResponse(null, code, "The update service is rate-limited. Try again later.")
            }
            if (code !in 200..299) {
                return UpdateHttpResponse(null, code, "The update check failed (HTTP $code).")
            }

            val stream = connection.inputStream
                ?: return UpdateHttpResponse(null, code, "The update service returned no body.")
            val body = stream.use { readCapped(it) }
            UpdateHttpResponse(body, code)
        } catch (e: IOException) {
            UpdateHttpResponse(null, error = "Could not reach the update service.")
        } catch (e: Exception) {
            UpdateHttpResponse(null, error = "The update check failed.")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun readCapped(stream: java.io.InputStream): String {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < MAX_BODY_BYTES) {
            val read = stream.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, minOf(read, MAX_BODY_BYTES - total))
            total += read
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        /** 2 MB: a releases list is far smaller; this only bounds a runaway stream. */
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}
