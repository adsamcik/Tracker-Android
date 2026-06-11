package com.adsamcik.tracker.statistics.preference

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SessionActivityContext")
class SessionActivityContextTest {

	@AfterEach
	fun cleanup() {
		SessionActivityContext.clearSessionActivity()
	}

	@Nested
	@DisplayName("get and set")
	inner class GetAndSet {

		@Test
		fun `initial value is null`() {
			SessionActivityContext.getSessionActivity().shouldBeNull()
		}

		@Test
		fun `set and get returns same value`() {
			val activity = com.adsamcik.tracker.shared.base.data.SessionActivity(
				id = 1L,
				name = "Walking",
				iconName = "ic_walk"
			)
			SessionActivityContext.setSessionActivity(activity)
			SessionActivityContext.getSessionActivity() shouldBe activity
		}

		@Test
		fun `set null clears value`() {
			val activity = com.adsamcik.tracker.shared.base.data.SessionActivity(
				id = 1L,
				name = "Running"
			)
			SessionActivityContext.setSessionActivity(activity)
			SessionActivityContext.setSessionActivity(null)
			SessionActivityContext.getSessionActivity().shouldBeNull()
		}
	}

	@Nested
	@DisplayName("clearSessionActivity")
	inner class ClearTests {

		@Test
		fun `clear removes value`() {
			val activity = com.adsamcik.tracker.shared.base.data.SessionActivity(
				id = 2L,
				name = "Cycling"
			)
			SessionActivityContext.setSessionActivity(activity)
			SessionActivityContext.clearSessionActivity()
			SessionActivityContext.getSessionActivity().shouldBeNull()
		}

		@Test
		fun `clear on empty context does not throw`() {
			SessionActivityContext.clearSessionActivity()
			SessionActivityContext.getSessionActivity().shouldBeNull()
		}
	}

	@Nested
	@DisplayName("withSessionActivity")
	inner class WithSessionActivityTests {

		@Test
		fun `executes block with provided activity`() {
			val activity = com.adsamcik.tracker.shared.base.data.SessionActivity(
				id = 3L,
				name = "Hiking"
			)
			val result = SessionActivityContext.withSessionActivity(activity) {
				SessionActivityContext.getSessionActivity()
			}
			result shouldBe activity
		}

		@Test
		fun `restores previous activity after block`() {
			val first = com.adsamcik.tracker.shared.base.data.SessionActivity(
				id = 1L,
				name = "First"
			)
			val second = com.adsamcik.tracker.shared.base.data.SessionActivity(
				id = 2L,
				name = "Second"
			)
			SessionActivityContext.setSessionActivity(first)
			SessionActivityContext.withSessionActivity(second) {
				SessionActivityContext.getSessionActivity() shouldBe second
			}
			SessionActivityContext.getSessionActivity() shouldBe first
		}

		@Test
		fun `restores null after block when no previous`() {
			val activity = com.adsamcik.tracker.shared.base.data.SessionActivity(
				id = 4L,
				name = "Temp"
			)
			SessionActivityContext.withSessionActivity(activity) {
				SessionActivityContext.getSessionActivity() shouldBe activity
			}
			SessionActivityContext.getSessionActivity().shouldBeNull()
		}

		@Test
		fun `restores previous even if block throws`() {
			val first = com.adsamcik.tracker.shared.base.data.SessionActivity(
				id = 1L,
				name = "Original"
			)
			SessionActivityContext.setSessionActivity(first)
			try {
				SessionActivityContext.withSessionActivity(null) {
					throw RuntimeException("boom")
				}
			} catch (_: RuntimeException) {
				// expected
			}
			SessionActivityContext.getSessionActivity() shouldBe first
		}

		@Test
		fun `returns block result`() {
			val result = SessionActivityContext.withSessionActivity(null) {
				42
			}
			result shouldBe 42
		}

		@Test
		fun `nested withSessionActivity works`() {
			val a1 = com.adsamcik.tracker.shared.base.data.SessionActivity(1L, "A")
			val a2 = com.adsamcik.tracker.shared.base.data.SessionActivity(2L, "B")

			SessionActivityContext.withSessionActivity(a1) {
				SessionActivityContext.getSessionActivity() shouldBe a1
				SessionActivityContext.withSessionActivity(a2) {
					SessionActivityContext.getSessionActivity() shouldBe a2
				}
				SessionActivityContext.getSessionActivity() shouldBe a1
			}
			SessionActivityContext.getSessionActivity().shouldBeNull()
		}
	}
}
