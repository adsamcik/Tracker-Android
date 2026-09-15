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
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationWalQualifier
import com.adsamcik.tracker.tracker.source.location.readProtectedLocationCanonicalReceipt
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
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
import io.mockk.mockk
import javax.inject.Provider
import kotlin.test.assertIs
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
	fun `protected location writer preserves canonical route curation and exact receipts`() =
		runTest(testDispatcher) {
			val controller = DefaultTrackerServiceController()
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
			val persistence = PersistenceProcessor(
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
				transactor = RoomPersistenceTransactor(database),
				sourceDestinationOwnerDao = database.sourceDestinationOwnerDao(),
				appDatabaseProvider = Provider { database },
				protectedLocationCanonicalPersistenceGuardProvider =
					Provider { persistenceGuard },
			)
			val orchestrator = TrackingOrchestrator(
				controller = controller,
				signalProcessors = setOf(persistence),
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
				enableNotifications = false,
			)

			insertPresentationOwner(LOGICAL_ID, RUN_ID)
			controller.updateServiceRunning(true)
			val binding = requireNotNull(orchestrator.initialize(
				context = context,
				isSessionUserInitiated = true,
				initialTier = PolicyTier.PRECISION,
				scope = backgroundScope,
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
			)
			val teleport = protectedLocationCommand(
				eventId = "protected-location-teleport",
				admissionOrdinal = 11L,
				sessionSegmentId = binding.sessionSegmentId,
				wallTimeMs = 11_000L,
				elapsedRealtimeNanos = 11_000_000_000L,
				latitude = -33.8688,
				longitude = 151.2093,
			)
			commands[accepted.mutation.identity.sourceEventId.value] = accepted
			commands[teleport.mutation.identity.sourceEventId.value] = teleport

			orchestrator.write(
				accepted,
				PROTECTED_LOCATION_ACQUISITION,
			) shouldBe ProtectedLocationCanonicalWriteResult.Committed
			orchestrator.write(
				teleport,
				PROTECTED_LOCATION_ACQUISITION,
			) shouldBe ProtectedLocationCanonicalWriteResult.Committed

			val acceptedReceipt = assertIs<ProtectedLocationCanonicalReceipt.Complete>(
				database.readProtectedLocationCanonicalReceipt(
					accepted,
					PROTECTED_LOCATION_ACQUISITION,
				),
			)
			val rejectedReceipt = assertIs<ProtectedLocationCanonicalReceipt.Complete>(
				database.readProtectedLocationCanonicalReceipt(
					teleport,
					PROTECTED_LOCATION_ACQUISITION,
				),
			)
			requireNotNull(acceptedReceipt.acceptedSample)
			rejectedReceipt.acceptedSample shouldBe null
			rejectedReceipt.decision.decision shouldBe "REJECTED"
			database.locationSampleDao().getBySourceSignalId(
				ProtectedLocationCanonicalSignalIdentity.canonicalProduct(
					teleport.mutation.identity.sourceEventId.value,
				),
			) shouldBe null
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

	private fun allEventCanonical() = TrackingRolloutState.eventCanonical(SourceKind.entries.toSet())

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
				wallTimeUncertaintyMs = 0L,
			),
			payloadVersion = 2,
			payload = LocationFixPayload(
				latitudeDegrees = latitude,
				longitudeDegrees = longitude,
				horizontalAccuracyMeters = 5f,
				altitudeMeters = null,
				verticalAccuracyMeters = null,
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
		const val LOGICAL_ID = "orchestrator-logical"
		const val RUN_ID = "orchestrator-run"
		val PROTECTED_LOCATION_ACQUISITION = LocationWalAcquisitionMetadata(
			LocationAcquisitionMode.FUSED,
			LocationRequestPriority.HIGH_ACCURACY,
		)
	}

}
