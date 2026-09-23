package com.darkvvpn.app

import android.graphics.BitmapFactory
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the boundary between Android drawables and Compose.
 *
 * ── The rule ─────────────────────────────────────────────────────────────────
 * `painterResource` can load exactly two things: a `<vector>` XML, or a real
 * bitmap (PNG/JPEG/WebP). Everything else — `<adaptive-icon>`, `<selector>`,
 * `<layer-list>`, `<shape>` — either throws outright or silently produces
 * nothing. The throw is fatal: it happens during composition, so it takes the
 * whole app down.
 *
 * v1.2.0 shipped exactly that bug. `R.mipmap.ic_launcher` resolves to the
 * launcher icon, which on API 26+ is an `<adaptive-icon>`; passing it to
 * `painterResource` on the splash screen crashed the app immediately after it
 * opened. These tests make the rule enforceable instead of remembered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ComposeResourceSafetyTest {

    private val resources get() = RuntimeEnvironment.getApplication().resources

    // ------------------------------------------------------------------
    // The resources Compose actually loads
    // ------------------------------------------------------------------

    @Test
    fun `the splash logo is a raster bitmap that Compose can load`() {
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.splash_logo)

        assertNotNull(
            "R.drawable.splash_logo must decode to a bitmap; painterResource " +
                "throws on anything that is not a bitmap or a <vector>",
            bitmap,
        )
        requireNotNull(bitmap)
        assertTrue("the logo must have real dimensions", bitmap.width > 0 && bitmap.height > 0)
    }

    @Test
    fun `every drawable in the module is loadable by Compose`() {
        // A general guard rather than a list of known ids: it walks the whole
        // R.drawable table, so a drawable added tomorrow that Compose cannot
        // handle fails here instead of on someone's phone.
        val unusable = mutableListOf<String>()

        R.drawable::class.java.fields.forEach { field ->
            val id = field.getInt(null)
            val name = runCatching { resources.getResourceName(id) }.getOrNull() ?: return@forEach

            val decodesAsBitmap = BitmapFactory.decodeResource(resources, id) != null
            val isVector = runCatching {
                resources.getXml(id).use { parser ->
                    // Advance to the root element and read its tag.
                    var type = parser.eventType
                    while (type != org.xmlpull.v1.XmlPullParser.START_TAG &&
                        type != org.xmlpull.v1.XmlPullParser.END_DOCUMENT
                    ) {
                        type = parser.next()
                    }
                    parser.name == "vector"
                }
            }.getOrDefault(false)

            if (!decodesAsBitmap && !isVector) {
                unusable += name
            }
        }

        assertTrue(
            "These drawables are neither bitmaps nor <vector> XML, so Compose's " +
                "painterResource will throw on them: $unusable",
            unusable.isEmpty(),
        )
    }

    @Test
    fun `the launcher icon is an adaptive icon and therefore off limits to Compose`() {
        // This is the crash, stated as a test. decodeResource returning null on an
        // <adaptive-icon> is the same condition that makes painterResource throw.
        // It is asserted positively so that if a future change makes the launcher
        // icon loadable, this test tells us the constraint has moved rather than
        // silently becoming meaningless.
        val decoded = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        assertNull(
            "R.mipmap.ic_launcher unexpectedly decoded to a bitmap. If the launcher " +
                "icon is no longer an <adaptive-icon>, the splash screen could use it — " +
                "but check every other mipmap too before changing anything.",
            decoded,
        )
    }

    @Test
    fun `no mipmap is handed to Compose anywhere in the source`() {
        // The strongest form of the guard: it reads the real call sites. A unit
        // test cannot see a Compose call graph, but it can read the text.
        val sourceRoot = resolveSourceRoot()
        val offenders = mutableListOf<String>()

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    if (code.contains("painterResource") && code.contains("R.mipmap")) {
                        offenders += "${file.relativeTo(sourceRoot)}:${index + 1}"
                    }
                }
            }

        assertFalse(
            "painterResource() cannot load a launcher icon: on API 26+ a mipmap " +
                "resolves to an <adaptive-icon>, and Compose throws during " +
                "composition. Use a bitmap drawable instead. Offenders: $offenders",
            offenders.isNotEmpty(),
        )
    }

    @Test
    fun `the notification icon is a vector, as the system requires`() {
        // Notification small icons are masked to a single colour by the system,
        // so a bitmap would arrive as a blurry rectangle. The rule is the
        // opposite of the splash logo's for the same underlying reason: pick the
        // format the consumer can actually handle.
        val isVector = runCatching {
            resources.getXml(R.drawable.ic_stat_vpn).use { parser ->
                var type = parser.eventType
                while (type != org.xmlpull.v1.XmlPullParser.START_TAG &&
                    type != org.xmlpull.v1.XmlPullParser.END_DOCUMENT
                ) {
                    type = parser.next()
                }
                parser.name == "vector"
            }
        }.getOrDefault(false)

        assertTrue("R.drawable.ic_stat_vpn must be a <vector>", isVector)
    }

    // ------------------------------------------------------------------

    /**
     * Unit tests run with the module directory as the working directory, so the
     * source tree is `src/main/java`. The fallback keeps the test working if it
     * is ever run from the repository root.
     */
    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("could not locate the main source set from ${File(".").absolutePath}")
    }
}
