package com.darkvvpn.app.data.update

/**
 * Decides whether the in-app update badge should be shown.
 *
 * ── Why this is a separate, pure function ────────────────────────────────────
 * The badge is the app's only standing notice that a newer release exists, so it
 * has to be correct in three awkward states that are easy to get wrong and
 * annoying to reproduce by hand:
 *
 *  - **Offline.** The badge must appear from the last known release without a
 *    network call, or a user who checks GitHub once and then goes offline never
 *    sees it.
 *  - **After installing.** The known release is still stored, but the installed
 *    version has caught up, so the badge must vanish on its own without anything
 *    clearing state.
 *  - **After skipping.** The user said "not this one"; the badge must stay hidden
 *    for that tag and reappear for the next.
 *
 * Keeping the rule here means each of those is a one-line test instead of a
 * manual scenario, and the three call sites (launch, manual check, and the
 * settings banner) cannot drift apart.
 */
object UpdateBadgePolicy {

    /**
     * @param knownVersion the newest release tag the app has seen, or `null`.
     * @param installedVersion the running build's version name.
     * @param skippedTag a tag the user asked not to be shown, or `null`.
     * @return `true` when the badge should be visible.
     */
    fun shouldShow(
        knownVersion: String?,
        installedVersion: String?,
        skippedTag: String?,
    ): Boolean {
        if (knownVersion.isNullOrBlank()) return false
        if (installedVersion.isNullOrBlank()) return false
        // A skipped release is hidden, but only that one: the next tag shows
        // again, because the comparison is on the tag the user skipped.
        if (knownVersion == skippedTag?.removePrefix("v")?.removePrefix("V")) return false
        return VersionComparator.isNewer(knownVersion, installedVersion)
    }
}
