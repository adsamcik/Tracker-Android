package com.adsamcik.tracker.shared.base.constant

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ResourcesConstants - Android resource sentinel values")
class ResourcesConstantsTest {

	@Test
	fun `ID_NULL is zero`() {
		ResourcesConstants.ID_NULL shouldBe 0
	}
}
