package com.adsamcik.tracker.tracker.service

import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
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
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.NoTimer
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Multi-session lifecycle integration test.
 *
 * Exercises a sequence of orchestrator lifecycles back-to-back against a
 * single shared [AppDatabase] to verify:
 *
 *  1. Two full init → cycles → shutdown cycles produce two distinct
 *     session_segment rows with non-overlapping time ranges.
 *  2. Each shutdown emits exactly one [DomainEvent.SessionEnded].
 *  3. An "empty session" (init → shutdown with zero cycles) leaves the
 *     database untouched — no stray session_segment row.
 *
 * Pure orchestrator wiring; no production code is modified.
 *
 * Tests reuse the [SessionEndProcessor], [RecordingDomainEventRepository]
 * and other fakes from [TrackingOrchestratorIntegrationTest] by declaring
 * local equivalents — keeping the two test files independent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class MultiSessionLifecycleTest {

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
	fun `two back-to-back sessions produce two non-overlapping session_segment rows`() = runTest(testDispatcher) {
		val domainEvents = RecordingDomainEventRepository()
		var fallbackEnqueueCount = 0

		// --- session 1 -----------------------------------------------------------
		val first = runFullSession(
			scope = backgroundScope,
			domainEvents = domainEvents,
			sessionStartMs = 1_000L,
			sessionEndMs = 2_000L,
			fallbackEnqueueCounter = { fallbackEnqueueCount++ },
		)
		first.shutdownResult.dailySummaryMaterialized shouldBe true
		first.processor.segmentCountWhenSessionEnded shouldBe 1L
		first.processor.lastSessionId shouldBeGreaterThanOrEqualTo 1L

		// --- session 2 -----------------------------------------------------------
		val second = runFullSession(
			scope = backgroundScope,
			domainEvents = domainEvents,
			sessionStartMs = 10_000L,
			sessionEndMs = 11_000L,
			fallbackEnqueueCounter = { fallbackEnqueueCount++ },
		)
		second.shutdownResult.dailySummaryMaterialized shouldBe true
		// At the moment the second SessionEnded is emitted, both segments must
		// already be in the database.
		second.processor.segmentCountWhenSessionEnded shouldBe 2L
		second.processor.lastSessionId shouldBeGreaterThanOrEqualTo first.processor.lastSessionId + 1L

		// --- shared assertions ---------------------------------------------------
		val segments = database.sessionSegmentDao()
			.getAllBetween(0L, Long.MAX_VALUE)
			.sortedBy { it.startTimeMs }
		segments shouldHaveSize 2
		val (s1, s2) = segments

		// Both segments must have valid time bounds with no overlap.
		(s1.startTimeMs < s1.endTimeMs) shouldBe true
		(s2.startTimeMs < s2.endTimeMs) shouldBe true
		(s1.endTimeMs <= s2.startTimeMs) shouldBe true
		// Distinct primary-key ids.
		(s1.id != s2.id) shouldBe true

		// Exactly one SessionEnded per shutdown.
		domainEvents.persisted.filterIsInstance<DomainEvent.SessionEnded>() shouldHaveSize 2
		// Fallback path must not be triggered when shutdown materializes cleanly.
		fallbackEnqueueCount shouldBe 0
	}

	@Test
	fun `init then shutdown with zero cycles persists no segment and emits no SessionEnded`() = runTest(testDispatcher) {
		val controller = DefaultTrackerServiceController()
		val domainEvents = RecordingDomainEventRepository()
		// Use a no-op processor so the assertion really verifies orchestrator
		// behaviour (no segments / no events), not the test-only SessionEnded
		// emission baked into SessionEndProcessor.
		val noopProcessor = NoOpProcessor()
		var fallbackEnqueueCount = 0

		val orchestrator = TrackingOrchestrator(
			controller = controller,
			trackerListenerManager = mockk<TrackerListenerManager>(relaxed = true),
			signalProcessors = setOf(noopProcessor),
			domainEventRepository = domainEvents,
			dispatchers = dispatchersProvider,
			appDatabase = database,
			trackingParamsRepository = newTrackingParamsRepo(),
			trackerSettingsRepository = FakeTrackerSettingsRepository(),
			dailySummaryFallbackEnqueuer = { fallbackEnqueueCount++ },
			enableNotifications = false,
		)

		controller.updateServiceRunning(true)
		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = true,
			initialTier = PolicyTier.PRECISION,
			scope = backgroundScope,
			timerReceiver = mockk(relaxed = true),
			timerAccessor = NoOpTimerAccessor(),
		)
		advanceUntilIdle()

		// Immediate shutdown with zero TrackingCycles in between.
		orchestrator.shutdown(context)
		advanceUntilIdle()

		database.sessionSegmentDao().getAllBetween(0L, Long.MAX_VALUE).shouldBeEmpty()
		domainEvents.persisted.filterIsInstance<DomainEvent.SessionEnded>().shouldBeEmpty()
		// The fallback enqueuer is a fail-safe — must not fire when there was
		// no session to materialize.
		fallbackEnqueueCount shouldBe 0
	}

	// --- helpers ----------------------------------------------------------------

	private data class SessionRunResult(
		val processor: SessionEndProcessor,
		val shutdownResult: ShutdownResult,
	)

	@OptIn(ExperimentalCoroutinesApi::class)
	private suspend fun runFullSession(
		scope: kotlinx.coroutines.CoroutineScope,
		domainEvents: RecordingDomainEventRepository,
		sessionStartMs: Long,
		sessionEndMs: Long,
		fallbackEnqueueCounter: () -> Unit,
	): SessionRunResult {
		val controller = DefaultTrackerServiceController()
		val processor = SessionEndProcessor(database, sessionEndMs)
		val orchestrator = TrackingOrchestrator(
			controller = controller,
			trackerListenerManager = mockk<TrackerListenerManager>(relaxed = true),
			signalProcessors = setOf(processor),
			domainEventRepository = domainEvents,
			dispatchers = dispatchersProvider,
			appDatabase = database,
			trackingParamsRepository = newTrackingParamsRepo(),
			trackerSettingsRepository = FakeTrackerSettingsRepository(),
			dailySummaryFallbackEnqueuer = { fallbackEnqueueCounter() },
			enableNotifications = false,
		)
		controller.updateServiceRunning(true)
		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = true,
			initialTier = PolicyTier.PRECISION,
			scope = scope,
			timerReceiver = mockk(relaxed = true),
			timerAccessor = NoOpTimerAccessor(),
		)
		testDispatcher.scheduler.advanceUntilIdle()

		val first = location(timeMs = sessionStartMs, latitude = 50.0, longitude = 14.0)
		orchestrator.onCycleUpdate(
			context = context,
			cycle = TrackingCycle(
				timestampMs = sessionStartMs,
				elapsedRealtimeNanos = sessionStartMs * 1_000_000L,
				location = LocationData(listOf(first), previousLocation = null, distance = null),
			),
		)
		val second = location(timeMs = sessionEndMs, latitude = 50.0001, longitude = 14.0001)
		orchestrator.onCycleUpdate(
			context = context,
			cycle = TrackingCycle(
				timestampMs = sessionEndMs,
				elapsedRealtimeNanos = sessionEndMs * 1_000_000L,
				location = LocationData(listOf(second), previousLocation = first, distance = 13f),
			),
		)

		val shutdownResult = orchestrator.shutdown(context)
		testDispatcher.scheduler.advanceUntilIdle()
		return SessionRunResult(processor = processor, shutdownResult = shutdownResult)
	}

	private fun newTrackingParamsRepo() = FakeTrackingParamsRepository(
		TrackingParamsState(
			activityEnabled = false,
			stepsEnabled = false,
			wifiEnabled = false,
			cellEnabled = false,
			skiDetectionEnabled = false,
		),
	)

	private fun location(timeMs: Long, latitude: Double, longitude: Double): Location {
		return Location("gps").apply {
			time = timeMs
			elapsedRealtimeNanos = timeMs * 1_000_000L
			this.latitude = latitude
			this.longitude = longitude
			accuracy = 5f
		}
	}

	/**
	 * Test processor that snapshots the session_segment count at SessionEnded
	 * time and emits exactly one [DomainEvent.SessionEnded] for the just-ended
	 * session. Uses `segments.last().id` so a second invocation against the
	 * same DB picks the second segment, not the first.
	 */
	private class SessionEndProcessor(
		private val database: AppDatabase,
		private val sessionEndMs: Long,
	) : SignalProcessor {
		var segmentCountWhenSessionEnded: Long = 0
			private set
		var lastSessionId: Long = -1L
			private set

		override val descriptor = ProcessorDescriptor(
			id = "multi-session-test",
			requiredTier = PolicyTier.AMBIENT,
			flushIntervalMs = 60_000L,
			priority = 0,
		)

		override suspend fun onStart(context: ProcessorContext) = Unit
		override fun onSignal(signal: TrackingSignal) = Unit
		override suspend fun onFlush(): List<DomainEvent> = emptyList()

		override suspend fun onStop(): List<DomainEvent> {
			val segments = database.sessionSegmentDao().getAllBetween(0L, Long.MAX_VALUE)
				.sortedBy { it.startTimeMs }
			segmentCountWhenSessionEnded = segments.size.toLong()
			val sessionId = segments.last().id
			lastSessionId = sessionId
			return listOf(
				DomainEvent.SessionEnded(
					timestampMs = EpochMs(sessionEndMs),
					processorId = descriptor.id,
					sessionId = sessionId,
					totalDistance = DistanceM.coerced(13f),
					totalSteps = StepCount(0),
					duration = DurationMs(1_000L),
				),
			)
		}

		override fun checkpoint(): ByteArray? = null
		override fun restore(state: ByteArray) = Unit
	}

	/** Does nothing. Used by the empty-session test. */
	private class NoOpProcessor : SignalProcessor {
		override val descriptor = ProcessorDescriptor(
			id = "noop-test",
			requiredTier = PolicyTier.AMBIENT,
			flushIntervalMs = 60_000L,
			priority = 0,
		)
		override suspend fun onStart(context: ProcessorContext) = Unit
		override fun onSignal(signal: TrackingSignal) = Unit
		override suspend fun onFlush(): List<DomainEvent> = emptyList()
		override suspend fun onStop(): List<DomainEvent> = emptyList()
		override fun checkpoint(): ByteArray? = null
		override fun restore(state: ByteArray) = Unit
	}

	private class RecordingDomainEventRepository : DomainEventRepository {
		val persisted = mutableListOf<DomainEvent>()

		override suspend fun persist(events: List<DomainEvent>) {
			persisted += events
		}

		override fun observeEvents(since: EpochMs): Flow<List<DomainEvent>> = emptyFlow()
		@Suppress("OVERRIDE_DEPRECATION")
		override suspend fun getUnconsumedBatch(
			consumerId: String,
			limit: Int,
		): List<DomainEvent> = emptyList()
		override suspend fun getUnconsumedBatchWithIds(
			consumerId: String,
			limit: Int,
		): List<UnconsumedEvent> = emptyList()
		@Suppress("OVERRIDE_DEPRECATION")
		override suspend fun markConsumed(
			consumerId: String,
			upToTimestamp: EpochMs,
		) = Unit
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
		override suspend fun setWifiNetworkEnabled(enabled: Boolean) {
			state.update { it.copy(wifiNetworkEnabled = enabled) }
		}
		override suspend fun setWifiLocationCountEnabled(enabled: Boolean) {
			state.update { it.copy(wifiLocationCountEnabled = enabled) }
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
		override suspend fun setSkiDetectionEnabled(enabled: Boolean) {
			state.update { it.copy(skiDetectionEnabled = enabled) }
		}
		override suspend fun setSailingDetectionEnabled(enabled: Boolean) {
			state.update { it.copy(sailingDetectionEnabled = enabled) }
		}
		override suspend fun setPlaneDetectionEnabled(enabled: Boolean) {
			state.update { it.copy(planeDetectionEnabled = enabled) }
		}
		override suspend fun setVehicleSpeedLimitBaselineMps(mps: Double) {
			state.update { it.copy(vehicleSpeedLimitBaselineMps = mps) }
		}
	}

	private class FakeTrackerSettingsRepository : TrackerSettingsRepository {
		override val data: Flow<TrackerSettingsState> = MutableStateFlow(TrackerSettingsState.DEFAULT)
		override suspend fun setAutoUnitSwitch(enabled: Boolean) = Unit
		override suspend fun setLengthSystem(system: com.adsamcik.tracker.shared.preferences.type.LengthSystem) = Unit
		override suspend fun setSpeedFormat(format: com.adsamcik.tracker.shared.preferences.type.SpeedFormat) = Unit
	}

	private class NoOpTimerAccessor : TrackerTierEscalationHandler.TimerAccessor {
		private var timer: CollectionTriggerComponent = NoTimer()
		override fun get(): CollectionTriggerComponent = timer
		override fun set(timer: CollectionTriggerComponent) {
			this.timer = timer
		}
	}
}
