package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
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
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityCapturedFactMaintenanceTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `retention removes the complete correction lineage at an uncertain floor`() = runTest {
		seedCapturedActivity(retainedFromMs = 1_990L, semanticRevisions = 2)

		database.pruneCapturedActivityFactsAffectedByRetentionFloor(
			beforeMs = 1_990L,
			expectedCollectedDataEpoch = 0L,
			markedAtMs = 5_000L,
		) shouldBe ActivityCapturedRetentionResult.Pruned(1, 2)

		database.activityCapturedFactDao().revisionCount() shouldBe 0L
		database.activityCapturedFactDao().fragmentCount() shouldBe 0L
		database.activityCapturedFactDao().evidenceCount() shouldBe 0L
		database.activityCapturedFactDao().cursorCount() shouldBe 0L
		database.activityCapturedFactDao().registrationPlanBindingCount() shouldBe 1L
	}

	@Test
	fun `revision overflow blocks retention before any lineage mutation`() = runTest {
		seedCapturedActivity(retainedFromMs = 1_990L, semanticRevisions = 2)
		val limits = ActivityCapturedMaintenanceLimits(
			revisionPageSize = 1,
			maximumRevisions = 1,
			maximumLogicalWindows = 1,
		)

		database.pruneCapturedActivityFactsAffectedByRetentionFloor(
			beforeMs = 1_990L,
			expectedCollectedDataEpoch = 0L,
			markedAtMs = 5_000L,
			limits = limits,
			checkpoint = {},
		) shouldBe ActivityCapturedRetentionResult.Blocked(
			ActivityCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)

		database.activityCapturedFactDao().revisionCount() shouldBe 2L
		database.activityCapturedFactDao().cursorCount() shouldBe 1L
	}

	@Test
	fun `retention authenticates a live current run with an open session boundary`() = runTest {
		seedCapturedActivity(retainedFromMs = 1_000L, sessionRunEffectEndNanos = Long.MAX_VALUE)
		makeCurrentRunLive()

		database.pruneCapturedActivityFactsAffectedByRetentionFloor(
			beforeMs = 1_000L,
			expectedCollectedDataEpoch = 0L,
			markedAtMs = 5_000L,
		) shouldBe ActivityCapturedRetentionResult.NoChange
	}

	@Test
	fun `retention authenticates an open old-run fact after a live replacement`() = runTest {
		seedCapturedActivity(retainedFromMs = 1_000L, sessionRunEffectEndNanos = Long.MAX_VALUE)
		installLiveReplacement()

		database.pruneCapturedActivityFactsAffectedByRetentionFloor(
			beforeMs = 1_000L,
			expectedCollectedDataEpoch = 0L,
			markedAtMs = 5_000L,
		) shouldBe ActivityCapturedRetentionResult.NoChange
	}

	@Test
	fun `retention accepts the exact terminal cutoff and rejects a changed cutoff`() = runTest {
		seedCapturedActivity(retainedFromMs = 1_000L)

		database.pruneCapturedActivityFactsAffectedByRetentionFloor(
			beforeMs = 1_000L,
			expectedCollectedDataEpoch = 0L,
			markedAtMs = 5_000L,
		) shouldBe ActivityCapturedRetentionResult.NoChange

		val sessionDao = database.sourceSessionDao()
		val session = requireNotNull(sessionDao.session(LOGICAL_TRACKING_ID))
		sessionDao.updateSession(session.copy(cutoffElapsedNanos = 501L)) shouldBe 1
		database.pruneCapturedActivityFactsAffectedByRetentionFloor(
			beforeMs = 1_000L,
			expectedCollectedDataEpoch = 0L,
			markedAtMs = 5_000L,
		) shouldBe ActivityCapturedRetentionResult.Blocked(
			ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `revoked capture deletion fences every run and preserves CONTROL demand`() = runTest {
		seedCapturedActivity(revokedCapture = true)
		database.sourceBrokerDao().insertDemands(listOf(controlDemand()))

		database.deleteCapturedActivityFactsAfterConsentReset(0L, 1L, 5_000L) shouldBe
			ActivityCapturedSourceDeletionResult.Deleted(1, 1, 1, 1)

		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			ACTIVITY_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
		)
		database.sourceDeletionFenceDao().contains(
			ACTIVITY_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			digest,
		) shouldBe true
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
		database.activityCapturedFactDao().registrationPlanBindingCount() shouldBe 0L
		database.sourceBrokerDao().activeDemands(ACTIVITY_SOURCE).single().purpose shouldBe
			SourceBrokerPurpose.CONTROL_AUTOSTART
	}

	@Test
	fun `nonterminal capture demand blocks deletion without touching facts`() = runTest {
		seedCapturedActivity(revokedCapture = true)
		database.sourceBrokerDao().insertDemands(listOf(captureDemand()))

		database.deleteCapturedActivityFactsAfterConsentReset(0L, 1L, 5_000L) shouldBe
			ActivityCapturedSourceDeletionResult.Blocked(
				ActivityCapturedSourceDeletionBlockedReason.CAPTURE_DEMAND_NOT_QUIESCED,
			)
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `compatible active provider blocks deletion before fact audit`() = runTest {
		seedCapturedActivity(revokedCapture = true, providerActive = true)

		database.deleteCapturedActivityFactsAfterConsentReset(0L, 1L, 5_000L) shouldBe
			ActivityCapturedSourceDeletionResult.Blocked(
				ActivityCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
			)
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
	}

	@Test
	fun `malformed fact payload blocks source deletion instead of being skipped`() = runTest {
		seedCapturedActivity(revokedCapture = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE activity_captured_window_revision SET effect_checksum = 'corrupt'",
		)

		database.deleteCapturedActivityFactsAfterConsentReset(0L, 1L, 5_000L) shouldBe
			ActivityCapturedSourceDeletionResult.Blocked(
				ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
	}

	@Test
	fun `foreign payload-bearing revision blocks complete source deletion audit`() = runTest {
		seedCapturedActivity(revokedCapture = true)
		database.openHelper.writableDatabase.execSQL(
			"""
			INSERT INTO activity_captured_window_revision SELECT
				'foreign-activity-writer', writer_projection_version, writer_binding_generation,
				writer_owner_generation, 'foreign-' || logical_window_id, semantic_revision,
				supersedes_semantic_revision, 'foreign-' || mutation_id, logical_tracking_id,
				service_run_id, session_segment_id, purpose, source_instance_id,
				registration_generation, configuration_revision, physical_configuration_fingerprint,
				authorization_revision, authorization_fingerprint, purpose_eligibility_mask,
				source_policy_revision, capture_consent_epoch, manifest_revision,
				lifecycle_lease_generation, collected_data_epoch, clock_domain_id, stored_zone_id,
				provider_acceptance_start_nanos, provider_acceptance_end_nanos,
				authorization_effect_start_nanos, authorization_effect_end_nanos,
				session_run_effect_start_nanos, session_run_effect_end_nanos,
				window_start_elapsed_realtime_nanos, window_end_elapsed_realtime_nanos, coverage,
				known_active_duration_nanos, known_inactive_duration_nanos,
				unknown_activity_duration_nanos, unobserved_duration_nanos, exact_duplicate_count,
				semantic_duplicate_count, unchanged_evidence_count, scope_deletion_generation,
				effect_checksum, applied_at_ms
			FROM activity_captured_window_revision LIMIT 1
			""".trimIndent(),
		)

		database.deleteCapturedActivityFactsAfterConsentReset(0L, 1L, 5_000L) shouldBe
			ActivityCapturedSourceDeletionResult.Blocked(
				ActivityCapturedSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT,
			)
		database.activityCapturedFactDao().revisionCount() shouldBe 2L
	}

	@Test
	fun `orphan fragment blocks source deletion before AlreadyDeleted`() = runTest {
		seedCapturedActivity(revokedCapture = true)
		corruptWithoutForeignKeys(
			"DELETE FROM activity_captured_evidence",
			"DELETE FROM activity_captured_window_cursor",
			"DELETE FROM activity_captured_window_revision",
			"DELETE FROM activity_captured_registration_plan",
		)

		database.deleteCapturedActivityFactsAfterConsentReset(0L, 1L, 5_000L) shouldBe
			ActivityCapturedSourceDeletionResult.Blocked(
				ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		database.activityCapturedFactDao().fragmentCount() shouldBe 1L
	}

	@Test
	fun `orphan evidence blocks source deletion before AlreadyDeleted`() = runTest {
		seedCapturedActivity(revokedCapture = true)
		corruptWithoutForeignKeys(
			"DELETE FROM activity_captured_fragment",
			"DELETE FROM activity_captured_window_cursor",
			"DELETE FROM activity_captured_window_revision",
			"DELETE FROM activity_captured_registration_plan",
		)

		database.deleteCapturedActivityFactsAfterConsentReset(0L, 1L, 5_000L) shouldBe
			ActivityCapturedSourceDeletionResult.Blocked(
				ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		database.activityCapturedFactDao().evidenceCount() shouldBe 1L
	}

	@Test
	fun `orphan cursor blocks source deletion before AlreadyDeleted`() = runTest {
		seedCapturedActivity(revokedCapture = true)
		corruptWithoutForeignKeys(
			"DELETE FROM activity_captured_evidence",
			"DELETE FROM activity_captured_fragment",
			"DELETE FROM activity_captured_window_revision",
			"DELETE FROM activity_captured_registration_plan",
		)

		database.deleteCapturedActivityFactsAfterConsentReset(0L, 1L, 5_000L) shouldBe
			ActivityCapturedSourceDeletionResult.Blocked(
				ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		database.activityCapturedFactDao().cursorCount() shouldBe 1L
	}

	@Test
	fun `bare revision blocks source deletion before an empty-source result`() = runTest {
		seedCapturedActivity(revokedCapture = true)
		corruptWithoutForeignKeys(
			"DELETE FROM activity_captured_evidence",
			"DELETE FROM activity_captured_fragment",
			"DELETE FROM activity_captured_window_cursor",
			"DELETE FROM activity_captured_registration_plan",
		)

		database.deleteCapturedActivityFactsAfterConsentReset(0L, 1L, 5_000L) shouldBe
			ActivityCapturedSourceDeletionResult.Blocked(
				ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
	}

	@Test
	fun `cancellation after payload removal rolls back facts and run fence`() = runTest {
		seedCapturedActivity(revokedCapture = true)

		shouldThrow<CancellationException> {
			database.deleteCapturedActivityFactsAfterConsentReset(
				expectedCollectedDataEpoch = 0L,
				expectedRevokedConsentEpoch = 1L,
				deletedAtMs = 5_000L,
				limits = ActivityCapturedMaintenanceLimits(),
				checkpoint = { checkpoint ->
					if (checkpoint == ActivityCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED) {
						throw CancellationException("test cancellation")
					}
				},
			)
		}

		database.activityCapturedFactDao().revisionCount() shouldBe 1L
		database.activityCapturedFactDao().cursorCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `selected deletion fences exact run and preserves nonpayload authority and CONTROL`() = runTest {
		seedCapturedActivity()
		database.sourceBrokerDao().insertDemands(listOf(controlDemand()))
		val segmentId = requireNotNull(
			database.sourceSessionDao().serviceRun(SERVICE_RUN_ID),
		).sessionSegmentId!!

		val result = database.withTransaction {
			database.deleteSelectedCapturedActivityFactsInTransaction(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				runScopes = listOf(ActivityCapturedSelectedRunScope(SERVICE_RUN_ID, segmentId)),
				expectedCollectedDataEpoch = 0L,
				deletedAtMs = 5_000L,
			)
		}

		result shouldBe ActivityCapturedSelectedDeletionResult.Deleted(1, 1, 1)
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
		database.activityCapturedFactDao().fragmentCount() shouldBe 0L
		database.activityCapturedFactDao().evidenceCount() shouldBe 0L
		database.activityCapturedFactDao().cursorCount() shouldBe 0L
		database.activityCapturedFactDao().registrationPlanBindingCount() shouldBe 1L
		database.sessionSegmentDao().getById(segmentId)?.id shouldBe segmentId
		database.sourceSessionDao().session(LOGICAL_TRACKING_ID)?.logicalTrackingId shouldBe
			LOGICAL_TRACKING_ID
		database.sourceSessionDao().serviceRun(SERVICE_RUN_ID)?.serviceRunId shouldBe SERVICE_RUN_ID
		database.sourceSessionDao().manifestsForServiceRun(SERVICE_RUN_ID, 2).size shouldBe 1
		database.sourceBrokerDao().activeDemands(ACTIVITY_SOURCE).single().purpose shouldBe
			SourceBrokerPurpose.CONTROL_AUTOSTART
		database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			ACTIVITY_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
		)
		database.sourceDeletionFenceDao().contains(
			ACTIVITY_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			digest,
		) shouldBe true
	}

	@Test
	fun `selected deletion catches one-sided cursor scope corruption before mutation`() = runTest {
		seedCapturedActivity()
		val segmentId = requireNotNull(
			database.sourceSessionDao().serviceRun(SERVICE_RUN_ID),
		).sessionSegmentId!!
		database.openHelper.writableDatabase.execSQL(
			"UPDATE activity_captured_window_cursor SET service_run_id = 'foreign-run'",
		)

		database.withTransaction {
			database.deleteSelectedCapturedActivityFactsInTransaction(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				runScopes = listOf(ActivityCapturedSelectedRunScope(SERVICE_RUN_ID, segmentId)),
				expectedCollectedDataEpoch = 0L,
				deletedAtMs = 5_000L,
			)
		} shouldBe ActivityCapturedSelectedDeletionResult.Blocked(
			ActivityCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
		database.activityCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `selected deletion cancellation after payload removal rolls back generation facts and fence`() =
		runTest {
			seedCapturedActivity()
			val segmentId = requireNotNull(
				database.sourceSessionDao().serviceRun(SERVICE_RUN_ID),
			).sessionSegmentId!!

			shouldThrow<CancellationException> {
				database.withTransaction {
					database.deleteSelectedCapturedActivityFactsInTransaction(
						logicalTrackingId = LOGICAL_TRACKING_ID,
						runScopes = listOf(
							ActivityCapturedSelectedRunScope(SERVICE_RUN_ID, segmentId),
						),
						expectedCollectedDataEpoch = 0L,
						deletedAtMs = 5_000L,
						limits = ActivityCapturedMaintenanceLimits(),
						checkpoint = { checkpoint ->
							if (checkpoint == ActivityCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED) {
								throw CancellationException("test cancellation")
							}
						},
					)
				}
			}

			database.activityCapturedFactDao().revisionCount() shouldBe 1L
			database.activityCapturedFactDao().cursorCount() shouldBe 1L
			database.sourceDeletionFenceDao().countAll() shouldBe 0L
			database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		}

	private suspend fun seedCapturedActivity(
		retainedFromMs: Long? = null,
		semanticRevisions: Int = 1,
		revokedCapture: Boolean = false,
		providerActive: Boolean = false,
		sessionRunEffectEndNanos: Long = 500L,
	) {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 0L, retainedFromMs = retainedFromMs),
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = ACTIVITY_SOURCE,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
				owner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
				ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				updatedAtMs = 1_000L,
			),
		)
		val segmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = 1_000L,
				endTimeMs = 3_000L,
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
		database.sourceSessionDao().insertSession(logicalSession())
		database.sourceSessionDao().insertServiceRun(serviceRun(segmentId))
		val source = manifestSource()
		val unsignedManifest = manifest()
		database.sourceSessionDao().insertManifest(
			unsignedManifest.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(source)),
			),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
		val policies = mutableListOf(historicalPolicy())
		if (revokedCapture) policies += revokedPolicy()
		database.sourcePolicyDao().insertPolicies(policies)
		val consents = mutableListOf(historicalConsent())
		if (revokedCapture) consents += revokedConsent()
		database.sourcePolicyDao().insertConsentEpochs(consents)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = if (revokedCapture) 2L else 1L,
				legacySettingsFingerprint = null,
				updatedAtMs = if (revokedCapture) 4_000L else 1_000L,
			),
		)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(1L, "plan-1", 1_000L, "APPLIED", 1L),
		)
		val planPayload = byteArrayOf(1, 2, 3, 4)
		val planChecksum = sha256(planPayload)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(SourceDesiredPlanEntity(1L, ACTIVITY_SOURCE, 1, planPayload, planChecksum)),
		)
		database.activityCapturedFactDao().insertRegistrationPlanBinding(
			ActivityCapturedRegistrationPlanEntity.create(
				sourceInstanceId = SOURCE_INSTANCE_ID,
				registrationGeneration = 1L,
				configurationRevision = 1L,
				desiredPlanPayloadVersion = 1,
				desiredPlanPayload = planPayload,
				desiredPlanPayloadChecksum = planChecksum,
				physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
				appliedAtElapsedRealtimeNanos = 100L,
				applyStatus = "APPLIED",
			),
		)
		database.sourceBrokerDao().insertRegistration(registration(providerActive))
		database.sourceBrokerDao().insertAuthorizations(listOf(authorization()))
		insertFactLineage(segmentId, semanticRevisions, sessionRunEffectEndNanos)
	}

	private suspend fun makeCurrentRunLive() {
		val sessionDao = database.sourceSessionDao()
		val session = requireNotNull(sessionDao.session(LOGICAL_TRACKING_ID))
		val run = requireNotNull(sessionDao.serviceRun(SERVICE_RUN_ID))
		sessionDao.updateSession(session.copy(
			state = "ACTIVE",
			cutoffAtMs = null,
			cutoffElapsedNanos = null,
			completedAtMs = null,
			finalAdmissionOrdinal = null,
			currentServiceRunId = SERVICE_RUN_ID,
		)) shouldBe 1
		sessionDao.updateServiceRun(run.copy(
			state = "ACTIVE",
			completedAtMs = null,
			completionReason = null,
			runtimeAcknowledgement = "START_ACCEPTED",
			runRevision = 3L,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)) shouldBe 1
	}

	private suspend fun installLiveReplacement() {
		val sessionDao = database.sourceSessionDao()
		val replacementSegmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = 3_100L,
				endTimeMs = 3_100L,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = null,
				createdAt = 3_100L,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			),
		)
		val replacementRun = serviceRun(replacementSegmentId).copy(
			serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			state = "ACTIVE",
			startedAtMs = 3_100L,
			startedElapsedNanos = 600L,
			completedAtMs = null,
			completionReason = null,
			leaseGeneration = 2L,
			runtimeAcknowledgement = "START_ACCEPTED",
			runRevision = 1L,
			startDeliveryToken = "delivery-replacement",
			preparedManifestRevision = 2L,
			preparedIntentRevision = 2L,
			androidDeliveryUpdatedAtMs = 3_100L,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)
		sessionDao.insertServiceRun(replacementRun)
		val replacementSource = manifestSource().copy(manifestRevision = 2L)
		val unsignedManifest = manifest().copy(
			manifestRevision = 2L,
			serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			effectiveElapsedRealtimeNanos = 600L,
			effectiveWallTimeMs = 3_100L,
			changeReason = "REPLACEMENT_START",
			manifestChecksum = "",
		)
		sessionDao.insertManifest(unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(replacementSource)),
		))
		sessionDao.insertManifestSources(listOf(replacementSource))
		val session = requireNotNull(sessionDao.session(LOGICAL_TRACKING_ID))
		sessionDao.updateSession(session.copy(
			state = "ACTIVE",
			cutoffAtMs = null,
			cutoffElapsedNanos = null,
			completedAtMs = null,
			finalAdmissionOrdinal = null,
			currentManifestRevision = 2L,
			currentIntentRevision = 2L,
			currentServiceRunId = REPLACEMENT_SERVICE_RUN_ID,
			lifecycleLeaseGeneration = 2L,
		)) shouldBe 1
	}

	private fun corruptWithoutForeignKeys(vararg statements: String) {
		val sqlite = database.openHelper.writableDatabase
		sqlite.execSQL("PRAGMA foreign_keys = OFF")
		try {
			statements.forEach { statement -> sqlite.execSQL(statement) }
		} finally {
			sqlite.execSQL("PRAGMA foreign_keys = ON")
		}
	}

	private suspend fun insertFactLineage(
		segmentId: Long,
		semanticRevisions: Int,
		sessionRunEffectEndNanos: Long,
	) {
		require(semanticRevisions in 1..2)
		val dao = database.activityCapturedFactDao()
		val persisted = (1..semanticRevisions).map { ordinal ->
			persistedRevision(
				segmentId = segmentId,
				semanticRevision = ordinal.toLong(),
				complete = ordinal == 2,
				sessionRunEffectEndNanos = sessionRunEffectEndNanos,
			)
		}
		persisted.forEach { value ->
			dao.insertRevision(value.revision)
			dao.insertFragments(value.fragments)
			dao.insertEvidence(value.evidence)
		}
		val latest = persisted.last().revision
		dao.insertCursor(
			ActivityCapturedWindowCursorEntity(
				writerProjectionId = WRITER_ID,
				writerProjectionVersion = WRITER_VERSION,
				logicalWindowId = latest.logicalWindowId,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				sessionSegmentId = segmentId,
				writerOwnerGeneration = 1L,
				latestSemanticRevision = latest.semanticRevision,
				latestMutationId = latest.mutationId,
				latestEffectChecksum = latest.effectChecksum,
				cursorRevision = latest.semanticRevision,
				collectedDataEpoch = 0L,
				updatedAtMs = latest.appliedAtMs,
			),
		)
	}

	private fun persistedRevision(
		segmentId: Long,
		semanticRevision: Long,
		complete: Boolean,
		sessionRunEffectEndNanos: Long,
	): PersistedTestRevision {
		val logicalWindowId = logicalWindowId(sessionRunEffectEndNanos)
		val fragments = if (complete) {
			listOf(bandFragment(logicalWindowId, semanticRevision, 0, 200L, 400L))
		} else {
			listOf(
				bandFragment(logicalWindowId, semanticRevision, 0, 200L, 300L),
				gapFragment(logicalWindowId, semanticRevision, 1, 300L, 400L),
			)
		}
		val evidence = listOf(evidence(logicalWindowId, semanticRevision))
		val unsigned = ActivityCapturedWindowRevisionEntity(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = 1L,
			writerOwnerGeneration = 1L,
			logicalWindowId = logicalWindowId,
			semanticRevision = semanticRevision,
			supersedesSemanticRevision = semanticRevision.takeIf { it > 1L }?.minus(1L),
			mutationId = mutationId(logicalWindowId, semanticRevision),
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sessionSegmentId = segmentId,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = 1L,
			configurationRevision = 1L,
			physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
			authorizationRevision = 1L,
			authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 0L,
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			collectedDataEpoch = 0L,
			clockDomainId = BOOT_ID,
			storedZoneId = "Europe/Prague",
			providerAcceptanceStartNanos = 100L,
			providerAcceptanceEndNanos = 1_000L,
			authorizationEffectStartNanos = 150L,
			authorizationEffectEndNanos = Long.MAX_VALUE,
			sessionRunEffectStartNanos = 100L,
			sessionRunEffectEndNanos = sessionRunEffectEndNanos,
			windowStartElapsedRealtimeNanos = 200L,
			windowEndElapsedRealtimeNanos = 400L,
			coverage = if (complete) "COMPLETE" else "PARTIAL",
			knownActiveDurationNanos = if (complete) 200L else 100L,
			knownInactiveDurationNanos = 0L,
			unknownActivityDurationNanos = 0L,
			unobservedDurationNanos = if (complete) 0L else 100L,
			exactDuplicateCount = 0,
			semanticDuplicateCount = 0,
			unchangedEvidenceCount = 0,
			scopeDeletionGeneration = 0L,
			effectChecksum = "pending",
			appliedAtMs = 2_200L + semanticRevision,
		)
		val checksum = effectChecksum(unsigned, fragments, evidence)
		return PersistedTestRevision(unsigned.copy(effectChecksum = checksum), fragments, evidence)
	}

	private fun bandFragment(
		logicalWindowId: String,
		semanticRevision: Long,
		fragmentOrdinal: Int,
		startNanos: Long,
		endNanos: Long,
	) = ActivityCapturedFragmentEntity(
		writerProjectionId = WRITER_ID,
		writerProjectionVersion = WRITER_VERSION,
		logicalWindowId = logicalWindowId,
		semanticRevision = semanticRevision,
		fragmentOrdinal = fragmentOrdinal,
		fragmentKind = ActivityCapturedFragmentEntity.KIND_BAND,
		bandOrdinal = 0,
		intervalStartElapsedRealtimeNanos = startNanos,
		intervalEndElapsedRealtimeNanos = endNanos,
		gapReason = null,
		activity = "WALKING",
		mechanism = "TRANSITION",
		refinedTransitionActivity = null,
		confidenceKind = ActivityCapturedFragmentEntity.CONFIDENCE_TRANSITION,
		confidenceMinimumPercent = null,
		confidenceMaximumPercent = null,
		confidenceObservationCount = null,
		startWallTimeMs = 2_000L,
		startWallTimeUncertaintyMs = 25L,
		startBoundaryKind = "EXACT_PROVIDER_OBSERVATION",
		startAnchorSourceEventId = "activity-event-1",
		startAnchorProviderElapsedNanos = 200L,
		endWallTimeMs = 2_001L,
		endWallTimeUncertaintyMs = 26L,
		endBoundaryKind = "SAME_CLOCK_EXTRAPOLATION",
		endAnchorSourceEventId = "activity-event-1",
		endAnchorProviderElapsedNanos = 200L,
		wallTimeContinuity = "SAME_ANCHOR",
	)

	private fun gapFragment(
		logicalWindowId: String,
		semanticRevision: Long,
		fragmentOrdinal: Int,
		startNanos: Long,
		endNanos: Long,
	) = ActivityCapturedFragmentEntity(
		writerProjectionId = WRITER_ID,
		writerProjectionVersion = WRITER_VERSION,
		logicalWindowId = logicalWindowId,
		semanticRevision = semanticRevision,
		fragmentOrdinal = fragmentOrdinal,
		fragmentKind = ActivityCapturedFragmentEntity.KIND_GAP,
		bandOrdinal = null,
		intervalStartElapsedRealtimeNanos = startNanos,
		intervalEndElapsedRealtimeNanos = endNanos,
		gapReason = "NO_QUALIFIED_EVIDENCE",
		activity = null,
		mechanism = null,
		refinedTransitionActivity = null,
		confidenceKind = null,
		confidenceMinimumPercent = null,
		confidenceMaximumPercent = null,
		confidenceObservationCount = null,
		startWallTimeMs = null,
		startWallTimeUncertaintyMs = null,
		startBoundaryKind = null,
		startAnchorSourceEventId = null,
		startAnchorProviderElapsedNanos = null,
		endWallTimeMs = null,
		endWallTimeUncertaintyMs = null,
		endBoundaryKind = null,
		endAnchorSourceEventId = null,
		endAnchorProviderElapsedNanos = null,
		wallTimeContinuity = null,
	)

	private fun evidence(logicalWindowId: String, semanticRevision: Long) =
		ActivityCapturedEvidenceEntity(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			logicalWindowId = logicalWindowId,
			semanticRevision = semanticRevision,
			fragmentOrdinal = 0,
			evidenceOrdinal = 0,
			sourceEventId = "activity-event-1",
			sourceAdmissionOrdinal = 1L,
			sourceSequence = 1L,
			providerElapsedRealtimeNanos = 200L,
			receivedElapsedRealtimeNanos = 210L,
			observationKind = ActivityCapturedEvidenceEntity.KIND_TRANSITION,
			observedActivity = "WALKING",
			transitionChange = "ENTER",
			confidencePercent = null,
			coverageEndExclusiveElapsedRealtimeNanos = null,
		)

	private fun logicalSession() = LogicalTrackingSessionEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		state = "FINALIZED",
		lifecycleRevision = 2L,
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		clockDomainId = BOOT_ID,
		startedAtMs = 1_000L,
		startedElapsedNanos = 100L,
		cutoffAtMs = 3_000L,
		cutoffElapsedNanos = 500L,
		completedAtMs = 3_000L,
		finalAdmissionOrdinal = 1L,
		failureCode = null,
		sessionMode = "MANUAL",
		currentManifestRevision = 1L,
		currentIntentRevision = 1L,
		currentServiceRunId = null,
		lifecycleLeaseGeneration = 1L,
		lifecycleBootId = BOOT_ID,
		automationEpoch = null,
	)

	private fun serviceRun(segmentId: Long) = SourceServiceRunEntity(
		serviceRunId = SERVICE_RUN_ID,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		state = "FINALIZED",
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1_000L,
		startedElapsedNanos = 100L,
		completedAtMs = 3_000L,
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
		androidDeliveryUpdatedAtMs = 3_000L,
		startIsUserInitiated = true,
		startIsAmbient = false,
		sessionSegmentId = segmentId,
		presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
		presentationAcknowledgedAtMs = 3_000L,
	)

	private fun manifest() = SessionManifestVersionEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		manifestRevision = 1L,
		serviceRunId = SERVICE_RUN_ID,
		sessionMode = "MANUAL",
		sourcePolicyRevision = 1L,
		acquisitionPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 100L,
		effectiveWallTimeMs = 1_000L,
		zoneId = "Europe/Prague",
		automationEpoch = null,
		changeReason = "TEST",
		manifestChecksum = "",
	)

	private fun manifestSource() = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		manifestRevision = 1L,
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		consentEpoch = 0L,
		persistenceEligible = true,
		qosCode = 1,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
		writerOwner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
		writerOwnerGeneration = 1L,
		writerProjectionId = WRITER_ID,
		writerProjectionVersion = WRITER_VERSION,
		writerBindingGeneration = 1L,
	)

	private fun historicalPolicy() = policy(1L, enabled = true, captureEligible = true, consent = 0L)

	private fun revokedPolicy() = policy(2L, enabled = true, captureEligible = false, consent = null)

	private fun policy(
		revision: Long,
		enabled: Boolean,
		captureEligible: Boolean,
		consent: Long?,
	) = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = ACTIVITY_SOURCE,
		enabled = enabled,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = captureEligible,
		controlPersistenceEligible = enabled,
		ambientPersistenceEligible = false,
		captureConsentEpoch = consent,
		controlConsentEpoch = 0L.takeIf { enabled },
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = if (revision == 1L) 100L else 600L,
		effectiveWallTimeMs = if (revision == 1L) 1_000L else 4_000L,
		changeReason = "TEST",
	)

	private fun historicalConsent() = SourceConsentEpochEntity(
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

	private fun revokedConsent() = historicalConsent().copy(
		epoch = 1L,
		eligible = false,
		persistenceEligible = false,
		policyRevision = 2L,
		effectiveElapsedRealtimeNanos = 600L,
		effectiveWallTimeMs = 4_000L,
	)

	private fun registration(active: Boolean) = ProviderRegistrationGenerationEntity(
		sourceKind = ACTIVITY_SOURCE,
		registrationGeneration = 1L,
		sourceInstanceId = SOURCE_INSTANCE_ID,
		ownerScope = "source-broker:$ACTIVITY_SOURCE",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-1",
		status = if (active) ProviderRegistrationGenerationEntity.STATUS_ACTIVE else
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = 900L,
		reservedElapsedRealtimeNanos = 90L,
		acceptedAtMs = 1_000L,
		acceptedElapsedRealtimeNanos = 100L,
		retiredAtMs = 3_500L.takeUnless { active },
		retiredElapsedRealtimeNanos = 1_000L.takeUnless { active },
		failureCode = null,
	)

	private fun authorization() = SourceAuthorizationEntity(
		sourceKind = ACTIVITY_SOURCE,
		registrationGeneration = 1L,
		authorizationRevision = 1L,
		memberId = "capture-member",
		authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
		purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		demandId = "historic-capture-demand",
		consumerId = "historic-capture-consumer",
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		sourcePolicyRevision = 1L,
		consentEpoch = 0L,
		persistenceEligible = true,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 150L,
		effectiveWallTimeMs = 1_500L,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		manifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
	)

	private fun captureDemand() = SourceDemandEntity(
		demandId = "live-capture-demand",
		consumerId = "live-capture-consumer",
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		manifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = 0L,
		persistenceEligible = true,
		qosCode = 1,
		maximumAgeMs = 0L,
		desiredLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = 200L,
		requestedAtMs = 2_000L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun controlDemand() = SourceDemandEntity(
		demandId = "control-demand",
		consumerId = "control-consumer",
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = 2L,
		consentEpoch = 0L,
		persistenceEligible = false,
		qosCode = 1,
		maximumAgeMs = 0L,
		desiredLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = 700L,
		requestedAtMs = 4_100L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun logicalWindowId(sessionRunEffectEndNanos: Long): String = digest(
		"activity-captured-window-v1",
		listOf(
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
			SOURCE_INSTANCE_ID,
			"1",
			"1",
			PHYSICAL_FINGERPRINT,
			"1",
			AUTHORIZATION_FINGERPRINT,
			SourceBrokerPurpose.MASK_SESSION_CAPTURE.toString(),
			"1",
			"0",
			"1",
			"1",
			"0",
			BOOT_ID,
			"100",
			"1000",
			"150",
			Long.MAX_VALUE.toString(),
			"100",
			sessionRunEffectEndNanos.toString(),
			"200",
			"400",
		),
	)

	private fun mutationId(logicalWindowId: String, semanticRevision: Long): String = digest(
		"activity-captured-mutation-v1",
		listOf(logicalWindowId, semanticRevision.toString()),
	)

	private fun effectChecksum(
		revision: ActivityCapturedWindowRevisionEntity,
		fragments: List<ActivityCapturedFragmentEntity>,
		evidence: List<ActivityCapturedEvidenceEntity>,
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
		) + fragments.flatMap { fragment ->
			listOf(
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
			).map { field -> field?.toString() ?: "null" }
		} + evidence.flatMap { item ->
			listOf(
				item.fragmentOrdinal.toString(),
				item.evidenceOrdinal.toString(),
				item.sourceEventId,
				item.sourceAdmissionOrdinal.toString(),
				item.sourceSequence.toString(),
				item.providerElapsedRealtimeNanos.toString(),
				item.receivedElapsedRealtimeNanos.toString(),
				item.observationKind,
				item.observedActivity,
				item.transitionChange ?: "null",
				item.confidencePercent?.toString() ?: "null",
				item.coverageEndExclusiveElapsedRealtimeNanos?.toString() ?: "null",
			)
		},
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

	private data class PersistedTestRevision(
		val revision: ActivityCapturedWindowRevisionEntity,
		val fragments: List<ActivityCapturedFragmentEntity>,
		val evidence: List<ActivityCapturedEvidenceEntity>,
	)

	companion object {
		private const val ACTIVITY_SOURCE = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		private const val WRITER_ID = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID
		private const val WRITER_VERSION = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION
		private const val LOGICAL_TRACKING_ID = "activity-maintenance-logical"
		private const val SERVICE_RUN_ID = "activity-maintenance-run"
		private const val REPLACEMENT_SERVICE_RUN_ID = "activity-maintenance-replacement-run"
		private const val SOURCE_INSTANCE_ID = "activity-maintenance-provider"
		private const val PHYSICAL_FINGERPRINT = "activity-maintenance-fingerprint"
		private const val AUTHORIZATION_FINGERPRINT = "activity-maintenance-authorization"
		private const val BOOT_ID = "activity-maintenance-boot"
	}
}
