package com.adsamcik.tracker.shared.preferences.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TrackerSettingsAccessTest {

	@Nested
	inner class `object identity` {
		@Test
		fun `TrackerSettingsAccess is a singleton`() {
			val ref1 = TrackerSettingsAccess
			val ref2 = TrackerSettingsAccess
			(ref1 === ref2) shouldBe true
		}
	}
}
