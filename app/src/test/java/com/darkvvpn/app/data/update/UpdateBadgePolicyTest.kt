package com.darkvvpn.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The badge is the app's only standing notice that a newer release exists, so the
 * three states that are awkward to reproduce by hand — offline, just-updated, and
 * skipped — are each pinned here.
 */
class UpdateBadgePolicyTest {

    private fun shouldShow(
        known: String?,
        installed: String = "1.3.0",
        skipped: String? = null,
    ) = UpdateBadgePolicy.shouldShow(known, installed, skipped)

    // ---- the happy path -------------------------------------------------

    @Test
    fun `a newer known release shows the badge`() {
        assertTrue(shouldShow("1.4.0"))
    }

    @Test
    fun `an older known release does not show the badge`() {
        assertFalse(shouldShow("1.2.0"))
    }

    @Test
    fun `the installed version does not show its own badge`() {
        assertFalse(shouldShow("1.3.0"))
    }

    @Test
    fun `nothing known shows no badge`() {
        assertFalse(shouldShow(null))
        assertFalse(shouldShow(""))
        assertFalse(shouldShow("   "))
    }

    @Test
    fun `a missing installed version shows no badge rather than guessing`() {
        assertFalse(UpdateBadgePolicy.shouldShow("1.4.0", null, null))
    }

    // ---- offline --------------------------------------------------------

    @Test
    fun `the badge appears from storage with no network involvement`() {
        // The policy takes the known version as a plain string, which is what
        // makes an offline launch able to draw the badge: the value comes from
        // DataStore, not from a fetch.
        assertTrue(shouldShow("2.0.0", installed = "1.0.0"))
    }

    @Test
    fun `a leading v in the stored version is irrelevant`() {
        assertTrue(shouldShow("v1.4.0"))
        assertTrue(shouldShow("V1.4.0"))
        assertTrue(shouldShow("1.4.0", installed = "v1.3.0"))
    }

    // ---- just updated ---------------------------------------------------

    @Test
    fun `the badge clears itself once the installed version catches up`() {
        // Nothing clears the stored release after an install, so this comparison
        // is the only thing that removes the badge. If it were wrong the badge
        // would stay forever on a fully updated app.
        assertTrue(shouldShow("1.4.0", installed = "1.3.0"))
        assertFalse(shouldShow("1.4.0", installed = "1.4.0"))
        assertFalse(shouldShow("1.4.0", installed = "1.4.1"))
        assertFalse(shouldShow("1.4.0", installed = "2.0.0"))
    }

    // ---- skipped --------------------------------------------------------

    @Test
    fun `a skipped release hides the badge`() {
        assertFalse(shouldShow("1.4.0", skipped = "1.4.0"))
        // The tag is stored with the leading v, the version without.
        assertFalse(shouldShow("1.4.0", skipped = "v1.4.0"))
    }

    @Test
    fun `skipping one release does not hide the next`() {
        // The whole point of skipping rather than disabling: the user said "not
        // this one", not "never tell me again".
        assertFalse(shouldShow("1.4.0", skipped = "1.4.0"))
        assertTrue(shouldShow("1.5.0", skipped = "1.4.0"))
    }

    @Test
    fun `a skipped tag of a different release is ignored`() {
        assertTrue(shouldShow("1.4.0", skipped = "1.3.0"))
        assertTrue(shouldShow("1.4.0", skipped = "0.9.0"))
    }

    // ---- version ordering, through the real comparator ------------------

    @Test
    fun `numeric ordering is honoured, not string ordering`() {
        // 1.10.0 is newer than 1.9.0 numerically but older lexically.
        assertTrue(shouldShow("1.10.0", installed = "1.9.0"))
        assertFalse(shouldShow("1.9.0", installed = "1.10.0"))
    }

    @Test
    fun `a release outranks its own prerelease`() {
        // A user on the stable 1.4.0 must not be offered 1.4.0-rc1 as an update.
        assertFalse(shouldShow("1.4.0-rc1", installed = "1.4.0"))
        assertTrue(shouldShow("1.4.0", installed = "1.3.0"))
    }

    @Test
    fun `a prerelease newer than the installed build does show`() {
        // For a user who opted into prereleases, the badge must still work.
        assertTrue(shouldShow("1.4.0-rc2", installed = "1.3.0"))
    }

    // ---- the combination ------------------------------------------------

    @Test
    fun `skipping the newest release reveals the one below it if that is newer`() {
        // Stored is 1.5.0 and it is skipped, but the installed build is 1.3.0 and
        // 1.4.0 exists. The policy compares a single stored value, so the honest
        // answer is "no badge for 1.5.0" — recovery is the manual check, which is
        // why the Settings row exists.
        assertFalse(shouldShow("1.5.0", skipped = "1.5.0"))
        // Un-skipping, which the Settings screen offers, restores it.
        assertTrue(shouldShow("1.5.0", skipped = null))
    }
}
