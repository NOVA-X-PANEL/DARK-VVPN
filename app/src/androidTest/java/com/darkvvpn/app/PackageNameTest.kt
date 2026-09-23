package com.darkvvpn.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the on-device build.
 *
 * The applicationId carries a `.debug` suffix on the debug variant, so the
 * assertion checks the prefix rather than exact equality.
 */
@RunWith(AndroidJUnit4::class)
class PackageNameTest {

    @Test
    fun packageNameStartsWithTheApplicationIdPrefix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "unexpected package: ${context.packageName}",
            context.packageName.startsWith("com.darkvvpn.app"),
        )
    }
}
