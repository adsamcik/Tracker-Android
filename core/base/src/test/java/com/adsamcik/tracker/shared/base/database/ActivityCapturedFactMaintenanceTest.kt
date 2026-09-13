package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
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
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
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

	private fun portableReader(
		limits: PortableActivityReadLimits = PortableActivityReadLimits(),
	): PortableCapturedActivityRoomReader = PortableCapturedActivityRoomReader(
		database = database,
		laneExecutionAuthority = ACTIVITY_LANE_AUTHORITY,
		limits = limits,
	)

	private fun portableExporter(): RoomExportPortableCapturedActivity =
		RoomExportPortableCapturedActivity(
			database = database,
			laneExecutionAuthority = ACTIVITY_LANE_AUTHORITY,
			ioDispatcher = Dispatchers.Unconfined,
		)

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
	fun `retention removes a gap-only lineage whose wall placement is unverifiable`() = runTest {
		seedCapturedActivity(retainedFromMs = 1_000L, gapOnly = true)

		database.pruneCapturedActivityFactsAffectedByRetentionFloor(
			beforeMs = 1_000L,
			expectedCollectedDataEpoch = 0L,
			markedAtMs = 5_000L,
		) shouldBe ActivityCapturedRetentionResult.Pruned(1, 1)
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
		database.activityCapturedFactDao().cursorCount() shouldBe 0L
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
	fun `portable export emits only the latest authenticated correction with opaque identities`() = runTest {
		seedCapturedActivity(semanticRevisions = 2)
		database.sourceBrokerDao().insertDemands(listOf(controlDemand()))

		val snapshot = portableReader().read(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) as PortableCapturedActivitySnapshot.Ready

		val entry = snapshot.envelope.entries.single()
		val run = entry.runs.single()
		val window = run.windows.single()
		entry.identity.value shouldBe PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			LOGICAL_TRACKING_ID,
		).value
		window.coverage shouldBe PortableActivityWindowCoverage.COMPLETE
		window.fragments.size shouldBe 1
		(snapshot.envelope.toString().contains(LOGICAL_TRACKING_ID)) shouldBe false
		(snapshot.envelope.toString().contains(SERVICE_RUN_ID)) shouldBe false
		(snapshot.envelope.toString().contains(SOURCE_INSTANCE_ID)) shouldBe false
		(snapshot.envelope.toString().contains("CONTROL_AUTOSTART")) shouldBe false
	}

	@Test
	fun `portable export retains an explicit gap without inventing a numeric Activity value`() = runTest {
		seedCapturedActivity(gapOnly = true)

		val snapshot = portableReader().read(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) as PortableCapturedActivitySnapshot.Ready

		val window = snapshot.envelope.entries.single().runs.single().windows.single()
		window.coverage shouldBe PortableActivityWindowCoverage.NONE
		window.knownActiveDurationNanos shouldBe 0L
		window.knownInactiveDurationNanos shouldBe 0L
		window.unknownActivityDurationNanos shouldBe 0L
		window.unobservedDurationNanos shouldBe 200L
		window.fragments.single() shouldBe PortableActivityFragmentV1.Gap(
			startOffsetNanos = 0L,
			endOffsetNanos = 200L,
			reason = "NO_QUALIFIED_EVIDENCE",
		)
	}

	@Test
	fun `portable range selects a complete group through an overlapping factless replacement`() = runTest {
		seedCapturedActivity()
		installTerminalReplacement(capturesActivity = false)

		val snapshot = portableReader().read(
			ExportPortableCapturedActivityRequest(3_000L, 4_001L),
		) as PortableCapturedActivitySnapshot.Ready

		val runs = snapshot.envelope.entries.single().runs
		runs.size shouldBe 2
		runs.first().captureCoverage shouldBe PortableActivityCaptureCoverage.WHOLE_RUN
		runs.last().captureCoverage shouldBe PortableActivityCaptureCoverage.NOT_CAPTURED
		runs.last().windows shouldBe emptyList()
	}

	@Test
	fun `portable range uses exact half-open replacement boundaries`() = runTest {
		seedCapturedActivity()
		installTerminalReplacement(capturesActivity = false)

		portableReader().read(
			ExportPortableCapturedActivityRequest(3_000L, 3_100L),
		) shouldBe PortableCapturedActivitySnapshot.Outcome(
			ExportPortableCapturedActivityResult.NoEntries,
		)
	}

	@Test
	fun `portable range rejects a corrupt overlapping replacement binding`() = runTest {
		seedCapturedActivity()
		installTerminalReplacement(capturesActivity = false)
		corruptWithoutForeignKeys(
			"UPDATE session_segment SET service_run_id = 'foreign-run' " +
				"WHERE service_run_id = '$REPLACEMENT_SERVICE_RUN_ID'",
		)

		portableReader().read(
			ExportPortableCapturedActivityRequest(3_000L, 4_001L),
		) shouldBe PortableCapturedActivitySnapshot.Outcome(
			ExportPortableCapturedActivityResult.Unverifiable(
				PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			),
		)
	}

	@Test
	fun `portable export waits for the lane to drain a later admitted Activity event`() = runTest {
		seedCapturedActivity()
		insertActivityWalEvent(1L)
		insertActivityWalEvent(2L)
		replaceActivityCompleteness(lastAdmissionOrdinal = 2L, lastSourceSequence = 2L)
		updateFinalAdmissionOrdinal(2L)
		replaceActivityLane(activityProductLane(contiguousAdmissionOrdinal = 1L))
		var sinkCalls = 0

		portableExporter().export(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) {
			sinkCalls++
		} shouldBe ExportPortableCapturedActivityResult.Unverifiable(
			PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING,
		)
		sinkCalls shouldBe 0
	}

	@Test
	fun `portable export treats retained WAL above stale completeness as materializing`() = runTest {
		seedCapturedActivity()
		insertActivityWalEvent(2L)
		var sinkCalls = 0

		portableExporter().export(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) {
			sinkCalls++
		} shouldBe ExportPortableCapturedActivityResult.Unverifiable(
			PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING,
		)
		sinkCalls shouldBe 0
	}

	@Test
	fun `portable export accepts retained WAL only at the exact drained target`() = runTest {
		seedCapturedActivity()
		insertActivityWalEvent(2L)
		replaceActivityCompleteness(lastAdmissionOrdinal = 2L, lastSourceSequence = 2L)
		updateFinalAdmissionOrdinal(2L)
		replaceActivityLane(activityProductLane(contiguousAdmissionOrdinal = 2L))

		portableExporter().export(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) {} shouldBe ExportPortableCapturedActivityResult.Exported(1)
	}

	@Test
	fun `portable export fails closed when retained WAL target proof exceeds its bound`() = runTest {
		seedCapturedActivity()
		insertActivityWalEvent(1L)
		insertActivityWalEvent(2L)
		val reader = portableReader(
			limits = PortableActivityReadLimits(maximumCapturedWalRows = 1),
		)

		reader.read(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) shouldBe
			PortableCapturedActivitySnapshot.Outcome(
				ExportPortableCapturedActivityResult.Unverifiable(
					PortableActivityExportUnverifiableReason.DEPENDENCY_OVERFLOW,
				),
			)
	}

	@Test
	fun `portable export cannot hide retained WAL by clearing its capture purpose mask`() = runTest {
		seedCapturedActivity()
		insertActivityWalEvent(2L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_purpose_eligibility_mask = 0 " +
				"WHERE event_id = 'activity-event-2'",
		)
		var sinkCalls = 0

		portableExporter().export(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) {
			sinkCalls++
		} shouldBe ExportPortableCapturedActivityResult.Unverifiable(
			PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
		sinkCalls shouldBe 0
	}

	@Test
	fun `portable export fails closed on a malformed selected-run WAL header`() = runTest {
		seedCapturedActivity()
		insertActivityWalEvent(2L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET source_policy_revision = 99 " +
				"WHERE event_id = 'activity-event-2'",
		)
		var sinkCalls = 0

		portableExporter().export(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) {
			sinkCalls++
		} shouldBe ExportPortableCapturedActivityResult.Unverifiable(
			PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
		sinkCalls shouldBe 0
	}

	@Test
	fun `portable export accepts the exact settled Activity lane target`() = runTest {
		seedCapturedActivity()

		portableExporter().export(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) {} shouldBe ExportPortableCapturedActivityResult.Exported(1)
	}

	@Test
	fun `portable export rejects final admission below the Activity completeness target`() = runTest {
		seedCapturedActivity()
		replaceActivityCompleteness(lastAdmissionOrdinal = 2L, lastSourceSequence = 2L)
		replaceActivityLane(activityProductLane(contiguousAdmissionOrdinal = 2L))

		portableReader().read(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) shouldBe
			PortableCapturedActivitySnapshot.Outcome(
				ExportPortableCapturedActivityResult.Unverifiable(
					PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
				),
			)
	}

	@Test
	fun `portable export authenticates a factless replacement provider before emission`() = runTest {
		seedCapturedActivity()
		installTerminalReplacement(capturesActivity = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET owner_scope = 'foreign-owner' " +
				"WHERE source_kind = $ACTIVITY_SOURCE AND registration_generation = 2",
		)
		var sinkCalls = 0

		portableExporter().export(ExportPortableCapturedActivityRequest(1_000L, 4_001L)) {
			sinkCalls++
		} shouldBe ExportPortableCapturedActivityResult.Unverifiable(
			PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
		sinkCalls shouldBe 0
	}

	@Test
	fun `portable export rejects missing or mismatched completeness and lane authority`() = runTest {
		seedCapturedActivity()
		database.sourceSessionDao().deleteAllCompleteness()

		portableReader().read(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) shouldBe
			PortableCapturedActivitySnapshot.Outcome(
				ExportPortableCapturedActivityResult.Unverifiable(
					PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
				),
			)

		database.sourceSessionDao().saveCompleteness(
			activityCompleteness().copy(sourceInstanceId = "foreign-activity-provider"),
		)
		portableReader().read(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) shouldBe
			PortableCapturedActivitySnapshot.Outcome(
				ExportPortableCapturedActivityResult.Unverifiable(
					PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
				),
			)

		database.sourceSessionDao().deleteAllCompleteness()
		database.sourceSessionDao().saveCompleteness(activityCompleteness())
		replaceActivityLane(activityProductLane().copy(productStage = "CORRUPT"))
		portableReader().read(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) shouldBe
			PortableCapturedActivitySnapshot.Outcome(
				ExportPortableCapturedActivityResult.Unverifiable(
					PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
				),
			)
	}

	@Test
	fun `portable export rejects a terminal Activity projection failure`() = runTest {
		seedCapturedActivity()
		database.sourceProjectionStateDao().saveFailure(
			SourceProjectionFailureEntity(
				projectionId = WRITER_ID,
				projectionVersion = WRITER_VERSION,
				admissionOrdinal = 1L,
				attemptCount = 1,
				failureCode = "ACTIVITY_TEST_FAILURE",
				terminal = true,
				lastAttemptAtMs = 3_000L,
			),
		)

		portableReader().read(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) shouldBe
			PortableCapturedActivitySnapshot.Outcome(
				ExportPortableCapturedActivityResult.Unverifiable(
					PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
				),
			)
	}

	@Test
	fun `portable export accepts only an exactly drained retired Activity lane`() = runTest {
		seedCapturedActivity()
		replaceActivityLane(
			activityProductLane(
				contiguousAdmissionOrdinal = 1L,
				captureAdmissionCutoffOrdinal = 1L,
				retentionRequired = false,
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				terminalDisposition = SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				terminalAtMs = 3_000L,
			),
		)

		portableExporter().export(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) {} shouldBe ExportPortableCapturedActivityResult.Exported(1)

		replaceActivityLane(
			activityProductLane(
				contiguousAdmissionOrdinal = 1L,
				captureAdmissionCutoffOrdinal = 1L,
				retentionRequired = false,
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				terminalDisposition = null,
				terminalAtMs = null,
			),
		)
		portableReader().read(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) shouldBe
			PortableCapturedActivitySnapshot.Outcome(
				ExportPortableCapturedActivityResult.Unverifiable(
					PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
				),
			)
	}

	@Test
	fun `portable export rejects retained gap-only authority without a wall anchor`() = runTest {
		seedCapturedActivity(retainedFromMs = 1_000L, gapOnly = true)

		portableReader().read(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) shouldBe PortableCapturedActivitySnapshot.Outcome(
			ExportPortableCapturedActivityResult.Unverifiable(
				PortableActivityExportUnverifiableReason.RETENTION_CROSSES_ENTRY,
			),
		)
	}

	@Test
	fun `portable export rejects a live member before any sink IO`() = runTest {
		seedCapturedActivity(sessionRunEffectEndNanos = Long.MAX_VALUE)
		makeCurrentRunLive()
		var sinkCalls = 0
		val result = portableExporter().export(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) {
			sinkCalls++
		}

		result shouldBe ExportPortableCapturedActivityResult.Unverifiable(
			PortableActivityExportUnverifiableReason.ENTRY_MATERIALIZING,
		)
		sinkCalls shouldBe 0
	}

	@Test
	fun `portable export rejects a fenced run before any sink IO`() = runTest {
		seedCapturedActivity()
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = ACTIVITY_SOURCE,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = 4_000L,
			),
		)
		var sinkCalls = 0
		val result = portableExporter().export(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) { sinkCalls++ }

		result shouldBe ExportPortableCapturedActivityResult.Unverifiable(
			PortableActivityExportUnverifiableReason.DELETED_SCOPE,
		)
		sinkCalls shouldBe 0
	}

	@Test
	fun `portable export rejects one corrupt correction lineage before any sink IO`() = runTest {
		seedCapturedActivity(semanticRevisions = 2)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE activity_captured_window_revision SET effect_checksum = 'corrupt' " +
				"WHERE semantic_revision = 1",
		)
		var sinkCalls = 0

		portableExporter().export(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) { sinkCalls++ } shouldBe ExportPortableCapturedActivityResult.Unverifiable(
			PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
		sinkCalls shouldBe 0
	}

	@Test
	fun `portable export bounds complete replacement membership before emission`() = runTest {
		seedCapturedActivity(sessionRunEffectEndNanos = Long.MAX_VALUE)
		installLiveReplacement()
		val reader = PortableCapturedActivityRoomReader(
			database = database,
			laneExecutionAuthority = ACTIVITY_LANE_AUTHORITY,
			limits = PortableActivityReadLimits(maximumRuns = 1),
		)

		reader.read(ExportPortableCapturedActivityRequest(1_000L, 3_001L)) shouldBe
			PortableCapturedActivitySnapshot.Outcome(
				ExportPortableCapturedActivityResult.Unverifiable(
					PortableActivityExportUnverifiableReason.DEPENDENCY_OVERFLOW,
				),
			)
	}

	@Test
	fun `portable export cancellation leaves the immutable snapshot unpublished`() = runTest {
		seedCapturedActivity()
		val reader = PortableCapturedActivityRoomReader(
			database = database,
			laneExecutionAuthority = ACTIVITY_LANE_AUTHORITY,
			checkpoint = { point ->
				if (point == PortableActivityReadCheckpoint.REPLACEMENT_MEMBERS_LOADED) {
					throw CancellationException("cancel before snapshot publication")
				}
			},
		)

		shouldThrow<CancellationException> {
			reader.read(ExportPortableCapturedActivityRequest(1_000L, 3_001L))
		}
	}

	@Test
	fun `portable sink runs only after the Room snapshot transaction`() = runTest {
		seedCapturedActivity()
		var observedInsideTransaction: Boolean? = null

		portableExporter().export(
			ExportPortableCapturedActivityRequest(1_000L, 3_001L),
		) {
			observedInsideTransaction = database.inTransaction()
		} shouldBe ExportPortableCapturedActivityResult.Exported(1)
		observedInsideTransaction shouldBe false
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

	private suspend fun replaceActivityCompleteness(
		lastAdmissionOrdinal: Long,
		lastSourceSequence: Long,
	) {
		database.sourceSessionDao().deleteAllCompleteness()
		database.sourceSessionDao().saveCompleteness(
			activityCompleteness(lastAdmissionOrdinal, lastSourceSequence),
		)
	}

	private suspend fun updateFinalAdmissionOrdinal(value: Long) {
		val dao = database.sourceSessionDao()
		val session = requireNotNull(dao.session(LOGICAL_TRACKING_ID))
		dao.updateSession(session.copy(finalAdmissionOrdinal = value)) shouldBe 1
	}

	private suspend fun replaceActivityLane(lane: SourceProductProjectionLaneEntity) {
		database.sourceProjectionStateDao().deleteAllProductLanes()
		database.sourceProjectionStateDao().installProductLane(lane)
	}

	private suspend fun insertActivityWalEvent(ordinal: Long) {
		val payload = byteArrayOf(ordinal.toByte())
		val checksum = sha256(payload)
		val unsealed = SourceEventWalEntity(
			admissionOrdinal = ordinal,
			eventId = "activity-event-$ordinal",
			providerDedupKey = "activity-provider-event-$ordinal",
			deliveryIdentity = "activity-delivery-$ordinal",
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sourceKind = ACTIVITY_SOURCE,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
			sourceSequence = ordinal,
			configRevision = 1L,
			planAttribution = 1,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = 200L + ordinal,
			receivedElapsedNanos = 210L + ordinal,
			wallTimeMs = 2_000L + ordinal,
			wallTimeUncertaintyMs = 5L,
			capturedCollectedDataEpoch = 0L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 0L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 2_000L + ordinal,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = checksum,
			integrityIdentity = "pending",
			createdAtMs = 2_000L + ordinal,
		)
		val sealed = unsealed.copy(integrityIdentity = unsealed.calculatedIntegrityIdentity())
		database.sourceEventWalDao().insertIgnoringDuplicate(sealed) shouldBe ordinal
	}

	private suspend fun seedCapturedActivity(
		retainedFromMs: Long? = null,
		semanticRevisions: Int = 1,
		revokedCapture: Boolean = false,
		providerActive: Boolean = false,
		sessionRunEffectEndNanos: Long = 500L,
		gapOnly: Boolean = false,
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
		database.sourceProjectionStateDao().installProductLane(activityProductLane())
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
		database.sourceSessionDao().insertLifecycleActions(listOf(captureStartAction()))
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
		val planPayload = activityPlanPayload()
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
		database.sourceSessionDao().saveCompleteness(activityCompleteness())
		insertFactLineage(segmentId, semanticRevisions, sessionRunEffectEndNanos, gapOnly)
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

	private suspend fun installTerminalReplacement(capturesActivity: Boolean) {
		val sessionDao = database.sourceSessionDao()
		val replacementSegmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = 3_100L,
				endTimeMs = 4_000L,
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
		sessionDao.insertServiceRun(
			serviceRun(replacementSegmentId).copy(
				serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
				startedAtMs = 3_100L,
				startedElapsedNanos = 600L,
				completedAtMs = 4_000L,
				leaseGeneration = 2L,
				runRevision = 2L,
				startDeliveryToken = "delivery-replacement-terminal",
				preparedManifestRevision = 2L,
				preparedIntentRevision = 2L,
				androidDeliveryUpdatedAtMs = 4_000L,
				presentationAcknowledgedAtMs = 4_000L,
			),
		)
		val replacementSources = if (capturesActivity) {
			listOf(manifestSource().copy(manifestRevision = 2L))
		} else {
			emptyList()
		}
		val unsignedManifest = manifest().copy(
			manifestRevision = 2L,
			serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			effectiveElapsedRealtimeNanos = 600L,
			effectiveWallTimeMs = 3_100L,
			changeReason = "REPLACEMENT_START",
			manifestChecksum = "",
		)
		sessionDao.insertManifest(
			unsignedManifest.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, replacementSources),
			),
		)
		if (replacementSources.isNotEmpty()) {
			sessionDao.insertManifestSources(replacementSources)
			val planPayload = activityPlanPayload()
			val planChecksum = sha256(planPayload)
			database.activityCapturedFactDao().insertRegistrationPlanBinding(
				ActivityCapturedRegistrationPlanEntity.create(
					sourceInstanceId = REPLACEMENT_SOURCE_INSTANCE_ID,
					registrationGeneration = 2L,
					configurationRevision = 1L,
					desiredPlanPayloadVersion = 1,
					desiredPlanPayload = planPayload,
					desiredPlanPayloadChecksum = planChecksum,
					physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
					appliedAtElapsedRealtimeNanos = 600L,
					applyStatus = "APPLIED",
				),
			)
			database.sourceBrokerDao().insertRegistration(
				registration(
					active = false,
					registrationGeneration = 2L,
					sourceInstanceId = REPLACEMENT_SOURCE_INSTANCE_ID,
					reservedAtMs = 3_000L,
					reservedElapsedNanos = 590L,
					acceptedAtMs = 3_100L,
					acceptedElapsedNanos = 600L,
					retiredAtMs = 3_900L,
					retiredElapsedNanos = 890L,
					callbackBarrierRevision = 2L,
				),
			)
			sessionDao.insertLifecycleActions(
				listOf(
					captureStartAction(
						serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
						manifestRevision = 2L,
						actionRevision = 2L,
						leaseGeneration = 2L,
						requestedAtMs = 3_100L,
						requestedElapsedNanos = 600L,
						sourceInstanceId = REPLACEMENT_SOURCE_INSTANCE_ID,
						registrationGeneration = 2L,
					),
				),
			)
			database.sourceBrokerDao().insertAuthorizations(
				listOf(
					authorization().copy(
						authorizationRevision = 2L,
						memberId = "capture-member-replacement",
						registrationGeneration = 2L,
						logicalTrackingId = LOGICAL_TRACKING_ID,
						serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
						manifestRevision = 2L,
						lifecycleLeaseGeneration = 2L,
						effectiveElapsedRealtimeNanos = 600L,
						effectiveWallTimeMs = 3_100L,
					),
				),
			)
		}
		sessionDao.saveCompleteness(
			if (capturesActivity) {
				activityCompleteness().copy(
					serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
					logicalTrackingId = LOGICAL_TRACKING_ID,
					sourceInstanceId = REPLACEMENT_SOURCE_INSTANCE_ID,
					registrationGeneration = 2L,
					updatedAtMs = 4_000L,
				)
			} else {
				SourceSessionCompletenessEntity(
					logicalTrackingId = LOGICAL_TRACKING_ID,
					serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
					sourceKind = ACTIVITY_SOURCE,
					sourceInstanceId = "not-owned-activity",
					registrationGeneration = 0L,
					lastAdmissionOrdinal = null,
					lastSourceSequence = null,
					appDrainComplete = true,
					providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
					stopStatus = "COMPLETE",
					unresolvedSequenceStart = null,
					unresolvedSequenceEnd = null,
					updatedAtMs = 4_000L,
				)
			},
		)
		val session = requireNotNull(sessionDao.session(LOGICAL_TRACKING_ID))
		sessionDao.updateSession(
			session.copy(
				cutoffAtMs = 4_000L,
				cutoffElapsedNanos = 900L,
				completedAtMs = 4_000L,
				currentManifestRevision = 2L,
				currentIntentRevision = 2L,
				lifecycleLeaseGeneration = 2L,
			),
		) shouldBe 1
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
		gapOnly: Boolean,
	) {
		require(semanticRevisions in 1..2)
		val dao = database.activityCapturedFactDao()
		val persisted = (1..semanticRevisions).map { ordinal ->
			persistedRevision(
				segmentId = segmentId,
				semanticRevision = ordinal.toLong(),
				complete = ordinal == 2,
				sessionRunEffectEndNanos = sessionRunEffectEndNanos,
				gapOnly = gapOnly,
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
		gapOnly: Boolean,
	): PersistedTestRevision {
		val logicalWindowId = logicalWindowId(sessionRunEffectEndNanos)
		val fragments = if (gapOnly) {
			listOf(gapFragment(logicalWindowId, semanticRevision, 0, 200L, 400L))
		} else if (complete) {
			listOf(bandFragment(logicalWindowId, semanticRevision, 0, 200L, 400L))
		} else {
			listOf(
				bandFragment(logicalWindowId, semanticRevision, 0, 200L, 300L),
				gapFragment(logicalWindowId, semanticRevision, 1, 300L, 400L),
			)
		}
		val evidence = if (gapOnly) emptyList() else listOf(evidence(logicalWindowId, semanticRevision))
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
			coverage = if (gapOnly) "NONE" else if (complete) "COMPLETE" else "PARTIAL",
			knownActiveDurationNanos = if (gapOnly) 0L else if (complete) 200L else 100L,
			knownInactiveDurationNanos = 0L,
			unknownActivityDurationNanos = 0L,
			unobservedDurationNanos = if (gapOnly) 200L else if (complete) 0L else 100L,
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

	private fun registration(
		active: Boolean,
		registrationGeneration: Long = 1L,
		sourceInstanceId: String = SOURCE_INSTANCE_ID,
		reservedAtMs: Long = 900L,
		reservedElapsedNanos: Long = 90L,
		acceptedAtMs: Long = 1_000L,
		acceptedElapsedNanos: Long = 100L,
		retiredAtMs: Long = 3_500L,
		retiredElapsedNanos: Long = 1_000L,
		callbackBarrierRevision: Long = 1L,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = ACTIVITY_SOURCE,
		registrationGeneration = registrationGeneration,
		sourceInstanceId = sourceInstanceId,
		ownerScope = "source-broker:$ACTIVITY_SOURCE",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-1",
		status = if (active) ProviderRegistrationGenerationEntity.STATUS_ACTIVE else
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = reservedAtMs,
		reservedElapsedRealtimeNanos = reservedElapsedNanos,
		acceptedAtMs = acceptedAtMs,
		acceptedElapsedRealtimeNanos = acceptedElapsedNanos,
		retiredAtMs = retiredAtMs.takeUnless { active },
		retiredElapsedRealtimeNanos = retiredElapsedNanos.takeUnless { active },
		failureCode = null,
		captureCallbackBarrierAuthorizationRevision = callbackBarrierRevision,
	)

	private fun captureStartAction(
		serviceRunId: String = SERVICE_RUN_ID,
		manifestRevision: Long = 1L,
		actionRevision: Long = 1L,
		leaseGeneration: Long = 1L,
		requestedAtMs: Long = 1_000L,
		requestedElapsedNanos: Long = 100L,
		sourceInstanceId: String = SOURCE_INSTANCE_ID,
		registrationGeneration: Long = 1L,
	) = LifecycleDesiredActionEntity(
		actionId = "activity-start-$serviceRunId-$manifestRevision",
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = serviceRunId,
		manifestRevision = manifestRevision,
		actionRevision = actionRevision,
		actionFamily = "SOURCE_RUNTIME",
		sourceKind = ACTIVITY_SOURCE,
		desiredState = "STARTED",
		desiredPlanRevision = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = 0L,
		startOrigin = "MANUAL_FOREGROUND_START",
		bootId = BOOT_ID,
		leaseGeneration = leaseGeneration,
		requestedAtMs = requestedAtMs,
		requestedElapsedRealtimeNanos = requestedElapsedNanos,
		status = "START_ACCEPTED",
		attemptCount = 1,
		acknowledgedAtMs = requestedAtMs,
		acknowledgedElapsedRealtimeNanos = requestedElapsedNanos,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
	)

	private fun activityCompleteness(
		lastAdmissionOrdinal: Long = 1L,
		lastSourceSequence: Long = 1L,
	) = SourceSessionCompletenessEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		sourceKind = ACTIVITY_SOURCE,
		sourceInstanceId = SOURCE_INSTANCE_ID,
		registrationGeneration = 1L,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		lastSourceSequence = lastSourceSequence,
		appDrainComplete = true,
		providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
		stopStatus = "COMPLETE",
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = 3_000L,
	)

	private fun activityProductLane(
		contiguousAdmissionOrdinal: Long = 1L,
		captureAdmissionCutoffOrdinal: Long? = null,
		retentionRequired: Boolean = true,
		status: String = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		terminalDisposition: String? = null,
		terminalAtMs: Long? = null,
	) = SourceProductProjectionLaneEntity(
		sourceKind = ACTIVITY_SOURCE,
		bindingGeneration = 1L,
		projectionId = WRITER_ID,
		projectionVersion = WRITER_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		activatedRolloutRevision = 1L,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = contiguousAdmissionOrdinal,
		captureAdmissionCutoffOrdinal = captureAdmissionCutoffOrdinal,
		retentionRequired = retentionRequired,
		status = status,
		terminalDisposition = terminalDisposition,
		terminalAtMs = terminalAtMs,
		installedAtMs = 900L,
		updatedAtMs = terminalAtMs ?: 3_000L,
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

	private fun activityPlanPayload(): ByteArray = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeInt(1)
			output.writeUTF("ACTIVITY")
			output.writeLong(1L)
			output.writeUTF("TRANSITIONS_ONLY")
			output.writeLong(1_000L)
			output.writeInt(50)
			output.writeInt(1)
			output.writeInt(7)
		}
		buffer.toByteArray()
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
		private val ACTIVITY_LANE_AUTHORITY = SourceProductLaneExecutionAuthority { lane ->
			lane.sourceKind == ACTIVITY_SOURCE && lane.bindingGeneration == 1L &&
				lane.projectionId == WRITER_ID && lane.projectionVersion == WRITER_VERSION
		}
		private const val ACTIVITY_SOURCE = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		private const val WRITER_ID = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID
		private const val WRITER_VERSION = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION
		private const val LOGICAL_TRACKING_ID = "activity-maintenance-logical"
		private const val SERVICE_RUN_ID = "activity-maintenance-run"
		private const val REPLACEMENT_SERVICE_RUN_ID = "activity-maintenance-replacement-run"
		private const val SOURCE_INSTANCE_ID = "activity-maintenance-provider"
		private const val REPLACEMENT_SOURCE_INSTANCE_ID = "activity-maintenance-replacement-provider"
		private const val PHYSICAL_FINGERPRINT =
			"9f472d9529dc1da87eadbc931567884a453cc8dfd499ffb2f7a733520f854a6f"
		private const val AUTHORIZATION_FINGERPRINT = "activity-maintenance-authorization"
		private const val BOOT_ID = "activity-maintenance-boot"
	}
}
