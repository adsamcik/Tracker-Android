package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CycleContext")
class CycleContextTest {

	private fun minimalCycle(): TrackingCycle = TestCycleFactory.minimal()

	@Nested
	@DisplayName("Construction defaults")
	inner class ConstructionDefaults {

		@Test
		fun `session defaults to null`() {
			val ctx = CycleContext(
				cycle = minimalCycle(),
				collectionData = mockk(relaxed = true),
			)
			ctx.session.shouldBeNull()
		}

		@Test
		fun `signal defaults to null`() {
			val ctx = CycleContext(
				cycle = minimalCycle(),
				collectionData = mockk(relaxed = true),
			)
			ctx.signal.shouldBeNull()
		}

		@Test
		fun `shouldContinue defaults to true`() {
			val ctx = CycleContext(
				cycle = minimalCycle(),
				collectionData = mockk(relaxed = true),
			)
			ctx.shouldContinue shouldBe true
		}
	}

	@Nested
	@DisplayName("Mutable enrichment")
	inner class MutableEnrichment {

		@Test
		fun `session can be set after construction`() {
			val ctx = CycleContext(
				cycle = minimalCycle(),
				collectionData = mockk(relaxed = true),
			)
			val session = mockk<com.adsamcik.tracker.shared.base.data.TrackerSession>(relaxed = true)
			ctx.session = session
			ctx.session shouldBe session
		}

		@Test
		fun `shouldContinue can be set to false`() {
			val ctx = CycleContext(
				cycle = minimalCycle(),
				collectionData = mockk(relaxed = true),
			)
			ctx.shouldContinue = false
			ctx.shouldContinue shouldBe false
		}
	}

	@Nested
	@DisplayName("Data class equality")
	inner class Equality {

		@Test
		fun `two contexts with same data are equal`() {
			val cycle = minimalCycle()
			val collectionData: MutableCollectionData = mockk(relaxed = true)
			val a = CycleContext(cycle = cycle, collectionData = collectionData)
			val b = CycleContext(cycle = cycle, collectionData = collectionData)
			a shouldBe b
		}
	}
}
