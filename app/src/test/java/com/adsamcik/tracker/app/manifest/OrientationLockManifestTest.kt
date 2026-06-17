package com.adsamcik.tracker.app.manifest

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.io.File

/**
 * Regression guard for the API 37 large-screen adaptivity work. Fixed screen
 * orientations are ignored on large screens (sw>=600dp) for apps targeting
 * Android 17, and were deliberately removed from the app's activities. Pinning
 * an activity orientation again would silently regress large-screen behaviour,
 * so re-introducing `android:screenOrientation` must fail this test.
 */
class OrientationLockManifestTest {

    @Test
    fun appManifest_declaresNoFixedScreenOrientation() {
        val manifest = File("src/main/AndroidManifest.xml")
        check(manifest.exists()) {
            "Expected app manifest at ${manifest.absolutePath} (working dir ${File(".").absolutePath})"
        }

        val containsOrientationLock = manifest.readText().contains("android:screenOrientation")

        containsOrientationLock shouldBe false
    }
}
