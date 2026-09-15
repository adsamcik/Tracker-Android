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
			qualifiedSources = emptySet(),
			steps = physicalStepsSourceNotCaptured(),
			activity = activity,
			pressure = physicalPressureSourceNotCaptured(),
		)

		assertPhysicalStepsSourceNotCaptured(
			(snapshot.session as SessionHistoryQuery.Found).history,
		)
		assertPhysicalPressureSourceNotCaptured(
			(snapshot.pressure as PressureSessionHistoryQuery.Found).history,
		)
		assertActivityOnlyNoQualifiedFacts(
			(snapshot.activity as ActivityHistoryQuery.Found).entry,
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
			qualifiedSources = emptySet(),
			steps = steps,
			activity = activitySourceNotCaptured(),
			pressure = physicalPressureSourceNotCaptured(),
		)

		assertCapturedStepsWithoutValue(
			(snapshot.session as SessionHistoryQuery.Found).history,
		)
		assertActivitySourceNotCaptured((snapshot.activity as ActivityHistoryQuery.Found).entry)
		assertPhysicalPressureSourceNotCaptured(
			(snapshot.pressure as PressureSessionHistoryQuery.Found).history,
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
			qualifiedSources = setOf(HistorySource.STEPS),
			steps = steps,
			activity = activitySourceNotCaptured(),
			pressure = physicalPressureSourceNotCaptured(),
		)

		val session = (snapshot.session as SessionHistoryQuery.Found).history
		session.qualifiedSources shouldBe setOf(HistorySource.STEPS)
		session.steps shouldBe StepsHistory(
			count = 12L,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
		)
		assertActivitySourceNotCaptured((snapshot.activity as ActivityHistoryQuery.Found).entry)
		assertPhysicalPressureSourceNotCaptured(
			(snapshot.pressure as PressureSessionHistoryQuery.Found).history,
		)
		snapshot.toTripDetailSourcePresentation() shouldBe
			TripDetailSourcePresentation.StepsOnly(steps)
	}

	@Test
	fun `every capture revision must remain exactly Pressure`() {
		val pressure = providerUnavailablePressure()
		val snapshot = snapshot(
			capture = HistoryCapture.Exact(
				listOf(
					revision(1L, setOf(HistorySource.PRESSURE)),
					revision(2L, setOf(HistorySource.PRESSURE)),
				),
			),
			qualifiedSources = emptySet(),
			steps = physicalStepsSourceNotCaptured(),
			activity = activitySourceNotCaptured(),
			pressure = pressure,
		)

		assertPhysicalStepsSourceNotCaptured(
			(snapshot.session as SessionHistoryQuery.Found).history,
		)
		assertActivitySourceNotCaptured((snapshot.activity as ActivityHistoryQuery.Found).entry)
		assertProviderUnavailablePressure(
			(snapshot.pressure as PressureSessionHistoryQuery.Found).history,
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
			qualifiedSources = emptySet(),
			steps = partialCaptureStepsWithoutValue(),
			activity = unavailableActivity(capturesOnlyActivity = false),
			pressure = physicalPressureSourceNotCaptured(),
		)

		val session = (snapshot.session as SessionHistoryQuery.Found).history
		session.qualifiedSources shouldBe emptySet<HistorySource>()
		session.steps shouldBe StepsHistory(
			count = null,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.CAPTURE_PARTIAL),
		)
		assertActivityNoQualifiedFacts((snapshot.activity as ActivityHistoryQuery.Found).entry)
		assertPhysicalPressureSourceNotCaptured(
			(snapshot.pressure as PressureSessionHistoryQuery.Found).history,
		)
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
			qualifiedSources = emptySet(),
			steps = physicalStepsSourceNotCaptured(),
			activity = activitySourceNotCaptured(),
			pressure = providerUnavailablePressure(),
		)

		assertPhysicalStepsSourceNotCaptured(
			(snapshot.session as SessionHistoryQuery.Found).history,
		)
		assertActivitySourceNotCaptured((snapshot.activity as ActivityHistoryQuery.Found).entry)
		assertProviderUnavailablePressure(
			(snapshot.pressure as PressureSessionHistoryQuery.Found).history,
		)
		val presentation = snapshot.toTripDetailSourcePresentation()
		presentation shouldBe TripDetailSourcePresentation.Standard
		loaded(presentation).supportsLocationPresentation shouldBe true
	}

	@Test
	fun `legacy unverifiable capture alone preserves retained standard detail`() {
		val snapshot = snapshot(
			capture = HistoryCapture.Unverifiable,
			qualifiedSources = emptySet(),
			steps = legacyUnverifiableSteps(),
			activity = legacyUnverifiableActivity(),
			pressure = legacyUnattributedPressure(),
		)
		assertLegacyUnverifiableSession(
			(snapshot.session as SessionHistoryQuery.Found).history,
		)
		assertLegacyUnverifiableActivity(
			(snapshot.activity as ActivityHistoryQuery.Found).entry,
		)
		assertLegacyUnattributedPressure(
			(snapshot.pressure as PressureSessionHistoryQuery.Found).history,
		)
		val presentation = snapshot.toTripDetailSourcePresentation()

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
			qualifiedSources = setOf(HistorySource.STEPS),
			steps = steps,
			activity = verifiedImportedStepsActivity(),
			pressure = verifiedImportedStepsPressure(),
		)
		val presentation = snapshot.toTripDetailSourcePresentation()

		presentation shouldBe TripDetailSourcePresentation.ImportedSteps(steps)
		assertVerifiedImportedStepsSession(
			(snapshot.session as SessionHistoryQuery.Found).history,
		)
		assertVerifiedImportedStepsActivity(
			(snapshot.activity as ActivityHistoryQuery.Found).entry,
		)
		assertVerifiedImportedStepsPressure(
			(snapshot.pressure as PressureSessionHistoryQuery.Found).history,
		)
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
		qualifiedSources: Set<HistorySource>,
		steps: StepsHistory,
		activity: ActivityHistoryEntry,
		pressure: PressureHistory,
	) = LiveSessionHistorySnapshot(
		segmentId = 42L,
		session = SessionHistoryQuery.Found(
			SessionHistory(
				segmentId = 42L,
				capture = capture,
				qualifiedSources = qualifiedSources,
				steps = steps,
			),
		),
		activity = ActivityHistoryQuery.Found(activity),
		pressure = PressureSessionHistoryQuery.Found(
			PressureSessionHistory(
				segmentId = 42L,
				capture = capture,
				qualifiedSources = emptySet(),
				pressure = pressure,
			),
		),
	)

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

	private fun unavailableActivity(
		capturesOnlyActivity: Boolean,
		cause: ActivityHistoryCause = ActivityHistoryCause.NO_QUALIFIED_FACTS,
		origin: ActivityHistoryOrigin = ActivityHistoryOrigin.LOCAL,
		storedZoneIds: Set<String> = setOf("UTC"),
	) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		storedZoneIds = storedZoneIds,
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(cause),
		origin = origin,
		capturesOnlyActivity = capturesOnlyActivity,
	)

	private fun activitySourceNotCaptured(
		origin: ActivityHistoryOrigin = ActivityHistoryOrigin.LOCAL,
		storedZoneIds: Set<String> = setOf("UTC"),
	) = unavailableActivity(
		capturesOnlyActivity = false,
		cause = ActivityHistoryCause.SOURCE_NOT_CAPTURED,
		origin = origin,
		storedZoneIds = storedZoneIds,
	)

	private fun legacyUnverifiableActivity() = unavailableActivity(
		capturesOnlyActivity = false,
		cause = ActivityHistoryCause.LEGACY_UNVERIFIABLE,
		storedZoneIds = emptySet(),
	)

	private fun verifiedImportedStepsActivity() = activitySourceNotCaptured(
		origin = ActivityHistoryOrigin.IMPORTED,
		storedZoneIds = emptySet(),
	)

	private fun providerUnavailablePressure() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.NONE,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.PROVIDER_UNAVAILABLE),
	)

	private fun physicalPressureSourceNotCaptured() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.FAILED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun verifiedImportedStepsPressure() = PressureHistory(
		availability = HistoryAvailability.DISABLED,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.NONE,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun legacyUnattributedPressure() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.FAILED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.LEGACY_UNATTRIBUTED),
	)

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

	private fun physicalStepsSourceNotCaptured() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.FAILED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		causes = setOf(
			StepsHistoryCause.SOURCE_NOT_CAPTURED,
			StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
		),
	)

	private fun capturedStepsWithoutValue() = StepsHistory(
		count = null,
		availability = HistoryAvailability.AVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.PARTIAL,
		coverage = StepsHistoryCoverage.NONE,
		causes = setOf(StepsHistoryCause.NO_OBSERVATION),
	)

	private fun partialCaptureStepsWithoutValue() = StepsHistory(
		count = null,
		availability = HistoryAvailability.AVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.PARTIAL,
		coverage = StepsHistoryCoverage.NONE,
		causes = setOf(StepsHistoryCause.CAPTURE_PARTIAL),
	)

	private fun legacyUnverifiableSteps() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		causes = setOf(
			StepsHistoryCause.LEGACY_UNVERIFIED,
			StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
		),
	)

	private fun assertActivitySourceNotCaptured(activity: ActivityHistoryEntry) {
		activity.state shouldBe ActivityHistoryProductState.UNAVAILABLE
		activity.coverage shouldBe ActivityHistoryCoverage.NONE
		activity.activeTime shouldBe null
		activity.fragments shouldBe emptyList()
		activity.causes shouldBe setOf(ActivityHistoryCause.SOURCE_NOT_CAPTURED)
		activity.origin shouldBe ActivityHistoryOrigin.LOCAL
		activity.capturesOnlyActivity shouldBe false
	}

	private fun assertPhysicalStepsSourceNotCaptured(history: SessionHistory) {
		history.qualifiedSources shouldBe emptySet<HistorySource>()
		history.steps shouldBe StepsHistory(
			count = null,
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(
				StepsHistoryCause.SOURCE_NOT_CAPTURED,
				StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
			),
		)
	}

	private fun assertCapturedStepsWithoutValue(history: SessionHistory) {
		history.qualifiedSources shouldBe emptySet<HistorySource>()
		history.steps shouldBe StepsHistory(
			count = null,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.NO_OBSERVATION),
		)
	}

	private fun assertVerifiedImportedStepsSession(history: SessionHistory) {
		history.qualifiedSources shouldBe setOf(HistorySource.STEPS)
		history.steps shouldBe StepsHistory(
			count = 0L,
			availability = HistoryAvailability.RETAINED_IMPORTED,
			evidence = HistoryEvidence.ACTIVE,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
		)
	}

	private fun assertActivityNoQualifiedFacts(activity: ActivityHistoryEntry) {
		activity.state shouldBe ActivityHistoryProductState.UNAVAILABLE
		activity.coverage shouldBe ActivityHistoryCoverage.NONE
		activity.activeTime shouldBe null
		activity.fragments shouldBe emptyList()
		activity.causes shouldBe setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS)
		activity.origin shouldBe ActivityHistoryOrigin.LOCAL
		activity.capturesOnlyActivity shouldBe false
	}

	private fun assertActivityOnlyNoQualifiedFacts(activity: ActivityHistoryEntry) {
		activity.state shouldBe ActivityHistoryProductState.UNAVAILABLE
		activity.coverage shouldBe ActivityHistoryCoverage.NONE
		activity.activeTime shouldBe null
		activity.fragments shouldBe emptyList()
		activity.causes shouldBe setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS)
		activity.origin shouldBe ActivityHistoryOrigin.LOCAL
		activity.capturesOnlyActivity shouldBe true
	}

	private fun assertVerifiedImportedStepsActivity(activity: ActivityHistoryEntry) {
		activity.state shouldBe ActivityHistoryProductState.UNAVAILABLE
		activity.coverage shouldBe ActivityHistoryCoverage.NONE
		activity.activeTime shouldBe null
		activity.fragments shouldBe emptyList()
		activity.causes shouldBe setOf(ActivityHistoryCause.SOURCE_NOT_CAPTURED)
		activity.origin shouldBe ActivityHistoryOrigin.IMPORTED
		activity.storedZoneIds shouldBe emptySet<String>()
		activity.capturesOnlyActivity shouldBe false
	}

	private fun assertPhysicalPressureSourceNotCaptured(history: PressureSessionHistory) {
		history.qualifiedSources shouldBe emptySet<HistorySource>()
		history.pressure shouldBe PressureHistory(
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.FAILED,
			coverage = PressureHistoryCoverage.UNKNOWN,
			windows = emptyList(),
			causes = setOf(PressureHistoryCause.SOURCE_NOT_CAPTURED),
		)
	}

	private fun assertProviderUnavailablePressure(history: PressureSessionHistory) {
		history.qualifiedSources shouldBe emptySet<HistorySource>()
		history.pressure shouldBe PressureHistory(
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.DEGRADED,
			coverage = PressureHistoryCoverage.NONE,
			windows = emptyList(),
			causes = setOf(PressureHistoryCause.PROVIDER_UNAVAILABLE),
		)
	}

	private fun assertVerifiedImportedStepsPressure(history: PressureSessionHistory) {
		history.qualifiedSources shouldBe emptySet<HistorySource>()
		history.pressure shouldBe PressureHistory(
			availability = HistoryAvailability.DISABLED,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.DEGRADED,
			coverage = PressureHistoryCoverage.NONE,
			windows = emptyList(),
			causes = setOf(PressureHistoryCause.SOURCE_NOT_CAPTURED),
		)
	}

	private fun assertLegacyUnverifiableSession(history: SessionHistory) {
		history.qualifiedSources shouldBe emptySet<HistorySource>()
		history.steps shouldBe StepsHistory(
			count = null,
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(
				StepsHistoryCause.LEGACY_UNVERIFIED,
				StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
			),
		)
	}

	private fun assertLegacyUnverifiableActivity(activity: ActivityHistoryEntry) {
		activity.state shouldBe ActivityHistoryProductState.UNAVAILABLE
		activity.coverage shouldBe ActivityHistoryCoverage.NONE
		activity.activeTime shouldBe null
		activity.fragments shouldBe emptyList()
		activity.causes shouldBe setOf(ActivityHistoryCause.LEGACY_UNVERIFIABLE)
		activity.origin shouldBe ActivityHistoryOrigin.LOCAL
		activity.storedZoneIds shouldBe emptySet<String>()
		activity.capturesOnlyActivity shouldBe false
	}

	private fun assertLegacyUnattributedPressure(history: PressureSessionHistory) {
		history.qualifiedSources shouldBe emptySet<HistorySource>()
		history.pressure shouldBe PressureHistory(
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.FAILED,
			coverage = PressureHistoryCoverage.UNKNOWN,
			windows = emptyList(),
			causes = setOf(PressureHistoryCause.LEGACY_UNATTRIBUTED),
		)
	}
}
