package com.adsamcik.tracker.app.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationSnapshot
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.di.InfrastructureModule
import com.adsamcik.tracker.app.di.TrackingInfrastructureIntegrationEntryPoint
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.app.tracebox.TrackerTraceboxHandleProvider
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticClearResult
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticDataControl
import com.adsamcik.tracker.impexp.backup.DataBackupRestore
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.impexp.exporter.proto.ExportPlansProto
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsWalBoundaryKind
import com.adsamcik.tracker.shared.base.database.data.StepsWalPayload
import com.adsamcik.tracker.shared.base.database.data.StepsWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.STEPS_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityBootstrapCoordinator
import com.adsamcik.tracker.tracker.api.TrackingPurposeDeletionFencer
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApiEntryPoint
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingTurnOffResult
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SharedSourceWriterDeletionRearm
import com.adsamcik.tracker.tracker.source.coordinator.StepsSessionFactWriterTransitionCoordinator
import com.adsamcik.tracker.tracker.source.coordinator.StepsWriterTransitionResult
import com.adsamcik.tracker.tracker.source.model.SourceKind
import dagger.hilt.android.EntryPointAccessors
import dev.tracebox.api.DeleteReport
import dev.tracebox.api.DeleteRequest
import dev.tracebox.api.TraceboxHandle
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App-layer proof for same-process retry across the two-transaction collected-data deletion seam.
 *
 * The first Room transaction clears collected rows and records the retained WAL high-water. The
 * second transaction reconstructs empty, manual-capture-authorized Steps writer metadata. The
 * durable operation receipt, marker, and process startup barrier fence the gap when post-rearm
 * diagnostics fail; retry resumes diagnostics without another clear, epoch, or writer generation.
 *
 * This does not prove cold-process recovery, provider capture, materialization, history/product
 * queries, export, UI, QUERYABLE status, or rollout readiness; those remain separate gates.
 */
@RunWith(AndroidJUnit4::class)
class CollectedDataDeletionStepsRearmIntegrationTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var lifecycleStore: CollectedDataLifecycleStore
	private lateinit var coordinator: StepsSessionFactWriterTransitionCoordinator
	private lateinit var executableLaneCatalog: ExecutableSourceLaneCatalog
	private lateinit var sourceWriterDeletionRearm: SharedSourceWriterDeletionRearm
	private lateinit var sourcePolicyBootstrap: SourcePolicyAuthorityBootstrapCoordinator
	private lateinit var deletionBarrier: TrackingStartupDeletionBarrier
	private lateinit var startupGate: TrackingStartupGate
	private lateinit var retentionAuthorityProducer: RetentionAuthorityProducer
	private lateinit var markerFile: File

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		val entryPoint = EntryPointAccessors.fromApplication(
			context,
			TrackingInfrastructureIntegrationEntryPoint::class.java,
		)
		lifecycleStore = entryPoint.collectedDataLifecycleStore()
		coordinator = entryPoint.stepsWriterTransitionCoordinator()
		executableLaneCatalog = entryPoint.executableSourceLaneCatalog()
		sourceWriterDeletionRearm = entryPoint.sharedSourceWriterDeletionRearm()
		sourcePolicyBootstrap = entryPoint.sourcePolicyAuthorityBootstrapCoordinator()
		database = AppDatabase.database(context)
		deletionBarrier = context.trackingStartupDeletionBarrier
		startupGate = context.trackingStartupGate
		retentionAuthorityProducer = entryPoint.retentionAuthorityProducer()
		markerFile = File(context.noBackupFilesDir, "collected-data-deletion-pending")
	}

	@Test
	fun failedPostRearmDiagnosticsRetainsFenceUntilRetryResumesSameGeneration() = runBlocking {
		// Settle the real app's startup/provider work before seeding this isolated re-arm seam.
		TrackerServiceApi.turnOffTrackingAndAwait(context) shouldBe TrackingTurnOffResult.COMPLETE
		startupGate.reconcile(retryFailedStorage = true)
			.shouldBeInstanceOf<TrackingStartupResult.Ready>()
		EntryPointAccessors.fromApplication(context, BackgroundTrackingApiEntryPoint::class.java)
			.chosenSourceWriterSetup().ensure(SourceKind.entries.toSet())
			.readySources shouldBe SourceKind.entries.toSet()
		markerFile.exists() shouldBe false
		startupGate.isReady shouldBe true
		deletionBarrier.isClosed shouldBe false

		val rolloutStore = RoomTrackingRolloutStateStore(
			database,
			executableLaneCatalog,
		)
		val initialLifecycle = lifecycleStore.snapshot()
		val initialEvidenceHighWater =
			database.sourceEvidenceStateDao().get()?.deletedSourceEventHighWaterOrdinal ?: 0L
		val initialGeneration = deletionBarrier.currentGeneration
		val initialPublishedGeneration = deletionBarrier.openGenerations.value
		initialPublishedGeneration shouldBe initialGeneration

		val canonicalState = establishCanonicalStepsWriter(rolloutStore)
		val deletedHighWater = seedCollectedStepsAndBrokerState(initialLifecycle.epoch)
		val expectedDeletedHighWater = maxOf(initialEvidenceHighWater, deletedHighWater)
		val scenario = DeletionScenario(
			rolloutStore = rolloutStore,
			initialLifecycleEpoch = initialLifecycle.epoch,
			initialGeneration = initialGeneration,
			initialPublishedGeneration = initialPublishedGeneration,
			canonicalState = canonicalState,
			expectedDeletedHighWater = expectedDeletedHighWater,
		)
		val fixture = createDeletionFixture(
			scenario = scenario,
			dispatchersProvider = DefaultDispatchersProvider,
		)

		assertFailedAttemptRemainsFenced(scenario, fixture)
		assertSuccessfulRetryReopens(scenario, fixture)
	}

	private fun createDeletionFixture(
		scenario: DeletionScenario,
		dispatchersProvider: DispatchersProvider,
	): DeletionFixture {
		val mocks = createDeletionMocks()
		val counters = RearmCheckCounters()

		coEvery { mocks.exportPlanStore.resetAllWatermarks() } coAnswers {
			counters.postRearm += 1
			assertAfterRearm(
				initialOwnerGeneration = scenario.canonicalState.ownerGeneration,
				initialRolloutRevision = scenario.canonicalState.rolloutRevision,
				initialLifecycleEpoch = scenario.initialLifecycleEpoch,
				expectedDeletedHighWater = scenario.expectedDeletedHighWater,
				initialPublishedGeneration = scenario.initialPublishedGeneration,
			)
			ExportPlansProto.getDefaultInstance()
		}

		val sourceWriterRearmProvider = Provider {
			counters.preRearm += 1
			assertBeforeRearm(
				initialOwnerGeneration = scenario.canonicalState.ownerGeneration,
				initialRolloutRevision = scenario.canonicalState.rolloutRevision,
				initialLifecycleEpoch = scenario.initialLifecycleEpoch,
				expectedDeletedHighWater = scenario.expectedDeletedHighWater,
				initialGeneration = scenario.initialGeneration,
				initialPublishedGeneration = scenario.initialPublishedGeneration,
			)
			sourceWriterDeletionRearm
		}

		val service = InfrastructureModule.provideCollectedDataDeletionService(
			context = context,
			pointsDatabase = mocks.pointsDatabase,
			exportPlanStore = mocks.exportPlanStore,
			writerQuiescer = mocks.writerQuiescer,
			collectedDataLifecycleStore = lifecycleStore,
			startupDeletionBarrier = deletionBarrier,
			activityRegistrationArbiter = Provider { mocks.activityArbiter },
			purposeDeletionFencer = Provider { mocks.purposeDeletionFencer },
			automaticControlRestorer = mocks.automaticControlRestorer,
			retentionAuthorityProducer = retentionAuthorityProducer,
			sourcePolicyAuthorityBootstrapCoordinator = Provider { sourcePolicyBootstrap },
			sourceWriterDeletionRearm = sourceWriterRearmProvider,
			providerFenceScope =
				CoroutineScope(SupervisorJob() + dispatchersProvider.default),
			dispatchersProvider = dispatchersProvider,
			traceboxHandleProvider = mocks.traceboxHandleProvider,
			trackingDiagnosticDataControl = mocks.trackingDiagnosticDataControl,
			dataBackupRestore = Provider { mocks.dataBackupRestore },
		)
		return DeletionFixture(
			service = service,
			automaticControlRestorer = mocks.automaticControlRestorer,
			traceboxHandle = mocks.traceboxHandle,
			writerQuiescer = mocks.writerQuiescer,
			counters = counters,
		)
	}

	private fun createDeletionMocks(): DeletionMocks {
		val pointsDao = mockk<PointsAwardedDao>()
		val mocks = DeletionMocks(
			pointsDatabase = mockk(),
			exportPlanStore = mockk(),
			writerQuiescer = mockk(),
			activityArbiter = mockk(),
			purposeDeletionFencer = mockk(),
			automaticControlRestorer = mockk(),
			traceboxHandle = mockk(),
			traceboxHandleProvider = mockk(),
			trackingDiagnosticDataControl = mockk(),
			dataBackupRestore = mockk(),
		)
		every { mocks.pointsDatabase.pointsAwardedDao() } returns pointsDao
		every { pointsDao.deleteAll() } just Runs
		coEvery { mocks.writerQuiescer.quiesce() } just Runs
		coEvery { mocks.trackingDiagnosticDataControl.clearAll() } returns
			TrackingDiagnosticClearResult.CLEARED
		coEvery { mocks.dataBackupRestore.cancelAndClearOwnedData() } just Runs
		every { mocks.writerQuiescer.resume() } just Runs
		coEvery { mocks.activityArbiter.closeForCollectedDataDeletion() } returns
			appliedRegistrationResult()
		coEvery { mocks.purposeDeletionFencer.fenceForCollectedDataDeletion() } returns
			TrackingPurposeSettingsReconciliationResult.Complete(emptySet())
		coEvery { mocks.automaticControlRestorer.schedule(any()) } returns
			PostDeletionRecoveryScheduleResult.Enqueued
		every { mocks.traceboxHandleProvider.handle } returns mocks.traceboxHandle
		every { mocks.traceboxHandle.delete(DeleteRequest.ALL_TRACEBOX_DATA) } returnsMany listOf(
			DeleteReport.PENDING_FAILURE,
			DeleteReport.COMPLETE,
		)
		return mocks
	}

	private suspend fun assertFailedAttemptRemainsFenced(
		scenario: DeletionScenario,
		fixture: DeletionFixture,
	) {
		val completion = fixture.service.deleteAll()
		assertEquals(
			"Expected diagnostic failure after re-arm, got $completion",
			CollectedDataDeletionCompletion.Retryable(
				CollectedDataDeletionReconciliationFailure.DeletionExecution,
			),
			completion,
		)
		assertEquals("Deletion stopped before writer re-arm: $completion", 1, fixture.counters.preRearm)
		assertEquals("Deletion stopped before post-rearm diagnostics: $completion", 1, fixture.counters.postRearm)
		markerFile.exists() shouldBe true
		deletionBarrier.isClosed shouldBe true
		deletionBarrier.currentGeneration shouldBe scenario.initialGeneration + 1L
		deletionBarrier.openGenerations.value shouldBe scenario.initialPublishedGeneration
		startupGate.isReady shouldBe false
		coVerify(exactly = 0) { fixture.automaticControlRestorer.schedule(any()) }
	}

	private suspend fun assertSuccessfulRetryReopens(
		scenario: DeletionScenario,
		fixture: DeletionFixture,
	) {
		fixture.service.reconcilePendingDeletion() shouldBe
			CollectedDataDeletionCompletion.Complete

		fixture.counters.preRearm shouldBe 1
		fixture.counters.postRearm shouldBe 2
		markerFile.exists() shouldBe false
		deletionBarrier.isClosed shouldBe false
		deletionBarrier.currentGeneration shouldBe scenario.initialGeneration + 1L
		deletionBarrier.openGenerations.value shouldBe scenario.initialGeneration + 1L
		startupGate.withReadyGeneration(scenario.initialGeneration) { true } shouldBe null
		lifecycleStore.snapshot().epoch shouldBe scenario.initialLifecycleEpoch + 1L
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe
			scenario.initialLifecycleEpoch + 1L
		assertCandidateAuthority(
			ownerGeneration = scenario.canonicalState.ownerGeneration + 1L,
			rolloutRevision = scenario.canonicalState.rolloutRevision + 1L,
			expectedDeletedHighWater = scenario.expectedDeletedHighWater,
			rolloutStore = scenario.rolloutStore,
		)
		coVerify(exactly = 1) {
			fixture.automaticControlRestorer.schedule(scenario.initialLifecycleEpoch + 1L)
		}
		verify(exactly = 2) {
			fixture.traceboxHandle.delete(DeleteRequest.ALL_TRACEBOX_DATA)
		}
		coVerify(exactly = 4) { fixture.writerQuiescer.quiesce() }
		startupGate.reconcile(retryFailedStorage = true)
			.shouldBeInstanceOf<TrackingStartupResult.Ready>()
		startupGate.isReady shouldBe true
		startupGate.withReadyGeneration(scenario.initialGeneration + 1L) { true } shouldBe true
	}

	private suspend fun establishCanonicalStepsWriter(
		rolloutStore: RoomTrackingRolloutStateStore,
	): CanonicalStepsState {
		val ownerDao = database.sourceDestinationOwnerDao()
		ownerDao.insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = STEPS_SOURCE,
				destination = STEPS_DESTINATION,
				owner = LEGACY_OWNER,
				ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
				updatedAtMs = 1L,
			),
		)
		val currentOwner = requireNotNull(ownerDao.get(STEPS_SOURCE, STEPS_DESTINATION))
		val currentRollout = rolloutStore.load()
		if (currentOwner.owner == CANDIDATE_OWNER) {
			assertCandidateAuthority(
				ownerGeneration = currentOwner.ownerGeneration,
				rolloutRevision = currentRollout.revision,
				expectedDeletedHighWater = requireNotNull(
					database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE),
				).contiguousAdmissionOrdinal,
				rolloutStore = rolloutStore,
			)
			return CanonicalStepsState(currentOwner.ownerGeneration, currentRollout.revision)
		}
		currentOwner.owner shouldBe LEGACY_OWNER
		val activeLane = database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)
		val shadowRevision = if (activeLane == null) {
			rolloutStore.installInertShadowLane(
				binding = STEPS_BINDING,
				rolloutRevision = currentRollout.revision + 1L,
				updatedAtMs = 10L,
			).rollout.revision
		} else {
			activeLane.productStage shouldBe SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW
			activeLane.activatedRolloutRevision shouldBe currentRollout.revision
			currentRollout.revision
		}
		coordinator.activateCandidate(
			expectedRolloutRevision = shadowRevision,
			updatedAtMs = 20L,
		).shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
		val owner = requireNotNull(
			database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION),
		)
		val rollout = rolloutStore.load()
		owner.owner shouldBe CANDIDATE_OWNER
		rollout.isCaptureReachable(
			SourceKind.STEPS,
			CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
		) shouldBe true
		return CanonicalStepsState(owner.ownerGeneration, rollout.revision)
	}

	private suspend fun seedCollectedStepsAndBrokerState(collectedDataEpoch: Long): Long {
		appendStepsWalEvent(eventId = "steps-delete-gap-1", sourceSequence = 1L, collectedDataEpoch)
		database.sourceEventWalDao().deleteAll()
		appendStepsWalEvent(eventId = "steps-delete-gap-2", sourceSequence = 2L, collectedDataEpoch)
		val highWater = requireNotNull(database.sourceEventWalDao().maximumAdmissionOrdinal())
		highWater shouldNotBe database.sourceEventWalDao().countAll()

		val intervalId = database.stepIntervalDao().insert(
			StepInterval(
				startTimeMs = 1_000L,
				endTimeMs = 2_000L,
				stepCount = 12,
				sensorValueStart = 100,
				sensorValueEnd = 112,
				sensorReset = false,
				createdAt = 2_000L,
				sourceSignalId = "steps-delete-gap-2",
			),
		)
		database.stepFactRevisionDao().insert(
			stepFact(intervalId, highWater, collectedDataEpoch),
		) shouldNotBe -1L
		database.pendingSignalDao().insertAll(
			listOf(
				PendingSignalEntity(
					signalId = "pending-steps-delete",
					sessionId = 1L,
					signalJson = "{}",
					createdAt = 2_000L,
					capturedEpoch = collectedDataEpoch,
					acquiredAtMs = 2_000L,
					stepsWriterOwner = CANDIDATE_OWNER,
					stepsWriterOwnerGeneration = currentCandidateOwnerGeneration(),
				),
			),
		)

		val demand = stepsCaptureDemand(collectedDataEpoch)
		val brokerDao = database.sourceBrokerDao()
		brokerDao.insertDemands(listOf(demand)).single() shouldNotBe -1L
		brokerDao.insertRegistration(stepsRegistration(collectedDataEpoch))
		brokerDao.insertAuthorizations(stepsAuthorizations(collectedDataEpoch))
		return highWater
	}

	private fun assertBeforeRearm(
		initialOwnerGeneration: Long,
		initialRolloutRevision: Long,
		initialLifecycleEpoch: Long,
		expectedDeletedHighWater: Long,
		initialGeneration: Long,
		initialPublishedGeneration: Long,
	) {
		markerFile.exists() shouldBe true
		deletionBarrier.isClosed shouldBe true
		deletionBarrier.currentGeneration shouldBe initialGeneration + 1L
		deletionBarrier.openGenerations.value shouldBe initialPublishedGeneration
		startupGate.isReady shouldBe false
		startupGate.withReadyGeneration(initialGeneration) { true } shouldBe null
		COLLECTED_TABLES.forEach { table -> rowCount(table) shouldBe 0L }
		queryLong(
			"SELECT owner_generation FROM source_destination_owner " +
				"WHERE source_kind = $STEPS_SOURCE AND destination = '$STEPS_DESTINATION'",
		) shouldBe initialOwnerGeneration
		queryString(
			"SELECT owner FROM source_destination_owner " +
				"WHERE source_kind = $STEPS_SOURCE AND destination = '$STEPS_DESTINATION'",
		) shouldBe CANDIDATE_OWNER
		queryLong("SELECT revision FROM tracking_rollout_state WHERE id = 1") shouldBe
			initialRolloutRevision
		queryString("SELECT projection_mode FROM tracking_rollout_state WHERE id = 1")
			.contains("$STEPS_SOURCE:EVENT_CANONICAL:") shouldBe true
		queryLong("SELECT collected_data_epoch FROM source_evidence_state WHERE id = 1") shouldBe
			initialLifecycleEpoch + 1L
		queryLong(
			"SELECT deleted_source_event_high_water_ordinal FROM source_evidence_state WHERE id = 1",
		) shouldBe expectedDeletedHighWater
	}

	private suspend fun assertAfterRearm(
		initialOwnerGeneration: Long,
		initialRolloutRevision: Long,
		initialLifecycleEpoch: Long,
		expectedDeletedHighWater: Long,
		initialPublishedGeneration: Long,
	) {
		markerFile.exists() shouldBe true
		deletionBarrier.isClosed shouldBe true
		deletionBarrier.openGenerations.value shouldBe initialPublishedGeneration
		startupGate.isReady shouldBe false
		lifecycleStore.snapshot().epoch shouldBe initialLifecycleEpoch + 1L
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe
			initialLifecycleEpoch + 1L
		assertCandidateAuthority(
			ownerGeneration = initialOwnerGeneration + 1L,
			rolloutRevision = initialRolloutRevision + 1L,
			expectedDeletedHighWater = expectedDeletedHighWater,
			rolloutStore = RoomTrackingRolloutStateStore(
				database,
				executableLaneCatalog,
			),
		)
	}

	private suspend fun assertCandidateAuthority(
		ownerGeneration: Long,
		rolloutRevision: Long,
		expectedDeletedHighWater: Long,
		rolloutStore: RoomTrackingRolloutStateStore,
	) {
		val owner = requireNotNull(
			database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION),
		)
		owner.owner shouldBe CANDIDATE_OWNER
		owner.ownerGeneration shouldBe ownerGeneration
		val lane = requireNotNull(
			database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE),
		)
		lane.productStage shouldBe SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL
		lane.activationOrdinal shouldBe expectedDeletedHighWater + 1L
		lane.contiguousAdmissionOrdinal shouldBe expectedDeletedHighWater
		val rollout = rolloutStore.load()
		rollout.revision shouldBe rolloutRevision
		rollout.isCaptureReachable(
			SourceKind.STEPS,
			CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
		) shouldBe true
	}

	private suspend fun currentCandidateOwnerGeneration(): Long = requireNotNull(
		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION),
	).ownerGeneration

	private suspend fun appendStepsWalEvent(
		eventId: String,
		sourceSequence: Long,
		collectedDataEpoch: Long,
	) {
		val payload = StepsWalPayloadCodec.encode(StepsWalPayload(
			bootClockDomainId = BOOT_ID,
			firstCumulativeCount = 100L,
			lastCumulativeCount = 112L,
			deltaCount = 12L,
			windowStartElapsedRealtimeNanos = 1_000_000_000L,
			windowEndElapsedRealtimeNanos = 2_000_000_000L,
			firstProviderSequence = sourceSequence,
			lastProviderSequence = sourceSequence,
			boundaryKind = StepsWalBoundaryKind.COVERED,
			counterEpochGeneration = 1L,
			counterDomainToken = null,
		))
		val authorization = stepsAuthorizations(collectedDataEpoch).single()
		val row = SourceEventWalEntity(
			eventId = eventId,
			providerDedupKey = null,
			logicalTrackingId = "tracking-delete",
			serviceRunId = "run-delete",
			sourceKind = STEPS_SOURCE,
			sourceInstanceId = "steps-provider-delete",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "steps-config-delete",
			authorizationRevision = authorization.authorizationRevision,
			authorizationPurposeEligibilityMask = authorization.purposeEligibilityMask,
			authorizationFingerprint = authorization.authorizationFingerprint,
			sourceSequence = sourceSequence,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = 2_000_000_000L,
			observedIntervalStartNanos = 1_000_000_000L,
			receivedElapsedNanos = 2_000_000_000L,
			wallTimeMs = 2_000L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = collectedDataEpoch,
			sourcePolicyRevision = authorization.sourcePolicyRevision,
			captureConsentEpoch = authorization.consentEpoch,
			sessionManifestRevision = authorization.manifestRevision,
			lifecycleLeaseGeneration = authorization.lifecycleLeaseGeneration,
			acquiredAtMs = 2_000L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = STEPS_WAL_PAYLOAD_VERSION,
			payload = payload,
			payloadChecksum = "",
			createdAtMs = 2_000L,
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(
			row.copy(payloadChecksum = row.calculatedPayloadChecksum(),
				integrityIdentity = row.calculatedIntegrityIdentity()),
		)
	}

	private fun stepsAuthorizations(collectedDataEpoch: Long) = SourceBrokerAuthorization.rows(
		sourceKind = STEPS_SOURCE,
		registrationGeneration = 1L,
		authorizationRevision = 1L,
		demands = listOf(stepsCaptureDemand(collectedDataEpoch)),
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 100L,
		effectiveWallTimeMs = 100L,
	)

	private fun stepFact(
		intervalId: Long,
		admissionOrdinal: Long,
		collectedDataEpoch: Long,
	) = StepFactRevisionEntity(
		logicalFactId = "steps-fact-delete",
		semanticRevision = 1L,
		mutationId = "steps-mutation-delete",
		stepIntervalId = intervalId,
		sourceEventId = "steps-delete-gap-2",
		sourceAdmissionOrdinal = admissionOrdinal,
		originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
		originIdentity = "steps-delete-gap-2",
		writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
		operation = StepFactRevisionEntity.OPERATION_UPSERT,
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 2_000L,
		intervalStartElapsedRealtimeNanos = 1_000_000_000L,
		intervalEndElapsedRealtimeNanos = 2_000_000_000L,
		clockDomainId = BOOT_ID,
		bootClockDomainId = BOOT_ID,
		cumulativeStepCountStart = 100L,
		cumulativeStepCountEnd = 112L,
		wallTimeUncertaintyMs = 0L,
		coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
		effectiveStepCount = 12L,
		logicalTrackingId = "tracking-delete",
		serviceRunId = "run-delete",
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = collectedDataEpoch,
		collectedDataEpoch = collectedDataEpoch,
		scopeDeletionGeneration = 0L,
		effectChecksum = "pending-fixture-checksum",
		appliedAtMs = 2_000L,
	).let { it.copy(effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(it)) }

	private fun stepsCaptureDemand(collectedDataEpoch: Long) = SourceDemandEntity(
		demandId = "steps-capture-delete",
		consumerId = "session:tracking-delete",
		sourceKind = STEPS_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = "tracking-delete",
		serviceRunId = "run-delete",
		manifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = collectedDataEpoch,
		persistenceEligible = true,
		qosCode = 1,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 1_000L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = 100L,
		requestedAtMs = 100L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun stepsRegistration(collectedDataEpoch: Long) =
		ProviderRegistrationGenerationEntity(
			sourceKind = STEPS_SOURCE,
			registrationGeneration = 1L,
			sourceInstanceId = "steps-provider-delete",
			ownerScope = "source-broker:$STEPS_SOURCE",
			providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			providerProcessIncarnationId = "process-delete",
			clockDomainId = BOOT_ID,
			physicalConfigurationFingerprint = "steps-config-delete",
			collectedDataEpoch = collectedDataEpoch,
			status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			reservedAtMs = 90L,
			reservedElapsedRealtimeNanos = 90L,
			acceptedAtMs = 100L,
			acceptedElapsedRealtimeNanos = 100L,
			retiredAtMs = null,
			retiredElapsedRealtimeNanos = null,
			failureCode = null,
		)

	private fun appliedRegistrationResult() = ActivityRegistrationResult(
		status = ActivityRegistrationStatus.APPLIED,
		snapshot = ActivityRegistrationSnapshot(
			active = false,
			identity = null,
			owners = emptySet(),
			continuousRecognitionIntervalSeconds = null,
			transitions = emptySet(),
		),
	)

	private fun rowCount(table: String): Long = queryLong("SELECT COUNT(*) FROM $table")

	private fun queryLong(sql: String): Long =
		database.openHelper.writableDatabase.query(sql).use { cursor ->
			check(cursor.moveToFirst()) { "No result for query: $sql" }
			cursor.getLong(0)
		}

	private fun queryString(sql: String): String =
		database.openHelper.writableDatabase.query(sql).use { cursor ->
			check(cursor.moveToFirst()) { "No result for query: $sql" }
			cursor.getString(0)
		}

	private data class DeletionScenario(
		val rolloutStore: RoomTrackingRolloutStateStore,
		val initialLifecycleEpoch: Long,
		val initialGeneration: Long,
		val initialPublishedGeneration: Long,
		val canonicalState: CanonicalStepsState,
		val expectedDeletedHighWater: Long,
	)

	private data class DeletionFixture(
		val service: CollectedDataDeletionService,
		val automaticControlRestorer: PostDeletionAutomaticControlRestorer,
		val traceboxHandle: TraceboxHandle,
		val writerQuiescer: CollectedDataWriterQuiescer,
		val counters: RearmCheckCounters,
	)

	private data class DeletionMocks(
		val pointsDatabase: PointsDatabase,
		val exportPlanStore: ExportPlanStore,
		val writerQuiescer: CollectedDataWriterQuiescer,
		val activityArbiter: ActivityRegistrationArbiter,
		val purposeDeletionFencer: TrackingPurposeDeletionFencer,
		val automaticControlRestorer: PostDeletionAutomaticControlRestorer,
		val traceboxHandle: TraceboxHandle,
		val traceboxHandleProvider: TrackerTraceboxHandleProvider,
		val trackingDiagnosticDataControl: TrackingDiagnosticDataControl,
		val dataBackupRestore: DataBackupRestore,
	)

	private data class RearmCheckCounters(
		var preRearm: Int = 0,
		var postRearm: Int = 0,
	)

	private data class CanonicalStepsState(
		val ownerGeneration: Long,
		val rolloutRevision: Long,
	)

	private companion object {
		const val BOOT_ID = "boot-delete"
		const val STEPS_SOURCE = SourceDestinationOwnerEntity.SOURCE_STEPS
		const val STEPS_DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
		const val LEGACY_OWNER = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		const val CANDIDATE_OWNER = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		val STEPS_BINDING = ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS
		val COLLECTED_TABLES = listOf(
			"source_event_wal",
			"step_interval",
			"step_fact_revision",
			"ambient_steps_fact_revision",
			"ambient_steps_import_cursor",
			"ambient_steps_import_gap",
			"ambient_steps_import_authority_transition",
			"pending_signal",
			"source_product_projection_lane",
			"source_demand",
			"provider_registration_generation",
			"source_authorization",
		)
	}
}

private infix fun <T> T.shouldBe(expected: T) {
	assertEquals(expected, this)
}

private infix fun <T> T.shouldNotBe(unexpected: T) {
	assertNotEquals(unexpected, this)
}

private inline fun <reified T> Any?.shouldBeInstanceOf(): T {
	assertTrue("Expected ${T::class.java.name}, got ${this?.javaClass?.name}", this is T)
	return this as T
}
