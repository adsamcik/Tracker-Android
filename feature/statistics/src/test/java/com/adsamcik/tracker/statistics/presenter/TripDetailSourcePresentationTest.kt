package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TripDetailSourcePresentationTest {
	@Test
	fun `failed source history never enables stale Location presentation`() {
		TripDetailState.Loaded(
			trip = TripSummary(
				id = 42L,
				startTimeMs = EpochMs(1_000L),
				endTimeMs = EpochMs(2_000L),
				distance = DistanceM(1_000f),
				steps = StepCount(10),
				duration = DurationMs(1_000L),
				primaryMode = TransportMode.WALK,
				sampleCount = 4,
			),
			steps = TripDetailStepsState.Failed,
			sourcePresentation = TripDetailSourcePresentation.Failed,
		).supportsLocationPresentation shouldBe false
	}

	@Test
	fun `every capture revision must remain exactly Pressure`() {
		val pressure = unavailablePressure()
		val history = session(
			capture = HistoryCapture.Exact(
				listOf(
					revision(1L, setOf(HistorySource.PRESSURE)),
					revision(2L, setOf(HistorySource.PRESSURE)),
				),
			),
			pressure = pressure,
		)

		history.toTripDetailSourcePresentation() shouldBe
			TripDetailSourcePresentation.PressureOnly(pressure)
	}

	@Test
	fun `one mixed revision preserves standard Trip Detail`() {
		val history = session(
			capture = HistoryCapture.Exact(
				listOf(
					revision(1L, setOf(HistorySource.PRESSURE)),
					revision(2L, setOf(HistorySource.PRESSURE, HistorySource.LOCATION)),
				),
			),
			pressure = unavailablePressure(),
		)

		history.toTripDetailSourcePresentation() shouldBe TripDetailSourcePresentation.Standard
	}

	@Test
	fun `legacy unverifiable capture preserves standard Trip Detail`() {
		val history = session(
			capture = HistoryCapture.Unverifiable,
			pressure = unavailablePressure(),
		)

		history.toTripDetailSourcePresentation() shouldBe TripDetailSourcePresentation.Standard
	}

	private fun session(
		capture: HistoryCapture,
		pressure: PressureHistory,
	) = PressureSessionHistory(
		segmentId = 42L,
		capture = capture,
		qualifiedSources = emptySet(),
		pressure = pressure,
	)

	private fun revision(revision: Long, sources: Set<HistorySource>) = HistoryCaptureRevision(
		revision = revision,
		effectiveAt = EpochMs(revision * 1_000L),
		capturedSources = sources,
		controlSources = setOf(HistorySource.ACTIVITY),
	)

	private fun unavailablePressure() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.PROVIDER_UNAVAILABLE),
	)
}
