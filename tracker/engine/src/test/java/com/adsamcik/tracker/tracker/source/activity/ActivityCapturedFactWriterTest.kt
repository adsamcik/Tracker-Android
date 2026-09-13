package com.adsamcik.tracker.tracker.source.activity

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAppliedPlanStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityCapturedFactWriterTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `control-only command is rejected without creating captured state`() = runTest {
		ActivityCapturedFactWriter(database).write(ActivityCapturedWriteCommand.ControlOnly) shouldBe
			ActivityCapturedWriteResult.Rejected(ActivityCapturedWriteRejection.CONTROL_ONLY)

		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `exact candidate authority commits one tiled revision and replay is unchanged`() = runTest {
		seedAuthority()
		val subject = ActivityCapturedFactWriter(database)
		val window = capturedWindow()

		val applied = subject.write(ActivityCapturedWriteCommand.Captured(window)) as
			ActivityCapturedWriteResult.Applied
		applied shouldBe ActivityCapturedWriteResult.Applied(
			logicalWindowId = applied.logicalWindowId,
			semanticRevision = 1L,
			cursorRevision = 1L,
		)
		val logicalWindowId = applied.logicalWindowId
		val dao = database.activityCapturedFactDao()
		dao.revisionCount() shouldBe 1L
		dao.fragments(WRITER_ID, WRITER_VERSION, logicalWindowId, 1L).map { it.fragmentKind } shouldBe
			listOf("BAND", "GAP")
		dao.evidence(WRITER_ID, WRITER_VERSION, logicalWindowId, 1L).single().let { evidence ->
			evidence.sourceEventId shouldBe "activity-event-1"
			evidence.observationKind shouldBe "TRANSITION"
			evidence.observedActivity shouldBe "WALKING"
			evidence.transitionChange shouldBe "ENTER"
			evidence.confidencePercent shouldBe null
			evidence.coverageEndExclusiveElapsedRealtimeNanos shouldBe null
		}
		dao.cursor(WRITER_ID, WRITER_VERSION, logicalWindowId)?.latestSemanticRevision shouldBe 1L
		database.activitySnapshotDao().getAllBetween(0L, Long.MAX_VALUE).size shouldBe 0

		subject.write(ActivityCapturedWriteCommand.Captured(window)) shouldBe
			ActivityCapturedWriteResult.Unchanged(logicalWindowId, 1L, 1L)
		dao.revisionCount() shouldBe 1L
	}

	@Test
	fun `late correction appends exact successor and advances cursor without rewriting revision one`() = runTest {
		seedAuthority()
		val subject = ActivityCapturedFactWriter(database)
		val first = subject.write(ActivityCapturedWriteCommand.Captured(capturedWindow()))
			as ActivityCapturedWriteResult.Applied
		val correction = capturedWindow(semanticRevision = 2L, supersedes = 1L, withGap = false)

		subject.write(ActivityCapturedWriteCommand.Captured(correction)) shouldBe
			ActivityCapturedWriteResult.Applied(first.logicalWindowId, 2L, 2L)
		val dao = database.activityCapturedFactDao()
		dao.revisionCount() shouldBe 2L
		dao.revision(WRITER_ID, WRITER_VERSION, first.logicalWindowId, 1L)?.coverage shouldBe "PARTIAL"
		dao.revision(WRITER_ID, WRITER_VERSION, first.logicalWindowId, 2L)?.coverage shouldBe "COMPLETE"
		dao.cursor(WRITER_ID, WRITER_VERSION, first.logicalWindowId)?.latestSemanticRevision shouldBe 2L
	}

	@Test
	fun `semantic successor with unchanged product effect is not stored`() = runTest {
		seedAuthority()
		val subject = ActivityCapturedFactWriter(database)
		val first = subject.write(ActivityCapturedWriteCommand.Captured(capturedWindow()))
			as ActivityCapturedWriteResult.Applied

		subject.write(
			ActivityCapturedWriteCommand.Captured(
				capturedWindow(semanticRevision = 2L, supersedes = 1L),
			),
		) shouldBe ActivityCapturedWriteResult.Unchanged(first.logicalWindowId, 1L, 1L)
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
	}

	@Test
	fun `permanent selected-run fence rejects replay before any Activity fact is written`() = runTest {
		seedAuthority()
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = 3_000L,
			),
		)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(ActivityCapturedWriteRejection.DELETED_SCOPE)
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `capture consent may be reused by a later policy revision without changing epoch`() = runTest {
		seedAuthority(sourcePolicyRevision = 2L, consentPolicyRevision = 1L)

		val result = ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(
				capturedWindow(authority = captureAuthority(sourcePolicyRevision = 2L)),
			),
		)

		(result is ActivityCapturedWriteResult.Applied) shouldBe true
	}

	@Test
	fun `invalid full service-run manifest timeline is rejected`() = runTest {
		seedAuthority(runDesiredPlanRevision = 2L)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH,
		)
	}

	@Test
	fun `invalid stored manifest zone is rejected before persistence`() = runTest {
		seedAuthority(manifestZoneId = "Not/A_Zone")

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH,
		)
	}

	@Test
	fun `desired Activity payload must produce the captured physical fingerprint`() = runTest {
		seedAuthority(activityPlan = DEFAULT_ACTIVITY_PLAN.copy(desiredDetectionLatencyMs = 60_000L))

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.ACQUISITION_CONFIGURATION_MISMATCH,
		)
	}

	@Test
	fun `desired Activity payload checksum must be canonical`() = runTest {
		seedAuthority(corruptDesiredChecksum = true)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.ACQUISITION_CONFIGURATION_MISMATCH,
		)
	}

	@Test
	fun `historical Activity plan must bind the exact registration generation`() = runTest {
		seedAuthority(historicalRegistrationGeneration = 2L)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.ACQUISITION_CONFIGURATION_MISMATCH,
		)
	}

	@Test
	fun `later mutable applied-plan state does not invalidate delayed historical evidence`() = runTest {
		seedAuthority()
		database.sourcePlanStateDao().saveAppliedState(
			SourceAppliedPlanStateEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				desiredRevision = 2L,
				appliedRevision = 2L,
				sourceInstanceId = "activity-provider-new",
				registrationGeneration = 2L,
				appliedAtElapsedNanos = 500L,
				status = "APPLIED",
				degradedReasons = "",
				updatedAtMs = 3_000L,
			),
		)

		val result = ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		)

		(result is ActivityCapturedWriteResult.Applied) shouldBe true
	}

	@Test
	fun `historical registration plan binding cannot be overwritten by reconfiguration`() = runTest {
		seedAuthority()
		val replacement = ActivityCapturedRegistrationPlanEntity.create(
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = 1L,
			configurationRevision = 2L,
			desiredPlanPayloadVersion = 1,
			desiredPlanPayloadChecksum = "replacement-checksum",
			physicalConfigurationFingerprint = "replacement-fingerprint",
			appliedAtElapsedRealtimeNanos = 500L,
			applyStatus = "APPLIED",
		)

		database.activityCapturedFactDao().insertRegistrationPlanBinding(replacement) shouldBe -1L
		database.activityCapturedFactDao().registrationPlanBinding(
			SOURCE_INSTANCE_ID,
			1L,
		)?.configurationRevision shouldBe 1L
	}

	@Test
	fun `historical Activity plan checksum mismatch is rejected`() = runTest {
		seedAuthority(historicalPlanChecksum = "different-plan-checksum")

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.ACQUISITION_CONFIGURATION_MISMATCH,
		)
	}

	@Test
	fun `replacement-run manifest does not become this run elapsed cutoff`() = runTest {
		seedAuthority()
		insertReplacementRunManifest(effectiveElapsedRealtimeNanos = 250L)

		val result = ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		)

		(result is ActivityCapturedWriteResult.Applied) shouldBe true
	}

	@Test
	fun `captured evidence must resolve to an exact durable WAL admission`() = runTest {
		seedAuthority(insertWalEvidence = false)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `durable WAL receipt time must match captured evidence`() = runTest {
		seedAuthority(walReceivedElapsedRealtimeNanos = 211L)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `durable WAL provider time must match captured evidence`() = runTest {
		seedAuthority(walObservedElapsedRealtimeNanos = 201L)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `durable WAL source sequence must match captured evidence`() = runTest {
		seedAuthority(walSourceSequence = 2L)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `durable WAL payload must be canonical Activity evidence`() = runTest {
		seedAuthority(walPayloadBytesOverride = byteArrayOf(1, 2, 3))

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `durable WAL transition meaning must match captured evidence`() = runTest {
		seedAuthority(
			walPayload = ActivityTransitionPayload(
				activityType = StableActivityTypeCode.RUNNING,
				transitionType = 0,
				providerElapsedRealtimeNanos = 200L,
			),
		)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `durable WAL transition direction must match captured evidence`() = runTest {
		seedAuthority(
			walPayload = ActivityTransitionPayload(
				activityType = StableActivityTypeCode.WALKING,
				transitionType = 1,
				providerElapsedRealtimeNanos = 200L,
			),
		)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `durable WAL payload with trailing bytes is not canonical evidence`() = runTest {
		val payload = ActivityTransitionPayload(
			activityType = StableActivityTypeCode.WALKING,
			transitionType = 0,
			providerElapsedRealtimeNanos = 200L,
		)
		val canonical = DefaultSourcePayloadCodec().encode(payload, 1).bytes
		seedAuthority(walPayload = payload, walPayloadBytesOverride = canonical + byteArrayOf(0))

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `sampled WAL confidence and admitted coverage are persisted per evidence child`() = runTest {
		seedAuthority(
			activityPlan = SAMPLED_ACTIVITY_PLAN,
			physicalFingerprint = SAMPLED_PHYSICAL_FINGERPRINT,
			walPayload = ActivityRecognitionPayload(
				activityType = StableActivityTypeCode.WALKING,
				confidencePercent = 90,
				providerElapsedRealtimeNanos = 200L,
			),
		)
		val result = ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(
				sampledCapturedWindow(
					authority = captureAuthority(
						physicalFingerprint = SAMPLED_PHYSICAL_FINGERPRINT,
					),
				),
			),
		) as ActivityCapturedWriteResult.Applied

		val evidence = database.activityCapturedFactDao().evidence(
			WRITER_ID,
			WRITER_VERSION,
			result.logicalWindowId,
			1L,
		).single()
		evidence.observationKind shouldBe "SAMPLED_CLASSIFICATION"
		evidence.observedActivity shouldBe "WALKING"
		evidence.transitionChange shouldBe null
		evidence.confidencePercent shouldBe 90
		evidence.coverageEndExclusiveElapsedRealtimeNanos shouldBe 300L
	}

	@Test
	fun `sampled WAL confidence mismatch is rejected`() = runTest {
		seedAuthority(
			activityPlan = SAMPLED_ACTIVITY_PLAN,
			physicalFingerprint = SAMPLED_PHYSICAL_FINGERPRINT,
			walPayload = ActivityRecognitionPayload(
				activityType = StableActivityTypeCode.WALKING,
				confidencePercent = 89,
				providerElapsedRealtimeNanos = 200L,
			),
		)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(
				sampledCapturedWindow(
					authority = captureAuthority(
						physicalFingerprint = SAMPLED_PHYSICAL_FINGERPRINT,
					),
				),
			),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `sampled coverage cannot extend beyond exact capture authority`() = runTest {
		seedAuthority(
			activityPlan = SAMPLED_ACTIVITY_PLAN,
			physicalFingerprint = SAMPLED_PHYSICAL_FINGERPRINT,
			walPayload = ActivityRecognitionPayload(
				activityType = StableActivityTypeCode.WALKING,
				confidencePercent = 90,
				providerElapsedRealtimeNanos = 200L,
			),
		)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(
				sampledCapturedWindow(
					authority = captureAuthority(
						physicalFingerprint = SAMPLED_PHYSICAL_FINGERPRINT,
					),
					coverageEndExclusiveElapsedRealtimeNanos = RUN_END_NANOS + 1L,
				),
			),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `sampled evidence below its historical plan threshold is rejected`() = runTest {
		seedAuthority(
			activityPlan = SAMPLED_ACTIVITY_PLAN,
			physicalFingerprint = SAMPLED_PHYSICAL_FINGERPRINT,
			walPayload = ActivityRecognitionPayload(
				activityType = StableActivityTypeCode.WALKING,
				confidencePercent = 60,
				providerElapsedRealtimeNanos = 200L,
			),
		)
		val window = sampledCapturedWindow(
			authority = captureAuthority(physicalFingerprint = SAMPLED_PHYSICAL_FINGERPRINT),
			confidencePercent = 60,
		)

		ActivityCapturedFactWriter(database).write(ActivityCapturedWriteCommand.Captured(window)) shouldBe
			ActivityCapturedWriteResult.Rejected(
				ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
			)
	}

	@Test
	fun `derived wall boundaries must retain their exact durable WAL anchor`() = runTest {
		seedAuthority(walWallTimeMs = 1_999L)

		ActivityCapturedFactWriter(database).write(
			ActivityCapturedWriteCommand.Captured(capturedWindow()),
		) shouldBe ActivityCapturedWriteResult.Rejected(
			ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH,
		)
	}

	@Test
	fun `all-gap correction cannot supersede a retained prior lineage`() = runTest {
		seedAuthority()
		val subject = ActivityCapturedFactWriter(database)
		subject.write(ActivityCapturedWriteCommand.Captured(capturedWindow()))
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 0L,
			retainedFromMs = 2_050L,
			updatedAtMs = 3_000L,
		) shouldBe 1

		subject.write(
			ActivityCapturedWriteCommand.Captured(allGapCorrection()),
		) shouldBe ActivityCapturedWriteResult.Rejected(ActivityCapturedWriteRejection.RETAINED_DATA)
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
	}

	private suspend fun seedAuthority(
		sourcePolicyRevision: Long = 1L,
		consentPolicyRevision: Long = sourcePolicyRevision,
		activityPlan: ActivityPlan = DEFAULT_ACTIVITY_PLAN,
		physicalFingerprint: String = PHYSICAL_FINGERPRINT,
		historicalRegistrationGeneration: Long = 1L,
		historicalPlanChecksum: String? = null,
		manifestZoneId: String = "Europe/Prague",
		runDesiredPlanRevision: Long = 1L,
		corruptDesiredChecksum: Boolean = false,
		insertWalEvidence: Boolean = true,
		walSourceSequence: Long = 1L,
		walObservedElapsedRealtimeNanos: Long = 200L,
		walReceivedElapsedRealtimeNanos: Long = 210L,
		walWallTimeMs: Long = 2_000L,
		walPayload: SourcePayload? = null,
		walPayloadBytesOverride: ByteArray? = null,
	) {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L))
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
				owner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
				ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				updatedAtMs = 1_000L,
			),
		)
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
				createdAt = 1_000L,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
			),
		)
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = runDesiredPlanRevision,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = BOOT_ID,
				startedAtMs = 1_000L,
				startedElapsedNanos = RUN_START_NANOS,
				cutoffAtMs = 2_000L,
				cutoffElapsedNanos = RUN_END_NANOS,
				completedAtMs = 2_000L,
				finalAdmissionOrdinal = 1L,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 2L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = SERVICE_RUN_ID,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = "FINALIZED",
				desiredPlanRevision = runDesiredPlanRevision,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1_000L,
				startedElapsedNanos = RUN_START_NANOS,
				completedAtMs = 2_000L,
				completionReason = "TEST",
				bootId = BOOT_ID,
				leaseGeneration = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOPPED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "delivery-1",
				startCommandGeneration = 1L,
				preparedManifestRevision = 1L,
				preparedIntentRevision = 1L,
				androidDeliveryState = "SETTLED",
				androidDeliveryUpdatedAtMs = 2_000L,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = 2_000L,
			),
		)
		insertManifest(sourcePolicyRevision, manifestZoneId)
		database.sourcePolicyDao().insertPolicies(listOf(activityPolicy(sourcePolicyRevision)))
		database.sourcePolicyDao().insertConsentEpochs(listOf(activityConsent(consentPolicyRevision)))
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(1L, "plan-1", 1_000L, "APPLIED", sourcePolicyRevision),
		)
		val encodedPlan = SourcePlanCodec().encode(activityPlan)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(
				SourceDesiredPlanEntity(
					1L,
					SourceKind.ACTIVITY.stableCode,
					1,
					encodedPlan.bytes,
					if (corruptDesiredChecksum) "corrupt-checksum" else encodedPlan.checksum,
				),
			),
		)
		database.activityCapturedFactDao().insertRegistrationPlanBinding(
			ActivityCapturedRegistrationPlanEntity.create(
				sourceInstanceId = SOURCE_INSTANCE_ID,
				registrationGeneration = historicalRegistrationGeneration,
				configurationRevision = 1L,
				desiredPlanPayloadVersion = 1,
				desiredPlanPayloadChecksum = historicalPlanChecksum ?: encodedPlan.checksum,
				physicalConfigurationFingerprint = physicalFingerprint,
				appliedAtElapsedRealtimeNanos = RUN_START_NANOS,
				applyStatus = "APPLIED",
			),
		)
		database.sourcePlanStateDao().saveAppliedState(
			SourceAppliedPlanStateEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				desiredRevision = 1L,
				appliedRevision = 1L,
				sourceInstanceId = SOURCE_INSTANCE_ID,
				registrationGeneration = 1L,
				appliedAtElapsedNanos = RUN_START_NANOS,
				status = "APPLIED",
				degradedReasons = "",
				updatedAtMs = 1_000L,
			),
		)
		database.sourceBrokerDao().insertRegistration(activityRegistration(physicalFingerprint))
		database.sourceBrokerDao().insertAuthorizations(
			listOf(activityAuthorization(sourcePolicyRevision)),
		)
		if (insertWalEvidence) {
			insertActivityWal(
				sourcePolicyRevision = sourcePolicyRevision,
				sourceSequence = walSourceSequence,
				observedElapsedRealtimeNanos = walObservedElapsedRealtimeNanos,
				receivedElapsedRealtimeNanos = walReceivedElapsedRealtimeNanos,
				wallTimeMs = walWallTimeMs,
				payload = walPayload ?: ActivityTransitionPayload(
					activityType = StableActivityTypeCode.WALKING,
					transitionType = 0,
					providerElapsedRealtimeNanos = walObservedElapsedRealtimeNanos,
				),
				payloadBytesOverride = walPayloadBytesOverride,
				physicalFingerprint = physicalFingerprint,
			)
		}
	}

	private suspend fun insertManifest(sourcePolicyRevision: Long, zoneId: String) {
		val source = activityManifestSource(manifestRevision = 1L)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = 1L,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = sourcePolicyRevision,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = RUN_START_NANOS,
			effectiveWallTimeMs = 1_000L,
			zoneId = zoneId,
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun activityManifestSource(manifestRevision: Long) = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = manifestRevision,
			sourceKind = SourceKind.ACTIVITY.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = 0L,
			persistenceEligible = true,
			qosCode = 1,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			writerOwner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		)

	private suspend fun insertReplacementRunManifest(effectiveElapsedRealtimeNanos: Long) {
		val replacementRunId = "service-run-activity-replacement"
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = replacementRunId,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = "FINALIZED",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1_500L,
				startedElapsedNanos = effectiveElapsedRealtimeNanos,
				completedAtMs = 2_000L,
				completionReason = "TEST_REPLACEMENT",
				bootId = BOOT_ID,
				leaseGeneration = 2L,
				startOrigin = "RECOVERY",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOPPED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "delivery-replacement",
				startCommandGeneration = 2L,
				preparedManifestRevision = 2L,
				preparedIntentRevision = 2L,
				androidDeliveryState = "SETTLED",
				androidDeliveryUpdatedAtMs = 2_000L,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = null,
			),
		)
		val source = activityManifestSource(manifestRevision = 2L)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = 2L,
			serviceRunId = replacementRunId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "RECOVERY",
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = 1_500L,
			zoneId = "Europe/Prague",
			automationEpoch = null,
			changeReason = "TEST_REPLACEMENT",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun activityPolicy(sourcePolicyRevision: Long) = SourcePolicyEntity(
		policyRevision = sourcePolicyRevision,
		sourceKind = SourceKind.ACTIVITY.stableCode,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = 0L,
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = RUN_START_NANOS,
		effectiveWallTimeMs = 1_000L,
		changeReason = "TEST",
	)

	private fun activityConsent(consentPolicyRevision: Long) = SourceConsentEpochEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		epoch = 0L,
		eligible = true,
		persistenceEligible = true,
		policyRevision = consentPolicyRevision,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = RUN_START_NANOS,
		effectiveWallTimeMs = 1_000L,
		changeReason = "TEST",
	)

	private fun activityRegistration(
		physicalFingerprint: String,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		registrationGeneration = 1L,
		sourceInstanceId = SOURCE_INSTANCE_ID,
		ownerScope = "activity-provider",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = physicalFingerprint,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-1",
		status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		reservedAtMs = 900L,
		reservedElapsedRealtimeNanos = 90L,
		acceptedAtMs = 1_000L,
		acceptedElapsedRealtimeNanos = RUN_START_NANOS,
		retiredAtMs = null,
		retiredElapsedRealtimeNanos = null,
		failureCode = null,
	)

	private fun activityAuthorization(sourcePolicyRevision: Long) = SourceAuthorizationEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode,
		registrationGeneration = 1L,
		authorizationRevision = 1L,
		memberId = "capture-member",
		authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
		purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		demandId = "capture-demand",
		consumerId = "capture-consumer",
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		sourcePolicyRevision = sourcePolicyRevision,
		consentEpoch = 0L,
		persistenceEligible = true,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = RUN_START_NANOS,
		effectiveWallTimeMs = 1_000L,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		manifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
	)

	private suspend fun insertActivityWal(
		sourcePolicyRevision: Long,
		sourceSequence: Long,
		observedElapsedRealtimeNanos: Long,
		receivedElapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		payload: SourcePayload,
		payloadBytesOverride: ByteArray?,
		physicalFingerprint: String,
	) {
		val encodedPayload = DefaultSourcePayloadCodec().encode(payload, 1)
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = 1L,
			eventId = "activity-event-1",
			providerDedupKey = "activity-provider-event-1",
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sourceKind = SourceKind.ACTIVITY.stableCode,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = physicalFingerprint,
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
			sourceSequence = sourceSequence,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = observedElapsedRealtimeNanos,
			receivedElapsedNanos = receivedElapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = 25L,
			capturedCollectedDataEpoch = 0L,
			sourcePolicyRevision = sourcePolicyRevision,
			captureConsentEpoch = 0L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 2_000L,
			qualityFlags = 0L,
			qualityConfidence = 0.90f,
			payloadVersion = 1,
			payload = payloadBytesOverride ?: encodedPayload.bytes,
			payloadChecksum = "",
			createdAtMs = 2_000L,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		val qualified = checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
		database.sourceEventWalDao().insertIgnoringDuplicate(qualified)
	}

	private fun capturedWindow(
		semanticRevision: Long = 1L,
		supersedes: Long? = null,
		withGap: Boolean = true,
		authority: ActivityCaptureAuthority = captureAuthority(),
	): ActivityCapturedWindow {
		val mutation = ActivityCapturedWindowMutation(
			identity = ActivityCapturedWindowIdentity(authority, 200L, 400L),
			semanticRevision = semanticRevision,
			supersedesSemanticRevision = supersedes,
		)
		val reference = ActivityCapturedObservationReference(
			SourceEventId("activity-event-1"),
			admissionOrdinal = 1L,
			sourceSequence = 1L,
			providerElapsedRealtimeNanos = 200L,
			receivedElapsedRealtimeNanos = 210L,
			observationKind = ActivityCapturedObservationKind.TRANSITION,
			observedActivity = CapturedActivityType.WALKING,
			transitionChange = ActivityTransitionChange.ENTER,
			confidencePercent = null,
			coverageEndExclusiveElapsedRealtimeNanos = null,
		)
		val bandEnd = if (withGap) 300L else 400L
		val band = ActivityCapturedBand(
			key = ActivityCapturedFactKey(mutation, 0),
			intervalStartElapsedRealtimeNanos = 200L,
			intervalEndExclusiveElapsedRealtimeNanos = bandEnd,
			wallTimeRange = ActivityDerivedWallTimeRange(
				startInclusive = boundary(reference, 2_000L, ActivityWallTimeBoundaryKind.EXACT_PROVIDER_OBSERVATION),
				endExclusive = boundary(reference, 2_000L, ActivityWallTimeBoundaryKind.SAME_CLOCK_EXTRAPOLATION),
				continuity = ActivityWallTimeContinuity.SAME_ANCHOR,
			),
			activity = CapturedActivityType.WALKING,
			mechanism = ActivityBandMechanism.TRANSITION,
			refinedTransitionActivity = null,
			confidence = ActivityBandConfidence.TransitionSignal,
			evidence = listOf(reference),
		)
		return ActivityCapturedWindow(
			mutation = mutation,
			bands = listOf(band),
			gaps = if (withGap) listOf(
				ActivityCoverageGap(300L, 400L, ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE),
			) else emptyList(),
			exactDuplicateCount = 0,
			semanticDuplicateCount = 0,
			unchangedEvidenceCount = 0,
		)
	}

	private fun sampledCapturedWindow(
		authority: ActivityCaptureAuthority,
		coverageEndExclusiveElapsedRealtimeNanos: Long = 300L,
		confidencePercent: Int = 90,
	): ActivityCapturedWindow {
		val mutation = ActivityCapturedWindowMutation(
			identity = ActivityCapturedWindowIdentity(authority, 200L, 400L),
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
		)
		val reference = ActivityCapturedObservationReference(
			sourceEventId = SourceEventId("activity-event-1"),
			admissionOrdinal = 1L,
			sourceSequence = 1L,
			providerElapsedRealtimeNanos = 200L,
			receivedElapsedRealtimeNanos = 210L,
			observationKind = ActivityCapturedObservationKind.SAMPLED_CLASSIFICATION,
			observedActivity = CapturedActivityType.WALKING,
			transitionChange = null,
			confidencePercent = confidencePercent,
			coverageEndExclusiveElapsedRealtimeNanos =
				coverageEndExclusiveElapsedRealtimeNanos,
		)
		return ActivityCapturedWindow(
			mutation = mutation,
			bands = listOf(
				ActivityCapturedBand(
					key = ActivityCapturedFactKey(mutation, 0),
					intervalStartElapsedRealtimeNanos = 200L,
					intervalEndExclusiveElapsedRealtimeNanos = 300L,
					wallTimeRange = ActivityDerivedWallTimeRange(
						startInclusive = boundary(
							reference,
							2_000L,
							ActivityWallTimeBoundaryKind.EXACT_PROVIDER_OBSERVATION,
						),
						endExclusive = boundary(
							reference,
							2_000L,
							ActivityWallTimeBoundaryKind.SAME_CLOCK_EXTRAPOLATION,
						),
						continuity = ActivityWallTimeContinuity.SAME_ANCHOR,
					),
					activity = CapturedActivityType.WALKING,
					mechanism = ActivityBandMechanism.SAMPLED_CLASSIFICATION,
					refinedTransitionActivity = null,
					confidence = ActivityBandConfidence.Sampled(
						confidencePercent,
						confidencePercent,
						1,
					),
					evidence = listOf(reference),
				),
			),
			gaps = listOf(
				ActivityCoverageGap(300L, 400L, ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE),
			),
			exactDuplicateCount = 0,
			semanticDuplicateCount = 0,
			unchangedEvidenceCount = 0,
		)
	}

	private fun allGapCorrection(): ActivityCapturedWindow {
		val authority = captureAuthority()
		val mutation = ActivityCapturedWindowMutation(
			identity = ActivityCapturedWindowIdentity(authority, 200L, 400L),
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
		)
		return ActivityCapturedWindow(
			mutation = mutation,
			bands = emptyList(),
			gaps = listOf(
				ActivityCoverageGap(200L, 400L, ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE),
			),
			exactDuplicateCount = 0,
			semanticDuplicateCount = 0,
			unchangedEvidenceCount = 0,
		)
	}

	private fun boundary(
		reference: ActivityCapturedObservationReference,
		wallTimeMs: Long,
		kind: ActivityWallTimeBoundaryKind,
	) = ActivityDerivedWallTimeBoundary(
		wallTimeMs = wallTimeMs,
		uncertaintyMs = if (kind == ActivityWallTimeBoundaryKind.EXACT_PROVIDER_OBSERVATION) 25L else 26L,
		authority = ActivityWallTimeDerivationAuthority(
			kind = kind,
			anchorSourceEventId = reference.sourceEventId,
			anchorProviderElapsedRealtimeNanos = reference.providerElapsedRealtimeNanos,
			clockDomainId = BOOT_ID,
		),
	)

	private fun captureAuthority(
		sourcePolicyRevision: Long = 1L,
		physicalFingerprint: String = PHYSICAL_FINGERPRINT,
	) = ActivityCaptureAuthority(
		logicalTrackingId = LogicalTrackingId(LOGICAL_TRACKING_ID),
		serviceRunId = ServiceRunId(SERVICE_RUN_ID),
		sourceInstanceId = SourceInstanceId(SOURCE_INSTANCE_ID),
		registrationGeneration = 1L,
		configurationRevision = 1L,
		physicalConfigurationFingerprint = physicalFingerprint,
		authorizationRevision = 1L,
		authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
		purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = 0L,
		sessionManifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		collectedDataEpoch = 0L,
		clockDomainId = BOOT_ID,
		temporalAuthority = ActivityCaptureTemporalAuthority(
			providerAcceptance = ActivityProviderTimeInterval(RUN_START_NANOS, Long.MAX_VALUE),
			authorizationEffect = ActivityProviderTimeInterval(RUN_START_NANOS, Long.MAX_VALUE),
			sessionRunEffect = ActivityProviderTimeInterval(RUN_START_NANOS, RUN_END_NANOS),
		),
	)

	companion object {
		private const val LOGICAL_TRACKING_ID = "logical-activity-1"
		private const val SERVICE_RUN_ID = "service-run-activity-1"
		private const val SOURCE_INSTANCE_ID = "activity-instance-1"
		private const val BOOT_ID = "boot-activity-1"
		private const val AUTHORIZATION_FINGERPRINT = "activity-authorization-1"
		private const val RUN_START_NANOS = 100L
		private const val RUN_END_NANOS = 1_000L
		private const val WRITER_ID = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID
		private const val WRITER_VERSION = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION
		private val DEFAULT_ACTIVITY_PLAN = ActivityPlan(
			revision = 1L,
			mode = ActivityMode.TRANSITIONS_ONLY,
			desiredDetectionLatencyMs = 30_000L,
			confidenceThresholdPercent = 65,
			transitionTypes = setOf(0, 1),
		)
		private val PHYSICAL_FINGERPRINT = DEFAULT_ACTIVITY_PLAN.physicalConfigurationFingerprint()
		private val SAMPLED_ACTIVITY_PLAN = DEFAULT_ACTIVITY_PLAN.copy(
			mode = ActivityMode.CONTINUOUS_RECOGNITION,
		)
		private val SAMPLED_PHYSICAL_FINGERPRINT =
			SAMPLED_ACTIVITY_PLAN.physicalConfigurationFingerprint()
	}
}
