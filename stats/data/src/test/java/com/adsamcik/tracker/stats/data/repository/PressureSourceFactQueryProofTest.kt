package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.PressureFactLineageProof
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class PressureSourceFactQueryProofTest {
	@Test
	fun `historical Pressure query coverage and completeness belong to selected revision`() {
		val selected = mockk<PressureFactRevisionEntity>()
		val current = mockk<PressureFactRevisionEntity>()
		every { selected.windowStartElapsedRealtimeNanos } returns 10L
		every { selected.windowEndElapsedRealtimeNanos } returns 20L
		every { selected.qualification } returns PressureFactRevisionEntity.QUALIFICATION_PARTIAL
		every { current.windowStartElapsedRealtimeNanos } returns 30L
		every { current.windowEndElapsedRealtimeNanos } returns 40L
		every { current.qualification } returns PressureFactRevisionEntity.QUALIFICATION_COMPLETE
		val proof = PressureFactLineageProof.Authenticated(
			current = current,
			revisions = listOf(selected, current),
			selected = selected,
		)

		proof.selectedPressureProofCoverage() shouldBe
			SourceProofCoverage.ProviderInterval(10L, 20L)
		proof.selectedPressureProofCompleteness() shouldBe SourceProofCompleteness.PARTIAL
	}

	@Test
	fun `historical Pressure query keeps complete selected evidence under partial current head`() {
		val selected = mockk<PressureFactRevisionEntity>()
		val current = mockk<PressureFactRevisionEntity>()
		every { selected.qualification } returns PressureFactRevisionEntity.QUALIFICATION_COMPLETE
		every { current.qualification } returns PressureFactRevisionEntity.QUALIFICATION_PARTIAL
		val proof = PressureFactLineageProof.Authenticated(
			current = current,
			revisions = listOf(selected, current),
			selected = selected,
		)

		proof.selectedPressureProofCompleteness() shouldBe SourceProofCompleteness.COMPLETE
	}

	@Test
	fun `historical Pressure query retention uses old revision under newer head`() {
		val selected = mockk<PressureFactRevisionEntity>()
		val current = mockk<PressureFactRevisionEntity>()
		every { selected.intervalStartTimeMs } returns 1_000L
		every { selected.wallTimeUncertaintyMs } returns 25L
		every { current.intervalStartTimeMs } returns 2_000L
		every { current.wallTimeUncertaintyMs } returns 5L
		val proof = PressureFactLineageProof.Authenticated(
			current = current,
			revisions = listOf(selected, current),
			selected = selected,
		)

		CanonicalRetentionBounds.pressureLineage(proof.revisions) shouldBe 975L
		CanonicalRetentionBounds.crossesFloor(
			CanonicalRetentionBounds.pressureLineage(proof.revisions),
			976L,
		) shouldBe true
	}
}
