package com.darkvvpn.app.data.update

/**
 * A release as the update checker sees it. Deliberately a small, UI-shaped
 * projection of the GitHub release payload rather than the raw JSON.
 */
data class AppRelease(
    val tag: String,
    val versionName: String,
    val name: String,
    val notes: String,
    val publishedAtEpochMillis: Long?,
    val isPrerelease: Boolean,
    val htmlUrl: String,

    /** Direct download URL of the first `.apk` asset, if the release has one. */
    val apkUrl: String?,
    val apkAssetName: String?,
    val apkSizeBytes: Long?,

    /**
     * `sha256:<hex>` as GitHub reports it for release assets. Used to verify the
     * downloaded file; `null` when the release predates the digest field, in
     * which case the download is size-checked only and the UI says so.
     */
    val apkSha256: String?,
)

/** Result of an update lookup. */
sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: AppRelease) : UpdateCheckResult

    /** The installed build is current. [latestTag] is `null` when nothing was found. */
    data class UpToDate(val latestTag: String?) : UpdateCheckResult

    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * Semantic-version comparison for the `vMAJOR.MINOR.PATCH[-prerelease]` tags this
 * project publishes.
 *
 * Implemented by hand rather than pulled in as a dependency because the rules
 * that matter here are narrow and worth stating explicitly:
 *
 *  - the leading `v` is optional (`v1.2.3` and `1.2.3` are the same version);
 *  - numeric identifiers compare numerically, so `1.10.0` is newer than `1.9.0`
 *    (a plain string compare gets this wrong);
 *  - a missing component is zero, so `1.2` == `1.2.0`;
 *  - a pre-release is **older** than its release, so `1.0.0-rc1` < `1.0.0`;
 *  - between pre-releases, numeric identifiers compare numerically and
 *    alphanumeric ones lexically, per semver's rule 11.
 *
 * Returns a negative number when [a] is older than [b], positive when newer,
 * and zero when they are the same version.
 */
object VersionComparator {

    fun compare(a: String, b: String): Int {
        val left = parse(a)
        val right = parse(b)

        for (i in 0 until maxOf(left.numbers.size, right.numbers.size)) {
            val diff = (left.numbers.getOrNull(i) ?: 0).compareTo(right.numbers.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }

        // Same numeric core: a release outranks a pre-release of itself.
        if (left.preRelease == null && right.preRelease == null) return 0
        if (left.preRelease == null) return 1
        if (right.preRelease == null) return -1
        return comparePreRelease(left.preRelease, right.preRelease)
    }

    /** True when [candidate] is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun comparePreRelease(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(i) ?: return -1 // fewer identifiers = lower precedence
            val r = right.getOrNull(i) ?: return 1
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val diff = when {
                ln != null && rn != null -> ln.compareTo(rn)
                // Numeric identifiers always have lower precedence than alphanumeric.
                ln != null -> -1
                rn != null -> 1
                else -> l.compareTo(r, ignoreCase = true)
            }
            if (diff != 0) return diff
        }
        return 0
    }

    private data class Parsed(val numbers: List<Int>, val preRelease: String?)

    private fun parse(raw: String): Parsed {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        // Build metadata is ignored for precedence (semver rule 10).
        val withoutBuild = trimmed.substringBefore('+')
        val core = withoutBuild.substringBefore('-')
        val pre = withoutBuild.substringAfter('-', "").takeIf { it.isNotBlank() }

        val numbers = core.split('.')
            .mapNotNull { part ->
                // Tolerate `1.2.3-beta4` style tails and non-numeric junk.
                part.takeWhile { it.isDigit() }.toIntOrNull()
            }
        return Parsed(numbers = numbers, preRelease = pre)
    }

    /** `sha256:abcd…` and a bare hex digest both yield the lowercase hex. */
    fun normaliseDigest(digest: String?): String? {
        if (digest.isNullOrBlank()) return null
        val hex = digest.substringAfter(':', digest).trim().lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    }
}
