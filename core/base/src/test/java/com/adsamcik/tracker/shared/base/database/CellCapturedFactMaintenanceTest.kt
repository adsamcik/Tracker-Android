package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
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
class CellCapturedFactMaintenanceTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `retention removes owner correction lineage and every dependent`() = runTest {
		val owner = seedCapturedCell(retainedFromMs = FLOOR_MS, semanticRevisions = 2)
		insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			aggregateOwner = owner,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = FLOOR_MS,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Pruned(2, 3)

		database.cellCapturedFactDao().revisionCount() shouldBe 0L
		database.cellCapturedFactDao().cursorCount() shouldBe 0L
		database.cellCapturedFactDao().maintenanceWalCount(CELL_SOURCE) shouldBe 2L
	}

	@Test
	fun `retired Cell registration with production default callback barrier remains authentic`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS)

		database.sourceBrokerDao().registration(
			CELL_SOURCE,
			REGISTRATION_GENERATION,
		)?.captureCallbackBarrierAuthorizationRevision shouldBe 0L
		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = FLOOR_MS,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Pruned(1, 1)
	}

	@Test
	fun `nonzero callback barrier within retained capture authorization blocks retention`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation " +
				"SET capture_callback_barrier_authorization_revision = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf(AUTHORIZATION_REVISION, CELL_SOURCE, REGISTRATION_GENERATION),
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			FLOOR_MS,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `retention removes the complete owner dependency closure when a dependent crosses`() = runTest {
		val owner = seedCapturedCell(
			retainedFromMs = FLOOR_MS,
			observedWallTimeMs = NEWER_WALL_MS,
		)
		insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = OBSERVED_WALL_MS,
			aggregateOwner = owner,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = FLOOR_MS,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Pruned(2, 2)

		database.cellCapturedFactDao().revisionCount() shouldBe 0L
		database.cellCapturedFactDao().cursorCount() shouldBe 0L
	}

	@Test
	fun `forged coverage count cannot reuse a direct aggregate owner`() = runTest {
		val owner = seedCapturedCell(retainedFromMs = 1_000L)
		insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			aggregateOwner = owner,
			acceptedChildCount = 2,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = 1_000L,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 2L
	}

	@Test
	fun `revision overflow blocks before retention mutates a lineage`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS, semanticRevisions = 2)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = FLOOR_MS,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
			limits = CellCapturedMaintenanceLimits(
				revisionPageSize = 1,
				maximumRevisions = 1,
				maximumLogicalFacts = 1,
			),
			checkpoint = {},
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 2L
		database.cellCapturedFactDao().cursorCount() shouldBe 1L
	}

	@Test
	fun `missing retained WAL blocks maintenance instead of trusting fact scalars`() = runTest {
		seedCapturedCell(retainedFromMs = 1_000L)
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_event_wal")

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = 1_000L,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `self rehashed foreign Cell plan blocks retention`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS)
		val foreignPlan = cellPlanPayload(mode = CELL_MODE_OBSERVE_AND_SPARSE_REFRESH)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_desired_plan SET payload = ?, payload_checksum = ? " +
				"WHERE revision = ? AND source_kind = ?",
			arrayOf(foreignPlan, sha256(foreignPlan), PLAN_REVISION, CELL_SOURCE),
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			FLOOR_MS,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
	}

	@Test
	fun `impossible provider registration chronology blocks retention`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET reserved_at_ms = accepted_at_ms + 1 " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf(CELL_SOURCE, REGISTRATION_GENERATION),
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			FLOOR_MS,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
	}

	@Test
	fun `live registration status with stale retirement fields blocks retention`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET status = 'ACTIVE' " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf(CELL_SOURCE, REGISTRATION_GENERATION),
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			FLOOR_MS,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `stale callback barrier beyond retained capture authorization blocks retention`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation " +
				"SET capture_callback_barrier_authorization_revision = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf(AUTHORIZATION_REVISION + 1L, CELL_SOURCE, REGISTRATION_GENERATION),
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			FLOOR_MS,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `consent deletion fences retained WAL after retention removed every fact`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS, revokedCapture = true)
		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = FLOOR_MS,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Pruned(1, 1)

		database.deleteCapturedCellFactsAfterConsentReset(
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			expectedRevokedConsentEpoch = REVOKED_CONSENT_EPOCH,
			deletedAtMs = DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Deleted(0, 0, 1)

		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			CELL_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
		)
		database.sourceDeletionFenceDao().contains(
			CELL_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			digest,
		) shouldBe true
		database.cellCapturedFactDao().deletionGenerationCount() shouldBe 1L
		database.cellCapturedFactDao().maintenanceWalCount(CELL_SOURCE) shouldBe 1L

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS + 1L,
		) shouldBe CellCapturedSourceDeletionResult.AlreadyDeleted
	}

	@Test
	fun `nonterminal direct Cell demand blocks consent deletion`() = runTest {
		seedCapturedCell(revokedCapture = true, directDemandActive = true)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.DIRECT_DEMAND_NOT_QUIESCED,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `cancellation after payload removal rolls back fact fence and generation`() = runTest {
		seedCapturedCell(revokedCapture = true)

		shouldThrow<CancellationException> {
			database.deleteCapturedCellFactsAfterConsentReset(
				expectedCollectedDataEpoch = 0L,
				expectedDeletedSourceEventHighWaterOrdinal = 0L,
				expectedRevokedConsentEpoch = REVOKED_CONSENT_EPOCH,
				deletedAtMs = DELETION_TIME_MS,
				limits = CellCapturedMaintenanceLimits(),
				checkpoint = { point ->
					if (point == CellCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED) {
						throw CancellationException("test cancellation")
					}
				},
			)
		}

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.cellCapturedFactDao().cursorCount() shouldBe 1L
		database.cellCapturedFactDao().deletionGenerationCount() shouldBe 0L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `retention keeps a lineage whose uncertainty lower bound equals the floor`() = runTest {
		val exactLowerBound = OBSERVED_WALL_MS - COVERAGE_SPAN_MS - WALL_UNCERTAINTY_MS
		seedCapturedCell(
			retainedFromMs = exactLowerBound,
			coverageSpanNanos = COVERAGE_SPAN_NANOS,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = exactLowerBound,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.NoChange

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.cellCapturedFactDao().cursorCount() shouldBe 1L
	}

	@Test
	fun `retention removes coverage whose oldest uncertainty bound crosses the floor`() = runTest {
		seedCapturedCell(
			retainedFromMs = FLOOR_MS,
			observedWallTimeMs = FLOOR_MS + COVERAGE_SPAN_MS + WALL_UNCERTAINTY_MS - 1L,
			coverageSpanNanos = COVERAGE_SPAN_NANOS,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = FLOOR_MS,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Pruned(1, 1)

		database.cellCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `cancellation after retention payload removal rolls back the whole lineage`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS, semanticRevisions = 2)

		shouldThrow<CancellationException> {
			database.pruneCapturedCellFactsAffectedByRetentionFloor(
				beforeMs = FLOOR_MS,
				expectedCollectedDataEpoch = 0L,
				expectedDeletedSourceEventHighWaterOrdinal = 0L,
				markedAtMs = MAINTENANCE_TIME_MS,
				limits = CellCapturedMaintenanceLimits(),
				checkpoint = { point ->
					if (point == CellCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED) {
						throw CancellationException("test retention cancellation")
					}
				},
			)
		}

		database.cellCapturedFactDao().revisionCount() shouldBe 2L
		database.cellCapturedFactDao().cursorCount() shouldBe 1L
	}

	@Test
	fun `nonterminal Cell registration blocks consent deletion`() = runTest {
		seedCapturedCell(revokedCapture = true)
		database.sourceBrokerDao().insertRegistration(
			registration().copy(
				registrationGeneration = 2L,
				sourceInstanceId = "active-cell-instance",
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
			),
		)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `current eligible capture consent blocks source deletion`() = runTest {
		seedCapturedCell(revokedCapture = false)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `foreign writer payload blocks deletion before any mutation`() = runTest {
		seedCapturedCell(revokedCapture = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET writer_projection_id = 'foreign-writer'",
		)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `orphan cursor blocks deletion before any mutation`() = runTest {
		seedCapturedCell(revokedCapture = true)
		database.openHelper.writableDatabase.execSQL("DELETE FROM cell_captured_fact_revision")

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.cellCapturedFactDao().cursorCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `self-consistent WAL scope tamper after retention cannot receive a deletion fence`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS, revokedCapture = true)
		val retainedWal = requireNotNull(
			database.sourceEventWalDao().getByEventId("cell-event-1"),
		)
		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = FLOOR_MS,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Pruned(1, 1)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = 'cell-event-1'",
		)
		val tamperedUnsigned = retainedWal.copy(
			serviceRunId = "forged-service-run",
			integrityIdentity = ZERO_CHECKSUM,
		)
		val tampered = tamperedUnsigned.copy(
			integrityIdentity = tamperedUnsigned.calculatedIntegrityIdentity(),
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(tampered) shouldBe
			retainedWal.admissionOrdinal

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.cellCapturedFactDao().deletionGenerationCount() shouldBe 0L
	}

	@Test
	fun `WAL only scope with self rehashed foreign plan cannot receive a deletion fence`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS, revokedCapture = true)
		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			FLOOR_MS,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Pruned(1, 1)
		val foreignPlan = cellPlanPayload(mode = CELL_MODE_OBSERVE_AND_SPARSE_REFRESH)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_desired_plan SET payload = ?, payload_checksum = ? " +
				"WHERE revision = ? AND source_kind = ?",
			arrayOf(foreignPlan, sha256(foreignPlan), PLAN_REVISION, CELL_SOURCE),
		)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.cellCapturedFactDao().deletionGenerationCount() shouldBe 0L
	}

	@Test
	fun `WAL only scope with impossible provider chronology cannot receive a deletion fence`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS, revokedCapture = true)
		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			FLOOR_MS,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Pruned(1, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation " +
				"SET reserved_elapsed_realtime_nanos = accepted_elapsed_realtime_nanos + 1 " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf(CELL_SOURCE, REGISTRATION_GENERATION),
		)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.cellCapturedFactDao().deletionGenerationCount() shouldBe 0L
	}

	@Test
	fun `source deletion preserves retired control demand and retained Cell WAL`() = runTest {
		seedCapturedCell(revokedCapture = true)
		val retiredControl = demand(active = false).copy(
			demandId = CONTROL_DEMAND_ID,
			consumerId = "control:$SERVICE_RUN_ID",
			purpose = SourceBrokerPurpose.CONTROL_CONTINUATION,
			persistenceEligible = false,
		)
		database.sourceBrokerDao().insertDemands(listOf(retiredControl))

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Deleted(1, 1, 1)

		database.sourceBrokerDao().demandsByIds(listOf(CONTROL_DEMAND_ID)) shouldBe
			listOf(retiredControl)
		database.cellCapturedFactDao().maintenanceWalCount(CELL_SOURCE) shouldBe 1L
	}

	private suspend fun seedCapturedCell(
		retainedFromMs: Long? = null,
		semanticRevisions: Int = 1,
		observedWallTimeMs: Long = OBSERVED_WALL_MS,
		coverageSpanNanos: Long = 0L,
		revokedCapture: Boolean = false,
		directDemandActive: Boolean = false,
	): CellCapturedFactRevisionEntity {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(retainedFromMs = retainedFromMs),
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = CELL_SOURCE,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
				owner = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
				ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				updatedAtMs = RUN_START_WALL_MS,
			),
		)
		installPolicy(revokedCapture)
		installPlan()
		val segmentId = database.sessionSegmentDao().insert(segment())
		installSession(segmentId)
		val demand = demand(directDemandActive)
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(registration())
		val authorization = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = listOf(demand),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
		)
		database.sourceBrokerDao().insertAuthorizations(authorization)
		return insertCapturedFact(
			deliveryIndex = 1,
			observedWallTimeMs = observedWallTimeMs,
			coverageSpanNanos = coverageSpanNanos,
			authorizationFingerprint = authorization.first().authorizationFingerprint,
			semanticRevisions = semanticRevisions,
		)
	}

	private suspend fun insertCapturedFact(
		deliveryIndex: Int,
		observedWallTimeMs: Long,
		authorizationFingerprint: String = requireNotNull(
			database.cellCapturedFactDao().maintenanceAuthorizationMembers(
				CELL_SOURCE,
				REGISTRATION_GENERATION,
				AUTHORIZATION_REVISION,
				2,
			),
		).single().authorizationFingerprint,
		aggregateOwner: CellCapturedFactRevisionEntity? = null,
		acceptedChildCount: Int = 1,
		semanticRevisions: Int = 1,
		coverageSpanNanos: Long = 0L,
	): CellCapturedFactRevisionEntity {
		require(deliveryIndex in 1..9 && semanticRevisions in 1..2 && coverageSpanNanos >= 0L)
		val deliveryIdentity = deliveryIndex.toString().repeat(64)
		val eventId = "cell-event-$deliveryIndex"
		val providerNanos = OBSERVED_NANOS + deliveryIndex
		val coverageStartNanos = providerNanos - coverageSpanNanos
		require(coverageStartNanos > 0L)
		val payload = byteArrayOf(deliveryIndex.toByte(), 1, 2, 3)
		val payloadChecksum = sha256(payload)
		val unsignedWal = SourceEventWalEntity(
			eventId = eventId,
			providerDedupKey = null,
			deliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sourceKind = CELL_SOURCE,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = authorizationFingerprint,
			sourceSequence = deliveryIndex.toLong(),
			configRevision = PLAN_REVISION,
			planAttribution = 0,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = providerNanos,
			observedIntervalStartNanos = coverageStartNanos,
			receivedElapsedNanos = providerNanos + 1L,
			wallTimeMs = observedWallTimeMs,
			wallTimeUncertaintyMs = WALL_UNCERTAINTY_MS,
			capturedCollectedDataEpoch = 0L,
			activityAutomationEpoch = null,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			sessionManifestRevision = MANIFEST_REVISION,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			acquiredAtMs = observedWallTimeMs,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = payloadChecksum,
			createdAtMs = observedWallTimeMs,
		)
		val wal = unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
		val admissionOrdinal = database.sourceEventWalDao().insertIgnoringDuplicate(wal)
		val logicalFactId = CellCapturedFactRevisionIntegrity.logicalFactId(
			deliveryIdentity,
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
			SEGMENT_ID,
			MANIFEST_REVISION,
			0L,
			0L,
		)
		val firstUnsigned = CellCapturedFactRevisionEntity(
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, 1L),
			factKind = if (aggregateOwner == null) {
				CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
			} else {
				CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
			},
			aggregateOwnerLogicalFactId = aggregateOwner?.logicalFactId,
			aggregateOwnerSemanticRevision = aggregateOwner?.semanticRevision,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sessionSegmentId = SEGMENT_ID,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			sourceDeliveryIdentity = deliveryIdentity,
			sourceEventId = eventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			walIntegrityIdentity = wal.integrityIdentity,
			payloadChecksum = payloadChecksum,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			sourceSequence = deliveryIndex.toLong(),
			planAttribution = CellCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION,
			payloadVersion = 1,
			canonicalProviderSemanticsDigest = deliveryIdentity,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = REGISTRATION_GENERATION,
			configurationRevision = PLAN_REVISION,
			physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationFingerprint = authorizationFingerprint,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			manifestRevision = MANIFEST_REVISION,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			collectedDataEpoch = 0L,
			scopeDeletionGeneration = 0L,
			clockDomainId = BOOT_ID,
			storedZoneId = ZONE_ID,
			structuralEpochDay = structuralDay(
				observedWallTimeMs - coverageSpanNanos / NANOS_PER_MILLISECOND - WALL_UNCERTAINTY_MS,
			),
			providerAcceptanceStartNanos = REGISTRATION_START_NANOS,
			providerAcceptanceEndNanos = REGISTRATION_END_NANOS,
			authorizationEffectStartNanos = AUTHORIZATION_START_NANOS,
			authorizationEffectEndNanos = REGISTRATION_END_NANOS,
			consentEffectStartNanos = POLICY_START_NANOS,
			consentEffectEndNanos = REGISTRATION_END_NANOS,
			sessionRunEffectStartNanos = RUN_START_NANOS,
			sessionRunEffectEndNanos = REGISTRATION_END_NANOS,
			deletionEffectStartNanos = RUN_START_NANOS,
			deletionEffectEndNanos = SESSION_END_NANOS,
			maximumObservationAgeNanos = MAXIMUM_OBSERVATION_AGE_NANOS,
			observedIntervalStartNanos = coverageStartNanos,
			observedElapsedNanos = providerNanos,
			receivedElapsedNanos = providerNanos + 1L,
			coverageIntervalStartNanos = coverageStartNanos,
			coverageIntervalEndNanos = providerNanos,
			observedWallTimeMs = observedWallTimeMs,
			wallTimeUncertaintyMs = WALL_UNCERTAINTY_MS,
			acquiredAtMs = observedWallTimeMs,
			createdAtMs = observedWallTimeMs,
			qualityFlags = 0L,
			qualityConfidence = null,
			availability = CellCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE,
			submittedChildCount = acceptedChildCount,
			acceptedChildCount = acceptedChildCount,
			staleChildCount = 0,
			futureTimeChildCount = 0,
			missingTimeChildCount = 0,
			clockUnverifiableChildCount = 0,
			authorityMismatchChildCount = 0,
			unsupportedTechnologyChildCount = 0,
			subscriptionCompleteness =
				CellCapturedFactRevisionEntity.SUBSCRIPTION_COMPLETENESS_UNKNOWN,
			childCompleteness = CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_COMPLETE,
			observationCount = acceptedChildCount.takeIf { aggregateOwner == null },
			registeredObservationCount = acceptedChildCount.takeIf { aggregateOwner == null },
			gsmCount = 0.takeIf { aggregateOwner == null },
			cdmaCount = 0.takeIf { aggregateOwner == null },
			wcdmaCount = 0.takeIf { aggregateOwner == null },
			tdscdmaCount = 0.takeIf { aggregateOwner == null },
			lteCount = acceptedChildCount.takeIf { aggregateOwner == null },
			nrCount = 0.takeIf { aggregateOwner == null },
			qualityUnknownCount = 0.takeIf { aggregateOwner == null },
			qualityNoneOrUnknownCount = 0.takeIf { aggregateOwner == null },
			qualityPoorCount = 0.takeIf { aggregateOwner == null },
			qualityModerateCount = 0.takeIf { aggregateOwner == null },
			qualityGoodCount = acceptedChildCount.takeIf { aggregateOwner == null },
			qualityGreatCount = 0.takeIf { aggregateOwner == null },
			weakObservationCount = 0.takeIf { aggregateOwner == null },
			knownQualityObservationCount = acceptedChildCount.takeIf { aggregateOwner == null },
			allKnownQualityIsWeak = false.takeIf { aggregateOwner == null },
			effectChecksum = ZERO_CHECKSUM,
			appliedAtMs = observedWallTimeMs,
		)
		val first = firstUnsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(firstUnsigned),
		)
		val revisions = buildList {
			add(first)
			if (semanticRevisions == 2) {
				val secondUnsigned = first.copy(
					semanticRevision = 2L,
					supersedesSemanticRevision = 1L,
					mutationId = CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, 2L),
					effectChecksum = ZERO_CHECKSUM,
				)
				add(secondUnsigned.copy(
					effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(secondUnsigned),
				))
			}
		}
		val dao = database.cellCapturedFactDao()
		revisions.forEach { revision -> dao.insertRevision(revision) }
		val latest = revisions.last()
		dao.insertCursor(
			CellCapturedFactCursorEntity(
				writerProjectionId = WRITER_ID,
				writerProjectionVersion = WRITER_VERSION,
				logicalFactId = latest.logicalFactId,
				logicalTrackingId = latest.logicalTrackingId,
				serviceRunId = latest.serviceRunId,
				sessionSegmentId = latest.sessionSegmentId,
				writerOwnerGeneration = latest.writerOwnerGeneration,
				collectedDataEpoch = latest.collectedDataEpoch,
				scopeDeletionGeneration = latest.scopeDeletionGeneration,
				latestSemanticRevision = latest.semanticRevision,
				latestMutationId = latest.mutationId,
				latestEffectChecksum = latest.effectChecksum,
				latestSourceAdmissionOrdinal = latest.sourceAdmissionOrdinal,
				cursorRevision = latest.semanticRevision,
				updatedAtMs = latest.appliedAtMs,
			),
		)
		return latest
	}

	private suspend fun installPolicy(revokedCapture: Boolean) {
		val policies = mutableListOf(historicalPolicy())
		val consents = mutableListOf(historicalConsent())
		if (revokedCapture) {
			policies += revokedPolicy()
			consents += revokedConsent()
		}
		database.sourcePolicyDao().insertPolicies(policies)
		database.sourcePolicyDao().insertConsentEpochs(consents)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = if (revokedCapture) REVOKED_POLICY_REVISION else POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = if (revokedCapture) REVOKED_POLICY_WALL_MS else RUN_START_WALL_MS,
			),
		)
	}

	private suspend fun installPlan() {
		val payload = cellPlanPayload()
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = PLAN_REVISION,
				planId = "cell-plan",
				createdAtMs = RUN_START_WALL_MS,
				status = "EFFECTIVE",
				sourcePolicyRevision = POLICY_REVISION,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(SourceDesiredPlanEntity(PLAN_REVISION, CELL_SOURCE, 1, payload, sha256(payload))),
		)
	}

	private suspend fun installSession(segmentId: Long) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = BOOT_ID,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				cutoffAtMs = SESSION_END_WALL_MS,
				cutoffElapsedNanos = SESSION_END_NANOS,
				completedAtMs = SESSION_END_WALL_MS,
				finalAdmissionOrdinal = 100L,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = SERVICE_RUN_ID,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = "FINALIZED",
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				completedAtMs = SESSION_END_WALL_MS,
				completionReason = "USER_STOP",
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "cell-start",
				startCommandGeneration = 1L,
				preparedManifestRevision = MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = RUN_START_WALL_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = SESSION_END_WALL_MS,
			),
		)
		val source = manifestSource()
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = RUN_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
			zoneId = ZONE_ID,
			automationEpoch = null,
			changeReason = "MANUAL_START",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun manifestSource() = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		manifestRevision = MANIFEST_REVISION,
		sourceKind = CELL_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = QOS_CODE,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
		writerOwner = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = WRITER_ID,
		writerProjectionVersion = WRITER_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
	)

	private fun historicalPolicy() = SourcePolicyEntity(
		policyRevision = POLICY_REVISION,
		sourceKind = CELL_SOURCE,
		enabled = true,
		qosCode = QOS_CODE,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = CONSENT_EPOCH,
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
		effectiveWallTimeMs = RUN_START_WALL_MS,
		changeReason = "TEST_CAPTURE",
	)

	private fun revokedPolicy() = historicalPolicy().copy(
		policyRevision = REVOKED_POLICY_REVISION,
		enabled = false,
		capturePersistenceEligible = false,
		captureConsentEpoch = null,
		effectiveElapsedRealtimeNanos = REVOKED_POLICY_NANOS,
		effectiveWallTimeMs = REVOKED_POLICY_WALL_MS,
		changeReason = "TEST_REVOKE",
	)

	private fun historicalConsent() = SourceConsentEpochEntity(
		sourceKind = CELL_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		epoch = CONSENT_EPOCH,
		eligible = true,
		persistenceEligible = true,
		policyRevision = POLICY_REVISION,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
		effectiveWallTimeMs = RUN_START_WALL_MS,
		changeReason = "TEST_CAPTURE",
	)

	private fun revokedConsent() = historicalConsent().copy(
		epoch = REVOKED_CONSENT_EPOCH,
		eligible = false,
		persistenceEligible = false,
		policyRevision = REVOKED_POLICY_REVISION,
		effectiveElapsedRealtimeNanos = REVOKED_POLICY_NANOS,
		effectiveWallTimeMs = REVOKED_POLICY_WALL_MS,
		changeReason = "TEST_REVOKE",
	)

	private fun demand(active: Boolean) = SourceDemandEntity(
		demandId = DEMAND_ID,
		consumerId = "session:$SERVICE_RUN_ID",
		sourceKind = CELL_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		lifecycleLeaseGeneration = LEASE_GENERATION,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = "cell:v1:change_callbacks",
		adaptiveReductionAllowed = false,
		maximumAgeMs = 1_000L,
		desiredLatencyMs = 1_000L,
		requestedDeliveryLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = RUN_START_WALL_MS,
		status = if (active) SourceDemandEntity.STATUS_ACTIVE else SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID.takeUnless { active },
		retireElapsedRealtimeNanos = SESSION_END_NANOS.takeUnless { active },
		retiredAtMs = SESSION_END_WALL_MS.takeUnless { active },
	)

	private fun registration() = ProviderRegistrationGenerationEntity(
		sourceKind = CELL_SOURCE,
		registrationGeneration = REGISTRATION_GENERATION,
		sourceInstanceId = SOURCE_INSTANCE_ID,
		ownerScope = "source-broker:$CELL_SOURCE",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "cell-process",
		status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = RUN_START_WALL_MS,
		reservedElapsedRealtimeNanos = RUN_START_NANOS,
		acceptedAtMs = RUN_START_WALL_MS,
		acceptedElapsedRealtimeNanos = REGISTRATION_START_NANOS,
		retiredAtMs = SESSION_END_WALL_MS,
		retiredElapsedRealtimeNanos = REGISTRATION_END_NANOS,
		failureCode = null,
		captureCallbackBarrierAuthorizationRevision = 0L,
	)

	private fun segment() = SessionSegment(
		id = SEGMENT_ID,
		startTimeMs = RUN_START_WALL_MS,
		endTimeMs = SESSION_END_WALL_MS,
		distanceM = 0f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = RUN_START_WALL_MS,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
	)

	private fun structuralDay(wallTimeMs: Long): Long = Instant.ofEpochMilli(wallTimeMs)
		.atZone(ZoneId.of(ZONE_ID))
		.toLocalDate()
		.toEpochDay()

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }

	private fun cellPlanPayload(mode: String = CELL_MODE_OBSERVE_CHANGES): ByteArray =
		ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(1)
				output.writeUTF("CELL")
				output.writeLong(PLAN_REVISION)
				output.writeUTF(mode)
				output.writeLong(MINIMUM_REFRESH_INTERVAL_MS)
				output.writeLong(MAXIMUM_ACCEPTABLE_CACHED_AGE_MS)
				output.writeInt(0)
				output.writeLong(BACKOFF_INITIAL_DELAY_MS)
				output.writeLong(BACKOFF_MAXIMUM_DELAY_MS)
				output.writeDouble(BACKOFF_MULTIPLIER)
			}
			buffer.toByteArray()
		}

	private companion object {
		const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
		const val WRITER_ID = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
		const val LOGICAL_TRACKING_ID = "logical-cell-maintenance"
		const val SERVICE_RUN_ID = "run-cell-maintenance"
		const val SOURCE_INSTANCE_ID = "cell-instance"
		const val DEMAND_ID = "cell-demand"
		const val CONTROL_DEMAND_ID = "cell-control-demand"
		const val BOOT_ID = "boot-cell"
		const val ZONE_ID = "Europe/Prague"
		val PHYSICAL_FINGERPRINT = MessageDigest.getInstance("SHA-256")
			.digest(
				listOf("CELL", "CHANGE_CALLBACK", "")
					.joinToString("\u001f")
					.toByteArray(Charsets.UTF_8),
			)
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
		const val PLAN_REVISION = 1L
		const val POLICY_REVISION = 1L
		const val REVOKED_POLICY_REVISION = 2L
		const val CONSENT_EPOCH = 1L
		const val REVOKED_CONSENT_EPOCH = 2L
		const val MANIFEST_REVISION = 1L
		const val LEASE_GENERATION = 1L
		const val REGISTRATION_GENERATION = 1L
		const val AUTHORIZATION_REVISION = 1L
		const val SEGMENT_ID = 1L
		const val QOS_CODE = 2
		const val RUN_START_NANOS = 100_000_000L
		const val REGISTRATION_START_NANOS = 110_000_000L
		const val AUTHORIZATION_START_NANOS = 150_000_000L
		const val OBSERVED_NANOS = 200_000_000L
		const val REGISTRATION_END_NANOS = 500_000_000L
		const val SESSION_END_NANOS = 600_000_000L
		const val REVOKED_POLICY_NANOS = 700_000_000L
		const val POLICY_START_NANOS = 100_000_000L
		const val RUN_START_WALL_MS = 1_000L
		const val OBSERVED_WALL_MS = 2_000L
		const val NEWER_WALL_MS = 2_500L
		const val SESSION_END_WALL_MS = 3_000L
		const val REVOKED_POLICY_WALL_MS = 4_000L
		const val FLOOR_MS = 1_990L
		const val MAINTENANCE_TIME_MS = 5_000L
		const val DELETION_TIME_MS = 6_000L
		const val WALL_UNCERTAINTY_MS = 20L
		const val COVERAGE_SPAN_MS = 50L
		const val COVERAGE_SPAN_NANOS = COVERAGE_SPAN_MS * 1_000_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val MINIMUM_REFRESH_INTERVAL_MS = 60_000L
		const val MAXIMUM_ACCEPTABLE_CACHED_AGE_MS = 1_000L
		const val MAXIMUM_OBSERVATION_AGE_NANOS =
			MAXIMUM_ACCEPTABLE_CACHED_AGE_MS * NANOS_PER_MILLISECOND
		const val BACKOFF_INITIAL_DELAY_MS = 1_000L
		const val BACKOFF_MAXIMUM_DELAY_MS = 60_000L
		const val BACKOFF_MULTIPLIER = 2.0
		const val CELL_MODE_OBSERVE_CHANGES = "OBSERVE_CHANGES"
		const val CELL_MODE_OBSERVE_AND_SPARSE_REFRESH = "OBSERVE_AND_SPARSE_REFRESH"
		const val ZERO_CHECKSUM =
			"0000000000000000000000000000000000000000000000000000000000000000"
	}
}
