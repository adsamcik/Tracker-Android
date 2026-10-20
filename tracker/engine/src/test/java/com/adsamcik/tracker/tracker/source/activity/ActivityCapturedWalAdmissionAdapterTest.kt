package com.adsamcik.tracker.tracker.source.activity

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ActivityCapturedRetentionResult
import com.adsamcik.tracker.shared.base.database.pruneCapturedActivityFactsAffectedByRetentionFloor
import com.adsamcik.tracker.tracker.source.advanceRetainedSourceOperations
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.coordinator.RoomSourceProductDrainRouter
import com.adsamcik.tracker.tracker.source.coordinator.SourceDrainRetirementClaim
import com.adsamcik.tracker.tracker.source.coordinator.SourceProductDrainAuthority
import com.adsamcik.tracker.tracker.source.coordinator.SourceProductDrainPlan
import com.adsamcik.tracker.tracker.source.coordinator.SourceProductDrainRequest
import com.adsamcik.tracker.tracker.source.coordinator.SourceProductDrainResult
import com.adsamcik.tracker.tracker.source.coordinator.authenticateSourceProductDrainRequest
import com.adsamcik.tracker.tracker.source.coordinator.buildSourceProductDrainPlan
import com.adsamcik.tracker.tracker.source.ingress.ActivitySourceDeliveryFactory
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.toStableFlags
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// One Room Activity carrier fixture keeps original plan, session-window and retained admission checks
// comparable.
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityCapturedWalAdmissionAdapterTest {
	private lateinit var database: AppDatabase
	private val payloadCodec = DefaultSourcePayloadCodec()
	private val planCodec = SourcePlanCodec()

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `retired interrupted Activity drains saved readings and settles with unknown coverage`() = runTest {
		val request = prepareInterruptedActivityDrain()
		request.sourceHighWaterAdmissionOrdinal shouldBe 2L
		request.settlementHighWaterAdmissionOrdinal shouldBe 3L
		val receipt = database.sourceSessionDao().rawRunRetirements(
			LOGICAL_TRACKING_ID, SERVICE_RUN_ID, ACTIVITY_SOURCE, 2,
		).single().validatedOrNull()
		val router = activityDrainRouter()

		authenticateSourceProductDrainRequest(database, request) shouldBe null
		router.drainThrough(request).shouldBeInstanceOf<SourceProductDrainResult.Unavailable>()
			.reason shouldBe "ACTIVITY_PRODUCT_TERMINALLY_UNAVAILABLE"
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceProjectionStateDao().activeProductLane(ACTIVITY_SOURCE)
			?.contiguousAdmissionOrdinal shouldBe 3L
		database.sourceSessionDao().rawRunRetirements(
			LOGICAL_TRACKING_ID, SERVICE_RUN_ID, ACTIVITY_SOURCE, 2,
		).single().validatedOrNull() shouldBe receipt
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		session.cutoffElapsedNanos shouldBe SESSION_END_NANOS
		session.cutoffAtMs shouldBe 2_000L
		router.drainThrough(request).shouldBeInstanceOf<SourceProductDrainResult.Unavailable>()
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
	}

	@Test
	fun `retired interrupted Activity with no callbacks settles without inventing readings`() = runTest {
		val request = prepareInterruptedActivityDrain(withReadings = false)
		request.sourceHighWaterAdmissionOrdinal shouldBe 0L
		activityDrainRouter().drainThrough(request).shouldBeInstanceOf<SourceProductDrainResult.Unavailable>()
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
		database.sourceEventWalDao().getByAdmissionOrdinal(0L) shouldBe null
	}

	@Test
	fun `interrupted Activity cannot settle a changed receipt or unfinished provider`() = runTest {
		val request = prepareInterruptedActivityDrain()
		val db = database.openHelper.writableDatabase
		db.execSQL("UPDATE source_run_retirement SET cutoff_wall_time_ms = 2001")
		authenticateSourceProductDrainRequest(database, request) shouldBe "SOURCE_DRAIN_SETTLEMENT_INCOMPLETE"
		db.execSQL("UPDATE source_run_retirement SET cutoff_wall_time_ms = 2000, app_drain_complete = 1")
		authenticateSourceProductDrainRequest(database, request) shouldBe "SOURCE_DRAIN_SETTLEMENT_INCOMPLETE"
		db.execSQL("UPDATE source_run_retirement SET app_drain_complete = 0")
		db.execSQL("UPDATE provider_registration_generation SET status = 'RETIRING'")
		authenticateSourceProductDrainRequest(database, request) shouldBe "SOURCE_DRAIN_SETTLEMENT_INCOMPLETE"
		db.execSQL("UPDATE provider_registration_generation SET status = 'RETIRED'")
		db.execSQL("UPDATE source_run_retirement SET action_id = 'different-action'")
		authenticateSourceProductDrainRequest(database, request) shouldBe "SOURCE_DRAIN_RETIREMENT_AUTHORITY_CHANGED"
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
		database.sourceProjectionStateDao().activeProductLane(ACTIVITY_SOURCE)
			?.contiguousAdmissionOrdinal shouldBe 0L
	}

	private fun activityDrainRouter() = RoomSourceProductDrainRouter(
		database = database, stepsLane = mockk(), pressureLane = mockk(),
		activityLane = ActivityCapturedFactProjectionLane(database, subject()),
		wifiLane = mockk(), cellLane = mockk(), protectedLocationDrain = mockk(),
	)

	private suspend fun prepareInterruptedActivityDrain(withReadings: Boolean = true): SourceProductDrainRequest {
		installFixture()
		if (withReadings) {
			insertLaterLocationWal()
		} else {
			database.openHelper.writableDatabase.execSQL("DELETE FROM source_event_wal")
		}
		sealCaptureTail()
		installProjectionLane()
		installSettledStoppingWindow("STOPPING", "FINALIZED")
		val dao = database.sourceSessionDao()
		val original = dao.completenessForServiceRun(LOGICAL_TRACKING_ID, SERVICE_RUN_ID).single()
		val complete = original.copy(lastAdmissionOrdinal = if (withReadings) { 2L } else { 0L },
			lastSourceSequence = if (withReadings) { 1L } else { 0L }, appDrainComplete = false,
			stopStatus = "PROCESS_RESTARTED", unresolvedSequenceStart = 1L.takeIf { withReadings },
			unresolvedSequenceEnd = 1L.takeIf { withReadings })
		dao.saveCompleteness(complete)
		val session = requireNotNull(dao.session(LOGICAL_TRACKING_ID))
		val finalOrdinal = database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L
		dao.updateSession(session.copy(finalAdmissionOrdinal = finalOrdinal)) shouldBe 1
		val receipt = SourceRunRetirementEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID, serviceRunId = SERVICE_RUN_ID,
			sourceKind = ACTIVITY_SOURCE, sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = REGISTRATION_GENERATION, actionId = "retired-activity-action",
			attemptCount = 1, leaseGeneration = LEASE_GENERATION,
			cutoffElapsedRealtimeNanos = SESSION_END_NANOS, cutoffWallTimeMs = 2_000L,
			state = SourceRunRetirementEntity.STATE_INTERRUPTED, appliedRevision = null,
			callbackEntryBarrierSequence = requireNotNull(complete.lastSourceSequence),
			lastSourceSequence = complete.lastSourceSequence, lastAdmissionOrdinal = complete.lastAdmissionOrdinal,
			failedAdmissionCount = if (withReadings) { 1L } else { 0L },
			unresolvedSequenceStart = complete.unresolvedSequenceStart, unresolvedSequenceEnd = complete.unresolvedSequenceEnd,
			registrationRemovalOutcome = "REMOVED", providerFlushOutcome = "NOT_REQUESTED",
			providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE", appDrainComplete = false,
			stopStatus = "PROCESS_RESTARTED", updatedAtMs = 2_000L,
		)
		dao.insertRunRetirementIntent(receipt)
		val authority = SourceProductDrainAuthority(
			captureBindings = mapOf(ACTIVITY_SOURCE to dao.manifestSources(LOGICAL_TRACKING_ID, MANIFEST_REVISION)),
			retirementClaims = listOf(SourceDrainRetirementClaim(SourceKind.ACTIVITY, SOURCE_INSTANCE_ID,
				REGISTRATION_GENERATION, receipt.actionId, 1, LEASE_GENERATION, cleanupOnly = false)),
		)
		return buildSourceProductDrainPlan(database, LOGICAL_TRACKING_ID, SERVICE_RUN_ID,
			SESSION_END_NANOS, 2_000L, finalOrdinal, authority)
			.shouldBeInstanceOf<SourceProductDrainPlan.Ready>()
			.requests.single()
	}

	private suspend fun insertLaterLocationWal() {
		val payload = payloadCodec.encode(LocationFixPayload(50.0, 14.0, 5f, null, null, null, null, "test"), 1)
		val first = requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(1L))
		val location = first.copy(admissionOrdinal = 3L, eventId = "later-location", sourceKind = SourceKind.LOCATION.stableCode,
			sourceInstanceId = "location-instance", sourceSequence = 0L, deliveryIdentity = "later-location-delivery",
			deliveryUnitIndex = 0, deliveryUnitCount = 1, payloadVersion = 1, payload = payload.bytes,
			payloadChecksum = payload.checksum, integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM)
		val qualified = location.copy(integrityIdentity = location.calculatedIntegrityIdentity())
		qualified.hasQualifiedIntegrity() shouldBe true
		database.sourceEventWalDao().insertIgnoringDuplicate(qualified) shouldBe 3L
	}

	@Test
	fun `adaptive Activity recorded after reboot recovers its terminal failure exactly once`() = runTest {
		installFixture(adaptive = true, settingsBootId = "previous-boot", settingsElapsedNanos = 10_000L)
		installProjectionLane()
		installHistoricalPlanFailure()
		val projection = ActivityCapturedFactProjectionLane(database, subject())

		projection.drainAvailable() shouldBe ActivityCapturedFactDrainResult.Complete(2L, 1, 2)
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceProjectionStateDao().failure(
			ActivityCapturedFactProjectionLane.WRITER_ID,
			ActivityCapturedFactProjectionLane.WRITER_VERSION,
			1L,
		) shouldBe null
		projection.drainAvailable() shouldBe ActivityCapturedFactDrainResult.Complete(2L, 0, 0)
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
		// The retained-data audit uses the same original settings after reboot as the writer.
		database.sourceEvidenceStateDao().updateLifecycle(0L, 0L, System.currentTimeMillis()) shouldBe 1
		database.pruneCapturedActivityFactsAffectedByRetentionFloor(
			0L, 0L, System.currentTimeMillis() + 1_000L,
		) shouldBe ActivityCapturedRetentionResult.NoChange
	}

	@Test
	fun `same boot settings after the measurement leave its terminal failure and cursor intact`() = runTest {
		installFixture(adaptive = true, settingsElapsedNanos = FIRST_EVENT_NANOS + 1L)
		installProjectionLane()
		installHistoricalPlanFailure()

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.POLICY_MISMATCH,
		)
		(ActivityCapturedFactProjectionLane(database, subject()).drainAvailable()
			is ActivityCapturedFactDrainResult.Failed) shouldBe true
		database.sourceProjectionStateDao().activeProductLane(ACTIVITY_SOURCE)
			?.contiguousAdmissionOrdinal shouldBe 0L
		database.sourceProjectionStateDao().failure(
			ActivityCapturedFactProjectionLane.WRITER_ID,
			ActivityCapturedFactProjectionLane.WRITER_VERSION,
			1L,
		)?.failureCode shouldBe "ACTIVITY_WAL_HISTORICAL_PLAN_MISMATCH"
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `retired Activity seal recovers saved app tail without changing unknown provider coverage`() = runTest {
		installFixture(adaptive = true, settingsBootId = "previous-boot", settingsElapsedNanos = 10_000L)
		val completeness = database.sourceSessionDao().completenessForServiceRun(
			LOGICAL_TRACKING_ID, SERVICE_RUN_ID,
		).single()
		database.sourceSessionDao().deleteAllCompleteness()
		val interrupted = completeness.copy(
			sourceInstanceId = "unresolved-activity",
			registrationGeneration = 0L,
			lastAdmissionOrdinal = null,
			lastSourceSequence = null,
			appDrainComplete = false,
			stopStatus = "TIMED_OUT",
		)
		database.sourceSessionDao().saveCompleteness(interrupted)
		sealCaptureTail()
		installProjectionLane()
		installHistoricalPlanFailure()

		ActivityCapturedFactProjectionLane(database, subject()).drainAvailable() shouldBe
			ActivityCapturedFactDrainResult.Complete(2L, 1, 2)
		database.sourceSessionDao().completenessForServiceRun(
			LOGICAL_TRACKING_ID, SERVICE_RUN_ID,
		).single() shouldBe interrupted
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
	}

	@Test
	fun `interrupted Activity recovers its original sealed readings without inventing an elapsed Stop`() = runTest {
		for (reason in listOf("FORCE_STOP_SUPPRESSED", "PREVIOUS_PROCESS_INTERRUPTED")) {
			database.clearAllTables()
			installFixture(adaptive = true, settingsBootId = "previous-boot", settingsElapsedNanos = 10_000L)
			interruptSession(reason)
			installProjectionLane()
			installHistoricalPlanFailure("ACTIVITY_WAL_SESSION_MISMATCH")
			val projection = ActivityCapturedFactProjectionLane(database, subject())

			projection.drainAvailable() shouldBe ActivityCapturedFactDrainResult.Complete(2L, 1, 2)
			val saved = database.activityCapturedFactDao().currentRevisionsForWriterAdmission(
				ActivityCapturedFactProjectionLane.WRITER_ID,
				ActivityCapturedFactProjectionLane.WRITER_VERSION, 1L, 2,
			).single()
			saved.sessionRunEffectEndNanos shouldBe Long.MAX_VALUE
			database.activityCapturedFactDao().evidence(saved.writerProjectionId, saved.writerProjectionVersion,
				saved.logicalWindowId, saved.semanticRevision).map { it.sourceEventId }.toSet() shouldBe
				setOf(EVENT_ID.value, "activity-event-2")
			val originalSession = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
			originalSession.cutoffElapsedNanos shouldBe null
			originalSession.finalAdmissionOrdinal shouldBe null
			database.sourceSessionDao().completenessForServiceRun(LOGICAL_TRACKING_ID, SERVICE_RUN_ID) shouldBe emptyList()
			projection.drainAvailable() shouldBe ActivityCapturedFactDrainResult.Complete(2L, 0, 0)
			database.activityCapturedFactDao().revisionCount() shouldBe 1L
		}
	}

	@Test
	fun `interrupted Activity cannot admit a reading at or after its known wall Stop`() = runTest {
		installFixture()
		interruptSession("FORCE_STOP_SUPPRESSED", cutoffAtMs = FIRST_EVENT_WALL_MS)
		installProjectionLane()
		installHistoricalPlanFailure("ACTIVITY_WAL_SESSION_MISMATCH")

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH,
		)
		val rawIdentities = (1L..2L).map { ordinal ->
			requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(ordinal)).let {
				it.eventId to it.integrityIdentity
			}
		}
		ActivityCapturedFactProjectionLane(database, subject()).drainAvailable() shouldBe
			ActivityCapturedFactDrainResult.Complete(2L, 0, 0)
		database.sourceProjectionStateDao().activeProductLane(ACTIVITY_SOURCE)
			?.contiguousAdmissionOrdinal shouldBe 2L
		(1L..2L).map { ordinal ->
			val rejected = requireNotNull(database.sourceProjectionStateDao().failure(
				ActivityCapturedFactProjectionLane.WRITER_ID,
				ActivityCapturedFactProjectionLane.WRITER_VERSION, ordinal,
			))
			rejected.failureCode shouldBe "ACTIVITY_WAL_SESSION_MISMATCH"
			rejected.terminal shouldBe true
			requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(ordinal)).let {
				it.eventId to it.integrityIdentity
			}
		} shouldBe rawIdentities
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `interrupted Activity cannot borrow a different instance sealed tail`() = runTest {
		installFixture()
		interruptSession("PREVIOUS_PROCESS_INTERRUPTED")
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_capture_admission_barrier SET source_instance_id = ?",
			arrayOf("different-activity-instance"),
		)
		installProjectionLane()
		installHistoricalPlanFailure("ACTIVITY_WAL_SESSION_MISMATCH")

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Unavailable(
			ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW,
		)
		(ActivityCapturedFactProjectionLane(database, subject()).drainAvailable()
			is ActivityCapturedFactDrainResult.Failed) shouldBe true
		database.sourceProjectionStateDao().activeProductLane(ACTIVITY_SOURCE)
			?.contiguousAdmissionOrdinal shouldBe 0L
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	private suspend fun interruptSession(reason: String, cutoffAtMs: Long = 3_000L) {
		val dao = database.sourceSessionDao()
		val session = requireNotNull(dao.session(LOGICAL_TRACKING_ID))
		val run = requireNotNull(dao.serviceRun(SERVICE_RUN_ID))
		dao.updateSession(session.copy(cutoffAtMs = cutoffAtMs, cutoffElapsedNanos = null,
			completedAtMs = cutoffAtMs, finalAdmissionOrdinal = null, failureCode = reason)) shouldBe 1
		dao.updateServiceRun(run.copy(completedAtMs = cutoffAtMs, completionReason = reason,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = cutoffAtMs)) shouldBe 1
		dao.deleteAllCompleteness()
		sealCaptureTail()
	}

	@Test
	fun `different Activity instance seal cannot substitute for missing app drain`() = runTest {
		installFixture()
		database.sourceSessionDao().deleteAllCompleteness()
		sealCaptureTail()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_capture_admission_barrier SET source_instance_id = ?",
			arrayOf("different-activity-instance"),
		)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Unavailable(
			ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW,
		)
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	private suspend fun installProjectionLane() {
		database.sourceProjectionStateDao().installProductLane(SourceProductProjectionLaneEntity(
			sourceKind = ACTIVITY_SOURCE,
			bindingGeneration = 1L,
			projectionId = ActivityCapturedFactProjectionLane.WRITER_ID,
			projectionVersion = ActivityCapturedFactProjectionLane.WRITER_VERSION,
			captureModeMask = 1L,
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			activatedRolloutRevision = 1L,
			activationOrdinal = 1L,
			contiguousAdmissionOrdinal = 0L,
			captureAdmissionCutoffOrdinal = null,
			retentionRequired = true,
			status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
			installedAtMs = 1_000L,
			updatedAtMs = 1_000L,
		))
	}

	private suspend fun installHistoricalPlanFailure(failureCode: String = "ACTIVITY_WAL_HISTORICAL_PLAN_MISMATCH") {
		database.sourceProjectionStateDao().saveFailure(SourceProjectionFailureEntity(
			projectionId = ActivityCapturedFactProjectionLane.WRITER_ID,
			projectionVersion = ActivityCapturedFactProjectionLane.WRITER_VERSION,
			admissionOrdinal = 1L,
			attemptCount = 1,
			failureCode = failureCode,
			terminal = true,
			lastAttemptAtMs = 2_000L,
		))
	}

	private suspend fun sealCaptureTail() {
		database.sourceBrokerDao().sealCaptureAdmissionBarrier(
			ACTIVITY_SOURCE, REGISTRATION_GENERATION, SOURCE_INSTANCE_ID,
			AUTHORIZATION_REVISION, SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			SESSION_END_NANOS, 2_000L,
		)
	}

	@Test
	fun `actual original Activity lane coalesces separately decoded observations into one saved window`() = runTest {
		installFixture()
		database.sourceProjectionStateDao().installProductLane(SourceProductProjectionLaneEntity(
			sourceKind = ACTIVITY_SOURCE,
			bindingGeneration = 1L,
			projectionId = ActivityCapturedFactProjectionLane.WRITER_ID,
			projectionVersion = ActivityCapturedFactProjectionLane.WRITER_VERSION,
			captureModeMask = 1L,
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			activatedRolloutRevision = 1L,
			activationOrdinal = 1L,
			contiguousAdmissionOrdinal = 0L,
			captureAdmissionCutoffOrdinal = null,
			retentionRequired = true,
			status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
			installedAtMs = 1_000L,
			updatedAtMs = 1_000L,
		))
		// Providers and lifecycle rows are fixtures. Admission, grouping, coalescing and writes
		// below use their actual Room-backed implementations, not mocked decoded objects.
		val projection = ActivityCapturedFactProjectionLane(database, subject())
		projection.drainAvailable() shouldBe ActivityCapturedFactDrainResult.Complete(2L, 1, 2)
		val dao = database.activityCapturedFactDao()
		dao.revisionCount() shouldBe 1L
		val saved = dao.currentRevisionsForWriterAdmission(
			ActivityCapturedFactProjectionLane.WRITER_ID,
			ActivityCapturedFactProjectionLane.WRITER_VERSION, 1L, 2).single()
		dao.evidence(saved.writerProjectionId, saved.writerProjectionVersion,
			saved.logicalWindowId, saved.semanticRevision).map { it.sourceEventId }.toSet() shouldBe
			setOf(EVENT_ID.value, "activity-event-2")
		database.sourceProjectionStateDao().activeProductLane(ACTIVITY_SOURCE)
			?.contiguousAdmissionOrdinal shouldBe 2L
		projection.drainAvailable() shouldBe ActivityCapturedFactDrainResult.Complete(2L, 0, 0)
		dao.revisionCount() shouldBe 1L
	}

	@Test
	fun `actual Activity lane persists original WAL after constructed later quality and End operations`() = runTest {
		installFixture(bindingGeneration = 2L, sessionMode = "AUTOMATIC")
		val before = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		val originalWal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		database.advanceRetainedSourceOperations(listOf(LOGICAL_TRACKING_ID to SERVICE_RUN_ID))
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = ACTIVITY_SOURCE,
				bindingGeneration = 2L,
				projectionId = ActivityCapturedFactProjectionLane.WRITER_ID,
				projectionVersion = ActivityCapturedFactProjectionLane.WRITER_VERSION,
				captureModeMask = 3,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 1L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				captureAdmissionCutoffOrdinal = null,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
		// The Android/provider and lifecycle transitions above are constructed. The complete
		// retained Room WAL -> adapter -> reducer -> writer -> product cursor below is real.
		val result = ActivityCapturedFactProjectionLane(database, subject()).drainAvailable()
			as ActivityCapturedFactDrainResult.Complete
		result.lastCompletedOrdinal shouldBe 2L
		result.windowsApplied shouldBe 1
		result.eventsValidated shouldBe 2
		database.sourceProjectionStateDao().activeProductLane(ACTIVITY_SOURCE)
			?.contiguousAdmissionOrdinal shouldBe 2L
		val dao = database.activityCapturedFactDao()
		val saved = dao.currentRevisionsForWriterAdmission(
			ActivityCapturedFactProjectionLane.WRITER_ID,
			ActivityCapturedFactProjectionLane.WRITER_VERSION,
			originalWal.admissionOrdinal,
			2,
		).single()
		saved.clockDomainId shouldBe BOOT_ID
		saved.lifecycleLeaseGeneration shouldBe LEASE_GENERATION
		saved.writerBindingGeneration shouldBe 2L
		saved.writerOwnerGeneration shouldBe 4L
		saved.sessionRunEffectEndNanos shouldBe SESSION_END_NANOS
		dao.evidence(saved.writerProjectionId, saved.writerProjectionVersion,
			saved.logicalWindowId, saved.semanticRevision).map { it.sourceEventId }.toSet() shouldBe
			setOf(EVENT_ID.value, "activity-event-2")
		dao.cursor(saved.writerProjectionId, saved.writerProjectionVersion, saved.logicalWindowId)
			?.latestEffectChecksum shouldBe saved.effectChecksum
		assertSameAdmissionValues(subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted, before)
		val retainedWal = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		retainedWal.copy(payload = originalWal.payload) shouldBe originalWal
		retainedWal.payload.contentEquals(originalWal.payload) shouldBe true
	}

	@Test
	fun `final manual Activity binding admits original delivery without changing its evidence`() = runTest {
		installFixture(bindingGeneration = 2L)
		val saved = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val result = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		result.observation.reference.sourceEventId shouldBe EVENT_ID
		result.observation.authority.sessionManifestRevision shouldBe MANIFEST_REVISION
		val after = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		after.payload.contentEquals(saved.payload) shouldBe true
		after.copy(payload = saved.payload) shouldBe saved
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `final automatic Activity binding admits original transition delivery`() = runTest {
		installFixture(bindingGeneration = 2L, sessionMode = "AUTOMATIC")
		val result = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		(result.observation as ActivityCapturedObservation.Transition).activity shouldBe CapturedActivityType.WALKING
		result.acquisitionAuthority.captureAuthority.logicalTrackingId.value shouldBe LOGICAL_TRACKING_ID
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `final automatic Activity binding admits its actual sampled plan`() = runTest {
		installFixture(bindingGeneration = 2L, sessionMode = "AUTOMATIC", sampled = true)
		val result = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		(result.observation as ActivityCapturedObservation.SampledClassification).confidencePercent shouldBe 90
		result.acquisitionAuthority.historicalConfiguration.activityPlan shouldBe SAMPLED_ACTIVITY_PLAN
	}

	@Test
	fun `original manual-only Activity binding cannot claim automatic capture`() = runTest {
		installFixture(sessionMode = "AUTOMATIC")
		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.MANIFEST_MISMATCH)
		subject().admitHistorical(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.MANIFEST_MISMATCH)
	}

	@Test
	fun `final Activity manifest cannot borrow the original current owner`() = runTest {
		installFixture(bindingGeneration = 2L, ownerGeneration = 2L)
		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DESTINATION_OWNER_MISMATCH)
	}

	@Test
	fun `original Activity manifest remains historical after final owner replaces it`() = runTest {
		installFixture(ownerGeneration = 4L)
		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DESTINATION_OWNER_MISMATCH)
		val historical = subject().admitHistorical(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		historical.observation.reference.sourceEventId shouldBe EVENT_ID
	}

	@Test
	fun `unknown Activity binding cannot become current or historical capture`() = runTest {
		installFixture(bindingGeneration = 3L)
		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DESTINATION_OWNER_MISMATCH)
		subject().admitHistorical(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.MANIFEST_MISMATCH)
	}

	@Test
	fun `Activity UTC rollback does not invalidate its original elapsed Stop and saved observations`() = runTest {
		installFixture()
		val original = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		val dao = database.sourceSessionDao()
		val session = requireNotNull(dao.session(LOGICAL_TRACKING_ID))
		val run = requireNotNull(dao.serviceRun(SERVICE_RUN_ID))
		dao.updateSession(session.copy(cutoffAtMs = 500L, completedAtMs = 500L)) shouldBe 1
		dao.updateServiceRun(run.copy(completedAtMs = 500L)) shouldBe 1
		database.advanceRetainedSourceOperations(listOf(LOGICAL_TRACKING_ID to SERVICE_RUN_ID))
		val after = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		assertSameAdmissionValues(after, original)
		val saved = requireNotNull(dao.session(LOGICAL_TRACKING_ID))
		saved.cutoffAtMs shouldBe 500L
		saved.completedAtMs shouldBe 500L
		val savedRun = requireNotNull(dao.serviceRun(SERVICE_RUN_ID))
		savedRun.completedAtMs shouldBe 500L
	}

	@Test
	fun `Activity still rejects negative lifecycle UTC metadata`() = runTest {
		installFixture()
		val dao = database.sourceSessionDao()
		val run = requireNotNull(dao.serviceRun(SERVICE_RUN_ID))
		dao.updateServiceRun(run.copy(completedAtMs = -1L)) shouldBe 1
		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH)
	}

	@Test
	fun `retained Activity admission keeps its original finite window after later operation state`() = runTest {
		installFixture()
		val before = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
		database.advanceRetainedSourceOperations(listOf(LOGICAL_TRACKING_ID to SERVICE_RUN_ID))
		val after = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		assertSameAdmissionValues(after, before)
		assertSameAdmissionValues(
			subject().admitHistorical(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted,
			before,
		)
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `retained Activity does not borrow a later operation boot for an unknown original Stop`() = runTest {
		installFixture()
		val dao = database.sourceSessionDao()
		val run = requireNotNull(dao.serviceRun(SERVICE_RUN_ID))
		val session = requireNotNull(dao.session(LOGICAL_TRACKING_ID))
		dao.updateServiceRun(run.copy(leaseGeneration = LEASE_GENERATION + 2L, bootId = "later-activity-operation",
			runRevision = run.runRevision + 1L))
		dao.updateSession(session.copy(lifecycleLeaseGeneration = LEASE_GENERATION + 2L,
			lifecycleBootId = "later-activity-operation", lifecycleRevision = session.lifecycleRevision + 1L))
		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Unavailable(
			ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW)
	}

	@Test
	fun `retained Activity still excludes the exact original Stop boundary after later operations`() = runTest {
		installFixture()
		val dao = database.sourceSessionDao()
		val session = requireNotNull(dao.session(LOGICAL_TRACKING_ID))
		val observed = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value)).observedElapsedNanos
		dao.updateSession(session.copy(cutoffElapsedNanos = observed))
		database.advanceRetainedSourceOperations(listOf(LOGICAL_TRACKING_ID to SERVICE_RUN_ID))
		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH)
	}

	@Test
	fun `first zero-based Activity WAL delivery admits exact transition`() = runTest {
		installFixture()

		val result = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted

		result.deliveryUnitIndex shouldBe 0
		result.deliveryUnitCount shouldBe 2
		result.settledWindow.interval shouldBe ActivityProviderTimeInterval(
			AUTHORIZATION_START_NANOS,
			REGISTRATION_END_NANOS,
		)
		(result.observation as ActivityCapturedObservation.Transition).let { observation ->
			observation.change shouldBe ActivityTransitionChange.ENTER
			observation.activity shouldBe CapturedActivityType.WALKING
			observation.reference.sourceEventId shouldBe EVENT_ID
			observation.reference.sourceSequence shouldBe 0L
		}
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `canonical sampled unit derives finite coverage only from its bound historical plan`() = runTest {
		installFixture(sampled = true)

		val result = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		(result.observation as ActivityCapturedObservation.SampledClassification).let { observation ->
			observation.activity shouldBe CapturedActivityType.RUNNING
			observation.confidencePercent shouldBe 90
			observation.coverageEndExclusiveElapsedRealtimeNanos shouldBe REGISTRATION_END_NANOS
		}
	}

	@Test
	fun `control-only retained delivery cannot enter captured admission`() = runTest {
		installFixture(controlOnly = true)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.CONTROL_ONLY,
		)
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `missing immutable registration plan is typed unavailable`() = runTest {
		installFixture(insertRegistrationPlan = false)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Unavailable(
			ActivityCapturedWalAdmissionUnavailable.MISSING_IMMUTABLE_REGISTRATION_PLAN,
		)
	}

	@Test
	fun `open provider and session cannot select a stable materialization window`() = runTest {
		installFixture(settled = false)

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe false
	}

	@Test
	fun `exactly settled STOPPING session admits before final lifecycle commit`() = runTest {
		installFixture()
		installSettledStoppingWindow(sessionState = "STOPPING", desiredState = "FINALIZED")

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe true
	}

	@Test
	fun `provider-unobservable Activity remains partial but materializes its exact admitted prefix`() =
		runTest {
			installFixture()
			installSettledStoppingWindow(sessionState = "STOPPING", desiredState = "FINALIZED")
			val current = database.sourceSessionDao().completenessForServiceRun(
				LOGICAL_TRACKING_ID,
				SERVICE_RUN_ID,
			).single()
			database.sourceSessionDao().saveCompleteness(
				current.copy(
					stopStatus = "PARTIAL_UNOBSERVABLE",
					providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				),
			)

			(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe true
		}

	@Test
	fun `exactly settled suspended run admits while logical session remains active`() = runTest {
		installFixture()
		installSettledStoppingWindow(sessionState = "ACTIVE", desiredState = "ACTIVE")

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe true
	}

	@Test
	fun `STOPPING without exact completeness remains unavailable`() = runTest {
		installFixture()
		installSettledStoppingWindow(sessionState = "STOPPING", desiredState = "FINALIZED")
		database.sourceSessionDao().deleteAllCompleteness()

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe false
	}

	@Test
	fun `STOPPING completeness with nullable admitted high-water remains unavailable`() = runTest {
		installFixture()
		installSettledStoppingWindow(sessionState = "STOPPING", desiredState = "FINALIZED")
		val current = database.sourceSessionDao().completenessForServiceRun(
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
		).single()
		database.sourceSessionDao().saveCompleteness(current.copy(lastAdmissionOrdinal = null))

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe false
	}

	@Test
	fun `cleared suspended settlement cannot revive Activity admission`() = runTest {
		installFixture()
		installSettledStoppingWindow(sessionState = "ACTIVE", desiredState = "ACTIVE")
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		val run = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
		database.sourceSessionDao().updateSession(
			session.copy(
				currentServiceRunId = null,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				finalAdmissionOrdinal = null,
			),
		) shouldBe 1
		database.sourceSessionDao().updateServiceRun(
			run.copy(
				state = "FINALIZED",
				completedAtMs = 2_000L,
				completionReason = "ANDROID_RESTART",
			),
		) shouldBe 1

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe false
	}

	@Test
	fun `reachable reconfiguration and stopping pairs remain typed unsettled`() = runTest {
		val reachablePairs = listOf(
			"RECONFIGURING" to "STARTING",
			"RECONFIGURING" to "ACTIVE",
			"ACTIVE" to "STOPPING",
		)
		for ((sessionState, runState) in reachablePairs) {
			database.clearAllTables()
			installFixture(settled = false)
			val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
			val run = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
			database.sourceSessionDao().updateSession(session.copy(state = sessionState)) shouldBe 1
			database.sourceSessionDao().updateServiceRun(run.copy(state = runState)) shouldBe 1

			subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Unavailable(
				ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW,
			)
		}
	}

	@Test
	fun `terminal logical session can authenticate an older completed replacement run`() = runTest {
		installFixture()
		val completed = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
		database.sourceSessionDao().insertServiceRun(
			completed.copy(
				serviceRunId = "activity-run-replacement",
				startedAtMs = 1_900L,
				startedElapsedNanos = 850L,
				completedAtMs = 1_950L,
				startDeliveryToken = "activity-replacement-token",
				sessionSegmentId = null,
			),
		)

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe true
	}

	@Test
	fun `oversized selected payload is rejected by payload preflight`() = runTest {
		installFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ? WHERE event_id = ?",
			arrayOf<Any>(ByteArray(22), EVENT_ID.value),
		)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELIVERY_TOO_LARGE,
		)
	}

	@Test
	fun `oversized delivery sibling is rejected before full delivery loading`() = runTest {
		installFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ? WHERE event_id = 'activity-event-2'",
			arrayOf<Any>(ByteArray(22)),
		)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELIVERY_TOO_LARGE,
		)
	}

	@Test
	fun `terminal session retaining an active run pointer fails closed`() = runTest {
		installFixture()
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		database.sourceSessionDao().updateSession(
			session.copy(currentServiceRunId = SERVICE_RUN_ID),
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH,
		)
	}

	@Test
	fun `terminal session with nonterminal physical run fails closed`() = runTest {
		installFixture()
		val run = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
		database.sourceSessionDao().updateServiceRun(
			run.copy(
				state = "ACTIVE",
				completedAtMs = null,
				completionReason = null,
			),
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH,
		)
	}

	@Test
	fun `terminal final admission ordinal must cover every delivery member`() = runTest {
		installFixture()
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		database.sourceSessionDao().updateSession(
			session.copy(finalAdmissionOrdinal = 1L),
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH,
		)
	}

	@Test
	fun `missing admitted sibling cannot authenticate declared Activity delivery`() = runTest {
		installFixture()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE delivery_unit_index = 1",
		)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY,
		)
	}

	@Test
	fun `sibling with different capture authority cannot authenticate the delivery`() = runTest {
		installFixture()
		val sibling = requireNotNull(database.sourceEventWalDao().getByEventId("activity-event-2"))
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = 'activity-event-2'",
		)
		val changed = sibling.copy(sourcePolicyRevision = POLICY_REVISION + 1L)
		database.sourceEventWalDao().insertDeliveryUnits(
			listOf(changed.copy(integrityIdentity = changed.calculatedIntegrityIdentity())),
		)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY,
		)
	}

	@Test
	fun `retention floor rejects provider wall uncertainty that can precede it`() = runTest {
		installFixture()
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 0L,
			retainedFromMs = FIRST_EVENT_WALL_MS,
			updatedAtMs = 3_000L,
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.RETAINED_EVIDENCE,
		)
	}

	@Test
	fun `deleted event highwater rejects every retained delivery unit atomically`() = runTest {
		installFixture()
		database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = 0L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 1L,
			updatedAtMs = 3_000L,
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE,
		)
	}

	private fun assertSameAdmissionValues(
		actual: ActivityCapturedWalAdmissionResult.Admitted,
		expected: ActivityCapturedWalAdmissionResult.Admitted,
	) {
		actual.observation shouldBe expected.observation
		actual.settledWindow shouldBe expected.settledWindow
		actual.deliveryIdentity shouldBe expected.deliveryIdentity
		actual.deliveryUnitIndex shouldBe expected.deliveryUnitIndex
		actual.deliveryUnitCount shouldBe expected.deliveryUnitCount
		actual.acquisitionAuthority.captureAuthority shouldBe expected.acquisitionAuthority.captureAuthority
		val actualConfiguration = actual.acquisitionAuthority.historicalConfiguration
		val expectedConfiguration = expected.acquisitionAuthority.historicalConfiguration
		actualConfiguration.identity shouldBe expectedConfiguration.identity
		actualConfiguration.providerAcceptance shouldBe expectedConfiguration.providerAcceptance
		actualConfiguration.authorizationEffect shouldBe expectedConfiguration.authorizationEffect
		actualConfiguration.sessionRunEffect shouldBe expectedConfiguration.sessionRunEffect
		actualConfiguration.desiredPlanPayloadVersion shouldBe expectedConfiguration.desiredPlanPayloadVersion
		actualConfiguration.desiredPlanPayloadChecksum shouldBe expectedConfiguration.desiredPlanPayloadChecksum
		actualConfiguration.activityPlan shouldBe expectedConfiguration.activityPlan
		actualConfiguration.maximumObservationAgeNanos shouldBe expectedConfiguration.maximumObservationAgeNanos
		actualConfiguration.sampledClassificationPolicy shouldBe expectedConfiguration.sampledClassificationPolicy
	}

	private fun subject() = ActivityCapturedWalAdmissionAdapter(
		database,
		planCodec,
		BootClockDomainProvider { BOOT_ID },
	)

	@Suppress("LongMethod")
	private suspend fun installFixture(
		controlOnly: Boolean = false,
		insertRegistrationPlan: Boolean = true,
		settled: Boolean = true,
		sampled: Boolean = false,
		bindingGeneration: Long = 1L,
		ownerGeneration: Long = SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration),
		sessionMode: String = "MANUAL",
		adaptive: Boolean = false,
		settingsBootId: String = BOOT_ID,
		settingsElapsedNanos: Long = RUN_START_NANOS,
	) {
		val activityPlan = if (sampled) {
			SAMPLED_ACTIVITY_PLAN
		} else {
			ACTIVITY_PLAN
		}
		val originalPlan = activityPlan.copy(adaptiveProfileVersion = 1.takeIf { adaptive })
		val physicalFingerprint = originalPlan.physicalConfigurationFingerprint()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L))
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
				owner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
				ownerGeneration = ownerGeneration,
				updatedAtMs = 1_000L,
			),
		)
		installPolicy(controlOnly, settingsBootId, settingsElapsedNanos)
		val encodedPlan = planCodec.encode(originalPlan)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = PLAN_REVISION,
				planId = "activity-plan-1",
				createdAtMs = 900L,
				status = "EFFECTIVE",
				sourcePolicyRevision = POLICY_REVISION,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(
				SourceDesiredPlanEntity(
					revision = PLAN_REVISION,
					sourceKind = ACTIVITY_SOURCE,
					payloadVersion = encodedPlan.formatVersion,
					payload = encodedPlan.bytes,
					payloadChecksum = encodedPlan.checksum,
				),
			),
		)
		if (insertRegistrationPlan) {
			database.activityCapturedFactDao().insertRegistrationPlanBinding(
				ActivityCapturedRegistrationPlanEntity.create(
					sourceInstanceId = SOURCE_INSTANCE_ID,
					registrationGeneration = REGISTRATION_GENERATION,
					configurationRevision = PLAN_REVISION,
					desiredPlanPayloadVersion = encodedPlan.formatVersion,
					desiredPlanPayload = encodedPlan.bytes,
					desiredPlanPayloadChecksum = encodedPlan.checksum,
					physicalConfigurationFingerprint = physicalFingerprint,
					appliedAtElapsedRealtimeNanos = REGISTRATION_START_NANOS,
					applyStatus = "APPLIED",
				),
			)
		}

		val segmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = 1_000L,
				endTimeMs = 2_000L,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = null,
				createdAt = 2_000L,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
			),
		)
		installSession(segmentId, settled, if (sampled) {
			1L
		} else {
			2L
		}, sessionMode)
		installManifest(bindingGeneration, sessionMode)

		val demand = if (controlOnly) {
			controlDemand()
		} else {
			captureDemand()
		}
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(registration(settled, physicalFingerprint))
		val authorizationRows = SourceBrokerAuthorization.rows(
			sourceKind = ACTIVITY_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = listOf(demand),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = 1_100L,
		)
		database.sourceBrokerDao().insertAuthorizations(authorizationRows)

		val identity = ActivityRegistrationIdentity(
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = REGISTRATION_GENERATION,
			collectedDataEpoch = 0L,
			clockDomainId = BOOT_ID,
			physicalConfigurationFingerprint = physicalFingerprint,
		)
		val delivery = ActivitySourceDeliveryFactory().create(
			batch = ActivityRecognitionEvidenceBatch(
				receivedElapsedRealtimeNanos = RECEIVED_NANOS,
				receivedWallTimeMs = RECEIVED_WALL_MS,
				registrationIdentity = identity,
				recognitions = if (sampled) {
					listOf(
					ActivityRecognitionEvidence(
						DetectedActivityType.RUNNING,
						90,
						FIRST_EVENT_NANOS,
					),
				)
				} else {
					emptyList()
				},
				transitions = if (sampled) {
					emptyList()
				} else {
					listOf(
					ActivityTransitionEvidence(
						DetectedActivityType.WALKING,
						ActivityTransitionType.ENTER,
						FIRST_EVENT_NANOS,
					),
					ActivityTransitionEvidence(
						DetectedActivityType.WALKING,
						ActivityTransitionType.EXIT,
						SECOND_EVENT_NANOS,
					),
				)
				},
			),
			identity = identity,
			automationAuthority = ActivityAutomationEpochEntity(),
		)
		val purposeMask = authorizationRows.first().purposeEligibilityMask
		val authorizationFingerprint = authorizationRows.first().authorizationFingerprint
		val rows = delivery.candidate.units.mapIndexed { index, unit ->
			val encoded = payloadCodec.encode(unit.evidence.payload, unit.evidence.payloadVersion)
			val unsigned = SourceEventWalEntity(
				admissionOrdinal = index + 1L,
				eventId = if (index == 0) {
					EVENT_ID.value
				} else {
					"activity-event-${index + 1}"
				},
				providerDedupKey = null,
				deliveryIdentity = delivery.candidate.identity.value,
				deliveryUnitIndex = index,
				deliveryUnitCount = delivery.candidate.units.size,
				logicalTrackingId = LOGICAL_TRACKING_ID.takeUnless { controlOnly },
				serviceRunId = SERVICE_RUN_ID.takeUnless { controlOnly },
				sourceKind = ACTIVITY_SOURCE,
				sourceInstanceId = SOURCE_INSTANCE_ID,
				registrationGeneration = REGISTRATION_GENERATION,
				physicalConfigurationFingerprint = physicalFingerprint,
				authorizationRevision = AUTHORIZATION_REVISION,
				authorizationPurposeEligibilityMask = purposeMask,
				authorizationFingerprint = authorizationFingerprint,
				sourceSequence = index.toLong(),
				configRevision = PLAN_REVISION,
				planAttribution = if (controlOnly) {
					PlanAttribution.RECEIVE_TIME_ONLY.ordinal
				} else {
					PlanAttribution.CAPTURED_REGISTRATION.ordinal
				},
				clockDomainId = BOOT_ID,
				observedElapsedNanos = unit.evidence.observedElapsedRealtimeNanos,
				observedIntervalStartNanos = unit.observedIntervalStartElapsedRealtimeNanos,
				receivedElapsedNanos = unit.evidence.receivedElapsedRealtimeNanos,
				receivedWallTimeMs = 2_001L,
				wallTimeMs = unit.evidence.wallTimeMs,
				wallTimeUncertaintyMs = unit.evidence.wallTimeUncertaintyMs,
				capturedCollectedDataEpoch = 0L,
				activityAutomationEpoch = null,
				sourcePolicyRevision = POLICY_REVISION.takeUnless { controlOnly },
				captureConsentEpoch = CONSENT_EPOCH.takeUnless { controlOnly },
				sessionManifestRevision = MANIFEST_REVISION.takeUnless { controlOnly },
				lifecycleLeaseGeneration = LEASE_GENERATION.takeUnless { controlOnly },
				acquiredAtMs = unit.evidence.acquiredAtMs,
				qualityFlags = unit.evidence.quality.toStableFlags(),
				qualityConfidence = unit.evidence.quality.confidence,
				payloadVersion = unit.evidence.payloadVersion,
				payload = encoded.bytes,
				payloadChecksum = encoded.checksum,
				createdAtMs = 2_000L,
			)
			unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		}
		database.sourceEventWalDao().insertDeliveryUnits(rows)
	}

	private suspend fun installPolicy(controlOnly: Boolean, settingsBootId: String, settingsElapsedNanos: Long) {
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = 1_000L,
			),
		)
		database.sourcePolicyDao().insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = POLICY_REVISION,
					sourceKind = ACTIVITY_SOURCE,
					enabled = true,
					qosCode = QOS_CODE,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = !controlOnly,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					captureConsentEpoch = CONSENT_EPOCH.takeUnless { controlOnly },
					controlConsentEpoch = if (controlOnly) {
						CONSENT_EPOCH
					} else {
						null
					},
					ambientConsentEpoch = null,
					effectiveBootId = settingsBootId,
					effectiveElapsedRealtimeNanos = settingsElapsedNanos,
					effectiveWallTimeMs = 1_000L,
					changeReason = "TEST",
				),
			),
		)
		if (!controlOnly) {
			database.sourcePolicyDao().insertConsentEpochs(
				listOf(
					SourceConsentEpochEntity(
						sourceKind = ACTIVITY_SOURCE,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						epoch = CONSENT_EPOCH,
						eligible = true,
						persistenceEligible = true,
						policyRevision = POLICY_REVISION,
						effectiveBootId = settingsBootId,
						effectiveElapsedRealtimeNanos = settingsElapsedNanos,
						effectiveWallTimeMs = 1_000L,
						changeReason = "TEST",
					),
				),
			)
		}
	}

	// Keep the fixed manual/automatic and active/settled session rows with their matching completeness.
	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun installSession(
		segmentId: Long,
		settled: Boolean,
		finalAdmissionOrdinal: Long,
		sessionMode: String,
	) {
		val automatic = sessionMode == "AUTOMATIC"
		val origin = if (automatic) {
			"AUTOMATIC_BACKGROUND_START"
		} else {
			"MANUAL_FOREGROUND_START"
		}
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = if (settled) {
					"FINALIZED"
				} else {
					"ACTIVE"
				},
				lifecycleRevision = if (settled) {
					2L
				} else {
					1L
				},
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				startOrigin = origin,
				clockDomainId = BOOT_ID,
				startedAtMs = 1_000L,
				startedElapsedNanos = RUN_START_NANOS,
				cutoffAtMs = if (settled) {
					2_000L
				} else {
					null
				},
				cutoffElapsedNanos = if (settled) {
					SESSION_END_NANOS
				} else {
					null
				},
				completedAtMs = if (settled) {
					2_000L
				} else {
					null
				},
				finalAdmissionOrdinal = finalAdmissionOrdinal.takeIf { settled },
				failureCode = null,
				sessionMode = sessionMode,
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = 1L,
				currentServiceRunId = if (settled) {
					null
				} else {
					SERVICE_RUN_ID
				},
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = 1L.takeIf { automatic },
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = SERVICE_RUN_ID,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = if (settled) {
					"FINALIZED"
				} else {
					"ACTIVE"
				},
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1_000L,
				startedElapsedNanos = RUN_START_NANOS,
				completedAtMs = if (settled) {
					2_000L
				} else {
					null
				},
				completionReason = if (settled) {
					"USER_STOP"
				} else {
					null
				},
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = origin,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = if (settled) {
					"STOPPED"
				} else {
					"RUNNING"
				},
				runtimeFailureCode = null,
				runRevision = if (settled) {
					2L
				} else {
					1L
				},
				startDeliveryToken = "activity-start-token",
				startCommandGeneration = 1L,
				preparedManifestRevision = MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = 1_000L,
				startIsUserInitiated = !automatic,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null,
			),
		)
		if (settled) {
			database.sourceSessionDao().saveCompleteness(
				SourceSessionCompletenessEntity(
					logicalTrackingId = LOGICAL_TRACKING_ID,
					serviceRunId = SERVICE_RUN_ID,
					sourceKind = ACTIVITY_SOURCE,
					sourceInstanceId = SOURCE_INSTANCE_ID,
					registrationGeneration = REGISTRATION_GENERATION,
					lastAdmissionOrdinal = finalAdmissionOrdinal,
				lastSourceSequence = finalAdmissionOrdinal - 1L,
					appDrainComplete = true,
					providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
					stopStatus = "COMPLETE",
					unresolvedSequenceStart = null,
					unresolvedSequenceEnd = null,
					updatedAtMs = 2_000L,
				),
			)
		}
	}

	private suspend fun installSettledStoppingWindow(
		sessionState: String,
		desiredState: String,
	) {
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		val run = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
		val intentRevision = 2L
		database.sourceSessionDao().insertLifecycleIntent(
			SessionLifecycleIntentVersionEntity(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				intentRevision = intentRevision,
				manifestRevision = MANIFEST_REVISION,
				desiredState = desiredState,
				startOrigin = if (desiredState == "ACTIVE") {
					"RECOVERY"
				} else {
					"POLICY_RECONCILIATION"
				},
				requestBootId = BOOT_ID,
				requestedElapsedRealtimeNanos = SESSION_END_NANOS,
				requestedWallTimeMs = 2_000L,
				automationEpoch = null,
				triggerId = null,
				triggerKind = null,
				triggerBootId = null,
				triggerObservedElapsedRealtimeNanos = null,
				triggerReceivedElapsedRealtimeNanos = null,
				triggerExpiresElapsedRealtimeNanos = null,
				stopReason = "TEST_SETTLED_STOPPING",
				stopDeadlineBootId = if (desiredState == "FINALIZED") {
					BOOT_ID
				} else {
					null
				},
				stopDeadlineElapsedRealtimeNanos =
					if (desiredState == "FINALIZED") {
						SESSION_END_NANOS + 1_000L
					} else {
						null
					},
				intentChecksum = "settled-stopping-intent",
			),
		)
		database.sourceSessionDao().updateSession(
			session.copy(
				state = sessionState,
				completedAtMs = null,
				currentIntentRevision = intentRevision,
				currentServiceRunId = SERVICE_RUN_ID,
			),
		) shouldBe 1
		database.sourceSessionDao().updateServiceRun(
			run.copy(
				state = "STOPPING",
				completedAtMs = null,
				completionReason = null,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runRevision = run.runRevision + 1L,
			),
		) shouldBe 1
	}

	private suspend fun installManifest(bindingGeneration: Long, sessionMode: String) {
		val automatic = sessionMode == "AUTOMATIC"
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = ACTIVITY_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			writerOwner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
			writerOwnerGeneration = SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration),
			writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			writerBindingGeneration = bindingGeneration,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = sessionMode,
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = 1L,
			startOrigin = if (automatic) {
				"AUTOMATIC_BACKGROUND_START"
			} else {
				"MANUAL_FOREGROUND_START"
			},
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = RUN_START_NANOS,
			effectiveWallTimeMs = 1_000L,
			zoneId = "Europe/Prague",
			automationEpoch = 1L.takeIf { automatic },
			changeReason = if (automatic) {
				"AUTOMATIC_START"
			} else {
				"MANUAL_START"
			},
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun registration(
		settled: Boolean,
		physicalFingerprint: String,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = ACTIVITY_SOURCE,
		registrationGeneration = REGISTRATION_GENERATION,
		sourceInstanceId = SOURCE_INSTANCE_ID,
		ownerScope = "activity-provider",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = physicalFingerprint,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
		providerProcessIncarnationId = null,
		status = if (settled) {
			ProviderRegistrationGenerationEntity.STATUS_RETIRED
		} else {
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		},
		reservedAtMs = 900L,
		reservedElapsedRealtimeNanos = 90L,
		acceptedAtMs = 1_050L,
		acceptedElapsedRealtimeNanos = REGISTRATION_START_NANOS,
		retiredAtMs = if (settled) {
			1_900L
		} else {
			null
		},
		retiredElapsedRealtimeNanos = if (settled) {
			REGISTRATION_END_NANOS
		} else {
			null
		},
		failureCode = null,
		captureCallbackBarrierAuthorizationRevision = if (settled) {
			AUTHORIZATION_REVISION
		} else {
			0L
		},
	)

	private fun captureDemand() = SourceDemandEntity(
		demandId = "activity-capture-demand",
		consumerId = "session:$SERVICE_RUN_ID",
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		lifecycleLeaseGeneration = LEASE_GENERATION,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = "activity:v1:transitions",
		adaptiveReductionAllowed = false,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 30_000L,
		requestedDeliveryLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = 1_000L,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = SESSION_END_NANOS,
		retiredAtMs = 2_000L,
	)

	private fun controlDemand() = SourceDemandEntity(
		demandId = "activity-control-demand",
		consumerId = "activity-control",
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = false,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = "activity:v1:transitions",
		adaptiveReductionAllowed = false,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 30_000L,
		requestedDeliveryLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = 1_000L,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = SESSION_END_NANOS,
		retiredAtMs = 2_000L,
	)

	private companion object {
		val EVENT_ID = SourceEventId("activity-event-1")
		const val ACTIVITY_SOURCE = 2
		const val LOGICAL_TRACKING_ID = "logical-activity"
		const val SERVICE_RUN_ID = "run-activity"
		const val SOURCE_INSTANCE_ID = "activity-instance"
		const val BOOT_ID = "boot-activity"
		const val PLAN_REVISION = 1L
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 1L
		const val MANIFEST_REVISION = 1L
		const val LEASE_GENERATION = 1L
		const val REGISTRATION_GENERATION = 1L
		const val AUTHORIZATION_REVISION = 1L
		const val QOS_CODE = 2
		const val RUN_START_NANOS = 100L
		const val REGISTRATION_START_NANOS = 120L
		const val AUTHORIZATION_START_NANOS = 150L
		const val FIRST_EVENT_NANOS = 200L
		const val SECOND_EVENT_NANOS = 250L
		const val RECEIVED_NANOS = 300L
		const val REGISTRATION_END_NANOS = 800L
		const val SESSION_END_NANOS = 900L
		const val RECEIVED_WALL_MS = 2_000L
		const val FIRST_EVENT_WALL_MS = 2_000L
		val ACTIVITY_PLAN = ActivityPlan(
			revision = PLAN_REVISION,
			mode = ActivityMode.TRANSITIONS_ONLY,
			desiredDetectionLatencyMs = 30_000L,
			confidenceThresholdPercent = 65,
			transitionTypes = setOf(0, 1),
		)
		val SAMPLED_ACTIVITY_PLAN = ACTIVITY_PLAN.copy(
			mode = ActivityMode.CONTINUOUS_RECOGNITION,
			desiredDetectionLatencyMs = 1L,
		)
	}
}
