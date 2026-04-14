package com.adsamcik.tracker.shared.utils.style.compose

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("AppTheme")
class AppThemeTest {

	@Nested
	@DisplayName("LocalReducedMotion")
	inner class LocalReducedMotionTest {

		@Test
		fun `composition local is defined`() {
			LocalReducedMotion shouldNotBe null
		}
	}
}
