package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedStepsCaptureRevision
import com.adsamcik.tracker.stats.api.repository.LiveSessionHistorySnapshot
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TripDetailSourcePresentationTest {
	@Test
	fun `failed source history never enables stale Location presentation or actions`() {
		TripDetailState.Loaded(
			trip = trip(),
			steps = TripDetailStepsState.Failed,
			sourcePresentation = TripDetailSourcePresentation.Failed,
		).let { loaded ->
			loaded.supportsLocationPresentation shouldBe false
			loaded.hasUnavailableSourceActions shouldBe false
		}
	}

	@Test
	fun `exact Activity-only capture selects retained Activity and ignores control sources`() {
		val activity = unavailableActivity(capturesOnlyActivity = true)
		val snapshot = snapshot(
			capture = exactCapture(
				sources = setOf(HistorySource.ACTIVITY),
				controlSources = setOf(HistorySource.LOCATION, HistorySource.STEPS),
			),
			activity = activity,
		)

		val presentation = snapshot.toTripDetailSourcePresentation()
		presentation shouldBe TripDetailSourcePresentation.ActivityOnly(activity)
		loaded(presentation).let { loaded ->
			loaded.supportsLocationPresentation shouldBe false
			loaded.hasUnavailableSourceActions shouldBe true
		}
	}

	@Test
	fun `exact Steps-only capture selects nullable Steps product despite Activity control`() {
		val steps = capturedStepsWithoutValue()
		val snapshot = snapshot(
			capture = exactCapture(
				sources = setOf(HistorySource.STEPS),
				controlSources = setOf(HistorySource.ACTIVITY),
			),
			steps = steps,
		)

		val presentation = snapshot.toTripDetailSourcePresentation()
		presentation shouldBe TripDetailSourcePresentation.StepsOnly(steps)
		loaded(presentation).supportsLocationPresentation shouldBe false
	}

	@Test
	fun `wrong source product cannot contradict the common exact Steps authority`() {
		val steps = completeSteps(12L)
		val capture = exactCapture(setOf(HistorySource.STEPS))
		val snapshot = snapshot(
			capture = capture,
			steps = steps,
		)

		(snapshot.session as SessionHistoryQuery.Found).history.qualifiedSources shouldBe
			setOf(HistorySource.STEPS)
		(snapshot.activity as ActivityHistoryQuery.Found).entry.causes shouldBe
			setOf(ActivityHistoryCause.SOURCE_NOT_CAPTURED)
		(snapshot.pressure as PressureSessionHistoryQuery.Found).history.pressure.causes shouldBe
			setOf(PressureHistoryCause.SOURCE_NOT_CAPTURED)
		snapshot.toTripDetailSourcePresentation() shouldBe
			TripDetailSourcePresentation.StepsOnly(steps)
	}

	@Test
	fun `every capture revision must remain exactly Pressure`() {
		val pressure = unavailablePressure()
		val snapshot = snapshot(
			capture = HistoryCapture.Exact(
				listOf(
					revision(1L, setOf(HistorySource.PRESSURE)),
					revision(2L, setOf(HistorySource.PRESSURE)),
				),
			),
			pressure = pressure,
		)

		val presentation = snapshot.toTripDetailSourcePresentation()
		presentation shouldBe TripDetailSourcePresentation.PressureOnly(pressure)
		loaded(presentation).supportsLocationPresentation shouldBe false
	}

	@Test
	fun `exact mixed capture without Location stays contained`() {
		val snapshot = snapshot(
			capture = HistoryCapture.Exact(
				listOf(
					revision(1L, setOf(HistorySource.ACTIVITY)),
					revision(2L, setOf(HistorySource.ACTIVITY, HistorySource.STEPS)),
				),
			),
		)

		val session = (snapshot.session as SessionHistoryQuery.Found).history
		session.qualifiedSources shouldBe emptySet<HistorySource>()
		session.steps.causes shouldBe setOf(StepsHistoryCause.NO_OBSERVATION)
		(snapshot.activity as ActivityHistoryQuery.Found).entry.causes shouldBe
			setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS)
		(snapshot.pressure as PressureSessionHistoryQuery.Found).history.pressure.causes shouldBe
			setOf(PressureHistoryCause.SOURCE_NOT_CAPTURED)
		val presentation = snapshot.toTripDetailSourcePresentation()
		presentation shouldBe TripDetailSourcePresentation.CapturedWithoutLocation(
			setOf(HistorySource.ACTIVITY, HistorySource.STEPS),
		)
		loaded(presentation).let { loaded ->
			loaded.supportsLocationPresentation shouldBe false
			loaded.hasUnavailableSourceActions shouldBe true
		}
	}

	@Test
	fun `one exact Location revision retains standard Trip Detail`() {
		val snapshot = snapshot(
			capture = HistoryCapture.Exact(
				listOf(
					revision(1L, setOf(HistorySource.PRESSURE)),
					revision(2L, setOf(HistorySource.PRESSURE, HistorySource.LOCATION)),
				),
			),
		)

		val presentation = snapshot.toTripDetailSourcePresentation()
		presentation shouldBe TripDetailSourcePresentation.Standard
		loaded(presentation).supportsLocationPresentation shouldBe true
	}

	@Test
	fun `legacy unverifiable capture alone preserves retained standard detail`() {
		val presentation = snapshot(
			capture = HistoryCapture.Unverifiable,
		).toTripDetailSourcePresentation()

		presentation shouldBe TripDetailSourcePresentation.LegacyUnverifiable
		loaded(presentation).supportsLocationPresentation shouldBe true
	}

	@Test
	fun `portable Steps membership remains explicit and never becomes exact Steps-only`() {
		val steps = completeSteps(0L, availability = HistoryAvailability.RETAINED_IMPORTED)
		val snapshot = snapshot(
			capture = HistoryCapture.ImportedSteps(
				listOf(ImportedStepsCaptureRevision(1L, EpochMs(1_000L))),
			),
			steps = steps,
		)
		val presentation = snapshot.toTripDetailSourcePresentation()

		presentation shouldBe TripDetailSourcePresentation.ImportedSteps(steps)
		(snapshot.session as SessionHistoryQuery.Found).history.qualifiedSources shouldBe
			setOf(HistorySource.STEPS)
		(snapshot.activity as ActivityHistoryQuery.Found).entry.origin shouldBe
			ActivityHistoryOrigin.IMPORTED
		(snapshot.pressure as PressureSessionHistoryQuery.Found).history.pressure.causes shouldBe
			setOf(PressureHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		loaded(presentation).let { loaded ->
			loaded.supportsLocationPresentation shouldBe false
			loaded.hasUnavailableSourceActions shouldBe true
		}
	}

	private fun loaded(sourcePresentation: TripDetailSourcePresentation) = TripDetailState.Loaded(
		trip = trip(),
		steps = TripDetailStepsState.Unavailable,
		sourcePresentation = sourcePresentation,
	)

	private fun trip() = TripSummary(
		id = 42L,
		startTimeMs = EpochMs(1_000L),
		endTimeMs = EpochMs(2_000L),
		distance = DistanceM(1_000f),
		duration = DurationMs(1_000L),
		primaryMode = TransportMode.WALK,
		sampleCount = 4,
	)

	private fun snapshot(
		capture: HistoryCapture,
		steps: StepsHistory = stepsFor(capture),
		activity: ActivityHistoryEntry = activityFor(capture),
		pressure: PressureHistory = pressureFor(capture),
	) = LiveSessionHistorySnapshot(
		segmentId = 42L,
		session = SessionHistoryQuery.Found(
			SessionHistory(
				segmentId = 42L,
				capture = capture,
				qualifiedSources = if (
					steps.count != null && capture.captures(HistorySource.STEPS)
				) {
					setOf(HistorySource.STEPS)
				} else {
					emptySet()
				},
				steps = steps,
			),
		),
		activity = ActivityHistoryQuery.Found(activity),
		pressure = PressureSessionHistoryQuery.Found(
			PressureSessionHistory(
				segmentId = 42L,
				capture = capture,
				qualifiedSources = if (pressure.hasRetainedObservation) {
					setOf(HistorySource.PRESSURE)
				} else {
					emptySet()
				},
				pressure = pressure,
			),
		),
	)

	private fun HistoryCapture.capturesOnlyActivity(): Boolean =
		(this as? HistoryCapture.Exact)?.revisions?.all { revision ->
			revision.capturedSources == setOf(HistorySource.ACTIVITY)
		} == true

	private fun HistoryCapture.captures(source: HistorySource): Boolean = when (this) {
		is HistoryCapture.Exact -> revisions.any { revision -> source in revision.capturedSources }
		is HistoryCapture.ImportedSteps -> source == HistorySource.STEPS
		HistoryCapture.Unverifiable -> false
	}

	private fun exactCapture(
		sources: Set<HistorySource>,
		controlSources: Set<HistorySource> = emptySet(),
	) = HistoryCapture.Exact(
		listOf(
			HistoryCaptureRevision(
				revision = 1L,
				effectiveAt = EpochMs(1_000L),
				capturedSources = sources,
				controlSources = controlSources,
			),
		),
	)

	private fun revision(revision: Long, sources: Set<HistorySource>) = HistoryCaptureRevision(
		revision = revision,
		effectiveAt = EpochMs(revision * 1_000L),
		capturedSources = sources,
		controlSources = emptySet(),
	)

	private fun activityFor(capture: HistoryCapture): ActivityHistoryEntry {
		val cause = when {
			capture == HistoryCapture.Unverifiable -> ActivityHistoryCause.LEGACY_UNVERIFIABLE
			capture is HistoryCapture.ImportedSteps -> ActivityHistoryCause.LEGACY_UNVERIFIABLE
			capture.captures(HistorySource.ACTIVITY) -> ActivityHistoryCause.NO_QUALIFIED_FACTS
			else -> ActivityHistoryCause.SOURCE_NOT_CAPTURED
		}
		return unavailableActivity(
			capturesOnlyActivity = capture.capturesOnlyActivity(),
			cause = cause,
			origin = if (capture is HistoryCapture.ImportedSteps) {
				ActivityHistoryOrigin.IMPORTED
			} else {
				ActivityHistoryOrigin.LOCAL
			},
		)
	}

	private fun unavailableActivity(
		capturesOnlyActivity: Boolean,
		cause: ActivityHistoryCause = ActivityHistoryCause.NO_QUALIFIED_FACTS,
		origin: ActivityHistoryOrigin = ActivityHistoryOrigin.LOCAL,
	) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(cause),
		origin = origin,
		capturesOnlyActivity = capturesOnlyActivity,
	)

	private fun pressureFor(capture: HistoryCapture): PressureHistory {
		val cause = when {
			capture == HistoryCapture.Unverifiable -> PressureHistoryCause.LEGACY_UNATTRIBUTED
			capture is HistoryCapture.ImportedSteps ->
				PressureHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
			capture.captures(HistorySource.PRESSURE) ->
				PressureHistoryCause.PROVIDER_UNAVAILABLE
			else -> PressureHistoryCause.SOURCE_NOT_CAPTURED
		}
		return unavailablePressure(cause)
	}

	private fun unavailablePressure(
		cause: PressureHistoryCause = PressureHistoryCause.PROVIDER_UNAVAILABLE,
	) = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(cause),
	)

	private fun stepsFor(capture: HistoryCapture): StepsHistory = when {
		capture == HistoryCapture.Unverifiable -> legacySteps()
		capture is HistoryCapture.ImportedSteps -> importedStepsWithoutValue()
		capture.captures(HistorySource.STEPS) -> capturedStepsWithoutValue()
		else -> notCapturedSteps()
	}

	private fun completeSteps(
		count: Long,
		availability: HistoryAvailability = HistoryAvailability.AVAILABLE,
	) = StepsHistory(
		count = count,
		availability = availability,
		evidence = if (count == 0L) HistoryEvidence.ACTIVE else HistoryEvidence.RECORDED,
		productState = HistoryProductState.READY,
		coverage = StepsHistoryCoverage.COMPLETE,
	)

	private fun notCapturedSteps() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.PARTIAL,
		coverage = StepsHistoryCoverage.NONE,
		causes = setOf(StepsHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun capturedStepsWithoutValue() = StepsHistory(
		count = null,
		availability = HistoryAvailability.AVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.PARTIAL,
		coverage = StepsHistoryCoverage.NONE,
		causes = setOf(StepsHistoryCause.NO_OBSERVATION),
	)

	private fun importedStepsWithoutValue() = StepsHistory(
		count = null,
		availability = HistoryAvailability.RETAINED_IMPORTED,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.PARTIAL,
		coverage = StepsHistoryCoverage.NONE,
		causes = setOf(StepsHistoryCause.FACTS_MISSING),
	)

	private fun legacySteps() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		causes = setOf(StepsHistoryCause.LEGACY_UNVERIFIED),
	)
}
