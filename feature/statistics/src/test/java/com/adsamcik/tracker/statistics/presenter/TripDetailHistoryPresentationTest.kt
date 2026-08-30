package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TripDetailHistoryPresentationTest {
	@Test
	fun `covered zero is the only zero presented as complete`() {
		val result = history(
			count = 0L,
			evidence = HistoryEvidence.ACTIVE,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
		).toTripDetailStepsState()

		result shouldBe TripDetailStepsState.Complete(0L)
	}

	@Test
	fun `missing and baseline evidence stay nonnumeric`() {
		val result = history(
			count = null,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.BASELINE_ONLY),
		).toTripDetailStepsState()

		result shouldBe TripDetailStepsState.Unavailable
	}

	@Test
	fun `materializing without a value stays nonnumeric`() {
		val result = history(
			count = null,
			evidence = HistoryEvidence.STARTING,
			productState = HistoryProductState.MATERIALIZING,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.SESSION_STILL_ACTIVE),
		).toTripDetailStepsState()

		result shouldBe TripDetailStepsState.Materializing
	}

	@Test
	fun `partial positive value remains an explicit lower bound`() {
		val result = history(
			count = 123L,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.PROVIDER_GAP),
		).toTripDetailStepsState()

		result shouldBe TripDetailStepsState.LowerBound(123L)
	}

	@Test
	fun `legacy count is never promoted to complete`() {
		val result = history(
			count = 456L,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(StepsHistoryCause.LEGACY_UNVERIFIED),
		).toTripDetailStepsState()

		result shouldBe TripDetailStepsState.LegacyUnverified(456L)
	}

	@Test
	fun `deleted facts stay explicit and nonnumeric`() {
		val result = history(
			count = null,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.DELETED),
		).toTripDetailStepsState()

		result shouldBe TripDetailStepsState.Deleted
	}

	@Test
	fun `partial deletion keeps the surviving correction-safe lower bound`() {
		val result = history(
			count = 3L,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.DELETED),
		).toTripDetailStepsState()

		result shouldBe TripDetailStepsState.LowerBound(3L)
	}

	@Test
	fun `production disabled shape remains distinct from generic not captured`() {
		val result = history(
			count = null,
			availability = HistoryAvailability.DISABLED,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.SOURCE_NOT_CAPTURED),
		).toTripDetailStepsState()

		result shouldBe TripDetailStepsState.Disabled
	}

	@Test
	fun `unbound production writer shape remains explicitly not captured`() {
		val result = history(
			count = null,
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.SOURCE_NOT_CAPTURED),
		).toTripDetailStepsState()

		result shouldBe TripDetailStepsState.NotCaptured
	}

	@Test
	fun `availability states are not collapsed into device hardware guesses`() {
		val expected = mapOf(
			HistoryAvailability.UNSUPPORTED to TripDetailStepsState.Unsupported,
			HistoryAvailability.PERMISSION_REQUIRED to TripDetailStepsState.PermissionRequired,
			HistoryAvailability.OS_LIMITED to TripDetailStepsState.OsLimited,
			HistoryAvailability.UNAVAILABLE to TripDetailStepsState.Unavailable,
		)

		expected.forEach { (availability, state) ->
			history(
				count = null,
				availability = availability,
				evidence = HistoryEvidence.NONE,
				productState = HistoryProductState.PARTIAL,
				coverage = StepsHistoryCoverage.NONE,
				causes = setOf(StepsHistoryCause.AVAILABILITY_UNAVAILABLE),
			).toTripDetailStepsState() shouldBe state
		}
	}

	private fun history(
		count: Long?,
		availability: HistoryAvailability = HistoryAvailability.AVAILABLE,
		evidence: HistoryEvidence,
		productState: HistoryProductState,
		coverage: StepsHistoryCoverage,
		causes: Set<StepsHistoryCause> = emptySet(),
	) = StepsHistory(
		count = count,
		availability = availability,
		evidence = evidence,
		productState = productState,
		coverage = coverage,
		causes = causes,
	)
}
