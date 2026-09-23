package com.darkvvpn.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.darkvvpn.app.data.update.AppRelease
import com.darkvvpn.app.ui.theme.DarkVvpnTheme
import com.darkvvpn.app.viewmodel.UpdateUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every state of the update sheet, rendered.
 *
 * ── The bug this pins ────────────────────────────────────────────────────────
 * The sheet previously received only the "there is an update" branch, so the other
 * two outcomes had nowhere to go and were handled by *closing* the sheet. Tapping
 * the update banner while already current, or with the network down, made the
 * dialog flash and vanish with no explanation — reported as "the update button
 * does nothing".
 *
 * These tests assert that each outcome actually draws, and that the failure case
 * offers an enabled Retry rather than nothing.
 *
 * The Application is a plain one: the app's own container starts coroutines that
 * keep invalidating the composition and make render tests flaky.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = android.app.Application::class,
    // Robolectric's default window is far smaller than any phone, and an
    // AlertDialog with a release-notes body overflows it — so `assertIsDisplayed`
    // failed for controls that are plainly visible on a real device. The
    // qualifiers below are a 411x891dp phone, which is what the layout is for.
    qualifiers = "w411dp-h891dp-xxhdpi",
)
class UpdateDialogStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val release = AppRelease(
        tag = "v1.5.0",
        versionName = "1.5.0",
        name = "The connection bug is fixed",
        notes = "ALPN is now decided per transport.",
        publishedAtEpochMillis = null,
        isPrerelease = false,
        htmlUrl = "https://example.invalid/releases/tag/v1.5.0",
        apkUrl = "https://example.invalid/app.apk",
        apkAssetName = "app.apk",
        apkSizeBytes = 68_000_000L,
        apkSha256 = "a".repeat(64),
    )

    private fun render(state: UpdateUiState) {
        compose.setContent {
            DarkVvpnTheme {
                UpdateDialog(
                    state = state,
                    onDownload = {},
                    onCancelDownload = {},
                    onInstall = {},
                    onOpenPermissionSettings = {},
                    onRetry = {},
                    onSkip = {},
                    onDismiss = {},
                )
            }
        }
    }

    // ---- the two states that used to close the sheet ---------------------

    @Test
    fun `an up-to-date check says so instead of closing silently`() {
        render(UpdateUiState.UpToDate(currentVersion = "1.5.0"))

        compose.onNodeWithText("Up to date").assertIsDisplayed()
        compose.onNodeWithText("DARK VVPN is up to date (1.5.0).").assertIsDisplayed()
        // The way out is a button, so the sheet is never a dead end.
        compose.onNodeWithText("Close").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `a failed check shows the reason and an enabled retry`() {
        val reason = "The provider is rate-limiting you (HTTP 429). Try again in a few minutes."
        render(UpdateUiState.Failed(reason))

        compose.onNodeWithText("Update check failed").assertIsDisplayed()
        compose.onNodeWithText(reason).assertIsDisplayed()
        // The important one: a failure must be actionable, not just informative.
        compose.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `a failure tells the user the text can be copied`() {
        render(UpdateUiState.Failed("Could not reach the update service."))
        compose.onNodeWithText(
            "This is usually a rate limit on shared networks, or no connection. " +
                "Retry in a few minutes. You can select the text above to copy it.",
        ).assertIsDisplayed()
    }

    // ---- the progress state ----------------------------------------------

    @Test
    fun `a check in progress shows a message, not an empty dialog`() {
        render(UpdateUiState.Checking)
        compose.onNodeWithText("Contacting GitHub for the latest release…").assertIsDisplayed()
    }

    // ---- the update state, which must still work -------------------------

    @Test
    fun `an available update offers an enabled download button`() {
        render(
            UpdateUiState.Available(
                release = release,
                installedVersion = "1.4.0",
            ),
        )

        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("v1.5.0").assertIsDisplayed()
        compose.onNodeWithText("1.4.0").assertIsDisplayed()
        compose.onNodeWithText("Download update").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `a downloaded update offers install and reports the digest check`() {
        render(
            UpdateUiState.Available(
                release = release,
                installedVersion = "1.4.0",
                readyToInstall = java.io.File("/tmp/does-not-need-to-exist.apk"),
                readyVerified = true,
            ),
        )

        compose.onNodeWithText("Install now").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Integrity verified (SHA-256)").assertIsDisplayed()
    }

    @Test
    fun `an unverified download says so rather than implying it is safe`() {
        render(
            UpdateUiState.Available(
                release = release,
                installedVersion = "1.4.0",
                readyToInstall = java.io.File("/tmp/does-not-need-to-exist.apk"),
                readyVerified = false,
            ),
        )

        compose.onNodeWithText(
            "Downloaded, but this release publishes no checksum — install only if you trust the source.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a missing install permission is explained before it is requested`() {
        render(
            UpdateUiState.Available(
                release = release,
                installedVersion = "1.4.0",
                readyToInstall = java.io.File("/tmp/does-not-need-to-exist.apk"),
                needsInstallPermission = true,
            ),
        )

        compose.onNodeWithText(
            "Allow \"Install unknown apps\" for DARK VVPN, then return here.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a download in progress shows a cancel and hides skip`() {
        render(
            UpdateUiState.Available(
                release = release,
                installedVersion = "1.4.0",
                isDownloading = true,
                downloadFraction = 0.42f,
                downloadedBytes = 28_000_000L,
                totalBytes = 68_000_000L,
            ),
        )

        compose.onNodeWithText("Cancel").assertIsDisplayed()
        // Skipping mid-download would leave the transfer with no UI.
        compose.onNodeWithText("Skip this version").assertDoesNotExist()
    }

    @Test
    fun `the skip option is offered when there is a release to skip`() {
        render(
            UpdateUiState.Available(
                release = release,
                installedVersion = "1.4.0",
            ),
        )

        compose.onNodeWithText("Skip this version").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
    }
}
