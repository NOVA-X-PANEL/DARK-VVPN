package com.darkvvpn.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the boundary between Android drawables and Compose.
 *
 * ── The rule ─────────────────────────────────────────────────────────────────
 * Compose's `painterResource` can load exactly two things: a `<vector>` XML, or
 * a raster image (PNG/JPEG/WebP). Everything else — `<adaptive-icon>`,
 * `<selector>`, `<layer-list>`, `<shape>` — either throws outright or silently
 * produces nothing. The throw is fatal: it happens during composition, so it
 * takes the whole app down.
 *
 * v1.2.0 shipped exactly that bug. The splash screen passed
 * `R.mipmap.ic_launcher` to `painterResource`; on API 26+ that resolves to an
 * `<adaptive-icon>`, and the app died immediately after opening.
 *
 * ── Why these assert against the source tree, not the framework ──────────────
 * The first version of this test asked `BitmapFactory.decodeResource` whether a
 * drawable was rasterizable. Under Robolectric that call is shadowed and returns
 * a synthetic bitmap for *-everything*, including the adaptive icon — so the
 * assertion could never fail and the suite proved nothing. Checking the files
 * AAPT actually compiles is deterministic, runs in milliseconds on the JVM, and
 * states the rule in the same terms a developer reads it in.
 */
class ComposeResourceSafetyTest {

    // ------------------------------------------------------------------
    // The resource tree
    // ------------------------------------------------------------------

    @Test
    fun `every XML drawable is a vector`() {
        // A non-vector XML in res/drawable*/ is precisely the trap: it looks like
        // a perfectly good drawable and throws the moment Compose loads it.
        val offenders = resourceDirs("drawable")
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.extension == "xml" }
            .filter { rootTagOf(it) != "vector" }
            .map { it.name }

        assertTrue(
            "These drawables are XML but not <vector>, so Compose's painterResource " +
                "will throw on them. Use a raster image instead: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every mipmap XML is an adaptive icon`() {
        // Stated positively so the reason the launcher icon is off limits to
        // Compose is visible in the test suite rather than only in a comment.
        val wrong = resourceDirs("mipmap")
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.extension == "xml" }
            .filter { rootTagOf(it) != "adaptive-icon" }
            .map { it.name }

        assertTrue("Unexpected mipmap XML: $wrong", wrong.isEmpty())
        assertTrue(
            "expected at least one adaptive launcher icon",
            resourceDirs("mipmap").flatMap { it.walkTopDown().toList() }
                .any { it.isFile && it.extension == "xml" },
        )
    }

    @Test
    fun `the splash logo is a raster file, which Compose can always load`() {
        val logo = resourceDirs("drawable")
            .flatMap { dir -> dir.listFiles().orEmpty().toList() }
            .firstOrNull { it.isFile && it.nameWithoutExtension == "splash_logo" }

        assertNotNull("res/drawable*/splash_logo.* is missing", logo)
        requireNotNull(logo)
        assertTrue(
            "splash_logo must be a raster image, not ${logo.extension}: Compose cannot " +
                "rasterize XML other than <vector>",
            logo.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp"),
        )
    }

    @Test
    fun `the launcher icon lives in a density-independent folder`() {
        // A PNG in drawable-nodpi is never scaled per density, so the logo renders
        // identically on every device instead of being resampled from a bucket.
        assertTrue(
            "expected a drawable-nodpi folder holding the splash logo",
            File(resRoot(), "drawable-nodpi").isDirectory,
        )
    }

    // ------------------------------------------------------------------
    // The call sites
    // ------------------------------------------------------------------

    @Test
    fun `the splash screen uses the raster logo and not the launcher icon`() {
        val splash = sourceRoot()
            .resolve("com/darkvvpn/app/ui/screens/SplashScreen.kt")

        assertTrue("SplashScreen.kt not found at $splash", splash.isFile)
        // Comments are stripped, because the comment explaining why a mipmap must
        // not be used here contains the string "R.mipmap" — and an assertion that
        // fires on its own documentation is worse than no assertion.
        val code = stripComments(splash.readText())

        assertTrue(
            "the splash screen must load R.drawable.splash_logo",
            code.contains("R.drawable.splash_logo"),
        )
        assertFalse(
            "the splash screen must not touch R.mipmap: on API 26+ a mipmap is an " +
                "<adaptive-icon>, which painterResource cannot rasterize",
            code.contains("R.mipmap"),
        )
    }

    @Test
    fun `no source file hands a mipmap to Compose`() {
        // The general form of the guard. A unit test cannot see a Compose call
        // graph, but it can read the files — and that is enough to stop the
        // regression returning.
        val offenders = mutableListOf<String>()

        sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                stripComments(file.readText()).lines().forEachIndexed { index, line ->
                    if (line.contains("painterResource") && line.contains("R.mipmap")) {
                        offenders += "${file.name}:${index + 1}"
                    }
                }
            }

        assertTrue(
            "painterResource() cannot load a launcher icon — it throws during " +
                "composition, taking the app down on launch. Offenders: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the notification icon is a vector, as the platform requires`() {
        // The opposite format for the opposite reason: the system masks a
        // notification icon to one colour, so a bitmap would arrive as a blurry
        // rectangle. Pick the format the consumer can actually handle.
        val icon = resourceDirs("drawable")
            .flatMap { it.listFiles().orEmpty().toList() }
            .firstOrNull { it.name == "ic_stat_vpn.xml" }

        assertNotNull("res/drawable/ic_stat_vpn.xml is missing", icon)
        requireNotNull(icon)
        assertTrue(
            "ic_stat_vpn.xml must be a <vector>, was <${rootTagOf(icon)}>",
            rootTagOf(icon) == "vector",
        )
    }

    // ------------------------------------------------------------------
    // Filesystem helpers
    // ------------------------------------------------------------------

    /** Unit tests run with the module directory as the working directory. */
    private fun moduleRoot(): File {
        val candidates = listOf(File("."), File("app"))
        return candidates.firstOrNull { File(it, "src/main/res").isDirectory }
            ?: error("could not locate src/main/res from ${File(".").absolutePath}")
    }

    private fun resRoot(): File = moduleRoot().resolve("src/main/res")

    private fun sourceRoot(): File = moduleRoot().resolve("src/main/java")

    /** Every `res/<prefix>*` folder, so density- and API-qualified variants count. */
    private fun resourceDirs(prefix: String): List<File> =
        resRoot().listFiles().orEmpty()
            .filter { it.isDirectory && (it.name == prefix || it.name.startsWith("$prefix-")) }

    /**
     * Removes `/* … *\/` blocks and `//` line comments.
     *
     * The `//` rule skips a slash pair preceded by a colon, so a URL inside a
     * string literal (`"https://…"`) is not mistaken for the start of a comment
     * and does not silently truncate a line that a check needs to see.
     */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")

    /**
     * The root element's tag name, ignoring the XML declaration and comments.
     * Returns an empty string when no element is found.
     */
    private fun rootTagOf(file: File): String {
        val text = file.readText()
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('<', i)
            if (open < 0) return ""
            when {
                text.startsWith("<?", open) -> i = text.indexOf("?>", open).let { if (it < 0) return "" else it + 2 }
                text.startsWith("<!--", open) -> i = text.indexOf("-->", open).let { if (it < 0) return "" else it + 3 }
                else -> {
                    val end = text.indexOfAny(charArrayOf(' ', '>', '\n', '\r', '\t'), open + 1)
                    return if (end < 0) "" else text.substring(open + 1, end).trim()
                }
            }
        }
        return ""
    }
}
