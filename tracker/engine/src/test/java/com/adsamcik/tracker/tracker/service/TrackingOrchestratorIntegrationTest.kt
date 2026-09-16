package com.adsamcik.tracker.tracker.service

import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.model.AltitudeContractVersions
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
import com.adsamcik.tracker.tracker.pipeline.persistence.DurableSignalBuffer
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.pipeline.persistence.RoomPersistenceTransactor
import com.adsamcik.tracker.tracker.pipeline.persistence.TrackingPersistenceTransactor
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.pipeline.DurableAdmissionStatus
import com.adsamcik.tracker.tracker.pipeline.DurableSignalProcessor
import com.adsamcik.tracker.tracker.presentation.PresentationQuiescenceResult
import com.adsamcik.tracker.tracker.presentation.SessionPresentationLifecycle
import com.adsamcik.tracker.tracker.source.location.LocationCaptureAuthority
import com.adsamcik.tracker.tracker.source.location.LocationCaptureTemporalAuthority
import com.adsamcik.tracker.tracker.source.location.LocationCapturedFactCommand
import com.adsamcik.tracker.tracker.source.location.LocationCapturedFactIdentity
import com.adsamcik.tracker.tracker.source.location.LocationCapturedFactMutation
import com.adsamcik.tracker.tracker.source.location.LocationCapturedProductEffect
import com.adsamcik.tracker.tracker.source.location.LocationDerivedQualification
import com.adsamcik.tracker.tracker.source.location.LocationDurableClockAuthority
import com.adsamcik.tracker.tracker.source.location.LocationDurableObservationEvidence
import com.adsamcik.tracker.tracker.source.location.LocationHistoricalAcquisitionConfiguration
import com.adsamcik.tracker.tracker.source.location.LocationObservationQualification
import com.adsamcik.tracker.tracker.source.location.LocationProviderTimeInterval
import com.adsamcik.tracker.tracker.source.location.LocationWalAcquisitionMetadata
import com.adsamcik.tracker.tracker.source.location.LocationWalAdapterResult
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalPersistenceGuard
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalReceipt
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalSignalIdentity
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalWriteResult
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationOfflineCanonicalWriter
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationWalQualifier
import com.adsamcik.tracker.tracker.source.location.PROTECTED_LOCATION_CANONICAL_CURATION_VERSION
import com.adsamcik.tracker.tracker.source.location.loadPreparedProtectedLocationCanonicalCurationState
import com.adsamcik.tracker.tracker.source.location.readProtectedLocationCanonicalReceipt
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.PersistenceLegacySourceWriterTransitionBoundary
import com.adsamcik.tracker.tracker.source.coordinator.PressureSessionFactWriterTransitionCoordinator
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionResult
import com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionTestDependencies
import com.adsamcik.tracker.tracker.source.pressure.PersistenceLegacyPressureWriterLifecycleBarrier
import com.adsamcik.tracker.tracker.source.pressure.RuntimePressureSourceEraseBarrier
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseSettlement
import com.adsamcik.tracker.tracker.source.runtime.PressureSourceRuntime
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
	@Suppress("LongMethod")
	fun `shutdown persists final session then defers summary until presentation settles`() =
		runTest(testDispatcher) {
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

		insertPresentationOwner(LOGICAL_ID, RUN_ID)
		controller.updateServiceRunning(true)
		val binding = orchestrator.initialize(
			context = context,
			isSessionUserInitiated = true,
			initialTier = PolicyTier.PRECISION,
			scope = backgroundScope,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ID,
			rolloutState = allEventCanonical(),
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

		val sourceSessionDao = database.sourceSessionDao()
		val activeRun = requireNotNull(sourceSessionDao.serviceRun(RUN_ID))
		sourceSessionDao.updateServiceRun(
			activeRun.copy(
				state = SessionLifecycleState.FINALIZED.name,
				completedAtMs = 3_000L,
				completionReason = "TEST_STOP",
			),
		) shouldBe 1
		val shutdownResult = orchestrator.shutdown(context)
		advanceUntilIdle()

		shutdownResult.dailySummaryMaterialized shouldBe false
		shutdownResult.fallbackEnqueued shouldBe true
		shutdownResult.presentationReceipt?.binding shouldBe binding
		orchestrator.shutdown(context) shouldBe shutdownResult
		SessionPresentationLifecycle(database).acknowledgeQuiescedExact(
			requireNotNull(binding),
			acknowledgedAtMs = 3_100L,
		) shouldBe PresentationQuiescenceResult.ACKNOWLEDGED
		fallbackEnqueueCount shouldBe 1
		stopProcessor.segmentCountWhenSessionEnded shouldBe 1L
		val persistedSegment = database.sessionSegmentDao().getAllBetween(0L, Long.MAX_VALUE)
			.single()
		orchestrator.resetMetadata()
		controller.lastSessionFlow.value?.end shouldBe persistedSegment.endTimeMs
		domainEvents.persisted.filterIsInstance<DomainEvent.SessionEnded>() shouldHaveSize 1
	}

	@Test
	@Suppress("LongMethod")
	fun `startup recovery seeds pending route and altitude before the next location cycle`() =
		runTest(testDispatcher) {
			val commands = mutableMapOf<String, LocationCapturedFactCommand>()
			val qualifier = ProtectedLocationWalQualifier { eventId ->
				LocationWalAdapterResult.Evaluated(
					LocationObservationQualification.Qualified(
						requireNotNull(commands[eventId.value]),
					),
					PROTECTED_LOCATION_ACQUISITION,
				)
			}
			installProtectedLocationWriterAuthority()
			database.sourceEvidenceStateDao().ensure()
			val persistenceGuard = ProtectedLocationCanonicalPersistenceGuard(
				database,
				qualifier,
				Unit,
			)
			val failedLifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
			fun newPersistence(
				transactor: TrackingPersistenceTransactor,
			) = PersistenceProcessor(
				locationSampleDao = database.locationSampleDao(),
				locationObservationDao = database.locationObservationDao(),
				locationObservationDecisionDao = database.locationObservationDecisionDao(),
				sourceEvidenceStateDao = database.sourceEvidenceStateDao(),
				cellSampleDao = database.cellSampleDao(),
				wifiObservationDao = database.wifiObservationDao(),
				pressureSampleDao = database.pressureSampleDao(),
				stepIntervalDao = database.stepIntervalDao(),
				activitySnapshotDao = database.activitySnapshotDao(),
				pendingSignalDao = database.pendingSignalDao(),
				pendingSignalClaimDao = database.pendingSignalClaimDao(),
				durableBuffer = DurableSignalBuffer(
					pendingSignalDao = database.pendingSignalDao(),
					dispatchers = testDispatcherProvider,
					pendingSignalClaimDao = database.pendingSignalClaimDao(),
					appDatabase = database,
				),
				transactor = transactor,
				sourceDestinationOwnerDao = database.sourceDestinationOwnerDao(),
				appDatabaseProvider = Provider { database },
				protectedLocationCanonicalPersistenceGuardProvider =
					Provider { persistenceGuard },
			)
			val failedPersistence = newPersistence(
				object : TrackingPersistenceTransactor {
					override suspend fun <R> inTransaction(block: suspend () -> R): R {
						error("simulated destination outage")
					}
				},
			)
			val failedController = DefaultTrackerServiceController()
			val failedOrchestrator = TrackingOrchestrator(
				controller = failedController,
				signalProcessors = setOf(failedPersistence),
				domainEventRepository = RecordingDomainEventRepository(),
				dispatchers = testDispatcherProvider,
				appDatabase = database,
				trackingParamsRepository = FakeTrackingParamsRepository(
					TrackingParamsState(
						activityEnabled = false,
						stepsEnabled = false,
						wifiEnabled = false,
						cellEnabled = false,
						requiredAccuracyMeters = 1,
					),
				),
				enableNotifications = false,
				persistenceLifecycleLease = failedLifecycleLease,
			)
			insertPresentationOwner(LOGICAL_ID, RUN_ID)
			failedController.updateServiceRunning(true)
			val failedSessionJob = SupervisorJob()
			val binding = requireNotNull(failedOrchestrator.initialize(
				context = context,
				isSessionUserInitiated = true,
				initialTier = PolicyTier.PRECISION,
				scope = CoroutineScope(testDispatcher + failedSessionJob),
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				rolloutState = allEventCanonical(),
			))
			advanceUntilIdle()

			val accepted = protectedLocationCommand(
				eventId = "protected-location-accepted",
				admissionOrdinal = 10L,
				sessionSegmentId = binding.sessionSegmentId,
				wallTimeMs = 10_000L,
				elapsedRealtimeNanos = 10_000_000_000L,
				latitude = 50.087,
				longitude = 14.421,
				accuracyMeters = 40f,
				altitudeMeters = 242.5,
				verticalAccuracyMeters = 3f,
			)
			commands[accepted.mutation.identity.sourceEventId.value] = accepted
			assertIs<ProtectedLocationCanonicalWriteResult.Deferred>(
				failedOrchestrator.write(
					accepted,
					PROTECTED_LOCATION_ACQUISITION,
				),
			)
			(database.pendingSignalDao().countAll() > 0) shouldBe true
			database.locationObservationDao().getBySourceEventId(
				accepted.mutation.identity.sourceEventId.value,
			) shouldBe null
			requireNotNull(
				database.loadPreparedProtectedLocationCanonicalCurationState(accepted),
			)
			failedSessionJob.cancel()
			failedController.updateServiceRunning(false)
			advanceUntilIdle()

			val lifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
			val recoveredPersistence = newPersistence(RoomPersistenceTransactor(database))
			val recoveredController = DefaultTrackerServiceController()
			val recoveredOrchestrator = TrackingOrchestrator(
				controller = recoveredController,
				signalProcessors = setOf(recoveredPersistence),
				domainEventRepository = RecordingDomainEventRepository(),
				dispatchers = testDispatcherProvider,
				appDatabase = database,
				trackingParamsRepository = FakeTrackingParamsRepository(
					TrackingParamsState(
						activityEnabled = false,
						stepsEnabled = false,
						wifiEnabled = false,
						cellEnabled = false,
						requiredAccuracyMeters = 1,
					),
				),
				enableNotifications = false,
				persistenceLifecycleLease = lifecycleLease,
			)
			recoveredController.updateServiceRunning(true)
			val recoveredBinding = requireNotNull(recoveredOrchestrator.initialize(
				context = context,
				isSessionUserInitiated = true,
				initialTier = PolicyTier.PRECISION,
				scope = backgroundScope,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				resumeSessionSegmentId = binding.sessionSegmentId,
				rolloutState = allEventCanonical(),
			))
			advanceUntilIdle()
			database.pendingSignalDao().countAll() shouldBe 0
			val recoveredReceipt = assertIs<ProtectedLocationCanonicalReceipt.Complete>(
				database.readProtectedLocationCanonicalReceipt(
					accepted,
					PROTECTED_LOCATION_ACQUISITION,
				),
			)
			requireNotNull(recoveredReceipt.acceptedSample)
			recoveredReceipt.curationState.altitudeProcessorState
				.fusionState.kalmanState.initialized shouldBe true

			val teleport = protectedLocationCommand(
				eventId = "protected-location-after-recovery",
				admissionOrdinal = 11L,
				sessionSegmentId = recoveredBinding.sessionSegmentId,
				wallTimeMs = 11_000L,
				elapsedRealtimeNanos = 11_000_000_000L,
				latitude = -33.8688,
				longitude = 151.2093,
				altitudeMeters = 246.0,
				verticalAccuracyMeters = 3f,
			)
			commands[teleport.mutation.identity.sourceEventId.value] = teleport
			recoveredOrchestrator.write(
				teleport,
				PROTECTED_LOCATION_ACQUISITION,
			) shouldBe ProtectedLocationCanonicalWriteResult.Committed
			val rejectedReceipt = assertIs<ProtectedLocationCanonicalReceipt.Complete>(
				database.readProtectedLocationCanonicalReceipt(
					teleport,
					PROTECTED_LOCATION_ACQUISITION,
				),
			)
			rejectedReceipt.acceptedSample shouldBe null
			rejectedReceipt.decision.decision shouldBe "REJECTED"
			rejectedReceipt.decision.reason shouldBe "CURATED_LOCATION_TELEPORT_CANDIDATE"
			requireNotNull(rejectedReceipt.curationState.pendingReacquisition)
			rejectedReceipt.curationState.altitudeProcessorState shouldBe
				recoveredReceipt.curationState.altitudeProcessorState

			val corroborating = protectedLocationCommand(
				eventId = "protected-location-corroborating",
				admissionOrdinal = 12L,
				sessionSegmentId = recoveredBinding.sessionSegmentId,
				wallTimeMs = 12_000L,
				elapsedRealtimeNanos = 12_000_000_000L,
				latitude = -33.8687,
				longitude = 151.2094,
				altitudeMeters = 247.0,
				verticalAccuracyMeters = 3f,
			)
			commands[corroborating.mutation.identity.sourceEventId.value] = corroborating
			recoveredOrchestrator.write(
				corroborating,
				PROTECTED_LOCATION_ACQUISITION,
			) shouldBe ProtectedLocationCanonicalWriteResult.Committed
			requireNotNull(
				assertIs<ProtectedLocationCanonicalReceipt.Complete>(
					database.readProtectedLocationCanonicalReceipt(
						corroborating,
						PROTECTED_LOCATION_ACQUISITION,
					),
				).acceptedSample,
			)

			recoveredOrchestrator.write(
				accepted,
				PROTECTED_LOCATION_ACQUISITION,
			) shouldBe ProtectedLocationCanonicalWriteResult.Committed
			val afterOlderReceipt = protectedLocationCommand(
				eventId = "protected-location-after-older-receipt",
				admissionOrdinal = 13L,
				sessionSegmentId = recoveredBinding.sessionSegmentId,
				wallTimeMs = 13_000L,
				elapsedRealtimeNanos = 13_000_000_000L,
				latitude = -33.8686,
				longitude = 151.2095,
				altitudeMeters = 248.0,
				verticalAccuracyMeters = 3f,
			)
			commands[afterOlderReceipt.mutation.identity.sourceEventId.value] = afterOlderReceipt
			recoveredOrchestrator.write(
				afterOlderReceipt,
				PROTECTED_LOCATION_ACQUISITION,
			) shouldBe ProtectedLocationCanonicalWriteResult.Committed
			requireNotNull(
				assertIs<ProtectedLocationCanonicalReceipt.Complete>(
					database.readProtectedLocationCanonicalReceipt(
						afterOlderReceipt,
						PROTECTED_LOCATION_ACQUISITION,
					),
				).acceptedSample,
			)
			database.locationSampleDao().countAll() shouldBe 3L
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
					owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
					ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					updatedAtMs = 20_000L,
				),
			)
			val offlineWriter = ProtectedLocationOfflineCanonicalWriter(
				context,
				database,
				testDispatcherProvider,
				recoveredPersistence,
				lifecycleLease,
			)
			val pressureRuntime = mockk<PressureSourceRuntime>()
			coEvery { pressureRuntime.establishSourceEraseBarrier(0L) } returns
				PressureProviderEraseSettlement.NoLocalProvider
			val pressureBarrier = RuntimePressureSourceEraseBarrier(
				pressureRuntime,
				PersistenceLegacyPressureWriterLifecycleBarrier(
					database,
					recoveredPersistence,
					lifecycleLease,
				),
			)
			val offlineAttempt = backgroundScope.async {
				offlineWriter.write(accepted, PROTECTED_LOCATION_ACQUISITION)
			}
			val pressureAttempt = backgroundScope.async {
				pressureBarrier.establish(0L)
			}
			runCurrent()
			offlineAttempt.isCompleted shouldBe false
			pressureAttempt.isCompleted shouldBe false
			coVerify(exactly = 0) { pressureRuntime.establishSourceEraseBarrier(0L) }

			recoveredOrchestrator.shutdown(context)

			offlineAttempt.await()
			assertIs<PressureSourceEraseBarrierResult.NoLocalProvider>(pressureAttempt.await())
			coVerify(exactly = 1) { pressureRuntime.establishSourceEraseBarrier(0L) }
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
			rolloutState = allEventCanonical(),
		)
		advanceUntilIdle()
		val activeAfterFirstInitialize = activeChildJobs()

		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = false,
			initialTier = PolicyTier.AMBIENT,
			scope = backgroundScope,
			rolloutState = allEventCanonical(),
		)
		advanceUntilIdle()

		activeChildJobs() shouldBe activeAfterFirstInitialize

		orchestrator.shutdown(context)
		advanceUntilIdle()

		activeChildJobs() shouldBe 0
	}

	@Test
	fun `live orchestrator holds persistence lifecycle through complete stop`() =
		runTest(testDispatcher) {
			val lease = ExclusiveTrackingPersistenceLifecycleLease()
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
				persistenceLifecycleLease = lease,
			)
			controller.updateServiceRunning(true)
			orchestrator.initialize(
				context = context,
				isSessionUserInitiated = false,
				initialTier = PolicyTier.AMBIENT,
				scope = backgroundScope,
				rolloutState = allEventCanonical(),
			)
			val offlineEntered = CompletableDeferred<Unit>()
			val offline = backgroundScope.async {
				lease.withOfflineLocationRecovery {
					offlineEntered.complete(Unit)
				}
			}

			runCurrent()
			offlineEntered.isCompleted shouldBe false
			orchestrator.shutdown(context)
			offline.await()
			offlineEntered.isCompleted shouldBe true
		}

	@Test
	@Suppress("LongMethod")
	fun `Pressure activation waits outside coordinator lease for the real live persistence owner`() =
		runTest(testDispatcher) {
			database.sourceEvidenceStateDao().ensure()
			val catalog = ExecutableSourceLaneCatalog()
			RoomTrackingRolloutStateStore(database, catalog).load() shouldBe
				TrackingRolloutState.contained(revision = 1L)
			installProtectedLocationWriterAuthority()
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
					owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
					ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
					updatedAtMs = 1L,
				),
			)
			val lifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
			val persistence = realPersistenceProcessor()
			val transitionBoundary = PersistenceLegacySourceWriterTransitionBoundary(
				persistence,
				lifecycleLease,
			)
			val coordinator = PressureSessionFactWriterTransitionCoordinator(
				database,
				catalog,
				SourceWriterTransitionTestDependencies(
					startupGate = ReadyTransitionStartupGate,
					bootClockDomainProvider =
						BootClockDomainProvider { TRANSITION_BOOT_ID },
					clock = FixedClock(1_000L, 1_000L),
					legacyWriterQuiescence = transitionBoundary,
				),
			)
			coordinator.installInertCandidate(1L, 10L)
				.shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
			val pressureRuntime = mockk<PressureSourceRuntime>()
			coEvery { pressureRuntime.establishSourceEraseBarrier(0L) } returns
				PressureProviderEraseSettlement.NoLocalProvider
			val runtimePressureBarrier = RuntimePressureSourceEraseBarrier(
				pressureRuntime,
				PersistenceLegacyPressureWriterLifecycleBarrier(
					database,
					persistence,
					lifecycleLease,
				),
			)
			val offlineWriter = ProtectedLocationOfflineCanonicalWriter(
				context,
				database,
				testDispatcherProvider,
				persistence,
				lifecycleLease,
			)
			val controller = DefaultTrackerServiceController().apply {
				updateServiceRunning(true)
			}
			val orchestrator = TrackingOrchestrator(
				controller = controller,
				signalProcessors = setOf(persistence),
				domainEventRepository = RecordingDomainEventRepository(),
				dispatchers = testDispatcherProvider,
				appDatabase = database,
				trackingParamsRepository =
					FakeTrackingParamsRepository(TrackingParamsState()),
				dailySummaryFallbackEnqueuer = {},
				enableNotifications = false,
				persistenceLifecycleLease = lifecycleLease,
			)
			orchestrator.initialize(
				context = context,
				isSessionUserInitiated = false,
				initialTier = PolicyTier.AMBIENT,
				scope = backgroundScope,
				rolloutState = allEventCanonical(),
			)
			val command = protectedLocationCommand(
				eventId = "cancelled-offline-location",
				admissionOrdinal = 1L,
				sessionSegmentId = 1L,
				wallTimeMs = 1_000L,
				elapsedRealtimeNanos = 1_000_000_000L,
				latitude = 50.0,
				longitude = 14.0,
			)
			val offlineAttempt = backgroundScope.async {
				offlineWriter.write(command, PROTECTED_LOCATION_ACQUISITION)
			}
			val pressureAttempt = backgroundScope.async {
				runtimePressureBarrier.establish(0L)
			}

			runCurrent()
			offlineAttempt.isCompleted shouldBe false
			pressureAttempt.isCompleted shouldBe false
			coVerify(exactly = 0) { pressureRuntime.establishSourceEraseBarrier(0L) }
			offlineAttempt.cancelAndJoin()
			pressureAttempt.cancelAndJoin()

			val activation = backgroundScope.async {
				coordinator.activateCandidate(2L, 11L)
			}
			runCurrent()
			activation.isCompleted shouldBe false
			val leaseDao = database.sourceProjectionStateDao()
			leaseDao.acquireOrRenewLease(
				leaseName = SESSION_COORDINATOR_LEASE,
				ownerToken = "live-pressure-teardown",
				bootId = TRANSITION_BOOT_ID,
				nowMs = 1_000L,
				expiresAtMs = 2_000L,
				nowElapsedNanos = 1_000L,
				expiresElapsedNanos = 2_000L,
			) shouldBe 1
			val teardownLease = requireNotNull(leaseDao.lease(SESSION_COORDINATOR_LEASE))
			leaseDao.releaseLease(
				SESSION_COORDINATOR_LEASE,
				"live-pressure-teardown",
				TRANSITION_BOOT_ID,
				teardownLease.generation,
				1_000L,
				1_000L,
			) shouldBe 1

			orchestrator.shutdown(context)

			activation.await().shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
		}

	@Test
	fun `pipeline start failure releases only after durability cleanup`() = runTest(testDispatcher) {
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val processor = LifecycleFailureDurabilityProcessor(failStart = true)
		val orchestrator = TrackingOrchestrator(
			controller = DefaultTrackerServiceController().apply {
				updateServiceRunning(true)
			},
			signalProcessors = setOf(processor),
			domainEventRepository = RecordingDomainEventRepository(),
			dispatchers = testDispatcherProvider,
			appDatabase = database,
			trackingParamsRepository = FakeTrackingParamsRepository(TrackingParamsState()),
			dailySummaryFallbackEnqueuer = {},
			enableNotifications = false,
			persistenceLifecycleLease = lease,
		)

		shouldThrow<IllegalStateException> {
			orchestrator.initialize(
				context = context,
				isSessionUserInitiated = false,
				initialTier = PolicyTier.AMBIENT,
				scope = backgroundScope,
				rolloutState = allEventCanonical(),
			)
		}

		processor.failStart = false
		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = false,
			initialTier = PolicyTier.AMBIENT,
			scope = backgroundScope,
			rolloutState = allEventCanonical(),
		)
		orchestrator.shutdown(context)
		lease.withOfflineLocationRecovery { "released" } shouldBe "released"
		processor.stopCalls shouldBe 2
	}

	@Test
	fun `failed final stop retains permit until retry completes`() = runTest(testDispatcher) {
		val lease = ExclusiveTrackingPersistenceLifecycleLease()
		val processor = LifecycleFailureDurabilityProcessor(stopFailures = 1)
		val controller = DefaultTrackerServiceController().apply {
			updateServiceRunning(true)
		}
		val orchestrator = TrackingOrchestrator(
			controller = controller,
			signalProcessors = setOf(processor),
			domainEventRepository = RecordingDomainEventRepository(),
			dispatchers = testDispatcherProvider,
			appDatabase = database,
			trackingParamsRepository = FakeTrackingParamsRepository(TrackingParamsState()),
			dailySummaryFallbackEnqueuer = {},
			enableNotifications = false,
			persistenceLifecycleLease = lease,
		)
		orchestrator.initialize(
			context = context,
			isSessionUserInitiated = false,
			initialTier = PolicyTier.AMBIENT,
			scope = backgroundScope,
			rolloutState = allEventCanonical(),
		)
		val offlineEntered = CompletableDeferred<Unit>()
		val offline = backgroundScope.async {
			lease.withOfflineLocationRecovery {
				offlineEntered.complete(Unit)
			}
		}

		shouldThrow<IllegalStateException> {
			orchestrator.shutdown(context)
		}
		runCurrent()
		offlineEntered.isCompleted shouldBe false
		orchestrator.shutdown(context)
		offline.await()
		offlineEntered.isCompleted shouldBe true
		processor.stopCalls shouldBe 2
	}

	private fun allEventCanonical() = TrackingRolloutState.eventCanonical(SourceKind.entries.toSet())

	private fun realPersistenceProcessor(
		transactor: TrackingPersistenceTransactor = RoomPersistenceTransactor(database),
	) = PersistenceProcessor(
		locationSampleDao = database.locationSampleDao(),
		locationObservationDao = database.locationObservationDao(),
		locationObservationDecisionDao = database.locationObservationDecisionDao(),
		sourceEvidenceStateDao = database.sourceEvidenceStateDao(),
		cellSampleDao = database.cellSampleDao(),
		wifiObservationDao = database.wifiObservationDao(),
		pressureSampleDao = database.pressureSampleDao(),
		stepIntervalDao = database.stepIntervalDao(),
		activitySnapshotDao = database.activitySnapshotDao(),
		pendingSignalDao = database.pendingSignalDao(),
		pendingSignalClaimDao = database.pendingSignalClaimDao(),
		durableBuffer = DurableSignalBuffer(
			pendingSignalDao = database.pendingSignalDao(),
			dispatchers = testDispatcherProvider,
			pendingSignalClaimDao = database.pendingSignalClaimDao(),
			appDatabase = database,
		),
		transactor = transactor,
		sourceDestinationOwnerDao = database.sourceDestinationOwnerDao(),
		appDatabaseProvider = Provider { database },
	)

	private suspend fun insertPresentationOwner(logicalTrackingId: String, serviceRunId: String) {
		val dao = database.sourceSessionDao()
		dao.insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalTrackingId,
				state = SessionLifecycleState.ACTIVE.name,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = "boot-test",
				startedAtMs = 1_000L,
				startedElapsedNanos = 1_000L,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				currentServiceRunId = serviceRunId,
			),
		)
		dao.insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				state = SessionLifecycleState.ACTIVE.name,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1_000L,
				startedElapsedNanos = 1_000L,
				completedAtMs = null,
				completionReason = null,
				bootId = "boot-test",
			),
		)
	}

	private suspend fun installProtectedLocationWriterAuthority() {
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
				owner = SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
				ownerGeneration =
					SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
				updatedAtMs = 1_000L,
			),
		)
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
				bindingGeneration =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION,
				projectionId =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
				projectionVersion =
					SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
				captureModeMask = 1L,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 1L,
				activationOrdinal = 10L,
				contiguousAdmissionOrdinal = 9L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
	}

	private fun protectedLocationCommand(
		eventId: String,
		admissionOrdinal: Long,
		sessionSegmentId: Long,
		wallTimeMs: Long,
		elapsedRealtimeNanos: Long,
		latitude: Double,
		longitude: Double,
		accuracyMeters: Float = 5f,
		altitudeMeters: Double? = null,
		verticalAccuracyMeters: Float? = null,
	): LocationCapturedFactCommand {
		val interval = LocationProviderTimeInterval(1L, Long.MAX_VALUE)
		val temporal = LocationCaptureTemporalAuthority(
			providerRegistration = interval,
			authorization = interval,
			sourcePolicy = interval,
			captureConsent = interval,
			sessionManifest = interval,
			lifecycleLease = interval,
		)
		val authority = LocationCaptureAuthority(
			logicalTrackingId = LogicalTrackingId(LOGICAL_ID),
			serviceRunId = ServiceRunId(RUN_ID),
			sessionSegmentId = sessionSegmentId,
			capturedSources = setOf(SourceKind.LOCATION),
			controlSources = emptySet(),
			sourceInstanceId = SourceInstanceId("orchestrator-location-runtime"),
			registrationGeneration = 1L,
			configurationRevision = 1L,
			physicalConfigurationFingerprint = "orchestrator-location-fingerprint",
			authorizationRevision = 1L,
			authorizationFingerprint = "orchestrator-location-authorization",
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			capturedCollectedDataEpoch = 0L,
			clockDomainId = "boot-test",
			zoneId = "Europe/Prague",
			permissionPrecision = LocationPermissionPrecision.PRECISE,
			temporalAuthority = temporal,
			acquisitionConfiguration = LocationHistoricalAcquisitionConfiguration(
				maximumObservationAgeNanos = 5_000_000_000L,
				maximumHorizontalAccuracyMeters = 50f,
			),
		)
		val deliveryIdentity = SourceDeliveryIdentity(
			if (admissionOrdinal % 2L == 0L) "a".repeat(64) else "b".repeat(64),
		)
		val evidence = LocationDurableObservationEvidence(
			sourceEventId = SourceEventId(eventId),
			sourceAdmissionOrdinal = admissionOrdinal,
			sourceSequence = admissionOrdinal,
			walIntegrityIdentity =
				if (admissionOrdinal % 2L == 0L) "c".repeat(64) else "d".repeat(64),
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			capturedAuthority = authority,
			clockAuthority = LocationDurableClockAuthority(
				clockDomainId = authority.clockDomainId,
				observedElapsedRealtimeNanos = elapsedRealtimeNanos,
				receivedElapsedRealtimeNanos = elapsedRealtimeNanos + 100_000_000L,
				observedWallTimeMs = wallTimeMs,
				receivedWallTimeMs = wallTimeMs + 100L,
				wallTimeUncertaintyMs = 0L,
			),
			payloadVersion = 2,
			payload = LocationFixPayload(
				latitudeDegrees = latitude,
				longitudeDegrees = longitude,
				horizontalAccuracyMeters = accuracyMeters,
				altitudeMeters = altitudeMeters,
				verticalAccuracyMeters = verticalAccuracyMeters,
				speedMetersPerSecond = null,
				bearingDegrees = null,
				provider = "gps",
				isMock = false,
			),
			quality = SourceQuality(),
			isMock = false,
		)
		val identity = LocationCapturedFactIdentity(
			sourceEventId = evidence.sourceEventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			walIntegrityIdentity = evidence.walIntegrityIdentity,
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			logicalTrackingId = authority.logicalTrackingId,
			serviceRunId = authority.serviceRunId,
			sessionSegmentId = sessionSegmentId,
			sessionManifestRevision = authority.sessionManifestRevision,
			capturedCollectedDataEpoch = authority.capturedCollectedDataEpoch,
		)
		return LocationCapturedFactCommand(
			mutation = LocationCapturedFactMutation(identity, 1L, null),
			authority = authority,
			productEffect = LocationCapturedProductEffect(
				durableEvidence = evidence,
				derivedQualification = LocationDerivedQualification(
					qualifierVersion = 1,
					deliveryAgeNanos = 100_000_000L,
					maximumObservationAgeNanos =
						authority.acquisitionConfiguration.maximumObservationAgeNanos,
					maximumHorizontalAccuracyMeters =
						authority.acquisitionConfiguration.maximumHorizontalAccuracyMeters,
					earliestPossibleWallTimeMs = wallTimeMs,
					latestPossibleWallTimeMs = wallTimeMs,
				),
			),
		)
	}

	@Test
	fun `providerless foreground shell shutdown does not touch Room or enqueue product work`() =
		runTest(testDispatcher) {
			val unavailableDatabase = mockk<AppDatabase>()
			var fallbackEnqueueCount = 0
			val orchestrator = TrackingOrchestrator(
				controller = DefaultTrackerServiceController(),
				signalProcessors = emptySet(),
				domainEventRepository = RecordingDomainEventRepository(),
				dispatchers = testDispatcherProvider,
				appDatabase = unavailableDatabase,
				trackingParamsRepository = FakeTrackingParamsRepository(TrackingParamsState()),
				dailySummaryFallbackEnqueuer = { fallbackEnqueueCount += 1 },
				enableNotifications = false,
			)

			orchestrator.shutdown(context) shouldBe ShutdownResult(
				dailySummaryMaterialized = false,
				fallbackEnqueued = false,
			)
			fallbackEnqueueCount shouldBe 0
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

	private class LifecycleFailureDurabilityProcessor(
		var failStart: Boolean = false,
		private var stopFailures: Int = 0,
	) : DurableSignalProcessor {
		override val descriptor = ProcessorDescriptor(
			id = "lifecycle-failure-durability",
			requiredTier = PolicyTier.AMBIENT,
			flushIntervalMs = Long.MAX_VALUE,
			priority = Int.MIN_VALUE,
		)
		var stopCalls = 0
			private set

		override suspend fun onStart(context: ProcessorContext) {
			if (failStart) error("simulated persistence start failure")
		}

		override fun onSignal(signal: TrackingSignal) = Unit
		override suspend fun onFlush(): List<DomainEvent> = emptyList()
		override suspend fun checkpointStagedSignals(): Boolean = true
		override suspend fun checkpointStagedSignalsStatus(): DurableAdmissionStatus =
			DurableAdmissionStatus.ADMITTED

		override suspend fun onStop(): List<DomainEvent> {
			stopCalls++
			if (stopFailures > 0) {
				stopFailures--
				error("simulated persistence stop failure")
			}
			return emptyList()
		}
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

	private object ReadyTransitionStartupGate : TrackingStartupGate {
		override val isReady: Boolean = true
		override val currentGeneration: Long = 1L

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)
	}

	private companion object {
		const val LOGICAL_ID = "orchestrator-logical"
		const val RUN_ID = "orchestrator-run"
		const val SESSION_COORDINATOR_LEASE = "tracking-session-coordinator"
		const val TRANSITION_BOOT_ID = "pressure-transition-boot"
		val PROTECTED_LOCATION_ACQUISITION = LocationWalAcquisitionMetadata(
			acquisitionMode = LocationAcquisitionMode.FUSED,
			requestPriority = LocationRequestPriority.HIGH_ACCURACY,
			requiredAccuracyMeters = 50f,
			policyTier = PolicyTier.PRECISION,
			policyName = "SOURCE_QOS_RESPONSIVE",
			curationVersion = PROTECTED_LOCATION_CANONICAL_CURATION_VERSION,
			altitudeModelVersion = AltitudeContractVersions.MODEL_VERSION,
			altitudeEstimatorVersion = AltitudeContractVersions.ESTIMATOR_VERSION,
			altitudeCalibrationVersion = AltitudeContractVersions.CALIBRATION_VERSION,
		)
	}

}
