package com.darkvvpn.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.darkvvpn.app.ui.theme.DarkVvpnTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the update notice for real.
 *
 * The badge and the banner are the only thing standing between "a release exists"
 * and the user knowing about it, so asserting the *state* behind them is not
 * enough — the splash-screen crash proved that lesson the expensive way, by
 * passing every state test while throwing the moment a pixel was drawn.
 *
 * Robolectric composes Compose for real on the JVM, so this catches a missing
 * string resource, a broken colour reference, or a layout that throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class UpdateBannerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the banner names the new version and offers the action`() {
        compose.setContent {
            DarkVvpnTheme {
                UpdateBanner(version = "1.4.0", isPrerelease = false, onClick = {})
            }
        }

        compose.onNodeWithText("Version 1.4.0 is available").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsDisplayed()
    }

    @Test
    fun `a prerelease says so instead of promising a normal upgrade`() {
        compose.setContent {
            DarkVvpnTheme {
                UpdateBanner(version = "2.0.0-rc1", isPrerelease = true, onClick = {})
            }
        }

        compose.onNodeWithText("Version 2.0.0-rc1 is available").assertIsDisplayed()
        compose.onNodeWithText("A pre-release. Tap to see the notes.").assertIsDisplayed()
    }

    @Test
    fun `the stable copy is shown for a normal release`() {
        compose.setContent {
            DarkVvpnTheme {
                UpdateBanner(version = "1.4.0", isPrerelease = false, onClick = {})
            }
        }

        compose.onNodeWithText("Tap to see what changed and install it.").assertIsDisplayed()
    }

    @Test
    fun `the tab dot draws without crashing`() {
        // The dot carries no text, so the assertion is simply that it composes and
        // that its amber comes from a real colour reference.
        compose.setContent { DarkVvpnTheme { UpdateDot() } }
        compose.waitForIdle()
    }

    @Test
    fun `the check report renders its message`() {
        compose.setContent {
            DarkVvpnTheme {
                UpdateCheckReport(
                    message = "You are on the newest version (1.4.0).",
                    isNotice = false,
                )
            }
        }

        compose.onNodeWithText("You are on the newest version (1.4.0).").assertIsDisplayed()
    }
}
