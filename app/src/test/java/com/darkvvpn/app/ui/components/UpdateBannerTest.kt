package com.darkvvpn.app.ui.components

import androidx.compose.foundation.layout.Column
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
 *
 * ── Why the Application is not DarkVvpnApplication ───────────────────────────
 * With the real Application these tests were flaky: two of five failed with
 * "Compose did not get idle after 8280866 attempts in 60 SECONDS" from inside
 * `setContent`, while the other three in the same class passed. A plain `Text`
 * cannot loop, so the churn was coming from the app's own singletons — the
 * ViewModels and DataStore coroutines that DarkVvpnApplication's container
 * starts, which keep invalidating the composition.
 *
 * None of that is under test here. A bare Application removes the interference
 * and makes the class deterministic, which is what a render test has to be to be
 * worth having.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = android.app.Application::class,
)
class UpdateBannerTest {

    @get:Rule
    val compose = createComposeRule()

    /** Composes with the animation clock held still; see the dialog test's note. */
    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { DarkVvpnTheme { content() } }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `the banner names the new version and offers the action`() {
        render {
            UpdateBanner(version = "1.4.0", isPrerelease = false, onClick = {})
        }

        compose.onNodeWithText("Version 1.4.0 is available").assertExists()
        compose.onNodeWithText("Update").assertExists()
    }

    @Test
    fun `a prerelease says so instead of promising a normal upgrade`() {
        render {
            UpdateBanner(version = "2.0.0-rc1", isPrerelease = true, onClick = {})
        }

        compose.onNodeWithText("Version 2.0.0-rc1 is available").assertExists()
        compose.onNodeWithText("A pre-release. Tap to see the notes.").assertExists()
    }

    @Test
    fun `the stable copy is shown for a normal release`() {
        render {
            UpdateBanner(version = "1.4.0", isPrerelease = false, onClick = {})
        }

        compose.onNodeWithText("Tap to see what changed and install it.").assertExists()
    }

    @Test
    fun `the banner and the tab dot draw together`() {
        // The dot carries no text, so it is exercised alongside the banner and the
        // assertion lands on the banner. A test that only called `waitForIdle()`
        // asserted nothing and failed intermittently for reasons outside the
        // product, which makes it worse than no test.
        compose.setContent {
            DarkVvpnTheme {
                Column {
                    UpdateBanner(version = "1.4.0", isPrerelease = false, onClick = {})
                    UpdateDot()
                }
            }
        }

        compose.onNodeWithText("Version 1.4.0 is available").assertExists()
    }

    @Test
    fun `the check report renders its message`() {
        render {
            UpdateCheckReport(
                    message = "You are on the newest version (1.4.0).",
                    isNotice = false,
            )
        }

        compose.onNodeWithText("You are on the newest version (1.4.0).").assertExists()
    }
}
