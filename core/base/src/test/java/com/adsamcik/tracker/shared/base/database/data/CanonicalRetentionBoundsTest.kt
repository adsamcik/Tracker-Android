package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class CanonicalRetentionBoundsTest {
	@Test
	fun `Location subtracts uncertainty and keeps exact floor equality`() {
		val lowerBound = CanonicalRetentionBounds.location(
			observedWallTimeMs = 1_000L,
			wallTimeUncertaintyMs = 25L,
		)

		lowerBound shouldBe 975L
		CanonicalRetentionBounds.crossesFloor(lowerBound, 975L) shouldBe false
		CanonicalRetentionBounds.crossesFloor(lowerBound, 976L) shouldBe true
	}

	@Test
	fun `Wi-Fi rounds a partial coverage millisecond into the lower bound`() {
		val lowerBound = CanonicalRetentionBounds.wifiCoverage(
			coverageEndWallTimeMs = 1_000L,
			wallTimeUncertaintyMs = 2L,
			coverageStartElapsedRealtimeNanos = 1_000_000L,
			coverageEndElapsedRealtimeNanos = 2_000_001L,
		)

		lowerBound shouldBe 996L
		CanonicalRetentionBounds.crossesFloor(lowerBound, 996L) shouldBe false
		CanonicalRetentionBounds.crossesFloor(lowerBound, 997L) shouldBe true
	}

	@Test
	fun `Cell derives coverage start from original WAL anchor and rounds fractional age down`() {
		val lowerBound = CanonicalRetentionBounds.cellCoverage(
			walObservedWallTimeMs = 1_000L,
			wallTimeUncertaintyMs = 20L,
			walObservedElapsedRealtimeNanos = 52_999_999L,
			coverageStartElapsedRealtimeNanos = 2_000_000L,
		)
		val coverageStart = CanonicalRetentionBounds.cellCoverageStartWallTime(
			walObservedWallTimeMs = 1_000L,
			walObservedElapsedRealtimeNanos = 52_999_999L,
			coverageStartElapsedRealtimeNanos = 2_000_000L,
		)

		lowerBound shouldBe 929L
		coverageStart shouldBe 949L
		CanonicalRetentionBounds.crossesFloor(lowerBound, 929L) shouldBe false
		CanonicalRetentionBounds.crossesFloor(lowerBound, 930L) shouldBe true
	}

	@Test
	fun `Pressure lineage lower bound keeps old revision under a newer head`() {
		val oldRevision = pressureRevision(
			intervalStartTimeMs = 1_000L,
			wallTimeUncertaintyMs = 25L,
		)
		val currentHead = pressureRevision(
			intervalStartTimeMs = 2_000L,
			wallTimeUncertaintyMs = 5L,
		)

		val lowerBound = CanonicalRetentionBounds.pressureLineage(
			listOf(oldRevision, currentHead),
		)

		lowerBound shouldBe 975L
		CanonicalRetentionBounds.crossesFloor(lowerBound, 975L) shouldBe false
		CanonicalRetentionBounds.crossesFloor(lowerBound, 976L) shouldBe true
	}

	@Test
	fun `Activity gap lineage and Steps end boundary match maintenance predicates`() {
		val gap = mockk<ActivityCapturedFragmentEntity>()
		every { gap.fragmentKind } returns ActivityCapturedFragmentEntity.KIND_GAP

		CanonicalRetentionBounds.crossesFloor(
			CanonicalRetentionBounds.activityLineage(listOf(gap)),
			1_000L,
		) shouldBe true
		CanonicalRetentionBounds.stepsWindowCrossesFloor(1_000L, 1_000L) shouldBe false
		CanonicalRetentionBounds.stepsWindowCrossesFloor(999L, 1_000L) shouldBe true
	}

	private fun pressureRevision(
		intervalStartTimeMs: Long,
		wallTimeUncertaintyMs: Long,
	): PressureFactRevisionEntity {
		val revision = mockk<PressureFactRevisionEntity>()
		every { revision.intervalStartTimeMs } returns intervalStartTimeMs
		every { revision.wallTimeUncertaintyMs } returns wallTimeUncertaintyMs
		return revision
	}
}
