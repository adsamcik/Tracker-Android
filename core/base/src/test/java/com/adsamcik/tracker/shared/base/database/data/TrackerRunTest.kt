package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TrackerRun - continuous tracking period entity")
class TrackerRunTest {

	private fun run(
		id: Long = 0,
		startTimeMs: Long = 1000L,
		endTimeMs: Long? = 2000L,
		policy: String = "PASSIVE_LOW",
		policyParams: String? = null,
		userInitiated: Boolean = true,
		createdAt: Long = 1000L
	) = TrackerRun(id, startTimeMs, endTimeMs, policy, policyParams, userInitiated, createdAt)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val r = run()
			r.id shouldBe 0
			r.startTimeMs shouldBe 1000L
			r.endTimeMs shouldBe 2000L
			r.policy shouldBe "PASSIVE_LOW"
			r.policyParams shouldBe null
			r.userInitiated shouldBe true
			r.createdAt shouldBe 1000L
			r.legacyRuntimeFenced shouldBe false
		}

		@Test
		fun `endTimeMs can be null for active runs`() {
			val r = run(endTimeMs = null)
			r.endTimeMs shouldBe null
		}

		@Test
		fun `policyParams can store JSON`() {
			val r = run(policyParams = """{"interval":30}""")
			r.policyParams shouldBe """{"interval":30}"""
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			run() shouldBe run()
		}

		@Test
		fun `inequality`() {
			run(policy = "A") shouldNotBe run(policy = "B")
		}

		@Test
		fun `copy modifies single field`() {
			val r = run().copy(policy = "ACTIVE_ELEVATED")
			r.policy shouldBe "ACTIVE_ELEVATED"
			r.startTimeMs shouldBe 1000L
		}
	}
}
