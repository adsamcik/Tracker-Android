package com.adsamcik.tracker.tracker.source.deletion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletionUnsupportedReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomActivitySelectedSessionDeletionServiceTest {
	private lateinit var database: AppDatabase
	private lateinit var dirtyTracker: MetricDirtyTracker
	private var drainRequests = 0

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dirtyTracker = mockk(relaxed = true)
		drainRequests = 0
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L))
		database.sourcePolicyDao().insertPolicies(listOf(activityPolicy()))
		database.sourcePolicyDao().insertConsentEpochs(listOf(activityConsent()))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `selected replacement entry fences every run preserves authority and permits CONTROL provider`() =
		runTest {
			val segments = insertReplacementSession()
			val unrelatedWindowId = insertCapturedActivityPayload(segments)
			insertActiveControlProvider()
			val service = service()

			service.deleteSelectedSession(segments.last().id) shouldBe
				ActivitySessionDeletionResult.Deleted

			segments.forEach { segment -> database.sessionSegmentDao().getById(segment.id) shouldBe null }
			listOf(FIRST_RUN_ID, SECOND_RUN_ID).forEach { runId ->
				val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
					ACTIVITY_SOURCE,
					SourceBrokerPurpose.SESSION_CAPTURE,
					LOGICAL_ID,
					runId,
				)
				database.sourceDeletionFenceDao().contains(
					ACTIVITY_SOURCE,
					SourceBrokerPurpose.SESSION_CAPTURE,
					SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					digest,
				) shouldBe true
				database.sourceSessionDao().serviceRun(runId)?.serviceRunId shouldBe runId
				database.sourceSessionDao().manifestsForServiceRun(runId, 2).size shouldBe 1
			}
			database.sourceSessionDao().session(LOGICAL_ID)?.logicalTrackingId shouldBe LOGICAL_ID
			database.sourceBrokerDao().activeDemands(ACTIVITY_SOURCE).single().purpose shouldBe
				SourceBrokerPurpose.CONTROL_AUTOSTART
			database.sourceBrokerDao().currentPhysicalRegistration(ACTIVITY_SOURCE)?.status shouldBe
				ProviderRegistrationGenerationEntity.STATUS_ACTIVE
			database.activityCapturedFactDao().maintenanceAuthorizationMembers(
				ACTIVITY_SOURCE,
				CONTROL_REGISTRATION_GENERATION,
				1L,
				2,
			).single().purpose shouldBe SourceBrokerPurpose.CONTROL_AUTOSTART
			database.activityCapturedFactDao().revisionCount() shouldBe 1L
			database.activityCapturedFactDao().fragmentCount() shouldBe 1L
			database.activityCapturedFactDao().evidenceCount() shouldBe 1L
			database.activityCapturedFactDao().cursorCount() shouldBe 1L
			database.activityCapturedFactDao().maintenanceRevisionPage(
				writerProjectionId = WRITER_ID,
				writerProjectionVersion = WRITER_VERSION,
				afterLogicalWindowId = null,
				afterSemanticRevision = null,
				limit = 2,
			).single().logicalWindowId shouldBe unrelatedWindowId
			database.activityCapturedFactDao().registrationPlanBindingCount() shouldBe 1L
			database.sourceSessionDao().serviceRun(UNRELATED_RUN_ID)?.serviceRunId shouldBe
				UNRELATED_RUN_ID
			database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
			drainRequests shouldBe 1
			verify(exactly = 1) {
				dirtyTracker.markDirty(
					setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
				)
			}
			service.deleteSelectedSession(segments.last().id) shouldBe
				ActivitySessionDeletionResult.NotFound
		}

	@Test
	fun `mixed capture authority blocks whole replacement group before any fence`() = runTest {
		val segments = insertReplacementSession(secondCapturedSource = SourceKindCode.STEPS)

		service().deleteSelectedSession(segments.first().id) shouldBe
			ActivitySessionDeletionResult.UnsupportedScope(
				ActivitySessionDeletionUnsupportedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
			)

		segments.forEach { segment -> database.sessionSegmentDao().getById(segment.id)?.id shouldBe segment.id }
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		drainRequests shouldBe 0
	}

	@Test
	fun `extra reverse-owned segment blocks incomplete replacement scope`() = runTest {
		val segments = insertReplacementSession()
		val orphan = insertSegment("orphan-run", 3_100L, 3_200L)

		service().deleteSelectedSession(segments.first().id) shouldBe
			ActivitySessionDeletionResult.UnsupportedScope(
				ActivitySessionDeletionUnsupportedReason.REPLACEMENT_SCOPE_MISMATCH,
			)

		(segments + orphan).forEach { segment ->
			database.sessionSegmentDao().getById(segment.id)?.id shouldBe segment.id
		}
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `nonterminal replacement owner is blocked without relying on presentation QUIESCED`() = runTest {
		val segments = insertReplacementSession(active = true)

		service().deleteSelectedSession(segments.first().id) shouldBe
			ActivitySessionDeletionResult.BlockedActive

		segments.forEach { segment -> database.sessionSegmentDao().getById(segment.id)?.id shouldBe segment.id }
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `latest selected capture authorization blocks while CONTROL-only authorization does not`() =
		runTest {
			val segments = insertReplacementSession()
			insertActiveSelectedCaptureProvider()

			service().deleteSelectedSession(segments.first().id) shouldBe
				ActivitySessionDeletionResult.BlockedActive

			segments.forEach { segment ->
				database.sessionSegmentDao().getById(segment.id)?.id shouldBe segment.id
			}
			database.sourceDeletionFenceDao().countAll() shouldBe 0L
		}

	@Test
	fun `oversized latest authorization snapshot fails closed before mutation`() = runTest {
		val segments = insertReplacementSession()
		val controls = (1..65).map { index ->
			controlDemand().copy(
				demandId = "control-demand-$index",
				consumerId = "activity-control-$index",
			)
		}
		database.sourceBrokerDao().insertDemands(controls)
		database.sourceBrokerDao().insertRegistration(activeRegistration())
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = ACTIVITY_SOURCE,
				registrationGeneration = CAPTURE_REGISTRATION_GENERATION,
				authorizationRevision = 1L,
				demands = controls,
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 60L,
				effectiveWallTimeMs = 600L,
			),
		)

		service().deleteSelectedSession(segments.first().id) shouldBe
			ActivitySessionDeletionResult.UnsupportedScope(
				ActivitySessionDeletionUnsupportedReason.PROVIDER_AUTHORITY_UNVERIFIABLE,
			)
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		segments.forEach { segment ->
			database.sessionSegmentDao().getById(segment.id)?.id shouldBe segment.id
		}
	}

	@Test
	fun `released unattributed presentation row is typed legacy unverifiable`() = runTest {
		val legacy = SessionSegment(
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			distanceM = 0f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 99,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = null,
			createdAt = 1_000L,
		)
		val id = database.sessionSegmentDao().insert(legacy)

		service().deleteSelectedSession(id) shouldBe ActivitySessionDeletionResult.LegacyUnverifiable
		database.sessionSegmentDao().getById(id)?.id shouldBe id
	}

	@Test
	fun `storage rejection is retryable and rolls back fences and evidence generation`() = runTest {
		val segments = insertReplacementSession()
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER fail_activity_segment_delete BEFORE DELETE ON session_segment " +
				"BEGIN SELECT RAISE(ABORT, 'forced Activity deletion failure'); END",
		)

		service().deleteSelectedSession(segments.first().id) shouldBe
			ActivitySessionDeletionResult.RetryableFailure(
				ActivitySessionDeletionRetryableReason.DATABASE_UNAVAILABLE,
			)

		segments.forEach { segment -> database.sessionSegmentDao().getById(segment.id)?.id shouldBe segment.id }
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		drainRequests shouldBe 0
	}

	@Test
	fun `cancellation after Activity payload mutation rolls back fences generation and segments`() =
		runTest {
			val segments = insertReplacementSession()
			val service = service(afterActivityPayloadDeleted = {
				throw CancellationException("test cancellation")
			})

			shouldThrow<CancellationException> {
				service.deleteSelectedSession(segments.first().id)
			}

			segments.forEach { segment -> database.sessionSegmentDao().getById(segment.id)?.id shouldBe segment.id }
			database.sourceDeletionFenceDao().countAll() shouldBe 0L
			database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
			drainRequests shouldBe 0
		}

	private fun service(
		afterActivityPayloadDeleted: suspend () -> Unit = {},
	) = RoomActivitySelectedSessionDeletionService(
		database = database,
		dirtyTracker = dirtyTracker,
		wallTimeMsProvider = { 10_000L },
		requestActivityDrain = { drainRequests += 1 },
		afterActivityPayloadDeleted = afterActivityPayloadDeleted,
	)

	private suspend fun insertReplacementSession(
		secondCapturedSource: Int = ACTIVITY_SOURCE,
		active: Boolean = false,
	): List<SessionSegment> {
		val firstSegment = insertSegment(FIRST_RUN_ID, 1_000L, 2_000L)
		val secondSegment = insertSegment(SECOND_RUN_ID, 2_100L, 3_000L)
		val firstRun = serviceRun(
			runId = FIRST_RUN_ID,
			segmentId = firstSegment.id,
			manifestRevision = 1L,
			startMs = 1_000L,
			startElapsedNanos = 100L,
			completedAtMs = 2_000L,
			startOrigin = "MANUAL_FOREGROUND_START",
		)
		val secondRun = serviceRun(
			runId = SECOND_RUN_ID,
			segmentId = secondSegment.id,
			manifestRevision = 2L,
			startMs = 2_100L,
			startElapsedNanos = 400L,
			completedAtMs = 3_000L,
			startOrigin = "RECOVERY",
			active = active,
		)
		database.sourceSessionDao().insertSession(logicalSession(active))
		database.sourceSessionDao().insertServiceRun(firstRun)
		database.sourceSessionDao().insertServiceRun(secondRun)
		insertManifest(firstRun, 1L, ACTIVITY_SOURCE)
		insertManifest(secondRun, 2L, secondCapturedSource)
		return listOf(firstSegment, secondSegment)
	}

	private suspend fun insertCapturedActivityPayload(selectedSegments: List<SessionSegment>): String {
		require(selectedSegments.size == 2)
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = ACTIVITY_SOURCE,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
				owner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
				ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				updatedAtMs = 500L,
			),
		)
		val planPayload = byteArrayOf(1, 4, 1, 5)
		val planChecksum = sha256(planPayload)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(1L, "activity-delete-plan", 500L, "APPLIED", 1L),
		)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(SourceDesiredPlanEntity(1L, ACTIVITY_SOURCE, 1, planPayload, planChecksum)),
		)
		database.activityCapturedFactDao().insertRegistrationPlanBinding(
			ActivityCapturedRegistrationPlanEntity.create(
				sourceInstanceId = CAPTURE_SOURCE_INSTANCE_ID,
				registrationGeneration = CAPTURE_REGISTRATION_GENERATION,
				configurationRevision = 1L,
				desiredPlanPayloadVersion = 1,
				desiredPlanPayload = planPayload,
				desiredPlanPayloadChecksum = planChecksum,
				physicalConfigurationFingerprint = CAPTURE_PHYSICAL_FINGERPRINT,
				appliedAtElapsedRealtimeNanos = 50L,
				applyStatus = "APPLIED",
			),
		)
		database.sourceBrokerDao().insertRegistration(historicalCaptureRegistration())

		val unrelatedSegment = insertUnrelatedActivitySession()
		val specs = listOf(
			CapturedFactSpec(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = FIRST_RUN_ID,
				sessionSegmentId = selectedSegments.first().id,
				manifestRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				sessionRunEffectStartNanos = 100L,
				windowStartNanos = 200L,
				windowEndNanos = 300L,
				wallStartMs = 1_500L,
				wallEndMs = 1_600L,
				appliedAtMs = 4_000L,
				sourceOrdinal = 1L,
			),
			CapturedFactSpec(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = SECOND_RUN_ID,
				sessionSegmentId = selectedSegments.last().id,
				manifestRevision = 2L,
				lifecycleLeaseGeneration = 2L,
				sessionRunEffectStartNanos = 400L,
				windowStartNanos = 450L,
				windowEndNanos = 550L,
				wallStartMs = 2_500L,
				wallEndMs = 2_600L,
				appliedAtMs = 5_000L,
				sourceOrdinal = 2L,
			),
			CapturedFactSpec(
				logicalTrackingId = UNRELATED_LOGICAL_ID,
				serviceRunId = UNRELATED_RUN_ID,
				sessionSegmentId = unrelatedSegment.id,
				manifestRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				sessionRunEffectStartNanos = 600L,
				windowStartNanos = 620L,
				windowEndNanos = 680L,
				wallStartMs = UNRELATED_START_MS + 20L,
				wallEndMs = UNRELATED_START_MS + 80L,
				appliedAtMs = 6_000L,
				sourceOrdinal = 3L,
			),
		)
		val historicalDemands = specs.map(::historicalCaptureDemand)
		database.sourceBrokerDao().insertDemands(historicalDemands)
		val authorizationRows = SourceBrokerAuthorization.rows(
			sourceKind = ACTIVITY_SOURCE,
			registrationGeneration = CAPTURE_REGISTRATION_GENERATION,
			authorizationRevision = 1L,
			demands = historicalDemands,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = 70L,
			effectiveWallTimeMs = 700L,
		)
		database.sourceBrokerDao().insertAuthorizations(authorizationRows)
		val authorizationFingerprint = authorizationRows.first().authorizationFingerprint
		val purposeMask = authorizationRows.first().purposeEligibilityMask
		return specs.map { spec ->
			insertCapturedFact(spec, authorizationFingerprint, purposeMask)
		}.last()
	}

	private suspend fun insertUnrelatedActivitySession(): SessionSegment {
		val segment = insertSegment(
			runId = UNRELATED_RUN_ID,
			startMs = UNRELATED_START_MS,
			endMs = UNRELATED_END_MS,
			logicalTrackingId = UNRELATED_LOGICAL_ID,
		)
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = UNRELATED_LOGICAL_ID,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = BOOT_ID,
				startedAtMs = UNRELATED_START_MS,
				startedElapsedNanos = 600L,
				cutoffAtMs = UNRELATED_END_MS,
				cutoffElapsedNanos = 700L,
				completedAtMs = UNRELATED_END_MS,
				finalAdmissionOrdinal = 3L,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
		)
		val run = serviceRun(
			runId = UNRELATED_RUN_ID,
			segmentId = segment.id,
			manifestRevision = 1L,
			startMs = UNRELATED_START_MS,
			startElapsedNanos = 600L,
			completedAtMs = UNRELATED_END_MS,
			startOrigin = "MANUAL_FOREGROUND_START",
			logicalTrackingId = UNRELATED_LOGICAL_ID,
		)
		database.sourceSessionDao().insertServiceRun(run)
		insertManifest(run, 1L, ACTIVITY_SOURCE)
		return segment
	}

	private suspend fun insertManifest(
		run: SourceServiceRunEntity,
		revision: Long,
		capturedSource: Int,
	) {
		val source = manifestSource(revision, capturedSource, run.logicalTrackingId)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = run.logicalTrackingId,
			manifestRevision = revision,
			serviceRunId = run.serviceRunId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = run.startOrigin,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = run.startedElapsedNanos,
			effectiveWallTimeMs = run.startedAtMs,
			zoneId = "Europe/Prague",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private suspend fun insertSegment(
		runId: String,
		startMs: Long,
		endMs: Long,
		logicalTrackingId: String = LOGICAL_ID,
	): SessionSegment {
		val row = SessionSegment(
			startTimeMs = startMs,
			endTimeMs = endMs,
			distanceM = 0f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 0,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = null,
			createdAt = startMs,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = runId,
		)
		return row.copy(id = database.sessionSegmentDao().insert(row))
	}

	private fun logicalSession(active: Boolean) = LogicalTrackingSessionEntity(
		logicalTrackingId = LOGICAL_ID,
		state = if (active) "ACTIVE" else "FINALIZED",
		lifecycleRevision = 3L,
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		clockDomainId = BOOT_ID,
		startedAtMs = 1_000L,
		startedElapsedNanos = 100L,
		cutoffAtMs = 3_000L.takeUnless { active },
		cutoffElapsedNanos = 700L.takeUnless { active },
		completedAtMs = 3_000L.takeUnless { active },
		finalAdmissionOrdinal = 2L.takeUnless { active },
		failureCode = null,
		sessionMode = "MANUAL",
		currentManifestRevision = 2L,
		currentIntentRevision = 2L,
		currentServiceRunId = SECOND_RUN_ID.takeIf { active },
		lifecycleLeaseGeneration = 2L,
		lifecycleBootId = BOOT_ID,
		automationEpoch = null,
	)

	private fun serviceRun(
		runId: String,
		segmentId: Long,
		manifestRevision: Long,
		startMs: Long,
		startElapsedNanos: Long,
		completedAtMs: Long,
		startOrigin: String,
		active: Boolean = false,
		logicalTrackingId: String = LOGICAL_ID,
	) = SourceServiceRunEntity(
		serviceRunId = runId,
		logicalTrackingId = logicalTrackingId,
		state = if (active) "ACTIVE" else "FINALIZED",
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = startMs,
		startedElapsedNanos = startElapsedNanos,
		completedAtMs = completedAtMs.takeUnless { active },
		completionReason = "TEST".takeUnless { active },
		bootId = BOOT_ID,
		leaseGeneration = manifestRevision,
		startOrigin = startOrigin,
		desiredForegroundCapabilityFlags = 0L,
		appliedForegroundCapabilityFlags = 0L,
		runtimeAcknowledgement = if (active) "START_ACCEPTED" else "STOPPED",
		runtimeFailureCode = null,
		runRevision = if (active) 1L else 2L,
		startDeliveryToken = "delivery-$runId",
		startCommandGeneration = manifestRevision,
		preparedManifestRevision = manifestRevision,
		preparedIntentRevision = manifestRevision,
		androidDeliveryState = if (active) "DELIVERED" else "SETTLED",
		androidDeliveryUpdatedAtMs = if (active) startMs else completedAtMs,
		startIsUserInitiated = startOrigin == "MANUAL_FOREGROUND_START",
		startIsAmbient = false,
		sessionSegmentId = segmentId,
		presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
		presentationAcknowledgedAtMs = null,
	)

	private fun manifestSource(
		revision: Long,
		sourceKind: Int,
		logicalTrackingId: String = LOGICAL_ID,
	) = SessionManifestSourceEntity(
		logicalTrackingId = logicalTrackingId,
		manifestRevision = revision,
		sourceKind = sourceKind,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		consentEpoch = 0L,
		persistenceEligible = true,
		qosCode = 1,
		outputDestination = if (sourceKind == ACTIVITY_SOURCE) {
			SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY
		} else {
			SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
		},
		writerOwner = if (sourceKind == ACTIVITY_SOURCE) {
			SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS
		} else {
			SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		},
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = if (sourceKind == ACTIVITY_SOURCE) {
			SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID
		} else {
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
		},
		writerProjectionVersion = 1,
		writerBindingGeneration = 1L,
	)

	private fun historicalCaptureRegistration() = ProviderRegistrationGenerationEntity(
		sourceKind = ACTIVITY_SOURCE,
		registrationGeneration = CAPTURE_REGISTRATION_GENERATION,
		sourceInstanceId = CAPTURE_SOURCE_INSTANCE_ID,
		ownerScope = "source-broker:$ACTIVITY_SOURCE",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = CAPTURE_PHYSICAL_FINGERPRINT,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "capture-process",
		status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = 500L,
		reservedElapsedRealtimeNanos = 50L,
		acceptedAtMs = 600L,
		acceptedElapsedRealtimeNanos = PROVIDER_ACCEPTANCE_START_NANOS,
		retiredAtMs = 8_000L,
		retiredElapsedRealtimeNanos = PROVIDER_ACCEPTANCE_END_NANOS,
		failureCode = null,
	)

	private fun historicalCaptureDemand(spec: CapturedFactSpec) = SourceDemandEntity(
		demandId = "historical-${spec.serviceRunId}",
		consumerId = "session:${spec.logicalTrackingId}",
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = spec.logicalTrackingId,
		serviceRunId = spec.serviceRunId,
		manifestRevision = spec.manifestRevision,
		lifecycleLeaseGeneration = spec.lifecycleLeaseGeneration,
		sourcePolicyRevision = 1L,
		consentEpoch = 0L,
		persistenceEligible = true,
		qosCode = 1,
		minimumAcquisitionSpec = "activity-capture-test",
		adaptiveReductionAllowed = true,
		maximumAgeMs = 0L,
		desiredLatencyMs = 0L,
		requestedDeliveryLatencyMs = null,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
		requestedAtMs = 700L,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = PROVIDER_ACCEPTANCE_END_NANOS,
		retiredAtMs = 8_000L,
	)

	private suspend fun insertCapturedFact(
		spec: CapturedFactSpec,
		authorizationFingerprint: String,
		purposeMask: Long,
	): String {
		val logicalWindowId = capturedWindowId(spec, authorizationFingerprint, purposeMask)
		val fragment = ActivityCapturedFragmentEntity(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			logicalWindowId = logicalWindowId,
			semanticRevision = 1L,
			fragmentOrdinal = 0,
			fragmentKind = ActivityCapturedFragmentEntity.KIND_BAND,
			bandOrdinal = 0,
			intervalStartElapsedRealtimeNanos = spec.windowStartNanos,
			intervalEndElapsedRealtimeNanos = spec.windowEndNanos,
			gapReason = null,
			activity = "WALKING",
			mechanism = "TRANSITION",
			refinedTransitionActivity = null,
			confidenceKind = ActivityCapturedFragmentEntity.CONFIDENCE_TRANSITION,
			confidenceMinimumPercent = null,
			confidenceMaximumPercent = null,
			confidenceObservationCount = null,
			startWallTimeMs = spec.wallStartMs,
			startWallTimeUncertaintyMs = 10L,
			startBoundaryKind = "EXACT_PROVIDER_OBSERVATION",
			startAnchorSourceEventId = "event-${spec.serviceRunId}",
			startAnchorProviderElapsedNanos = spec.windowStartNanos,
			endWallTimeMs = spec.wallEndMs,
			endWallTimeUncertaintyMs = 10L,
			endBoundaryKind = "SAME_CLOCK_EXTRAPOLATION",
			endAnchorSourceEventId = "event-${spec.serviceRunId}",
			endAnchorProviderElapsedNanos = spec.windowStartNanos,
			wallTimeContinuity = "SAME_ANCHOR",
		)
		val evidence = ActivityCapturedEvidenceEntity(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			logicalWindowId = logicalWindowId,
			semanticRevision = 1L,
			fragmentOrdinal = 0,
			evidenceOrdinal = 0,
			sourceEventId = "event-${spec.serviceRunId}",
			sourceAdmissionOrdinal = spec.sourceOrdinal,
			sourceSequence = spec.sourceOrdinal,
			providerElapsedRealtimeNanos = spec.windowStartNanos,
			receivedElapsedRealtimeNanos = spec.windowStartNanos + 1L,
			observationKind = ActivityCapturedEvidenceEntity.KIND_TRANSITION,
			observedActivity = "WALKING",
			transitionChange = "ENTER",
			confidencePercent = null,
			coverageEndExclusiveElapsedRealtimeNanos = null,
		)
		val unsigned = ActivityCapturedWindowRevisionEntity(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalWindowId = logicalWindowId,
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			mutationId = digest("activity-captured-mutation-v1", listOf(logicalWindowId, "1")),
			logicalTrackingId = spec.logicalTrackingId,
			serviceRunId = spec.serviceRunId,
			sessionSegmentId = spec.sessionSegmentId,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			sourceInstanceId = CAPTURE_SOURCE_INSTANCE_ID,
			registrationGeneration = CAPTURE_REGISTRATION_GENERATION,
			configurationRevision = 1L,
			physicalConfigurationFingerprint = CAPTURE_PHYSICAL_FINGERPRINT,
			authorizationRevision = 1L,
			authorizationFingerprint = authorizationFingerprint,
			purposeEligibilityMask = purposeMask,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 0L,
			manifestRevision = spec.manifestRevision,
			lifecycleLeaseGeneration = spec.lifecycleLeaseGeneration,
			collectedDataEpoch = 0L,
			clockDomainId = BOOT_ID,
			storedZoneId = "Europe/Prague",
			providerAcceptanceStartNanos = PROVIDER_ACCEPTANCE_START_NANOS,
			providerAcceptanceEndNanos = PROVIDER_ACCEPTANCE_END_NANOS,
			authorizationEffectStartNanos = AUTHORIZATION_START_NANOS,
			authorizationEffectEndNanos = Long.MAX_VALUE,
			sessionRunEffectStartNanos = spec.sessionRunEffectStartNanos,
			sessionRunEffectEndNanos = SESSION_EFFECT_END_NANOS,
			windowStartElapsedRealtimeNanos = spec.windowStartNanos,
			windowEndElapsedRealtimeNanos = spec.windowEndNanos,
			coverage = "COMPLETE",
			knownActiveDurationNanos = spec.windowEndNanos - spec.windowStartNanos,
			knownInactiveDurationNanos = 0L,
			unknownActivityDurationNanos = 0L,
			unobservedDurationNanos = 0L,
			exactDuplicateCount = 0,
			semanticDuplicateCount = 0,
			unchangedEvidenceCount = 0,
			scopeDeletionGeneration = 0L,
			effectChecksum = "pending",
			appliedAtMs = spec.appliedAtMs,
		)
		val revision = unsigned.copy(
			effectChecksum = capturedEffectChecksum(unsigned, fragment, evidence),
		)
		val dao = database.activityCapturedFactDao()
		dao.insertRevision(revision)
		dao.insertFragments(listOf(fragment))
		dao.insertEvidence(listOf(evidence))
		dao.insertCursor(
			ActivityCapturedWindowCursorEntity(
				writerProjectionId = WRITER_ID,
				writerProjectionVersion = WRITER_VERSION,
				logicalWindowId = logicalWindowId,
				logicalTrackingId = spec.logicalTrackingId,
				serviceRunId = spec.serviceRunId,
				sessionSegmentId = spec.sessionSegmentId,
				writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				latestSemanticRevision = 1L,
				latestMutationId = revision.mutationId,
				latestEffectChecksum = revision.effectChecksum,
				cursorRevision = 1L,
				collectedDataEpoch = 0L,
				updatedAtMs = revision.appliedAtMs,
			),
		)
		return logicalWindowId
	}

	private suspend fun insertActiveControlProvider() {
		database.sourceBrokerDao().insertDemands(listOf(controlDemand()))
		database.sourceBrokerDao().insertRegistration(
			activeRegistration(CONTROL_REGISTRATION_GENERATION),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = ACTIVITY_SOURCE,
				registrationGeneration = CONTROL_REGISTRATION_GENERATION,
				authorizationRevision = 1L,
				demands = listOf(controlDemand()),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 60L,
				effectiveWallTimeMs = 600L,
			),
		)
	}

	private suspend fun insertActiveSelectedCaptureProvider() {
		database.sourceBrokerDao().insertRegistration(activeRegistration())
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = ACTIVITY_SOURCE,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					memberId = "selected-capture-member",
					authorizationFingerprint = "selected-capture-fingerprint",
					purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
					demandId = "retired-selected-demand",
					consumerId = "session:$LOGICAL_ID",
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					sourcePolicyRevision = 1L,
					consentEpoch = 0L,
					persistenceEligible = true,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = 60L,
					effectiveWallTimeMs = 600L,
					logicalTrackingId = LOGICAL_ID,
					serviceRunId = SECOND_RUN_ID,
					manifestRevision = 2L,
					lifecycleLeaseGeneration = 2L,
				),
			),
		)
	}

	private fun activeRegistration(
		registrationGeneration: Long = CAPTURE_REGISTRATION_GENERATION,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = ACTIVITY_SOURCE,
		registrationGeneration = registrationGeneration,
		sourceInstanceId = "activity-instance",
		ownerScope = "source-broker:$ACTIVITY_SOURCE",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = "activity-control-plan",
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-1",
		status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		reservedAtMs = 500L,
		reservedElapsedRealtimeNanos = 50L,
		acceptedAtMs = 600L,
		acceptedElapsedRealtimeNanos = 60L,
		retiredAtMs = null,
		retiredElapsedRealtimeNanos = null,
		failureCode = null,
	)

	private fun controlDemand() = SourceDemandEntity(
		demandId = "control-demand",
		consumerId = "activity-control",
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = 1L,
		consentEpoch = 0L,
		persistenceEligible = false,
		qosCode = 1,
		minimumAcquisitionSpec = "activity-control-test",
		adaptiveReductionAllowed = true,
		maximumAgeMs = 10_000L,
		desiredLatencyMs = 10_000L,
		requestedDeliveryLatencyMs = null,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = 50L,
		requestedAtMs = 500L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun activityPolicy() = SourcePolicyEntity(
		policyRevision = 1L,
		sourceKind = ACTIVITY_SOURCE,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = true,
		ambientPersistenceEligible = false,
		captureConsentEpoch = 0L,
		controlConsentEpoch = 0L,
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 100L,
		effectiveWallTimeMs = 1_000L,
		changeReason = "TEST",
	)

	private fun activityConsent() = SourceConsentEpochEntity(
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		epoch = 0L,
		eligible = true,
		persistenceEligible = true,
		policyRevision = 1L,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 100L,
		effectiveWallTimeMs = 1_000L,
		changeReason = "TEST",
	)

	private fun capturedWindowId(
		spec: CapturedFactSpec,
		authorizationFingerprint: String,
		purposeMask: Long,
	): String = digest(
		"activity-captured-window-v1",
		listOf(
			spec.logicalTrackingId,
			spec.serviceRunId,
			CAPTURE_SOURCE_INSTANCE_ID,
			CAPTURE_REGISTRATION_GENERATION.toString(),
			"1",
			CAPTURE_PHYSICAL_FINGERPRINT,
			"1",
			authorizationFingerprint,
			purposeMask.toString(),
			"1",
			"0",
			spec.manifestRevision.toString(),
			spec.lifecycleLeaseGeneration.toString(),
			"0",
			BOOT_ID,
			PROVIDER_ACCEPTANCE_START_NANOS.toString(),
			PROVIDER_ACCEPTANCE_END_NANOS.toString(),
			AUTHORIZATION_START_NANOS.toString(),
			Long.MAX_VALUE.toString(),
			spec.sessionRunEffectStartNanos.toString(),
			SESSION_EFFECT_END_NANOS.toString(),
			spec.windowStartNanos.toString(),
			spec.windowEndNanos.toString(),
		),
	)

	private fun capturedEffectChecksum(
		revision: ActivityCapturedWindowRevisionEntity,
		fragment: ActivityCapturedFragmentEntity,
		evidence: ActivityCapturedEvidenceEntity,
	): String = digest(
		"activity-captured-effect-v1",
		listOf(
			revision.logicalWindowId,
			revision.storedZoneId,
			revision.coverage,
			revision.knownActiveDurationNanos.toString(),
			revision.knownInactiveDurationNanos.toString(),
			revision.unknownActivityDurationNanos.toString(),
			revision.unobservedDurationNanos.toString(),
		) + listOf(
			fragment.fragmentOrdinal,
			fragment.fragmentKind,
			fragment.bandOrdinal,
			fragment.intervalStartElapsedRealtimeNanos,
			fragment.intervalEndElapsedRealtimeNanos,
			fragment.gapReason,
			fragment.activity,
			fragment.mechanism,
			fragment.refinedTransitionActivity,
			fragment.confidenceKind,
			fragment.confidenceMinimumPercent,
			fragment.confidenceMaximumPercent,
			fragment.confidenceObservationCount,
			fragment.startWallTimeMs,
			fragment.startWallTimeUncertaintyMs,
			fragment.startBoundaryKind,
			fragment.startAnchorSourceEventId,
			fragment.startAnchorProviderElapsedNanos,
			fragment.endWallTimeMs,
			fragment.endWallTimeUncertaintyMs,
			fragment.endBoundaryKind,
			fragment.endAnchorSourceEventId,
			fragment.endAnchorProviderElapsedNanos,
			fragment.wallTimeContinuity,
		).map { field -> field?.toString() ?: "null" } + listOf(
			evidence.fragmentOrdinal.toString(),
			evidence.evidenceOrdinal.toString(),
			evidence.sourceEventId,
			evidence.sourceAdmissionOrdinal.toString(),
			evidence.sourceSequence.toString(),
			evidence.providerElapsedRealtimeNanos.toString(),
			evidence.receivedElapsedRealtimeNanos.toString(),
			evidence.observationKind,
			evidence.observedActivity,
			evidence.transitionChange ?: "null",
			evidence.confidencePercent?.toString() ?: "null",
			evidence.coverageEndExclusiveElapsedRealtimeNanos?.toString() ?: "null",
		),
	)

	private fun digest(domain: String, values: List<String>): String {
		val canonical = (listOf(domain) + values).joinToString(separator = "") { value ->
			"${value.length}:$value"
		}
		return sha256(canonical.toByteArray(Charsets.UTF_8))
	}

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }

	private data class CapturedFactSpec(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val sessionSegmentId: Long,
		val manifestRevision: Long,
		val lifecycleLeaseGeneration: Long,
		val sessionRunEffectStartNanos: Long,
		val windowStartNanos: Long,
		val windowEndNanos: Long,
		val wallStartMs: Long,
		val wallEndMs: Long,
		val appliedAtMs: Long,
		val sourceOrdinal: Long,
	)

	private object SourceKindCode {
		const val STEPS = SourceDestinationOwnerEntity.SOURCE_STEPS
	}

	private companion object {
		const val ACTIVITY_SOURCE = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		const val WRITER_ID = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION
		const val LOGICAL_ID = "activity-logical"
		const val FIRST_RUN_ID = "activity-run-1"
		const val SECOND_RUN_ID = "activity-run-2"
		const val UNRELATED_LOGICAL_ID = "unrelated-activity-logical"
		const val UNRELATED_RUN_ID = "unrelated-activity-run"
		const val UNRELATED_START_MS = 300_000_000L
		const val UNRELATED_END_MS = 300_001_000L
		const val CAPTURE_REGISTRATION_GENERATION = 1L
		const val CONTROL_REGISTRATION_GENERATION = 2L
		const val CAPTURE_SOURCE_INSTANCE_ID = "activity-captured-instance"
		const val CAPTURE_PHYSICAL_FINGERPRINT = "activity-captured-plan"
		const val PROVIDER_ACCEPTANCE_START_NANOS = 60L
		const val PROVIDER_ACCEPTANCE_END_NANOS = 800L
		const val AUTHORIZATION_START_NANOS = 70L
		const val SESSION_EFFECT_END_NANOS = 700L
		const val BOOT_ID = "boot-activity"
	}
}
