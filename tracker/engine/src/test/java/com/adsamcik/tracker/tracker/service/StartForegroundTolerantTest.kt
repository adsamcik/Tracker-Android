package com.adsamcik.tracker.tracker.service

import android.os.Build
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [startForegroundTolerant] delegates its "is this a background-start restriction" decision to
 * [isForegroundServiceStartRestriction] (covered exhaustively in
 * [IsForegroundServiceStartRestrictionTest]); this class instead covers its own control flow —
 * success, [SecurityException] tolerance, and propagation of anything else — using
 * Robolectric because the failure paths call through to [Reporter], which logs via
 * `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StartForegroundTolerantTest {

	@Test
	fun `start that succeeds returns true`() {
		startForegroundTolerant(sdkInt = Build.VERSION_CODES.S, tag = "test") {
			// no-op: represents a successful startForeground call
		} shouldBe true
	}

	@Test
	fun `SecurityException is tolerated and returns false`() {
		startForegroundTolerant(sdkInt = Build.VERSION_CODES.S, tag = "test") {
			throw SecurityException("declared foreground-service type no longer permitted")
		} shouldBe false
	}

	@Test(expected = IllegalStateException::class)
	fun `unrelated RuntimeException propagates unchanged`() {
		startForegroundTolerant(sdkInt = Build.VERSION_CODES.S, tag = "test") {
			throw IllegalStateException("unrelated failure")
		}
	}
}
