package com.adsamcik.tracker.tracker.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.processor.ProcessorContext
import com.adsamcik.tracker.stats.api.processor.ProcessorDescriptor
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Session crash recovery contract test.
 *
 * Simulates the "process killed mid-session" scenario by writing an orphan
 * [SessionSegment] row directly to the database (as the
 * [com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent]
 * would have written before the crash), then exercising the next
 * [TrackingOrchestrator] lifecycle to pin the recovery contract:
 *
 *  1. **Orphan preservation:** `initialize()` does NOT scan for, mutate,
 *     or delete unfinished session_segment rows. The row from a crashed
 *     session is preserved byte-identical. A new zero-distance placeholder
 *     segment IS added for the freshly started session, but the crashed
 *     session's data is never touched. This documents an intentional
 *     design choice — the tracker treats existing rows as ground truth
 *     and never destructively "cleans up" stale data on startup.
 *
 *  2. **Daily-summary catch-up:** The orchestrator's shutdown path calls
 *     `DailySummaryAggregator.materializeToday()`, which re-reads ALL
 *     session_segment rows for today. A second session's normal shutdown
 *     therefore picks up the orphan from the crashed session and rolls it
 *     into today's `daily_summary` row alongside the second session's own
 *     segments — even though the first session never ran shutdown itself.
 *
 * Reuses the private-fake pattern from
 * [TrackingOrchestratorIntegrationTest] / [MultiSessionLifecycleTest] so this
 * file stays independent. No production code is modified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCrashRecoveryTest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var dispatchersProvider: DispatchersProvider
	private val testDispatcher = StandardTestDispatcher()

	@Before
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dispatchersProvider = TestDispatchersProvider(testDispatcher)
	}

	@After
	fun tearDown() {
		database.close()
		Dispatchers.resetMain()
	}

	@Test
	fun `initialize preserves orphan session_segment row written by a crashed session`() = runTest(testDispatcher) {
		// Simulate a crash mid-session: the SessionTrackerComponent had time to
		// persist a segment row, but the process died before shutdown() ran.
		val orphanStartMs = todayStartMs() + ONE_HOUR_MS * 6L
		val orphanEndMs = orphanStartMs + ONE_HOUR_MS
		val orphanId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = orphanStartMs,
				endTimeMs = orphanEndMs,
				distanceM = 2_500f,
				steps = 4_000,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 12,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "crashed-v1",
				createdAt = orphanStartMs,
			),
		)
		orphanId shouldBeGreaterThanOrEqualTo 1L

		// Initialize a brand-new orchestrator — as the freshly restarted
		// service would do after the OS killed the prior process.
		val controller = DefaultTrackerServiceController()
		val domainEvents = RecordingDomainEventRepository()
		val orchestrator = TrackingOrchestrator(
			controller = controller,
			signalProcessors = setOf(NoOpProcessor()),
			domainEventRepository = domainEvents,
			dispatchers = dispatchersProvider,
			appDatabase = database,
			trackingParamsRepository = newTrackingParamsRepo(),
			dailySummaryFallbackEnqueuer = { /* no-op */ },
			enableNotifications = false,
		)

		controller.updateServiceRunning(true)
		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = true,
			initialTier = PolicyTier.PRECISION,
			scope = backgroundScope,
		)
		testDispatcher.scheduler.advanceUntilIdle()

		// CONTRACT: initialize() is non-destructive with respect to existing
		// session_segment rows. The orphan row is preserved byte-identical.
		// A new zero-distance placeholder segment is added for the freshly
		// started session, but the crashed session's data is never mutated
		// or deleted.
		val afterInit = database.sessionSegmentDao().getAllBetween(0L, Long.MAX_VALUE)
		afterInit shouldHaveAtLeastSize 1
		val preserved = afterInit.single { it.id == orphanId }
		preserved.id shouldBe orphanId
		preserved.startTimeMs shouldBe orphanStartMs
		preserved.endTimeMs shouldBe orphanEndMs
		preserved.distanceM shouldBe 2_500f.plusOrMinus(0.001f)
		preserved.steps shouldBe 4_000
		preserved.source shouldBe SegmentSource.USER_CREATED
		preserved.inferenceVersion shouldBe "crashed-v1"

		// Clean shutdown so other tests are not affected by lingering state.
		orchestrator.shutdown(context)
		testDispatcher.scheduler.advanceUntilIdle()
	}

	@Test
	fun `second session's shutdown materializes daily_summary that includes a crashed session's orphan segment`() = runTest(testDispatcher) {
		val today = LocalDate.now().toEpochDay()
		val todayStart = startOfLocalDayMs(today)

		// Crashed session: one orphan segment in today's local day.
		val orphanStart = todayStart + ONE_HOUR_MS * 3L
		val orphanEnd = orphanStart + ONE_HOUR_MS
		database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = orphanStart,
				endTimeMs = orphanEnd,
				distanceM = 1_000f,
				steps = 2_000,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 8,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "crashed-v1",
				createdAt = orphanStart,
			),
		)
		// No prior daily_summary row — the crashed session never had a chance
		// to materialize one.
		database.dailySummaryDao().getByDay(today) shouldBe null

		// Second session: insert a second segment for today (simulating what
		// the next SessionTrackerComponent would persist) and then run a full
		// orchestrator shutdown so materializeToday() reads both rows.
		val secondStart = todayStart + ONE_HOUR_MS * 10L
		val secondEnd = secondStart + (ONE_HOUR_MS / 2L)
		database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = secondStart,
				endTimeMs = secondEnd,
				distanceM = 500f,
				steps = 800,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 6,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "recovery-v2",
				createdAt = secondStart,
			),
		)

		runShutdownOnlyOrchestrator(backgroundScope)

		// CONTRACT: the second session's shutdown re-reads today's segments
		// and rolls the orphan into the materialized daily_summary.
		val summary = database.dailySummaryDao().getByDay(today)
		summary.shouldNotBeNull()
		summary.totalDistanceM shouldBe (1_000f + 500f).plusOrMinus(0.5f)
		summary.totalSteps shouldBe (2_000 + 800)
		summary.totalDurationMs shouldBe (ONE_HOUR_MS + ONE_HOUR_MS / 2L)
		summary.tripCount shouldBe 2

		// Both segments are still present — neither orphan cleanup nor segment
		// merging happens implicitly on shutdown.
		database.sessionSegmentDao().getAllBetween(0L, Long.MAX_VALUE) shouldHaveAtLeastSize 2
	}

	// --- helpers ----------------------------------------------------------------

	/**
	 * Run a brand-new orchestrator end-to-end (init -> shutdown with zero
	 * tracking cycles) so the shutdown path's `materializeToday()` executes
	 * against the current `session_segment` table state.
	 */
	private suspend fun runShutdownOnlyOrchestrator(
		scope: CoroutineScope,
	) {
		val controller = DefaultTrackerServiceController()
		val orchestrator = TrackingOrchestrator(
			controller = controller,
			signalProcessors = setOf(NoOpProcessor()),
			domainEventRepository = RecordingDomainEventRepository(),
			dispatchers = dispatchersProvider,
			appDatabase = database,
			trackingParamsRepository = newTrackingParamsRepo(),
			dailySummaryFallbackEnqueuer = { /* no-op */ },
			enableNotifications = false,
		)
		controller.updateServiceRunning(true)
		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = true,
			initialTier = PolicyTier.PRECISION,
			scope = scope,
		)
		testDispatcher.scheduler.advanceUntilIdle()
		orchestrator.shutdown(context)
		testDispatcher.scheduler.advanceUntilIdle()
	}

	private fun newTrackingParamsRepo() = FakeTrackingParamsRepository(
		TrackingParamsState(
			activityEnabled = false,
			stepsEnabled = false,
			wifiEnabled = false,
			cellEnabled = false,
		),
	)

	private fun todayStartMs(): Long = startOfLocalDayMs(LocalDate.now().toEpochDay())

	private fun startOfLocalDayMs(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay)
		.atStartOfDay(ZoneId.systemDefault())
		.toInstant()
		.toEpochMilli()

	private class NoOpProcessor : SignalProcessor {
		override val descriptor = ProcessorDescriptor(
			id = "crash-recovery-test",
			requiredTier = PolicyTier.AMBIENT,
			flushIntervalMs = 60_000L,
			priority = 0,
		)
		override suspend fun onStart(context: ProcessorContext) = Unit
		override fun onSignal(signal: TrackingSignal) = Unit
		override suspend fun onFlush(): List<DomainEvent> = emptyList()
		override suspend fun onStop(): List<DomainEvent> = emptyList()
	}

	private class RecordingDomainEventRepository : DomainEventRepository {
		val persisted = mutableListOf<DomainEvent>()

		override suspend fun persist(events: List<DomainEvent>) {
			persisted += events
		}

		override fun observeEvents(since: EpochMs): Flow<List<DomainEvent>> = emptyFlow()

		override suspend fun getUnconsumedBatchWithIds(
			consumerId: String,
			limit: Int,
		): List<UnconsumedEvent> = emptyList()

		override suspend fun markBatchConsumed(
			consumerId: String,
			upToTimestamp: EpochMs,
			upToEventId: Long,
		) = Unit
	}

	private class FakeTrackingParamsRepository(
		initialState: TrackingParamsState,
	) : TrackingParamsRepository {
		private val state = MutableStateFlow(initialState)
		override val data: Flow<TrackingParamsState> = state.asStateFlow()
		override suspend fun update(block: TrackingParamsState.() -> TrackingParamsState) {
			state.update { it.block() }
		}
		override suspend fun setLocationEnabled(enabled: Boolean) {
			state.update { it.copy(locationEnabled = enabled) }
		}
		override suspend fun setActivityEnabled(enabled: Boolean) {
			state.update { it.copy(activityEnabled = enabled) }
		}
		override suspend fun setStepsEnabled(enabled: Boolean) {
			state.update { it.copy(stepsEnabled = enabled) }
		}
		override suspend fun setWifiEnabled(enabled: Boolean) {
			state.update { it.copy(wifiEnabled = enabled) }
		}
		override suspend fun setCellEnabled(enabled: Boolean) {
			state.update { it.copy(cellEnabled = enabled) }
		}
		override suspend fun setBarometerEnabled(enabled: Boolean) {
			state.update { it.copy(barometerEnabled = enabled) }
		}
		override suspend fun setTransitionDetectionEnabled(enabled: Boolean) {
			state.update { it.copy(transitionDetectionEnabled = enabled) }
		}
		override suspend fun setNotificationStyled(enabled: Boolean) {
			state.update { it.copy(notificationStyled = enabled) }
		}
		override suspend fun setMinDistanceMeters(meters: Int) {
			state.update { it.copy(minDistanceMeters = meters) }
		}
		override suspend fun setMinTimeSeconds(seconds: Int) {
			state.update { it.copy(minTimeSeconds = seconds) }
		}
		override suspend fun setRequiredAccuracyMeters(meters: Int) {
			state.update { it.copy(requiredAccuracyMeters = meters) }
		}
		override suspend fun setPreset(preset: TrackingPreset) {
			state.update { it.copy(presetName = preset.name) }
		}
	}

	private companion object {
		const val ONE_HOUR_MS: Long = 60L * 60L * 1_000L
	}
}
