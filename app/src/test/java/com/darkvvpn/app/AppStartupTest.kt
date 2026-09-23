package com.darkvvpn.app

import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Starts the app the way the launcher does, on the JVM.
 *
 * ── Why this exists ──────────────────────────────────────────────────────────
 * v1.2.0 crashed immediately after opening. Nothing caught it: the build passed,
 * 120 unit tests passed, and the APK was structurally valid. The crash was in a
 * *resource* — the splash screen handed an `<adaptive-icon>` XML to Compose's
 * `painterResource`, which can only rasterize a `<vector>` or a bitmap, and
 * threw during composition.
 *
 * A pure unit test never touches the resource system, and no amount of APK
 * inspection sees a composition-time failure. Robolectric runs the real
 * resource system, the real `Application` and the real `Activity`, so the
 * splash screen is composed for real and that class of bug becomes testable.
 *
 * ── The vacuous-pass trap ────────────────────────────────────────────────────
 * "It did not crash" means nothing if nothing was ever drawn. If composition
 * were skipped, every assertion here would pass while the app was still broken,
 * so each test also asserts that Compose actually attached and composed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AppStartupTest {

    @Test
    fun `the application initialises its container`() {
        val app = RuntimeEnvironment.getApplication() as DarkVvpnApplication

        // AppContainer is built in onCreate; a failure there is a launch crash
        // with no UI on screen to explain it.
        assertNotNull(app.container.settingsRepository)
        assertNotNull(app.container.serverRepository)
        assertNotNull(app.container.subscriptionRepository)
        assertNotNull(app.container.updateChecker)
        assertNotNull(app.container.updateDownloader)
        assertNotNull(app.container.updateInstaller)
    }

    @Test
    fun `the seeded catalogue is populated so the app is usable on first run`() {
        val app = RuntimeEnvironment.getApplication() as DarkVvpnApplication
        val servers = app.container.serverRepository.servers.value

        assertTrue("the catalogue must not be empty on a fresh install", servers.isNotEmpty())
        assertTrue(
            "every seeded node needs a dialable host and port",
            servers.all { it.host.isNotBlank() && it.port in 1..65535 },
        )
    }

    @Test
    fun `main activity launches, composes, and survives a recreate`() {
        val app = ApplicationProvider.getApplicationContext<DarkVvpnApplication>()

        // Turn the launch-time update check off before the activity composes. It
        // performs a real HTTPS request, and a test that reaches the network is a
        // test that fails on a build machine with no egress.
        runBlocking { app.container.settingsRepository.setCheckForUpdatesOnLaunch(false) }

        // setup() = create + start + resume + attach to a window.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()

        val activity = controller.get()
        assertFalse("the activity must not have finished", activity.isFinishing)
        assertFalse("the activity must not have been destroyed", activity.isDestroyed)

        // Prove the composition ran. Without this the test would pass even if
        // Compose never attached — the exact shape of the bug it is guarding.
        val composeClasses = activity.window.decorView.descendantClassNames()
            .filter { it.contains("Compose") }
        assertTrue(
            "Compose never attached, so this test proved nothing. " +
                "Views found: ${activity.window.decorView.descendantClassNames()}",
            composeClasses.isNotEmpty(),
        )

        // A configuration change tears the composition down and builds it again.
        controller.recreate()
        settle()

        assertFalse(controller.get().isFinishing)
        assertTrue(
            "Compose did not re-attach after recreate",
            controller.get().window.decorView.descendantClassNames().any { it.contains("Compose") },
        )
    }

    /** Runs whatever the main looper has pending, including scheduled frames. */
    private fun settle() {
        repeat(3) { shadowOf(Looper.getMainLooper()).idle() }
    }
}

/** Every class name in the subtree rooted at this view, for diagnostics. */
private fun View.descendantClassNames(): List<String> {
    val names = mutableListOf(this.javaClass.name)
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            names += getChildAt(i).descendantClassNames()
        }
    }
    return names
}
