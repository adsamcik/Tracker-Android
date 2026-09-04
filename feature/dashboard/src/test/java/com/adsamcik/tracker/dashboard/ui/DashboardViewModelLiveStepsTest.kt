package com.adsamcik.tracker.dashboard.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.dashboard.data.DashboardHistoryRepository
import com.adsamcik.tracker.dashboard.data.DashboardLayout
import com.adsamcik.tracker.dashboard.data.DashboardLayoutStore
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveStepsValue
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
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

			repository.started shouldContainExactly listOf(FIRST_SEGMENT_ID)
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

			repository.cancelled shouldContain FIRST_SEGMENT_ID
			repository.started shouldContainExactly listOf(FIRST_SEGMENT_ID, SECOND_SEGMENT_ID)
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.HistoryUnavailable(SECOND_SEGMENT_ID)
			observed shouldContain DashboardLiveSessionPresentation.Resolving(SECOND_SEGMENT_ID)

			running.value = false
			advanceUntilIdle()

			repository.cancelled shouldContain SECOND_SEGMENT_ID
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.Inactive
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

			repository.update(
				FIRST_SEGMENT_ID,
				SessionHistoryQuery.Found(stepsOnlyHistory(SECOND_SEGMENT_ID)),
			)
			advanceUntilIdle()
			viewModel.liveSessionPresentation.value shouldBe
				DashboardLiveSessionPresentation.HistoryUnavailable(FIRST_SEGMENT_ID)
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

	private companion object {
		const val FIRST_SEGMENT_ID = 41L
		const val SECOND_SEGMENT_ID = 42L
	}
}

private class RecordingTrackingHistoryRepository : TrackingHistoryRepository {
	private val states = mutableMapOf<Long, MutableStateFlow<SessionHistoryQuery>>()
	val started = mutableListOf<Long>()
	val cancelled = mutableListOf<Long>()

	override fun observeSession(segmentId: Long): Flow<SessionHistoryQuery> = flow {
		started += segmentId
		try {
			emitAll(state(segmentId))
		} finally {
			cancelled += segmentId
		}
	}

	override fun observeRecentStepsOnlyEntries(limit: Int): Flow<List<StepsOnlyHistoryEntry>> =
		flowOf(emptyList())

	override fun observeRecentStepsAwarePage(
		candidateSegmentIds: List<Long>,
		limit: Int,
	): Flow<List<StepsAwareHistoryPageEntry>> = flowOf(emptyList())

	fun update(segmentId: Long, query: SessionHistoryQuery) {
		state(segmentId).value = query
	}

	private fun state(segmentId: Long): MutableStateFlow<SessionHistoryQuery> =
		states.getOrPut(segmentId) { MutableStateFlow(SessionHistoryQuery.NotFound) }
}
