package com.adsamcik.tracker.tracker.service

import android.os.Build
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class IsForegroundServiceStartRestrictionTest {

	@Test
	fun `matching class name on API 31+ is a restriction`() {
		isForegroundServiceStartRestriction(
			sdkInt = Build.VERSION_CODES.S,
			exceptionClassName = "android.app.ForegroundServiceStartNotAllowedException",
		) shouldBe true
	}

	@Test
	fun `matching class name above API 31 is a restriction`() {
		isForegroundServiceStartRestriction(
			sdkInt = Build.VERSION_CODES.S + 5,
			exceptionClassName = "android.app.ForegroundServiceStartNotAllowedException",
		) shouldBe true
	}

	@Test
	fun `matching class name below API 31 is not a restriction`() {
		// The platform exception cannot be thrown before API 31, so a coincidentally matching
		// class name from some other RuntimeException must not be swallowed.
		isForegroundServiceStartRestriction(
			sdkInt = Build.VERSION_CODES.R,
			exceptionClassName = "android.app.ForegroundServiceStartNotAllowedException",
		) shouldBe false
	}

	@Test
	fun `unrelated class name on API 31+ is not a restriction`() {
		isForegroundServiceStartRestriction(
			sdkInt = Build.VERSION_CODES.S,
			exceptionClassName = "java.lang.IllegalStateException",
		) shouldBe false
	}
}
