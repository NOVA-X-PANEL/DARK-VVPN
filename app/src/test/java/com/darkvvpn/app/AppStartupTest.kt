package com.darkvvpn.app

import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun `the app starts with no bundled nodes`() {
        val app = RuntimeEnvironment.getApplication() as DarkVvpnApplication
        val servers = app.container.serverRepository.servers.value

        // Deliberate. Earlier versions shipped demo nodes on `*.invalid`
        // hostnames: they could never resolve, so they only ever produced
        // failures, and they made a working import look broken because the list
        // still showed unusable entries above the real ones. The app now starts
        // empty and the Servers screen says why.
        assertTrue(
            "no nodes should be bundled; found ${servers.map { it.host }}",
            servers.isEmpty(),
        )
    }

    @Test
    fun `the server repository accepts nodes and merges them without duplicates`() {
        val app = RuntimeEnvironment.getApplication() as DarkVvpnApplication
        val repository = app.container.serverRepository

        val node = VpnServer(
            name = "Test",
            host = "node.example",
            port = 443,
            protocol = VpnProtocol.VLESS,
            uuid = "11111111-2222-3333-4444-555555555555",
        )

        repository.addImported(listOf(node))
        assertEquals(1, repository.servers.value.size)

        // The same endpoint pasted twice must not appear twice — the merge is on
        // protocol + host + port.
        repository.addImported(listOf(node.copy(id = "other-id")))
        assertEquals("a duplicate endpoint must not be added twice", 1, repository.servers.value.size)

        // A subscription supersedes an identical manual entry rather than adding
        // a second row for the same endpoint.
        repository.replaceSubscriptionNodes(listOf(node.copy(id = "from-sub", name = "From sub")))
        assertEquals(1, repository.servers.value.size)
        assertEquals("From sub", repository.servers.value.single().name)

        // Dropping the subscription leaves nothing behind, because the manual
        // entry was the one it superseded.
        repository.clearSubscriptionNodes()
        assertTrue(repository.servers.value.isEmpty())
    }

    @Test
    fun `a selection survives a refresh that keeps the node`() {
        val app = RuntimeEnvironment.getApplication() as DarkVvpnApplication
        val repository = app.container.serverRepository

        val node = VpnServer(
            name = "Keep me",
            host = "keep.example",
            port = 443,
        )
        repository.replaceSubscriptionNodes(listOf(node))
        repository.select(node.id)
        assertEquals(node.id, repository.selectedServerId.value)

        // A refresh re-serves the same endpoint; the selection must not be lost.
        repository.replaceSubscriptionNodes(listOf(node.copy(name = "Renamed")))
        assertEquals(node.id, repository.selectedServerId.value)
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
