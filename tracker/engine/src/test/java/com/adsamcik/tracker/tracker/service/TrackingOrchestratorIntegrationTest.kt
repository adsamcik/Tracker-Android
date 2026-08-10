package com.adsamcik.tracker.tracker.service

import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.database.AppDatabase
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
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import com.adsamcik.tracker.tracker.controller.LivePlaneState
import com.adsamcik.tracker.tracker.controller.LiveSailingState
import com.adsamcik.tracker.tracker.controller.LiveSkiState
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingOrchestratorIntegrationTest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var testDispatcherProvider: DispatchersProvider
	private val testDispatcher = StandardTestDispatcher()

	@Before
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		testDispatcherProvider = TestDispatchersProvider(testDispatcher)
	}

	@After
	fun tearDown() {
		database.close()
		Dispatchers.resetMain()
	}

	@Test
	fun `initialize updates and shutdown persist final session before SessionEnded`() = runTest(testDispatcher) {
		val controller = DefaultTrackerServiceController()
		val domainEvents = RecordingDomainEventRepository()
		val stopProcessor = SessionEndProcessor(database)
		var fallbackEnqueueCount = 0
		val orchestrator = TrackingOrchestrator(
			controller = controller,
			signalProcessors = setOf(stopProcessor),
			domainEventRepository = domainEvents,
			dispatchers = testDispatcherProvider,
			appDatabase = database,
			trackingParamsRepository = FakeTrackingParamsRepository(
				TrackingParamsState(
					activityEnabled = false,
					stepsEnabled = false,
					wifiEnabled = false,
					cellEnabled = false,
				),
			),
			dailySummaryFallbackEnqueuer = { fallbackEnqueueCount++ },
			enableNotifications = false,
		)

		controller.updateServiceRunning(true)
		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = true,
			initialTier = PolicyTier.PRECISION,
			scope = backgroundScope,
		)
		advanceUntilIdle()

		val first = location(timeMs = 1_000L, latitude = 50.0, longitude = 14.0)
		orchestrator.onCycleUpdate(
			context = context,
			cycle = TrackingCycle(
				timestampMs = 1_000L,
				elapsedRealtimeNanos = 1_000_000_000L,
				location = LocationData(listOf(first), previousLocation = null, distance = null),
			),
		)
		val second = location(timeMs = 2_000L, latitude = 50.0001, longitude = 14.0001)
		orchestrator.onCycleUpdate(
			context = context,
			cycle = TrackingCycle(
				timestampMs = 2_000L,
				elapsedRealtimeNanos = 2_000_000_000L,
				location = LocationData(listOf(second), previousLocation = first, distance = 13f),
			),
		)

		val shutdownResult = orchestrator.shutdown(context)
		advanceUntilIdle()

		shutdownResult.dailySummaryMaterialized shouldBe true
		shutdownResult.fallbackEnqueued shouldBe false
		fallbackEnqueueCount shouldBe 0
		stopProcessor.segmentCountWhenSessionEnded shouldBe 1L
		val persistedSegment = database.sessionSegmentDao().getAllBetween(0L, Long.MAX_VALUE)
			.single()
		orchestrator.resetMetadata()
		controller.lastSessionFlow.value?.end shouldBe persistedSegment.endTimeMs
		domainEvents.persisted.filterIsInstance<DomainEvent.SessionEnded>() shouldHaveSize 1
	}

	@Test
	fun `reinitialize cancels prior session collectors and shutdown clears current collectors`() = runTest(testDispatcher) {
		val controller = DefaultTrackerServiceController()
		val orchestrator = TrackingOrchestrator(
			controller = controller,
			signalProcessors = emptySet(),
			domainEventRepository = RecordingDomainEventRepository(),
			dispatchers = testDispatcherProvider,
			appDatabase = database,
			trackingParamsRepository = FakeTrackingParamsRepository(
				TrackingParamsState(
					activityEnabled = false,
					stepsEnabled = false,
					wifiEnabled = false,
					cellEnabled = false,
				),
			),
			dailySummaryFallbackEnqueuer = {},
			enableNotifications = false,
		)
		fun activeChildJobs(): Int = requireNotNull(backgroundScope.coroutineContext[Job])
			.children
			.count { it.isActive }

		controller.updateServiceRunning(true)
		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = false,
			initialTier = PolicyTier.AMBIENT,
			scope = backgroundScope,
		)
		advanceUntilIdle()
		val activeAfterFirstInitialize = activeChildJobs()

		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = false,
			initialTier = PolicyTier.AMBIENT,
			scope = backgroundScope,
		)
		advanceUntilIdle()

		activeChildJobs() shouldBe activeAfterFirstInitialize

		orchestrator.shutdown(context)
		advanceUntilIdle()

		activeChildJobs() shouldBe 0
	}

	@Test
	fun `reset metadata clears detector states after collector shutdown`() {
		val controller = DefaultTrackerServiceController()
		val orchestrator = TrackingOrchestrator(
			controller = controller,
			signalProcessors = emptySet(),
			domainEventRepository = RecordingDomainEventRepository(),
			dispatchers = testDispatcherProvider,
			appDatabase = database,
			trackingParamsRepository = FakeTrackingParamsRepository(TrackingParamsState()),
			dailySummaryFallbackEnqueuer = {},
			enableNotifications = false,
		)
		controller.updateSkiState(mockk<LiveSkiState>())
		controller.updateSailingState(mockk<LiveSailingState>())
		controller.updatePlaneState(mockk<LivePlaneState>())

		orchestrator.resetMetadata()

		controller.skiStateFlow.value shouldBe null
		controller.sailingStateFlow.value shouldBe null
		controller.planeStateFlow.value shouldBe null
	}

	private fun location(timeMs: Long, latitude: Double, longitude: Double): Location {
		return Location("gps").apply {
			time = timeMs
			elapsedRealtimeNanos = timeMs * 1_000_000L
			this.latitude = latitude
			this.longitude = longitude
			accuracy = 5f
		}
	}

	private class SessionEndProcessor(
		private val database: AppDatabase,
	) : SignalProcessor {
		var segmentCountWhenSessionEnded: Long = 0
			private set

		override val descriptor = ProcessorDescriptor(
			id = "shutdown-integration",
			requiredTier = PolicyTier.AMBIENT,
			flushIntervalMs = 60_000L,
			priority = 0,
		)

		override suspend fun onStart(context: ProcessorContext) = Unit
		override fun onSignal(signal: TrackingSignal) = Unit
		override suspend fun onFlush(): List<DomainEvent> = emptyList()

		override suspend fun onStop(): List<DomainEvent> {
			val segments = database.sessionSegmentDao().getAllBetween(0L, Long.MAX_VALUE)
			segmentCountWhenSessionEnded = segments.size.toLong()
			val sessionId = segments.single().id
			return listOf(
				DomainEvent.SessionEnded(
					timestampMs = EpochMs(2_000L),
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

	private class RecordingDomainEventRepository : DomainEventRepository {
		val persisted = mutableListOf<DomainEvent>()

		override suspend fun persist(events: List<DomainEvent>) {
			persisted += events
		}

		override fun observeEvents(since: EpochMs): Flow<List<DomainEvent>> = emptyFlow()
		override suspend fun getUnconsumedBatch(
			consumerId: String,
			limit: Int,
		): List<DomainEvent> = emptyList()
		override suspend fun getUnconsumedBatchWithIds(
			consumerId: String,
			limit: Int,
		): List<UnconsumedEvent> = emptyList()
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
		override suspend fun setVehicleSpeedLimitBaselineMps(mps: Double) {
			state.update { it.copy(vehicleSpeedLimitBaselineMps = mps) }
		}
	}

}
