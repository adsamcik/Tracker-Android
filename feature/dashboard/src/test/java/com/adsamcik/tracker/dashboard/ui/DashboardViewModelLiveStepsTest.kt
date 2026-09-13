package com.adsamcik.tracker.dashboard.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.dashboard.data.DashboardHistoryRepository
import com.adsamcik.tracker.dashboard.data.DashboardLayout
import com.adsamcik.tracker.dashboard.data.DashboardLayoutStore
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureMetrics
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveStepsValue
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.ActivityActiveTime
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryConfidence
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryMechanism
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryWallTimeContinuity
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.LiveSessionHistorySnapshot
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryWindow
import com.adsamcik.tracker.stats.api.repository.PressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.PressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.StepsAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardViewModelLiveStepsTest {
	@Test
	fun `segment replacement and stop cancel the previous history observation`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val running = MutableStateFlow(false)
			val session = MutableStateFlow<TrackerSessionSnapshot?>(null)
			val repository = RecordingTrackingHistoryRepository()
			val viewModel = createViewModel(running, session, repository)
			val observed = mutableListOf<DashboardLiveSessionPresentation>()
			val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.liveSessionPresentation.collect(observed::add)
			}

			session.value = TrackerSessionSnapshot(id = FIRST_SEGMENT_ID, start = 1L)
			running.value = true
			advanceUntilIdle()

			repository.liveStarted shouldContainExactly listOf(FIRST_SEGMENT_ID)
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.HistoryUnavailable(FIRST_SEGMENT_ID)
			observed shouldContain DashboardLiveSessionPresentation.Resolving(FIRST_SEGMENT_ID)

			repository.update(
				FIRST_SEGMENT_ID,
				SessionHistoryQuery.Found(stepsOnlyHistory(FIRST_SEGMENT_ID)),
			)
			advanceUntilIdle()
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.StepsOnly(
					FIRST_SEGMENT_ID,
					DashboardLiveStepsValue.Materializing,
				)

			session.value = TrackerSessionSnapshot(id = SECOND_SEGMENT_ID, start = 2L)
			advanceUntilIdle()

			repository.liveCancelled shouldContain FIRST_SEGMENT_ID
			repository.liveStarted shouldContainExactly listOf(FIRST_SEGMENT_ID, SECOND_SEGMENT_ID)
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.HistoryUnavailable(SECOND_SEGMENT_ID)
			observed shouldContain DashboardLiveSessionPresentation.Resolving(SECOND_SEGMENT_ID)

			running.value = false
			advanceUntilIdle()

			repository.liveCancelled shouldContain SECOND_SEGMENT_ID
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.Inactive
			collection.cancel()
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `accepted exact Pressure only authority selects direct Pressure layout`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val running = MutableStateFlow(true)
			val session = MutableStateFlow<TrackerSessionSnapshot?>(
				TrackerSessionSnapshot(id = FIRST_SEGMENT_ID, start = 1L),
			)
			val repository = RecordingTrackingHistoryRepository()
			val viewModel = createViewModel(running, session, repository)
			val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.liveSessionPresentation.collect { }
			}
			advanceUntilIdle()

			repository.updatePressure(
				FIRST_SEGMENT_ID,
				PressureSessionHistoryQuery.Found(pressureHistory(FIRST_SEGMENT_ID)),
			)
			advanceUntilIdle()

			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.PressureOnly(
					FIRST_SEGMENT_ID,
					DashboardLivePressureValue.Ready(
						DashboardLivePressureMetrics(
							latestHectopascals = 1001.5f,
							minimumHectopascals = 999.5f,
							maximumHectopascals = 1002.0f,
							changeHectopascals = 1.5f,
							coverage = PressureHistoryCoverage.COMPLETE,
							zoneAuthorities = listOf("Europe/Prague"),
						),
					),
				)
			collection.cancel()
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `Pressure only intent selects direct layout before the first fact`() =
		runTest {
			val mainDispatcher = StandardTestDispatcher(testScheduler)
			Dispatchers.setMain(mainDispatcher)
			try {
				val running = MutableStateFlow(true)
				val session = MutableStateFlow<TrackerSessionSnapshot?>(
					TrackerSessionSnapshot(id = FIRST_SEGMENT_ID, start = 1L),
				)
				val repository = RecordingTrackingHistoryRepository()
				val viewModel = createViewModel(running, session, repository)
				val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
					viewModel.liveSessionPresentation.collect { }
				}
				repository.update(
					FIRST_SEGMENT_ID,
					SessionHistoryQuery.Found(pressureIntentFallbackHistory(FIRST_SEGMENT_ID)),
				)
				repository.updatePressure(
					FIRST_SEGMENT_ID,
					PressureSessionHistoryQuery.Found(
						pressureHistory(FIRST_SEGMENT_ID, retained = false, retentionMarker = false),
					),
				)
				advanceUntilIdle()

				viewModel.liveSessionPresentation.value shouldBe
					DashboardLiveSessionPresentation.PressureOnly(
						FIRST_SEGMENT_ID,
						DashboardLivePressureValue.Partial(null),
					)

				repository.updatePressure(
					FIRST_SEGMENT_ID,
					PressureSessionHistoryQuery.Found(
						pressureHistory(FIRST_SEGMENT_ID, retained = false, retentionMarker = true),
					),
				)
				advanceUntilIdle()
				viewModel.liveSessionPresentation.value shouldBe
					DashboardLiveSessionPresentation.PressureOnly(
						FIRST_SEGMENT_ID,
						DashboardLivePressureValue.Partial(null),
					)
				collection.cancel()
			} finally {
				Dispatchers.resetMain()
			}
		}

	@Test
	fun `mixed Pressure history never hides the standard surface`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val running = MutableStateFlow(true)
			val session = MutableStateFlow<TrackerSessionSnapshot?>(
				TrackerSessionSnapshot(id = FIRST_SEGMENT_ID, start = 1L),
			)
			val repository = RecordingTrackingHistoryRepository()
			val viewModel = createViewModel(running, session, repository)
			val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.liveSessionPresentation.collect { }
			}
			repository.update(
				FIRST_SEGMENT_ID,
				SessionHistoryQuery.Found(mixedHistory(FIRST_SEGMENT_ID)),
			)
			repository.updatePressure(
				FIRST_SEGMENT_ID,
				PressureSessionHistoryQuery.Found(
					pressureHistory(FIRST_SEGMENT_ID, capturedSources = setOf(
						HistorySource.PRESSURE,
						HistorySource.LOCATION,
					)),
				),
			)
			advanceUntilIdle()
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.Standard(FIRST_SEGMENT_ID)
			collection.cancel()
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `only exact Steps capture selects Steps layout and mismatched rows fail closed`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val running = MutableStateFlow(true)
			val session = MutableStateFlow<TrackerSessionSnapshot?>(
				TrackerSessionSnapshot(id = FIRST_SEGMENT_ID, start = 1L),
			)
			val repository = RecordingTrackingHistoryRepository()
			val viewModel = createViewModel(running, session, repository)
			val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.liveSessionPresentation.collect { }
			}
			advanceUntilIdle()

			// Activity is control-only and must not turn the captured history into a mixed session.
			repository.update(
				FIRST_SEGMENT_ID,
				SessionHistoryQuery.Found(stepsOnlyHistory(FIRST_SEGMENT_ID)),
			)
			advanceUntilIdle()
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.StepsOnly(
					FIRST_SEGMENT_ID,
					DashboardLiveStepsValue.Materializing,
				)

			repository.update(
				FIRST_SEGMENT_ID,
				SessionHistoryQuery.Found(mixedHistory(FIRST_SEGMENT_ID)),
			)
			advanceUntilIdle()
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.Standard(FIRST_SEGMENT_ID)

			repository.update(
				FIRST_SEGMENT_ID,
				SessionHistoryQuery.Found(
					mixedHistory(FIRST_SEGMENT_ID, qualifiedSteps = true),
				),
			)
			advanceUntilIdle()
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.Standard(
					FIRST_SEGMENT_ID,
					DashboardLiveStepsValue.Complete(1_500L),
				)
			collection.cancel()
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `exact Activity-only live snapshot exposes only retained captured evidence`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val running = MutableStateFlow(true)
			val session = MutableStateFlow<TrackerSessionSnapshot?>(
				TrackerSessionSnapshot(id = FIRST_SEGMENT_ID, start = 1L),
			)
			val repository = RecordingTrackingHistoryRepository()
			repository.update(
				FIRST_SEGMENT_ID,
				SessionHistoryQuery.Found(activityOnlySessionHistory(FIRST_SEGMENT_ID)),
			)
			repository.updateActivity(
				FIRST_SEGMENT_ID,
				ActivityHistoryQuery.Found(capturedActivityEntry()),
			)
			val viewModel = createViewModel(running, session, repository)
			val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.liveSessionPresentation.collect { }
			}

			advanceUntilIdle()

			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.ActivityOnly(
					segmentId = FIRST_SEGMENT_ID,
					activity = com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveActivityValue(
						state = ActivityHistoryProductState.PARTIAL,
						coverage = ActivityHistoryCoverage.PARTIAL,
						knownActiveDurationNanos = 2_000_000_000L,
						latestMovementBand = ActivityHistoryType.WALKING,
						gapCount = 0,
						hasRetainedQualifiedEvidence = true,
					),
				)
			collection.cancel()
		} finally {
			Dispatchers.resetMain()
		}
	}

	private fun createViewModel(
		running: MutableStateFlow<Boolean>,
		session: MutableStateFlow<TrackerSessionSnapshot?>,
		trackingHistoryRepository: TrackingHistoryRepository,
	): DashboardViewModel {
		val layoutRepository = mockk<DashboardLayoutStore>()
		every { layoutRepository.layout } returns flowOf(DashboardLayout())
		val trackerStateReader = mockk<TrackerStateReader>()
		every { trackerStateReader.isServiceRunningFlow } returns running
		every { trackerStateReader.sessionFlow } returns session
		val lockManager = mockk<LockManager>()
		every { lockManager.isLockedFlow } returns MutableStateFlow(false)
		val trackingParamsRepository = mockk<TrackingParamsRepository>()
		every { trackingParamsRepository.data } returns flowOf(TrackingParamsState())

		return DashboardViewModel(
			appContext = ApplicationProvider.getApplicationContext<Context>(),
			historyRepository = mockk<DashboardHistoryRepository>(),
			trackingHistoryRepository = trackingHistoryRepository,
			layoutRepository = layoutRepository,
			sessionInsightsGenerator = mockk(relaxed = true),
			widgetRegistry = mockk(relaxed = true),
			trackerStateReader = trackerStateReader,
			lockManager = lockManager,
			dailySummaryProvider = mockk(),
			dailyPointsProviderFactory = mockk(),
			goalProgressProviderFactory = mockk(),
			trackingParamsRepository = trackingParamsRepository,
		)
	}

	private fun stepsOnlyHistory(segmentId: Long) = SessionHistory(
		segmentId = segmentId,
		capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(1L),
					capturedSources = setOf(HistorySource.STEPS),
					controlSources = setOf(HistorySource.ACTIVITY),
				),
			),
		),
		// Capture authority, not post-observation qualification, selects the live layout.
		qualifiedSources = emptySet(),
		steps = materializingSteps(),
	)

	private fun activityOnlySessionHistory(segmentId: Long) = SessionHistory(
		segmentId = segmentId,
		capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(100L),
					capturedSources = setOf(HistorySource.ACTIVITY),
					controlSources = emptySet(),
				),
			),
		),
		qualifiedSources = emptySet(),
		steps = materializingSteps(),
	)

	private fun capturedActivityEntry() = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity"),
		startTime = EpochMs(100L),
		endTime = EpochMs(2_100L),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.PARTIAL,
		coverage = ActivityHistoryCoverage.PARTIAL,
		activeTime = ActivityActiveTime(2_000_000_000L, 0L, 0L, 0L),
		fragments = listOf(
			ActivityHistoryFragment.Band(
				storedZoneId = "UTC",
				startTime = EpochMs(100L),
				endTime = EpochMs(2_100L),
				startUncertaintyMs = 0L,
				endUncertaintyMs = 0L,
				activity = ActivityHistoryType.WALKING,
				mechanism = ActivityHistoryMechanism.TRANSITION,
				refinedTransitionActivity = null,
				confidence = ActivityHistoryConfidence.TransitionSignal,
				wallTimeContinuity = ActivityHistoryWallTimeContinuity.SAME_ANCHOR,
				durationNanos = 2_000_000_000L,
			),
		),
		causes = setOf(ActivityHistoryCause.PROVIDER_GAP),
		capturesOnlyActivity = true,
	)

	private fun mixedHistory(
		segmentId: Long,
		qualifiedSteps: Boolean = false,
	) = SessionHistory(
		segmentId = segmentId,
		capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(1L),
					capturedSources = setOf(HistorySource.STEPS),
					controlSources = emptySet(),
				),
				HistoryCaptureRevision(
					revision = 2L,
					effectiveAt = EpochMs(2L),
					capturedSources = setOf(HistorySource.STEPS, HistorySource.LOCATION),
					controlSources = emptySet(),
				),
			),
		),
		qualifiedSources = if (qualifiedSteps) {
			setOf(HistorySource.STEPS)
		} else {
			emptySet()
		},
		steps = if (qualifiedSteps) {
			completeSteps()
		} else {
			materializingSteps()
		},
	)

	private fun completeSteps() = StepsHistory(
		count = 1_500L,
		availability = HistoryAvailability.AVAILABLE,
		evidence = HistoryEvidence.RECORDED,
		productState = HistoryProductState.READY,
		coverage = StepsHistoryCoverage.COMPLETE,
	)

	private fun materializingSteps() = StepsHistory(
		count = null,
		availability = HistoryAvailability.AVAILABLE,
		evidence = HistoryEvidence.STARTING,
		productState = HistoryProductState.MATERIALIZING,
		coverage = StepsHistoryCoverage.NONE,
		causes = setOf(StepsHistoryCause.SESSION_STILL_ACTIVE),
	)

	private fun pressureIntentFallbackHistory(segmentId: Long) = SessionHistory(
		segmentId = segmentId,
		capture = capture(setOf(HistorySource.PRESSURE)),
		qualifiedSources = emptySet(),
		steps = StepsHistory(
			count = null,
			availability = HistoryAvailability.DISABLED,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.DEGRADED,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.SOURCE_NOT_CAPTURED),
		),
	)

	private fun pressureHistory(
		segmentId: Long,
		retained: Boolean = true,
		retentionMarker: Boolean = false,
		capturedSources: Set<HistorySource> = setOf(HistorySource.PRESSURE),
	): PressureSessionHistory {
		val windows = if (retained) listOf(pressureWindow()) else emptyList()
		return PressureSessionHistory(
			segmentId = segmentId,
			capture = capture(capturedSources),
			qualifiedSources = if (retained) setOf(HistorySource.PRESSURE) else emptySet(),
			pressure = PressureHistory(
				availability = HistoryAvailability.AVAILABLE,
				evidence = if (retained) HistoryEvidence.RECORDED else HistoryEvidence.NONE,
				productState = if (retained) HistoryProductState.READY else HistoryProductState.PARTIAL,
				coverage = if (retained) {
					PressureHistoryCoverage.COMPLETE
				} else {
					PressureHistoryCoverage.UNKNOWN
				},
				windows = windows,
				causes = if (retained) {
					emptySet()
				} else if (retentionMarker) {
					setOf(PressureHistoryCause.RETENTION_TRUNCATED)
				} else {
					setOf(PressureHistoryCause.FACTS_MISSING_FOR_ADMITTED_RUN)
				},
			),
		)
	}

	private fun capture(sources: Set<HistorySource>) = HistoryCapture.Exact(
		listOf(
			HistoryCaptureRevision(
				revision = 1L,
				effectiveAt = EpochMs(1L),
				capturedSources = sources,
				controlSources = setOf(HistorySource.ACTIVITY),
			),
		),
	)

	private fun pressureWindow() = PressureHistoryWindow(
		intervalStartTime = EpochMs(1L),
		intervalEndTime = EpochMs(2L),
		sampleCount = 5,
		expectedSampleCount = 5,
		meanHectopascals = 1000.5,
		sumSquaredDeviations = 1.0,
		minimumHectopascals = 999.5f,
		maximumHectopascals = 1002.0f,
		firstHectopascals = 1000.0f,
		latestHectopascals = 1001.5f,
		slopeHectopascalsPerSecond = 0.1,
		rSquared = 0.8,
		sensorAccuracy = PressureSensorAccuracy.HIGH,
		effectiveSamplePeriodMicros = 200_000,
		effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 1_000_000L,
		maximumInterSampleGapNanos = 200_000L,
		closure = PressureWindowClosure.TARGET_ELAPSED,
		qualification = PressureWindowQualification.COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		zoneId = "Europe/Prague",
	)

	private companion object {
		const val FIRST_SEGMENT_ID = 41L
		const val SECOND_SEGMENT_ID = 42L
	}
}

private class RecordingTrackingHistoryRepository : TrackingHistoryRepository {
	private val snapshots = mutableMapOf<Long, MutableStateFlow<LiveSessionHistorySnapshot>>()
	val liveStarted = mutableListOf<Long>()
	val liveCancelled = mutableListOf<Long>()

	override fun observeSession(segmentId: Long): Flow<SessionHistoryQuery> =
		flowOf(SessionHistoryQuery.NotFound)

	override fun observeLiveSession(segmentId: Long): Flow<LiveSessionHistorySnapshot> = flow {
		liveStarted += segmentId
		try {
			emitAll(snapshotState(segmentId))
		} finally {
			liveCancelled += segmentId
		}
	}

	override fun observeRecentStepsOnlyEntries(limit: Int): Flow<List<StepsOnlyHistoryEntry>> =
		flowOf(emptyList())

	override fun observeRecentStepsAwarePage(
		candidateSegmentIds: List<Long>,
		limit: Int,
	): Flow<List<StepsAwareHistoryPageEntry>> = flowOf(emptyList())

	override fun observeRecentSourceAwarePage(
		candidateSegmentIds: List<Long>,
		limit: Int,
	): Flow<SourceAwareHistoryPageQuery> = flowOf(SourceAwareHistoryPageQuery.Content(emptyList()))

	override fun observePressureSession(segmentId: Long): Flow<PressureSessionHistoryQuery> =
		flowOf(PressureSessionHistoryQuery.NotFound)

	override fun observeRecentPressureOnlyEntries(
		limit: Int,
	): Flow<List<PressureOnlyHistoryEntry>> = flowOf(emptyList())

	fun update(segmentId: Long, query: SessionHistoryQuery) {
		snapshotState(segmentId).value = when (query) {
			SessionHistoryQuery.NotFound -> missingSnapshot(segmentId)
			is SessionHistoryQuery.Found -> foundSnapshot(query)
		}
	}

	fun updatePressure(segmentId: Long, query: PressureSessionHistoryQuery) {
		val found = when (query) {
			PressureSessionHistoryQuery.NotFound -> {
				snapshotState(segmentId).value = missingSnapshot(segmentId)
				return
			}
			is PressureSessionHistoryQuery.Found -> query
		}
		val current = snapshotState(segmentId).value
		val session = (current.session as? SessionHistoryQuery.Found)?.takeIf {
			it.history.capture == found.history.capture
		} ?: SessionHistoryQuery.Found(
			SessionHistory(
				segmentId = segmentId,
				capture = found.history.capture,
				qualifiedSources = emptySet(),
				steps = missingStepsHistory(),
			),
		)
		snapshotState(segmentId).value = LiveSessionHistorySnapshot(
			segmentId = segmentId,
			session = session,
			activity = defaultActivityQuery(segmentId),
			pressure = found,
		)
	}

	fun updateActivity(segmentId: Long, query: ActivityHistoryQuery) {
		val found = when (query) {
			ActivityHistoryQuery.NotFound -> {
				snapshotState(segmentId).value = missingSnapshot(segmentId)
				return
			}
			is ActivityHistoryQuery.Found -> query
		}
		val current = snapshotState(segmentId).value
		val session = current.session as? SessionHistoryQuery.Found
			?: error("Activity update requires a common session snapshot")
		val pressure = current.pressure as? PressureSessionHistoryQuery.Found
			?: defaultPressureQuery(session.history)
		snapshotState(segmentId).value = LiveSessionHistorySnapshot(
			segmentId = segmentId,
			session = session,
			activity = found,
			pressure = pressure,
		)
	}

	private fun foundSnapshot(query: SessionHistoryQuery.Found) = LiveSessionHistorySnapshot(
		segmentId = query.history.segmentId,
		session = query,
		activity = defaultActivityQuery(query.history.segmentId),
		pressure = defaultPressureQuery(query.history),
	)

	private fun snapshotState(segmentId: Long): MutableStateFlow<LiveSessionHistorySnapshot> =
		snapshots.getOrPut(segmentId) { MutableStateFlow(missingSnapshot(segmentId)) }

	private fun missingSnapshot(segmentId: Long) = LiveSessionHistorySnapshot(
		segmentId = segmentId,
		session = SessionHistoryQuery.NotFound,
		activity = ActivityHistoryQuery.NotFound,
		pressure = PressureSessionHistoryQuery.NotFound,
	)

	private fun defaultActivityQuery(segmentId: Long) = ActivityHistoryQuery.Found(
		ActivityHistoryEntry(
			key = ActivityHistoryEntryKey("mixed:$segmentId"),
			startTime = EpochMs(1L),
			endTime = EpochMs(2L),
			storedZoneIds = emptySet(),
			state = ActivityHistoryProductState.UNAVAILABLE,
			coverage = ActivityHistoryCoverage.NONE,
			activeTime = null,
			fragments = emptyList(),
			causes = setOf(ActivityHistoryCause.SOURCE_NOT_CAPTURED),
		),
	)

	private fun defaultPressureQuery(session: SessionHistory) = PressureSessionHistoryQuery.Found(
		PressureSessionHistory(
			segmentId = session.segmentId,
			capture = session.capture,
			qualifiedSources = emptySet(),
			pressure = missingPressureHistory(),
		),
	)

	private fun missingStepsHistory() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		causes = setOf(StepsHistoryCause.LEGACY_UNVERIFIED),
	)

	private fun missingPressureHistory() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.LEGACY_UNATTRIBUTED),
	)
}
