package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellProviderDeliveryIdentityFact
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
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
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.canonicalCellProviderDeliveryIdentity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
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
	fun `nonzero callback barrier within retained capture authorization remains authentic`() = runTest {
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
		) shouldBe CellCapturedRetentionResult.Pruned(1, 1)
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
	fun `same count coverage cannot retarget a different radio aggregate`() = runTest {
		seedCapturedCell(retainedFromMs = 1_000L)
		val incompatibleOwner = insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			radioType = CELL_TECHNOLOGY_GSM,
		)
		insertCapturedFact(
			deliveryIndex = 3,
			observedWallTimeMs = NEWER_WALL_MS,
			aggregateOwner = incompatibleOwner,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			1_000L,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `same count coverage cannot retarget a different registered-state aggregate`() = runTest {
		seedCapturedCell(retainedFromMs = 1_000L)
		val incompatibleOwner = insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			registered = false,
		)
		insertCapturedFact(
			deliveryIndex = 3,
			observedWallTimeMs = NEWER_WALL_MS,
			aggregateOwner = incompatibleOwner,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			1_000L,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `same count coverage cannot retarget a different signal quality aggregate`() = runTest {
		seedCapturedCell(retainedFromMs = 1_000L)
		val incompatibleOwner = insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			signalLevelDbm = -115,
		)
		insertCapturedFact(
			deliveryIndex = 3,
			observedWallTimeMs = NEWER_WALL_MS,
			aggregateOwner = incompatibleOwner,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			1_000L,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `self rehashed direct aggregate must still match canonical Cell payload`() = runTest {
		val original = seedCapturedCell(retainedFromMs = 1_000L)
		val forgedUnsigned = original.copy(
			gsmCount = 1,
			lteCount = 0,
			effectChecksum = ZERO_CHECKSUM,
		)
		val forged = forgedUnsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(forgedUnsigned),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET gsm_count = ?, lte_count = ?, effect_checksum = ? " +
				"WHERE writer_projection_id = ? AND writer_projection_version = ? " +
				"AND logical_fact_id = ? AND semantic_revision = ?",
			arrayOf(
				forged.gsmCount,
				forged.lteCount,
				forged.effectChecksum,
				forged.writerProjectionId,
				forged.writerProjectionVersion,
				forged.logicalFactId,
				forged.semanticRevision,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_effect_checksum = ? " +
				"WHERE writer_projection_id = ? AND writer_projection_version = ? AND logical_fact_id = ?",
			arrayOf(
				forged.effectChecksum,
				forged.writerProjectionId,
				forged.writerProjectionVersion,
				forged.logicalFactId,
			),
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			1_000L,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `self rehashed provider semantics cannot retain an old delivery identity`() = runTest {
		val original = seedCapturedCell(
			retainedFromMs = 1_000L,
			coverageSpanNanos = COVERAGE_SPAN_NANOS,
			acceptedChildCount = 3,
		)
		val originalWal = requireNotNull(
			database.sourceEventWalDao().getByEventId(original.sourceEventId),
		)
		val middleProviderTime = original.observedIntervalStartNanos + 1L
		val tamperedObservations = listOf(
			CellTestObservation(
				CELL_TECHNOLOGY_GSM,
				registered = false,
				signalLevelDbm = -115,
				providerTimestampNanos = middleProviderTime,
			),
			CellTestObservation(
				CELL_TECHNOLOGY_LTE,
				registered = true,
				signalLevelDbm = -95,
				providerTimestampNanos = original.observedIntervalStartNanos,
			),
			CellTestObservation(
				CELL_TECHNOLOGY_LTE,
				registered = true,
				signalLevelDbm = -95,
				providerTimestampNanos = original.observedElapsedNanos,
			),
		)
		val tamperedPayload = cellPayload(tamperedObservations)
		(canonicalCellProviderDeliveryIdentity(
			BOOT_ID,
			tamperedObservations.mapNotNull { it.toProviderDeliveryIdentityFactOrNull() },
		) == original.sourceDeliveryIdentity) shouldBe false
		val tamperedPayloadChecksum = sha256(tamperedPayload)
		val tamperedWalUnsigned = originalWal.copy(
			payload = tamperedPayload,
			payloadChecksum = tamperedPayloadChecksum,
			integrityIdentity = ZERO_CHECKSUM,
		)
		val tamperedWal = tamperedWalUnsigned.copy(
			integrityIdentity = tamperedWalUnsigned.calculatedIntegrityIdentity(),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ?, payload_checksum = ?, integrity_identity = ? " +
				"WHERE event_id = ?",
			arrayOf(
				tamperedPayload,
				tamperedPayloadChecksum,
				tamperedWal.integrityIdentity,
				original.sourceEventId,
			),
		)
		val tamperedFactUnsigned = original.copy(
			walIntegrityIdentity = tamperedWal.integrityIdentity,
			payloadChecksum = tamperedPayloadChecksum,
			registeredObservationCount = 2,
			gsmCount = 1,
			lteCount = 2,
			qualityPoorCount = 1,
			qualityGoodCount = 2,
			weakObservationCount = 1,
			effectChecksum = ZERO_CHECKSUM,
		)
		val tamperedFact = tamperedFactUnsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(tamperedFactUnsigned),
		)
		tamperedWal.deliveryIdentity shouldBe original.sourceDeliveryIdentity
		tamperedFact.sourceDeliveryIdentity shouldBe original.sourceDeliveryIdentity
		tamperedFact.logicalFactId shouldBe original.logicalFactId
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET wal_integrity_identity = ?, payload_checksum = ?, " +
				"registered_observation_count = ?, gsm_count = ?, lte_count = ?, " +
				"quality_poor_count = ?, quality_good_count = ?, weak_observation_count = ?, " +
				"effect_checksum = ? WHERE writer_projection_id = ? AND writer_projection_version = ? " +
				"AND logical_fact_id = ? AND semantic_revision = ?",
			arrayOf(
				tamperedFact.walIntegrityIdentity,
				tamperedFact.payloadChecksum,
				tamperedFact.registeredObservationCount,
				tamperedFact.gsmCount,
				tamperedFact.lteCount,
				tamperedFact.qualityPoorCount,
				tamperedFact.qualityGoodCount,
				tamperedFact.weakObservationCount,
				tamperedFact.effectChecksum,
				tamperedFact.writerProjectionId,
				tamperedFact.writerProjectionVersion,
				tamperedFact.logicalFactId,
				tamperedFact.semanticRevision,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_effect_checksum = ? " +
				"WHERE writer_projection_id = ? AND writer_projection_version = ? AND logical_fact_id = ?",
			arrayOf(
				tamperedFact.effectChecksum,
				tamperedFact.writerProjectionId,
				tamperedFact.writerProjectionVersion,
				tamperedFact.logicalFactId,
			),
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			1_000L,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.cellCapturedFactDao().cursorCount() shouldBe 1L
	}

	@Test
	fun `coverage cannot retarget an owner across provider registration generations`() = runTest {
		val owner = seedCapturedCell(retainedFromMs = 1_000L)
		database.sourceBrokerDao().insertRegistration(
			registration(
				generation = REPLACEMENT_REGISTRATION_GENERATION,
				sourceInstanceId = SOURCE_INSTANCE_ID,
			),
		)
		val replacementAuthorization = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = listOf(demand(active = false)),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
		)
		database.sourceBrokerDao().insertAuthorizations(replacementAuthorization)
		insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			aggregateOwner = owner,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
			authorizationFingerprint = replacementAuthorization.single().authorizationFingerprint,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			1_000L,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `coverage cannot retarget an owner across authorization revisions`() = runTest {
		val originalOwner = seedCapturedCell(retainedFromMs = 1_000L)
		val laterAuthorization = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = SECOND_AUTHORIZATION_REVISION,
			demands = listOf(demand(active = false)),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = SECOND_AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = NEWER_WALL_MS,
		)
		database.sourceBrokerDao().insertAuthorizations(laterAuthorization)
		val correctedOwnerUnsigned = originalOwner.copy(
			authorizationEffectEndNanos = SECOND_AUTHORIZATION_START_NANOS,
			consentEffectEndNanos = SECOND_AUTHORIZATION_START_NANOS,
			effectChecksum = ZERO_CHECKSUM,
		)
		val correctedOwner = correctedOwnerUnsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(correctedOwnerUnsigned),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET authorization_effect_end_nanos = ?, " +
				"consent_effect_end_nanos = ?, effect_checksum = ? WHERE writer_projection_id = ? " +
				"AND writer_projection_version = ? AND logical_fact_id = ? AND semantic_revision = ?",
			arrayOf(
				correctedOwner.authorizationEffectEndNanos,
				correctedOwner.consentEffectEndNanos,
				correctedOwner.effectChecksum,
				correctedOwner.writerProjectionId,
				correctedOwner.writerProjectionVersion,
				correctedOwner.logicalFactId,
				correctedOwner.semanticRevision,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_effect_checksum = ? " +
				"WHERE writer_projection_id = ? AND writer_projection_version = ? AND logical_fact_id = ?",
			arrayOf(
				correctedOwner.effectChecksum,
				correctedOwner.writerProjectionId,
				correctedOwner.writerProjectionVersion,
				correctedOwner.logicalFactId,
			),
		)
		insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			providerNanos = SECOND_AUTHORIZATION_START_NANOS + 1L,
			aggregateOwner = correctedOwner,
			authorizationFingerprint = laterAuthorization.single().authorizationFingerprint,
			authorizationRevision = SECOND_AUTHORIZATION_REVISION,
			authorizationEffectStartNanos = SECOND_AUTHORIZATION_START_NANOS,
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			1_000L,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `coverage must reference the current aggregate owner revision`() = runTest {
		val currentOwner = seedCapturedCell(retainedFromMs = 1_000L, semanticRevisions = 2)
		insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			aggregateOwner = currentOwner.copy(semanticRevision = 1L),
		)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			1_000L,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `canonical payload with missing provider time blocks maintenance`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS, missingTimeChildCount = 1)

		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			FLOOR_MS,
			0L,
			0L,
			MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Blocked(
			CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.cellCapturedFactDao().cursorCount() shouldBe 1L
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
	fun `active Cell registration without authorization is unverifiable for consent deletion`() = runTest {
		seedCapturedCell(revokedCapture = true)
		database.sourceBrokerDao().insertRegistration(
			registration().copy(
				registrationGeneration = 2L,
				sourceInstanceId = "active-cell-instance",
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `newer CONTROL-only Cell WAL survives post-barrier capture consent deletion`() = runTest {
		seedCapturedCell(revokedCapture = true)
		val control = installActiveControlOnlyProvider(
			captureBarrierRevision = SECOND_AUTHORIZATION_REVISION,
		)
		val controlRegistration = requireNotNull(database.sourceBrokerDao().registration(
			CELL_SOURCE,
			REPLACEMENT_REGISTRATION_GENERATION,
		))
		val controlWal = insertNewerControlOnlyWal()

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Deleted(1, 1, 1)

		database.sourceBrokerDao().demandsByIds(listOf(control.demandId)) shouldBe listOf(control)
		requireNotNull(database.sourceBrokerDao().registration(
			CELL_SOURCE,
			REPLACEMENT_REGISTRATION_GENERATION,
		)) shouldBe controlRegistration
		database.cellCapturedFactDao().maintenanceWalCount(CELL_SOURCE) shouldBe 2L
		requireNotNull(database.sourceEventWalDao().getByEventId(CONTROL_WAL_EVENT_ID)).let { retained ->
			retained.configRevision shouldBe PLAN_REVISION
			retained.planAttribution shouldBe RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION
			retained.logicalTrackingId shouldBe null
			retained.serviceRunId shouldBe null
			retained.sourcePolicyRevision shouldBe null
			retained.captureConsentEpoch shouldBe null
			retained.sessionManifestRevision shouldBe null
			retained.lifecycleLeaseGeneration shouldBe null
			retained.authorizationFingerprint shouldBe controlWal.authorizationFingerprint
			retained.createdAtMs shouldBe CONTROL_WAL_CREATED_AT_MS
			retained.payloadChecksum shouldBe controlWal.payloadChecksum
			retained.integrityIdentity shouldBe controlWal.integrityIdentity
			retained.payload.contentEquals(controlWal.payload) shouldBe true
			retained.copy(
				admissionOrdinal = controlWal.admissionOrdinal,
				payload = controlWal.payload,
			) shouldBe controlWal
		}
		val scopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			CELL_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
		)
		database.sourceDeletionFenceDao().contains(
			CELL_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeDigest,
		) shouldBe true
		database.cellCapturedFactDao().deletionGeneration(
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
		)?.generation shouldBe 1L
		database.cellCapturedFactDao().revisionCount() shouldBe 0L
		database.cellCapturedFactDao().cursorCount() shouldBe 0L
	}

	@Test
	fun `CONTROL-only provider without exact callback barrier blocks capture deletion`() = runTest {
		seedCapturedCell(revokedCapture = true)
		val control = installActiveControlOnlyProvider(captureBarrierRevision = 0L)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
		)

		database.sourceBrokerDao().demandsByIds(listOf(control.demandId)) shouldBe listOf(control)
		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `callback barrier beyond Cell capture history is unverifiable`() = runTest {
		seedCapturedCell(revokedCapture = true)
		installActiveControlOnlyProvider(captureBarrierRevision = CONTROL_AUTHORIZATION_REVISION)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.cellCapturedFactDao().revisionCount() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `already-fenced demand retains its first boundary while Cell capture is retired`() = runTest {
		seedCapturedCell(revokedCapture = true)
		val demandId = demand(active = false).demandId
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = ?, " +
				"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf(BOOT_ID, REVOKED_POLICY_NANOS, REVOKED_POLICY_WALL_MS, demandId),
		)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Deleted(1, 1, 1)

		database.sourceBrokerDao().demandsByIds(listOf(demandId)).single().let { retired ->
			retired.status shouldBe SourceDemandEntity.STATUS_RETIRED
			retired.retireBootId shouldBe BOOT_ID
			retired.retireElapsedRealtimeNanos shouldBe REVOKED_POLICY_NANOS
			retired.retiredAtMs shouldBe REVOKED_POLICY_WALL_MS
		}
	}

	@Test
	fun `impossible capture-demand retirement chronology rolls back deletion`() = runTest {
		seedCapturedCell(revokedCapture = true)
		val demandId = demand(active = false).demandId
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = ?, " +
				"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf(BOOT_ID, RUN_START_NANOS - 1L, REVOKED_POLICY_WALL_MS, demandId),
		)

		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		database.sourceBrokerDao().demandsByIds(listOf(demandId)).single().status shouldBe
			SourceDemandEntity.STATUS_RETIRING
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
			database.sourceEventWalDao().getByEventId("cell-event-0"),
		)
		database.pruneCapturedCellFactsAffectedByRetentionFloor(
			beforeMs = FLOOR_MS,
			expectedCollectedDataEpoch = 0L,
			expectedDeletedSourceEventHighWaterOrdinal = 0L,
			markedAtMs = MAINTENANCE_TIME_MS,
		) shouldBe CellCapturedRetentionResult.Pruned(1, 1)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = 'cell-event-0'",
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

	@Test
	fun `portable export emits only authenticated identity-free Cell product evidence`() = runTest {
		val firstIngress = seedCapturedCell(semanticRevisions = 2, withPortableExportState = true)
		var emitted: PortableCapturedCellEntryV1? = null
		var sinkObservedRoomTransaction: Boolean? = null
		firstIngress.sourceSequence shouldBe 0L

		val result = portableExporter().export(
			ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID),
		) { entry ->
			sinkObservedRoomTransaction = database.openHelper.writableDatabase.inTransaction()
			emitted = entry
		}

		val entry = requireNotNull(emitted)
		result shouldBe ExportPortableCapturedCellResult.Exported(
			entry.identity,
			entry.contentChecksum,
			1,
			1,
		)
		entry.format shouldBe CellCapturedPortableFormatV1.FORMAT
		entry.schemaVersion shouldBe CellCapturedPortableFormatV1.SCHEMA_VERSION
		entry.sessionMode shouldBe PortableCellSessionMode.MANUAL
		entry.subscriptionGrouping shouldBe PortableCellSubscriptionGrouping.UNKNOWN
		entry.runs.single().captureCoverage shouldBe PortableCellCaptureCoverage.WHOLE_RUN
		entry.runs.single().availability shouldBe PortableCellRunAvailability.RETAINED
		entry.runs.single().acquisitionCompleteness shouldBe
			PortableCellAcquisitionCompleteness.PARTIAL
		entry.runs.single().observations.single().let { observation ->
			observation.semanticRevision shouldBe 2L
			observation.supersedesSemanticRevision shouldBe 1L
			observation.lteCount shouldBe 1
			observation.observationCount shouldBe 1
			observation.subscriptionGrouping shouldBe PortableCellSubscriptionGrouping.UNKNOWN
			observation.contentChecksum shouldBe
				CellCapturedPortableIntegrity.observationChecksum(observation)
		}
		entry.runs.single().contentChecksum shouldBe
			CellCapturedPortableIntegrity.runChecksum(entry.runs.single())
		entry.contentChecksum shouldBe CellCapturedPortableIntegrity.entryChecksum(entry)
		sinkObservedRoomTransaction shouldBe false
		entry.toString().contains(LOGICAL_TRACKING_ID) shouldBe false
		entry.toString().contains(SERVICE_RUN_ID) shouldBe false
		entry.toString().contains(SOURCE_INSTANCE_ID) shouldBe false
		entry.toString().contains(BOOT_ID) shouldBe false
	}

	@Test
	fun `portable export preserves one-hop aggregate reuse without exposing owner identity`() = runTest {
		val owner = seedCapturedCell(withPortableExportState = true)
		insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			aggregateOwner = owner,
		)
		installPortableExportState(throughOrdinal = 2L, lastSourceSequence = 2L)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		val observations = requireNotNull(emitted).runs.single().observations
		observations.size shouldBe 2
		val ownerPortable = observations.single { it.aggregateOwnerIdentity == null }
		val dependent = observations.single { it.aggregateOwnerIdentity != null }
		dependent.aggregateOwnerIdentity shouldBe ownerPortable.identity
		dependent.aggregateOwnerSemanticRevision shouldBe owner.semanticRevision
		dependent.observationCount shouldBe ownerPortable.observationCount
		dependent.lteCount shouldBe ownerPortable.lteCount
	}

	@Test
	fun `portable export retains complete replacement membership without inventing Cell capture`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installNonCellReplacementRun()
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		val runs = requireNotNull(emitted).runs
		runs.size shouldBe 2
		runs[0].captureCoverage shouldBe PortableCellCaptureCoverage.WHOLE_RUN
		runs[0].availability shouldBe PortableCellRunAvailability.RETAINED
		runs[1].captureCoverage shouldBe PortableCellCaptureCoverage.NOT_CAPTURED
		runs[1].availability shouldBe PortableCellRunAvailability.NOT_CAPTURED
		runs[1].observations shouldBe emptyList()
	}

	@Test
	fun `portable export retains partial child and unknown multi SIM truth`() = runTest {
		seedCapturedCell(missingTimeChildCount = 1, withPortableExportState = true)
		database.sourceSessionDao().saveCompleteness(
			portableCompleteness(
				providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				stopStatus = "TIMED_OUT",
				appDrainComplete = false,
			),
		)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		val run = requireNotNull(emitted).runs.single()
		run.acquisitionCompleteness shouldBe PortableCellAcquisitionCompleteness.PARTIAL
		run.subscriptionGrouping shouldBe PortableCellSubscriptionGrouping.UNKNOWN
		run.observations.single().childCompleteness shouldBe PortableCellChildCompleteness.PARTIAL
		run.observations.single().missingTimeChildCount shouldBe 1
	}

	@Test
	fun `portable export ignores retained CONTROL lane WAL`() = runTest {
		seedCapturedCell(revokedCapture = true, withPortableExportState = true)
		installActiveControlOnlyProvider(AUTHORIZATION_REVISION)
		val controlWal = insertNewerControlOnlyWal()
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single().observations.size shouldBe 1
		database.sourceEventWalDao().getByEventId(controlWal.eventId) shouldBe controlWal
	}

	@Test
	fun `portable export ignores retained AMBIENT-only WAL`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		val ambientWal = insertNewerAmbientOnlyWal()
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single().observations.size shouldBe 1
		database.sourceEventWalDao().getByEventId(ambientWal.eventId) shouldBe ambientWal
	}

	@Test
	fun `portable export reports retention loss before sink IO`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS, withPortableExportState = true)
		var sinkReached = false

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			sinkReached = true
		} shouldBe ExportPortableCapturedCellResult.Unavailable(
			PortableCellUnavailableReason.RETENTION_LIMIT,
		)

		sinkReached shouldBe false
	}

	@Test
	fun `portable export keeps retained observations and marks partial retention truth`() = runTest {
		seedCapturedCell(retainedFromMs = FLOOR_MS, withPortableExportState = true)
		insertCapturedFact(deliveryIndex = 2, observedWallTimeMs = NEWER_WALL_MS)
		installPortableExportState(throughOrdinal = 2L, lastSourceSequence = 2L)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		val run = requireNotNull(emitted).runs.single()
		run.availability shouldBe PortableCellRunAvailability.RETAINED
		run.retentionLoss shouldBe true
		run.observations.size shouldBe 1
		run.observations.single().observedTimeMs shouldBe NEWER_WALL_MS
	}

	@Test
	fun `portable export reports missing selected logical entry before sink IO`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		var sinkReached = false

		portableExporter().export(ExportPortableCapturedCellRequest("missing-cell-entry")) {
			sinkReached = true
		} shouldBe ExportPortableCapturedCellResult.Unavailable(
			PortableCellUnavailableReason.ENTRY_NOT_FOUND,
		)

		sinkReached shouldBe false
	}

	@Test
	fun `portable export reports materializing before sink IO when Cell lane trails`() = runTest {
		seedCapturedCell(withPortableExportState = true, portableLaneThroughOrdinal = 0L)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Materializing
	}

	@Test
	fun `portable export is not blocked by unrelated valid history beyond its selected cap`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		val retainedWal = requireNotNull(database.sourceEventWalDao().getByEventId("cell-event-0"))
		database.withTransaction {
			repeat(PORTABLE_AUDIT_CAP + 1) { index ->
				insertUnrelatedDeletionGeneration(index, includeFence = true)
				insertUnrelatedCellWal(retainedWal, index)
			}
		}
		var emitted: PortableCapturedCellEntryV1? = null

		val result = portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}
		val entry = requireNotNull(emitted)
		result shouldBe ExportPortableCapturedCellResult.Exported(
			entry.identity,
			entry.contentChecksum,
			1,
			1,
		)
	}

	@Test
	fun `portable export excludes unrelated source completeness before applying its bound`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		database.withTransaction {
			repeat(CellCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS + 1) { index ->
				database.sourceSessionDao().saveCompleteness(
					portableCompleteness().copy(
						sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
						sourceInstanceId = "unrelated-steps-$index",
						registrationGeneration = index + 1L,
					),
				)
			}
		}
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single().observations.size shouldBe 1
	}

	@Test
	fun `portable export fails closed at one selected Cell completeness row beyond its bound`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		database.withTransaction {
			repeat(CellCapturedPortableFormatV1.MAX_COMPLETENESS_ROWS) { index ->
				database.sourceSessionDao().saveCompleteness(
					portableCompleteness().copy(
						sourceInstanceId = "cell-completeness-overflow-$index",
						registrationGeneration = index + 2L,
					),
				)
			}
		}

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
	}

	@Test
	fun `portable export ignores unrelated corrupt deletion history`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		insertUnrelatedDeletionGeneration(1, includeFence = false)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single().observations.size shouldBe 1
	}

	@Test
	fun `portable export rejects an orphan reverse segment`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		database.sessionSegmentDao().insert(
			segment().copy(
				id = ORPHAN_SEGMENT_ID,
				serviceRunId = "orphan-cell-run",
			),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a mismatched reverse segment`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_segment SET service_run_id = ? WHERE id = ?",
			arrayOf("mismatched-cell-run", SEGMENT_ID),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a selected logical deletion generation without a captured run`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		insertDeletionGeneration(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = "absent-cell-run",
			includeFence = true,
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export reports a selected terminal projection failure before lane lag`() = runTest {
		seedCapturedCell(
			withPortableExportState = true,
			portableLaneThroughOrdinal = 0L,
			persistCapturedProduct = false,
		)
		database.cellCapturedFactDao().maintenanceWalCount(CELL_SOURCE) shouldBe 1L
		database.cellCapturedFactDao().revisionCount() shouldBe 0L
		database.cellCapturedFactDao().cursorCount() shouldBe 0L
		database.sourceProjectionStateDao().saveFailure(
			SourceProjectionFailureEntity(
				projectionId = WRITER_ID,
				projectionVersion = WRITER_VERSION,
				admissionOrdinal = 1L,
				attemptCount = 1,
				failureCode = "CELL_CLASSIFICATION_STALE",
				terminal = true,
				lastAttemptAtMs = SESSION_END_WALL_MS,
			),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export reports materializing for the same unprojected WAL without failure`() = runTest {
		seedCapturedCell(
			withPortableExportState = true,
			portableLaneThroughOrdinal = 0L,
			persistCapturedProduct = false,
		)
		database.cellCapturedFactDao().maintenanceWalCount(CELL_SOURCE) shouldBe 1L
		database.cellCapturedFactDao().revisionCount() shouldBe 0L
		database.cellCapturedFactDao().cursorCount() shouldBe 0L

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Materializing
	}

	@Test
	fun `portable export accepts a captured replacement with zero callbacks`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		val replacement = requireNotNull(emitted).runs.single { run ->
			run.startTimeMs == REPLACEMENT_RUN_START_WALL_MS
		}
		replacement.captureCoverage shouldBe PortableCellCaptureCoverage.WHOLE_RUN
		replacement.availability shouldBe PortableCellRunAvailability.NO_RETAINED_OBSERVATION
		replacement.acquisitionCompleteness shouldBe PortableCellAcquisitionCompleteness.PARTIAL
	}

	@Test
	fun `portable export finds a doubly rebound Cell WAL and fact behind zero settlement`() = runTest {
		val initialFact = seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		val (wal, admissionOrdinal) = insertReplacementCapturedWal()
		val fact = insertReplacementCapturedFact(initialFact, wal)
		advancePortableLaneThrough(admissionOrdinal)
		val zeroSettlement = database.trackingHistoryReadDao().sourceCompleteness(
			CELL_SOURCE,
			listOf(REPLACEMENT_SERVICE_RUN_ID),
			2,
		).single()
		zeroSettlement.lastAdmissionOrdinal shouldBe null
		zeroSettlement.lastSourceSequence shouldBe null
		val rebound = wal.copy(
			logicalTrackingId = "foreign-cell-entry",
			serviceRunId = "foreign-cell-run",
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		).let { unsigned ->
			unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET logical_tracking_id = ?, service_run_id = ?, " +
				"integrity_identity = ? WHERE event_id = ?",
			arrayOf(
				rebound.logicalTrackingId,
				rebound.serviceRunId,
				rebound.integrityIdentity,
				rebound.eventId,
			),
		)
		val reboundLogicalFactId = CellCapturedFactRevisionIntegrity.logicalFactId(
			fact.sourceDeliveryIdentity,
			requireNotNull(rebound.logicalTrackingId),
			requireNotNull(rebound.serviceRunId),
			fact.sessionSegmentId,
			fact.manifestRevision,
			fact.collectedDataEpoch,
			fact.scopeDeletionGeneration,
		)
		val reboundFactUnsigned = fact.copy(
			logicalFactId = reboundLogicalFactId,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(reboundLogicalFactId, 1L),
			logicalTrackingId = requireNotNull(rebound.logicalTrackingId),
			serviceRunId = requireNotNull(rebound.serviceRunId),
			walIntegrityIdentity = rebound.integrityIdentity,
			effectChecksum = ZERO_CHECKSUM,
		)
		val reboundFact = reboundFactUnsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(reboundFactUnsigned),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET logical_fact_id = ?, mutation_id = ?, " +
				"logical_tracking_id = ?, service_run_id = ?, wal_integrity_identity = ?, " +
				"effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf(
				reboundFact.logicalFactId,
				reboundFact.mutationId,
				reboundFact.logicalTrackingId,
				reboundFact.serviceRunId,
				reboundFact.walIntegrityIdentity,
				reboundFact.effectChecksum,
				fact.logicalFactId,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET logical_fact_id = ?, logical_tracking_id = ?, " +
				"service_run_id = ?, latest_mutation_id = ?, latest_effect_checksum = ? " +
				"WHERE logical_fact_id = ?",
			arrayOf(
				reboundFact.logicalFactId,
				reboundFact.logicalTrackingId,
				reboundFact.serviceRunId,
				reboundFact.mutationId,
				reboundFact.effectChecksum,
				fact.logicalFactId,
			),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export authenticates a complete registration authorization history before filtering`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		val replacementDemand = replacementDemand()
		val reboundDemand = replacementDemand.copy(
			logicalTrackingId = "foreign-cell-entry",
			serviceRunId = "foreign-cell-run",
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = CELL_SOURCE,
				registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
				authorizationRevision = CONTROL_AUTHORIZATION_REVISION,
				demands = listOf(reboundDemand),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = SESSION_END_NANOS,
				effectiveWallTimeMs = SESSION_END_WALL_MS,
			),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export accepts an authenticated unrelated revision after selected capture`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = CELL_SOURCE,
				registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
				authorizationRevision = CONTROL_AUTHORIZATION_REVISION,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = SESSION_END_NANOS,
				effectiveWallTimeMs = SESSION_END_WALL_MS,
			),
		)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single { run ->
			run.startTimeMs == REPLACEMENT_RUN_START_WALL_MS
		}.availability shouldBe PortableCellRunAvailability.NO_RETAINED_OBSERVATION
	}

	@Test
	fun `portable export accepts a captured replacement with only a provider gap`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(unresolvedSequenceStart = 1L, unresolvedSequenceEnd = 2L)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		val replacement = requireNotNull(emitted).runs.single { run ->
			run.startTimeMs == REPLACEMENT_RUN_START_WALL_MS
		}
		replacement.availability shouldBe PortableCellRunAvailability.NO_RETAINED_OBSERVATION
		replacement.acquisitionCompleteness shouldBe PortableCellAcquisitionCompleteness.PARTIAL
	}

	@Test
	fun `portable export accepts a drain complete positive generation provider failure`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			stopStatus = "PROVIDER_FAILED",
			appDrainComplete = true,
		)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single { run ->
			run.startTimeMs == REPLACEMENT_RUN_START_WALL_MS
		}.availability shouldBe PortableCellRunAvailability.NO_RETAINED_OBSERVATION
	}

	@Test
	fun `portable export requires an exact registration for a zero callback settlement`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(includeRegistration = false)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export requires exact authorization for a zero callback settlement`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(includeAuthorization = false)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export requires the authorized persistent demand for a zero callback settlement`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(includeDemand = false)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a self-consistent noncanonical capture consumer`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			demandConsumerId = "session:$REPLACEMENT_SERVICE_RUN_ID",
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a reused source authorization revision`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(authorizationRevision = AUTHORIZATION_REVISION)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a forged registration retirement reason`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET failure_code = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf("FORGED_STOP", CELL_SOURCE, REPLACEMENT_REGISTRATION_GENERATION),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects an overlapping same boot Cell replacement`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET reserved_elapsed_realtime_nanos = ? " +
				"WHERE source_kind = ? " +
				"AND registration_generation = ?",
			arrayOf(
				REGISTRATION_END_NANOS - 2L,
				CELL_SOURCE,
				REPLACEMENT_REGISTRATION_GENERATION,
			),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a new Cell source instance within the same boot and epoch`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(sourceInstanceId = "forged-cell-instance")

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `failed generation cannot hide an overlapping accepted Cell predecessor`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION + 1L,
		)
		insertFailedCellRegistration(
			generation = REPLACEMENT_REGISTRATION_GENERATION,
			reservedElapsedRealtimeNanos = REGISTRATION_END_NANOS + 1L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET reserved_elapsed_realtime_nanos = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf(
				REGISTRATION_END_NANOS - 1L,
				CELL_SOURCE,
				REPLACEMENT_REGISTRATION_GENERATION + 1L,
			),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `failed generation cannot hide a different accepted Cell predecessor instance`() = runTest {
		val selectedSourceInstanceId = "forged-cell-instance"
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION + 1L,
			sourceInstanceId = selectedSourceInstanceId,
		)
		insertFailedCellRegistration(
			generation = REPLACEMENT_REGISTRATION_GENERATION,
			sourceInstanceId = selectedSourceInstanceId,
			reservedElapsedRealtimeNanos = REGISTRATION_END_NANOS + 1L,
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `failed generation cannot hide an overlapping accepted Cell successor`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		insertFailedCellRegistration(
			generation = REPLACEMENT_REGISTRATION_GENERATION + 1L,
			reservedElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
		)
		insertAcceptedCellRegistration(
			generation = REPLACEMENT_REGISTRATION_GENERATION + 2L,
			reservedElapsedRealtimeNanos = SESSION_END_NANOS - 1L,
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `failed generation cannot hide a different accepted Cell successor instance`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		insertFailedCellRegistration(
			generation = REPLACEMENT_REGISTRATION_GENERATION + 1L,
			reservedElapsedRealtimeNanos = SESSION_END_NANOS + 1L,
		)
		insertAcceptedCellRegistration(
			generation = REPLACEMENT_REGISTRATION_GENERATION + 2L,
			sourceInstanceId = "forged-cell-successor-instance",
			reservedElapsedRealtimeNanos = SESSION_END_NANOS + 2L,
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export accepts a generation zero complete coordinator fallback`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			registrationGeneration = 0L,
			sourceInstanceId = "not-owned-cell",
			stopStatus = "COMPLETE",
			appDrainComplete = true,
		)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single { run ->
			run.startTimeMs == REPLACEMENT_RUN_START_WALL_MS
		}.availability shouldBe PortableCellRunAvailability.NO_RETAINED_OBSERVATION
	}

	@Test
	fun `portable export accepts a generation zero timed out coordinator fallback`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			registrationGeneration = 0L,
			sourceInstanceId = "unresolved-cell",
			stopStatus = "TIMED_OUT",
			appDrainComplete = false,
		)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single { run ->
			run.startTimeMs == REPLACEMENT_RUN_START_WALL_MS
		}.acquisitionCompleteness shouldBe PortableCellAcquisitionCompleteness.PARTIAL
	}

	@Test
	fun `portable export accepts a generation zero unavailable provider failure`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			registrationGeneration = 0L,
			sourceInstanceId = "unavailable-cell",
			stopStatus = "PROVIDER_FAILED",
			appDrainComplete = true,
		)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single { run ->
			run.startTimeMs == REPLACEMENT_RUN_START_WALL_MS
		}.availability shouldBe PortableCellRunAvailability.NO_RETAINED_OBSERVATION
	}

	@Test
	fun `portable export rejects a generation zero synthetic status mismatch`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			registrationGeneration = 0L,
			sourceInstanceId = "not-owned-cell",
			stopStatus = "PROVIDER_FAILED",
			appDrainComplete = true,
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects callback high water on a generation zero fallback`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			registrationGeneration = 0L,
			sourceInstanceId = "not-owned-cell",
		)
		database.sourceSessionDao().saveCompleteness(
			replacementCompleteness(
				registrationGeneration = 0L,
				sourceInstanceId = "not-owned-cell",
			).copy(lastAdmissionOrdinal = 1L, lastSourceSequence = 1L),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a provider gap on a generation zero fallback`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			registrationGeneration = 0L,
			sourceInstanceId = "unresolved-cell",
			unresolvedSequenceStart = 1L,
			unresolvedSequenceEnd = 1L,
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects impossible positive generation stop shapes`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		listOf(
			"PROVIDER_FAILED" to false,
			"PERMISSION_LOST" to true,
			"PROCESS_RESTARTED" to true,
		).forEach { (stopStatus, appDrainComplete) ->
			database.sourceSessionDao().saveCompleteness(
				replacementCompleteness(
					stopStatus = stopStatus,
					appDrainComplete = appDrainComplete,
				),
			)

			exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
				PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
			)
		}
	}

	@Test
	fun `portable export rejects fabricated callback-complete Cell coverage`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		database.sourceSessionDao().saveCompleteness(
			portableCompleteness(providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER"),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export accepts duplicate-only replay across Cell generations`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun()
		database.sourceSessionDao().saveCompleteness(
			replacementCompleteness().copy(
				lastAdmissionOrdinal = 1L,
				lastSourceSequence = 1L,
			),
		)
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single { run ->
			run.startTimeMs == REPLACEMENT_RUN_START_WALL_MS
		}.observations shouldBe emptyList()
	}

	@Test
	fun `portable export rejects a zero callback sequence high water`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		database.sourceSessionDao().saveCompleteness(
			portableCompleteness(lastAdmissionOrdinal = 1L, lastSourceSequence = 0L),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a zero callback sequence gap boundary`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installCapturedReplacementRun(
			unresolvedSequenceStart = 0L,
			unresolvedSequenceEnd = 1L,
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a selected fact whose WAL is missing`() = runTest {
		val fact = seedCapturedCell(withPortableExportState = true)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = ?",
			arrayOf(fact.sourceEventId),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a settled selected WAL without its fact`() = runTest {
		val fact = seedCapturedCell(withPortableExportState = true)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM cell_captured_fact_cursor WHERE logical_fact_id = ?",
			arrayOf(fact.logicalFactId),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM cell_captured_fact_revision WHERE logical_fact_id = ?",
			arrayOf(fact.logicalFactId),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export reports partial capture across manifest revisions`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		installControlOnlyReconciliationManifest()
		var emitted: PortableCapturedCellEntryV1? = null

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			emitted = it
		}

		requireNotNull(emitted).runs.single().captureCoverage shouldBe
			PortableCellCaptureCoverage.PARTIAL_RUN
	}

	@Test
	fun `portable export rejects a retained aggregate dependent whose owner crossed retention`() = runTest {
		val owner = seedCapturedCell(
			retainedFromMs = FLOOR_MS,
			withPortableExportState = true,
		)
		insertCapturedFact(
			deliveryIndex = 2,
			observedWallTimeMs = NEWER_WALL_MS,
			aggregateOwner = owner,
		)
		installPortableExportState(throughOrdinal = 2L, lastSourceSequence = 2L)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unavailable(
			PortableCellUnavailableReason.RETENTION_LIMIT,
		)
	}

	@Test
	fun `portable export rejects a corrupt selected deletion fence`() = runTest {
		seedCapturedCell(revokedCapture = true, withPortableExportState = true)
		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Deleted(1, 1, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_deletion_fence SET effect_checksum = ? WHERE source_kind = ?",
			arrayOf(ZERO_CHECKSUM, CELL_SOURCE),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable export rejects a deletion generation that disagrees with its fence`() = runTest {
		seedCapturedCell(revokedCapture = true, withPortableExportState = true)
		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Deleted(1, 1, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_capture_deletion_generation SET generation = generation + 1 " +
				"WHERE logical_tracking_id = ? AND service_run_id = ?",
			arrayOf(LOGICAL_TRACKING_ID, SERVICE_RUN_ID),
		)

		exportExpectingNoSink() shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}

	@Test
	fun `portable Cell ordinary sink failure propagates`() = runTest {
		seedCapturedCell(withPortableExportState = true)

		shouldThrow<IllegalStateException> {
			portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
				throw IllegalStateException("portable sink failed")
			}
		}
	}

	@Test
	fun `portable export rejects an executable lane not owned by this binary`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		val exporter = RoomExportPortableCapturedCell(
			database,
			SourceProductLaneExecutionAuthority { false },
			Dispatchers.Unconfined,
		)
		var sinkReached = false

		exporter.export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			sinkReached = true
		} shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.WRITER_AUTHORITY_UNVERIFIABLE,
		)

		sinkReached shouldBe false
	}

	@Test
	fun `portable export fails typed before sink IO when replacement membership exceeds its bound`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		repeat(CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) { index ->
			insertOverflowReplacementMembership(index + 1)
		}
		var sinkReached = false

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			sinkReached = true
		} shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.DEPENDENCY_OVERFLOW,
		)

		sinkReached shouldBe false
	}

	@Test
	fun `portable export fails closed on corrupt correction before sink IO`() = runTest {
		val fact = seedCapturedCell(withPortableExportState = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET effect_checksum = ? " +
				"WHERE logical_fact_id = ? AND semantic_revision = ?",
			arrayOf(ZERO_CHECKSUM, fact.logicalFactId, fact.semanticRevision),
		)
		var sinkReached = false

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			sinkReached = true
		} shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		sinkReached shouldBe false
	}

	@Test
	fun `portable export fails closed when facts belong to an older source epoch`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET collected_data_epoch = collected_data_epoch + 1, " +
				"revision = revision + 1 WHERE id = 1",
		)
		var sinkReached = false

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			sinkReached = true
		} shouldBe ExportPortableCapturedCellResult.Unverifiable(
			PortableCellUnverifiableReason.FACT_AUTHORITY_UNVERIFIABLE,
		)

		sinkReached shouldBe false
	}

	@Test
	fun `portable export reports exact deleted Cell scope and cannot emit stale facts`() = runTest {
		seedCapturedCell(revokedCapture = true, withPortableExportState = true)
		database.deleteCapturedCellFactsAfterConsentReset(
			0L,
			0L,
			REVOKED_CONSENT_EPOCH,
			DELETION_TIME_MS,
		) shouldBe CellCapturedSourceDeletionResult.Deleted(1, 1, 1)
		var sinkReached = false

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			sinkReached = true
		} shouldBe ExportPortableCapturedCellResult.Deleted

		sinkReached shouldBe false
	}

	@Test
	fun `portable Cell replay is deterministic`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		val emitted = mutableListOf<PortableCapturedCellEntryV1>()

		repeat(2) {
			portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) { entry ->
				emitted += entry
			}
		}

		emitted.size shouldBe 2
		emitted[0] shouldBe emitted[1]
	}

	@Test
	fun `portable Cell sink cancellation propagates without being retyped`() = runTest {
		seedCapturedCell(withPortableExportState = true)

		shouldThrow<CancellationException> {
			portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
				throw CancellationException("cancel portable sink")
			}
		}
	}

	@Test
	fun `portable Cell closed storage is retryable and never reaches sink`() = runTest {
		seedCapturedCell(withPortableExportState = true)
		database.close()
		var sinkReached = false

		portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			sinkReached = true
		} shouldBe ExportPortableCapturedCellResult.RetryableFailure(
			PortableCellRetryableReason.STORAGE_UNAVAILABLE,
		)

		sinkReached shouldBe false
	}

	private suspend fun seedCapturedCell(
		retainedFromMs: Long? = null,
		semanticRevisions: Int = 1,
		missingTimeChildCount: Int = 0,
		observedWallTimeMs: Long = OBSERVED_WALL_MS,
		coverageSpanNanos: Long = 0L,
		acceptedChildCount: Int? = null,
		revokedCapture: Boolean = false,
		directDemandActive: Boolean = false,
		withPortableExportState: Boolean = false,
		portableLaneThroughOrdinal: Long? = null,
		persistCapturedProduct: Boolean = true,
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
		val fact = insertCapturedFact(
			deliveryIndex = 0,
			observedWallTimeMs = observedWallTimeMs,
			coverageSpanNanos = coverageSpanNanos,
			acceptedChildCount = acceptedChildCount ?: if (coverageSpanNanos == 0L) 1 else 2,
			authorizationFingerprint = authorization.first().authorizationFingerprint,
			semanticRevisions = semanticRevisions,
			missingTimeChildCount = missingTimeChildCount,
			persistProduct = persistCapturedProduct,
		)
		if (withPortableExportState) {
			installPortableExportState(
				throughOrdinal = portableLaneThroughOrdinal ?: fact.sourceAdmissionOrdinal,
				lastSourceSequence = 1L,
			)
		}
		return fact
	}

	private fun portableExporter() = RoomExportPortableCapturedCell(
		database,
		SourceProductLaneExecutionAuthority { lane ->
			lane.sourceKind == CELL_SOURCE && lane.projectionId == WRITER_ID &&
				lane.projectionVersion == WRITER_VERSION &&
				lane.bindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION
		},
		Dispatchers.Unconfined,
	)

	private suspend fun exportExpectingNoSink(): ExportPortableCapturedCellResult {
		var sinkReached = false
		val result = portableExporter().export(ExportPortableCapturedCellRequest(LOGICAL_TRACKING_ID)) {
			sinkReached = true
		}
		sinkReached shouldBe false
		return result
	}

	private suspend fun insertUnrelatedDeletionGeneration(index: Int, includeFence: Boolean) {
		insertDeletionGeneration(
			logicalTrackingId = "unrelated-cell-entry-$index",
			serviceRunId = "unrelated-cell-run-$index",
			includeFence = includeFence,
			updatedAtMs = DELETION_TIME_MS + index,
		)
	}

	private suspend fun insertUnrelatedCellWal(retained: SourceEventWalEntity, index: Int) {
		val unsigned = retained.copy(
			admissionOrdinal = 0L,
			eventId = "unrelated-cell-event-$index",
			deliveryIdentity = sha256("unrelated-cell-delivery-$index".toByteArray()),
			logicalTrackingId = "unrelated-cell-entry-$index",
			serviceRunId = "unrelated-cell-run-$index",
			sourceInstanceId = "unrelated-cell-instance-$index",
			registrationGeneration = UNRELATED_REGISTRATION_GENERATION_BASE + index,
			sourceSequence = 0L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val wal = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		database.sourceEventWalDao().insertIgnoringDuplicate(wal)
	}

	private suspend fun insertDeletionGeneration(
		logicalTrackingId: String,
		serviceRunId: String,
		includeFence: Boolean,
		updatedAtMs: Long = DELETION_TIME_MS,
	) {
		database.cellCapturedFactDao().insertDeletionGeneration(
			CellCaptureDeletionGenerationEntity(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				collectedDataEpoch = 0L,
				generation = 1L,
				updatedAtMs = updatedAtMs,
			),
		)
		if (includeFence) {
			database.sourceDeletionFenceDao().insertIfAbsent(
				SourceDeletionFenceEntity.createLogicalServiceRun(
					sourceKind = CELL_SOURCE,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
					fenceGeneration = 1L,
					collectedDataEpoch = 0L,
					deletedAtMs = updatedAtMs,
				),
			)
		}
	}

	private suspend fun installPortableExportState(
		throughOrdinal: Long,
		lastSourceSequence: Long,
	) {
		val existing = database.sourceProjectionStateDao().productLane(
			CELL_SOURCE,
			SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
			WRITER_ID,
			WRITER_VERSION,
		)
		if (existing == null) {
			database.sourceProjectionStateDao().installProductLane(
				SourceProductProjectionLaneEntity(
					sourceKind = CELL_SOURCE,
					bindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
					projectionId = WRITER_ID,
					projectionVersion = WRITER_VERSION,
					captureModeMask = 1L,
					productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
					activatedRolloutRevision = 1L,
					activationOrdinal = 1L,
					contiguousAdmissionOrdinal = throughOrdinal,
					retentionRequired = true,
					status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
					installedAtMs = RUN_START_WALL_MS,
					updatedAtMs = SESSION_END_WALL_MS,
				),
			)
		} else if (existing.contiguousAdmissionOrdinal != throughOrdinal) {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_product_projection_lane SET contiguous_admission_ordinal = ? " +
					"WHERE source_kind = ? AND binding_generation = ?",
				arrayOf(
					throughOrdinal,
					CELL_SOURCE,
					SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
				),
			)
		}
		database.sourceSessionDao().saveCompleteness(
			portableCompleteness(lastAdmissionOrdinal = throughOrdinal.coerceAtLeast(1L),
				lastSourceSequence = lastSourceSequence),
		)
	}

	private fun advancePortableLaneThrough(throughOrdinal: Long) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane SET contiguous_admission_ordinal = ? " +
				"WHERE source_kind = ? AND binding_generation = ?",
			arrayOf(
				throughOrdinal,
				CELL_SOURCE,
				SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
			),
		)
	}

	private fun portableCompleteness(
		lastAdmissionOrdinal: Long? = 1L,
		lastSourceSequence: Long? = 1L,
		providerCoverage: String = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
		stopStatus: String = "COMPLETE",
		appDrainComplete: Boolean = true,
	) = SourceSessionCompletenessEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		sourceKind = CELL_SOURCE,
		sourceInstanceId = SOURCE_INSTANCE_ID,
		registrationGeneration = REGISTRATION_GENERATION,
		lastAdmissionOrdinal = lastAdmissionOrdinal,
		lastSourceSequence = lastSourceSequence,
		appDrainComplete = appDrainComplete,
		providerCoverage = providerCoverage,
		stopStatus = stopStatus,
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = SESSION_END_WALL_MS,
	)

	private suspend fun installNonCellReplacementRun() {
		closeInitialRunForReplacement()
		val segmentId = database.sessionSegmentDao().insert(
			segment().copy(
				id = REPLACEMENT_SEGMENT_ID,
				startTimeMs = REPLACEMENT_RUN_START_WALL_MS,
				serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = "FINALIZED",
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = REPLACEMENT_RUN_START_WALL_MS,
				startedElapsedNanos = REPLACEMENT_RUN_START_NANOS,
				completedAtMs = SESSION_END_WALL_MS,
				completionReason = "USER_STOP",
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runRevision = 2L,
				startDeliveryToken = "cell-replacement-start",
				startCommandGeneration = 2L,
				preparedManifestRevision = REPLACEMENT_MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = REPLACEMENT_RUN_START_WALL_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = SESSION_END_WALL_MS,
			),
		)
		val control = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = REPLACEMENT_MANIFEST_REVISION,
			sourceKind = CELL_SOURCE,
			purpose = SessionManifestPurposeCode.CONTROL,
			consentEpoch = CONTROL_CONSENT_EPOCH,
			persistenceEligible = false,
			qosCode = QOS_CODE,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = REPLACEMENT_MANIFEST_REVISION,
			serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = REPLACEMENT_RUN_START_NANOS,
			effectiveWallTimeMs = REPLACEMENT_RUN_START_WALL_MS,
			zoneId = ZONE_ID,
			automationEpoch = null,
			changeReason = "PROCESS_REPLACEMENT",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(control))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(control))
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		database.sourceSessionDao().updateSession(
			session.copy(currentManifestRevision = REPLACEMENT_MANIFEST_REVISION),
		) shouldBe 1
	}

	private suspend fun installCapturedReplacementRun(
		unresolvedSequenceStart: Long? = null,
		unresolvedSequenceEnd: Long? = null,
		registrationGeneration: Long = REPLACEMENT_REGISTRATION_GENERATION,
		sourceInstanceId: String = SOURCE_INSTANCE_ID,
		stopStatus: String = if (unresolvedSequenceStart == null) "COMPLETE" else "TIMED_OUT",
		appDrainComplete: Boolean = unresolvedSequenceStart == null,
		includeDemand: Boolean = true,
		includeRegistration: Boolean = true,
		includeAuthorization: Boolean = true,
		authorizationRevision: Long = SECOND_AUTHORIZATION_REVISION,
		demandConsumerId: String = "session:$LOGICAL_TRACKING_ID",
	) {
		closeInitialRunForReplacement()
		val segmentId = database.sessionSegmentDao().insert(
			segment().copy(
				id = REPLACEMENT_SEGMENT_ID,
				startTimeMs = REPLACEMENT_RUN_START_WALL_MS,
				serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = "FINALIZED",
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = REPLACEMENT_RUN_START_WALL_MS,
				startedElapsedNanos = REPLACEMENT_RUN_START_NANOS,
				completedAtMs = SESSION_END_WALL_MS,
				completionReason = "USER_STOP",
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runRevision = 2L,
				startDeliveryToken = "cell-captured-replacement-start",
				startCommandGeneration = 2L,
				preparedManifestRevision = REPLACEMENT_MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = REPLACEMENT_RUN_START_WALL_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = SESSION_END_WALL_MS,
			),
		)
		val capture = manifestSource().copy(manifestRevision = REPLACEMENT_MANIFEST_REVISION)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = REPLACEMENT_MANIFEST_REVISION,
			serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = REPLACEMENT_RUN_START_NANOS,
			effectiveWallTimeMs = REPLACEMENT_RUN_START_WALL_MS,
			zoneId = ZONE_ID,
			automationEpoch = null,
			changeReason = "PROCESS_REPLACEMENT",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(capture))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(capture))
		val replacementDemand = replacementDemand(demandConsumerId)
		if (includeDemand) database.sourceBrokerDao().insertDemands(listOf(replacementDemand))
		if (registrationGeneration > 0L && includeRegistration) {
			database.sourceBrokerDao().insertRegistration(
				registration(registrationGeneration, sourceInstanceId).copy(
					reservedAtMs = REPLACEMENT_RUN_START_WALL_MS,
					reservedElapsedRealtimeNanos = REPLACEMENT_REGISTRATION_START_NANOS,
					acceptedAtMs = REPLACEMENT_RUN_START_WALL_MS,
					acceptedElapsedRealtimeNanos = REPLACEMENT_REGISTRATION_ACCEPTED_NANOS,
					retiredAtMs = SESSION_END_WALL_MS,
					retiredElapsedRealtimeNanos = SESSION_END_NANOS,
					status = if (stopStatus == "PROVIDER_FAILED") {
						ProviderRegistrationGenerationEntity.STATUS_RETIRING
					} else {
						ProviderRegistrationGenerationEntity.STATUS_RETIRED
					},
				),
			)
			if (includeAuthorization) {
				database.sourceBrokerDao().insertAuthorizations(
					SourceBrokerAuthorization.rows(
						sourceKind = CELL_SOURCE,
						registrationGeneration = registrationGeneration,
						authorizationRevision = authorizationRevision,
						demands = listOf(replacementDemand),
						effectiveBootId = BOOT_ID,
						effectiveElapsedRealtimeNanos = REPLACEMENT_REGISTRATION_START_NANOS,
						effectiveWallTimeMs = REPLACEMENT_RUN_START_WALL_MS,
					),
				)
			}
		}
		database.sourceSessionDao().saveCompleteness(
			replacementCompleteness(
				registrationGeneration = registrationGeneration,
				sourceInstanceId = sourceInstanceId,
				stopStatus = stopStatus,
				appDrainComplete = appDrainComplete,
				unresolvedSequenceStart = unresolvedSequenceStart,
				unresolvedSequenceEnd = unresolvedSequenceEnd,
			),
		)
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		database.sourceSessionDao().updateSession(
			session.copy(currentManifestRevision = REPLACEMENT_MANIFEST_REVISION),
		) shouldBe 1
	}

	private suspend fun closeInitialRunForReplacement() = database.withTransaction {
		val initialRun = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
		database.sourceSessionDao().updateServiceRun(
			initialRun.copy(
				completedAtMs = REPLACEMENT_RUN_START_WALL_MS,
				completionReason = "PROCESS_RESTART",
				presentationAcknowledgedAtMs = REPLACEMENT_RUN_START_WALL_MS,
			),
		) shouldBe 1
		val initialSegment = requireNotNull(database.sessionSegmentDao().getById(SEGMENT_ID))
		database.sessionSegmentDao().update(
			initialSegment.copy(endTimeMs = REPLACEMENT_RUN_START_WALL_MS),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET retire_boot_id = ?, retire_elapsed_realtime_nanos = ?, " +
				"retired_at_ms = ? WHERE demand_id = ?",
			arrayOf(
				BOOT_ID,
				REPLACEMENT_RUN_START_NANOS,
				REPLACEMENT_RUN_START_WALL_MS,
				demand(active = false).demandId,
			),
		)
	}

	private fun replacementCompleteness(
		registrationGeneration: Long = REPLACEMENT_REGISTRATION_GENERATION,
		sourceInstanceId: String = SOURCE_INSTANCE_ID,
		stopStatus: String = "COMPLETE",
		appDrainComplete: Boolean = true,
		unresolvedSequenceStart: Long? = null,
		unresolvedSequenceEnd: Long? = null,
	) = SourceSessionCompletenessEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
		sourceKind = CELL_SOURCE,
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
		lastAdmissionOrdinal = null,
		lastSourceSequence = null,
		appDrainComplete = appDrainComplete,
		providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
		stopStatus = stopStatus,
		unresolvedSequenceStart = unresolvedSequenceStart,
		unresolvedSequenceEnd = unresolvedSequenceEnd,
		updatedAtMs = SESSION_END_WALL_MS,
	)

	private fun replacementDemand(
		consumerId: String = "session:$LOGICAL_TRACKING_ID",
	) = demand(
		active = false,
		serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
		manifestRevision = REPLACEMENT_MANIFEST_REVISION,
		requestedElapsedRealtimeNanos = REPLACEMENT_RUN_START_NANOS,
		requestedAtMs = REPLACEMENT_RUN_START_WALL_MS,
		consumerId = consumerId,
	)

	private suspend fun insertReplacementCapturedWal(): Pair<SourceEventWalEntity, Long> {
		val authorization = database.cellCapturedFactDao().maintenanceAuthorizationMembers(
			CELL_SOURCE,
			REPLACEMENT_REGISTRATION_GENERATION,
			SECOND_AUTHORIZATION_REVISION,
			2,
		).single()
		val observations = listOf(
			CellTestObservation(
				radioType = CELL_TECHNOLOGY_LTE,
				registered = true,
				signalLevelDbm = -95,
				providerTimestampNanos = REPLACEMENT_WAL_OBSERVED_NANOS,
			),
		)
		val payload = cellPayload(observations)
		val unsigned = SourceEventWalEntity(
			eventId = REPLACEMENT_WAL_EVENT_ID,
			providerDedupKey = null,
			deliveryIdentity = canonicalCellProviderDeliveryIdentity(
				BOOT_ID,
				observations.mapNotNull { it.toProviderDeliveryIdentityFactOrNull() },
			),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			sourceKind = CELL_SOURCE,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
			authorizationRevision = SECOND_AUTHORIZATION_REVISION,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = authorization.authorizationFingerprint,
			sourceSequence = 1L,
			configRevision = PLAN_REVISION,
			planAttribution = 0,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = REPLACEMENT_WAL_OBSERVED_NANOS,
			observedIntervalStartNanos = REPLACEMENT_WAL_OBSERVED_NANOS,
			receivedElapsedNanos = REPLACEMENT_WAL_OBSERVED_NANOS + 1L,
			wallTimeMs = REPLACEMENT_WAL_WALL_MS,
			wallTimeUncertaintyMs = WALL_UNCERTAINTY_MS,
			capturedCollectedDataEpoch = 0L,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			sessionManifestRevision = REPLACEMENT_MANIFEST_REVISION,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			acquiredAtMs = REPLACEMENT_WAL_WALL_MS,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = sha256(payload),
			createdAtMs = REPLACEMENT_WAL_WALL_MS,
		)
		val wal = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		val admissionOrdinal = database.sourceEventWalDao().insertIgnoringDuplicate(wal)
		return wal.copy(admissionOrdinal = admissionOrdinal) to admissionOrdinal
	}

	private suspend fun insertReplacementCapturedFact(
		template: CellCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
	): CellCapturedFactRevisionEntity {
		val logicalFactId = CellCapturedFactRevisionIntegrity.logicalFactId(
			requireNotNull(wal.deliveryIdentity),
			LOGICAL_TRACKING_ID,
			REPLACEMENT_SERVICE_RUN_ID,
			REPLACEMENT_SEGMENT_ID,
			REPLACEMENT_MANIFEST_REVISION,
			0L,
			0L,
		)
		val unsigned = template.copy(
			logicalFactId = logicalFactId,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, 1L),
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = REPLACEMENT_SERVICE_RUN_ID,
			sessionSegmentId = REPLACEMENT_SEGMENT_ID,
			sourceDeliveryIdentity = requireNotNull(wal.deliveryIdentity),
			sourceEventId = wal.eventId,
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			walIntegrityIdentity = wal.integrityIdentity,
			payloadChecksum = wal.payloadChecksum,
			sourceSequence = wal.sourceSequence,
			canonicalProviderSemanticsDigest = requireNotNull(wal.deliveryIdentity),
			sourceInstanceId = wal.sourceInstanceId,
			registrationGeneration = wal.registrationGeneration,
			authorizationRevision = requireNotNull(wal.authorizationRevision),
			authorizationFingerprint = requireNotNull(wal.authorizationFingerprint),
			manifestRevision = REPLACEMENT_MANIFEST_REVISION,
			providerAcceptanceStartNanos = REPLACEMENT_REGISTRATION_ACCEPTED_NANOS,
			providerAcceptanceEndNanos = SESSION_END_NANOS,
			authorizationEffectStartNanos = REPLACEMENT_REGISTRATION_START_NANOS,
			authorizationEffectEndNanos = SESSION_END_NANOS,
			consentEffectEndNanos = SESSION_END_NANOS,
			sessionRunEffectStartNanos = REPLACEMENT_RUN_START_NANOS,
			sessionRunEffectEndNanos = SESSION_END_NANOS,
			deletionEffectStartNanos = REPLACEMENT_RUN_START_NANOS,
			deletionEffectEndNanos = SESSION_END_NANOS,
			observedIntervalStartNanos = requireNotNull(wal.observedIntervalStartNanos),
			observedElapsedNanos = wal.observedElapsedNanos,
			receivedElapsedNanos = wal.receivedElapsedNanos,
			coverageIntervalStartNanos = requireNotNull(wal.observedIntervalStartNanos),
			coverageIntervalEndNanos = wal.observedElapsedNanos,
			observedWallTimeMs = requireNotNull(wal.wallTimeMs),
			wallTimeUncertaintyMs = requireNotNull(wal.wallTimeUncertaintyMs),
			acquiredAtMs = wal.acquiredAtMs,
			createdAtMs = wal.createdAtMs,
			appliedAtMs = requireNotNull(wal.wallTimeMs),
			effectChecksum = ZERO_CHECKSUM,
		)
		val fact = unsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned),
		)
		val dao = database.cellCapturedFactDao()
		dao.insertRevision(fact)
		dao.insertCursor(
			CellCapturedFactCursorEntity(
				writerProjectionId = fact.writerProjectionId,
				writerProjectionVersion = fact.writerProjectionVersion,
				logicalFactId = fact.logicalFactId,
				logicalTrackingId = fact.logicalTrackingId,
				serviceRunId = fact.serviceRunId,
				sessionSegmentId = fact.sessionSegmentId,
				writerOwnerGeneration = fact.writerOwnerGeneration,
				collectedDataEpoch = fact.collectedDataEpoch,
				scopeDeletionGeneration = fact.scopeDeletionGeneration,
				latestSemanticRevision = fact.semanticRevision,
				latestMutationId = fact.mutationId,
				latestEffectChecksum = fact.effectChecksum,
				latestSourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
				cursorRevision = fact.semanticRevision,
				updatedAtMs = fact.appliedAtMs,
			),
		)
		return fact
	}

	private suspend fun installControlOnlyReconciliationManifest() {
		val control = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = REPLACEMENT_MANIFEST_REVISION,
			sourceKind = CELL_SOURCE,
			purpose = SessionManifestPurposeCode.CONTROL,
			consentEpoch = CONTROL_CONSENT_EPOCH,
			persistenceEligible = false,
			qosCode = QOS_CODE,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = REPLACEMENT_MANIFEST_REVISION,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = 1L,
			startOrigin = "POLICY_RECONCILIATION",
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = SECOND_AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = NEWER_WALL_MS,
			zoneId = ZONE_ID,
			automationEpoch = null,
			changeReason = "POLICY_RECONCILIATION",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(control))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(control))
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		database.sourceSessionDao().updateSession(
			session.copy(currentManifestRevision = REPLACEMENT_MANIFEST_REVISION),
		) shouldBe 1
	}

	private suspend fun insertOverflowReplacementMembership(index: Int) {
		val segmentId = OVERFLOW_SEGMENT_ID_BASE + index
		val runId = "cell-overflow-run-$index"
		val startWallTimeMs = REPLACEMENT_RUN_START_WALL_MS + index
		val startElapsedNanos = REPLACEMENT_RUN_START_NANOS + index
		database.sessionSegmentDao().insert(
			segment().copy(
				id = segmentId.toLong(),
				startTimeMs = startWallTimeMs,
				serviceRunId = runId,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = "FINALIZED",
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startWallTimeMs,
				startedElapsedNanos = startElapsedNanos,
				completedAtMs = SESSION_END_WALL_MS,
				completionReason = "USER_STOP",
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runRevision = 2L,
				startDeliveryToken = "cell-overflow-start-$index",
				startCommandGeneration = index + 2L,
				preparedManifestRevision = index + 2L,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = startWallTimeMs,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId.toLong(),
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = SESSION_END_WALL_MS,
			),
		)
	}

	private suspend fun insertCapturedFact(
		deliveryIndex: Int,
		observedWallTimeMs: Long,
		providerNanos: Long = OBSERVED_NANOS + deliveryIndex,
		authorizationFingerprint: String? = null,
		aggregateOwner: CellCapturedFactRevisionEntity? = null,
		acceptedChildCount: Int = 1,
		missingTimeChildCount: Int = 0,
		semanticRevisions: Int = 1,
		coverageSpanNanos: Long = 0L,
		radioType: String = CELL_TECHNOLOGY_LTE,
		registered: Boolean = true,
		signalLevelDbm: Int? = -95,
		sourceInstanceId: String = SOURCE_INSTANCE_ID,
		registrationGeneration: Long = REGISTRATION_GENERATION,
		authorizationRevision: Long = AUTHORIZATION_REVISION,
		authorizationEffectStartNanos: Long = AUTHORIZATION_START_NANOS,
		authorizationEffectEndNanos: Long = REGISTRATION_END_NANOS,
		consentEffectEndNanos: Long = REGISTRATION_END_NANOS,
		persistProduct: Boolean = true,
	): CellCapturedFactRevisionEntity {
		require(
			deliveryIndex in 0..9 && semanticRevisions in 1..2 && coverageSpanNanos >= 0L &&
				acceptedChildCount > 0 && missingTimeChildCount >= 0 && providerNanos > 0L,
		)
		val exactAuthorizationFingerprint = authorizationFingerprint ?: requireNotNull(
			database.cellCapturedFactDao().maintenanceAuthorizationMembers(
				CELL_SOURCE,
				registrationGeneration,
				authorizationRevision,
				2,
			),
		).single().authorizationFingerprint
		val eventId = "cell-event-$deliveryIndex"
		val coverageStartNanos = providerNanos - coverageSpanNanos
		require(coverageStartNanos > 0L)
		val observations = buildList {
			repeat(acceptedChildCount) { index ->
				add(CellTestObservation(
					radioType = radioType,
					registered = registered,
					signalLevelDbm = signalLevelDbm,
					providerTimestampNanos = if (index == 0) coverageStartNanos else providerNanos,
				))
			}
			repeat(missingTimeChildCount) {
				add(CellTestObservation(radioType, registered, signalLevelDbm, null))
			}
		}
		val payload = cellPayload(observations)
		val deliveryIdentity = canonicalCellProviderDeliveryIdentity(
			BOOT_ID,
			observations.mapNotNull { it.toProviderDeliveryIdentityFactOrNull() },
		)
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
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
			physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
			authorizationRevision = authorizationRevision,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = exactAuthorizationFingerprint,
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
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
			configurationRevision = PLAN_REVISION,
			physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
			authorizationRevision = authorizationRevision,
			authorizationFingerprint = exactAuthorizationFingerprint,
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
			authorizationEffectStartNanos = authorizationEffectStartNanos,
			authorizationEffectEndNanos = authorizationEffectEndNanos,
			consentEffectStartNanos = POLICY_START_NANOS,
			consentEffectEndNanos = consentEffectEndNanos,
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
			submittedChildCount = Math.addExact(acceptedChildCount, missingTimeChildCount),
			acceptedChildCount = acceptedChildCount,
			staleChildCount = 0,
			futureTimeChildCount = 0,
			missingTimeChildCount = missingTimeChildCount,
			clockUnverifiableChildCount = 0,
			authorityMismatchChildCount = 0,
			unsupportedTechnologyChildCount = 0,
			subscriptionCompleteness =
				CellCapturedFactRevisionEntity.SUBSCRIPTION_COMPLETENESS_UNKNOWN,
			childCompleteness = if (missingTimeChildCount == 0) {
				CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_COMPLETE
			} else {
				CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_PARTIAL
			},
			observationCount = acceptedChildCount.takeIf { aggregateOwner == null },
			registeredObservationCount = (if (registered) acceptedChildCount else 0)
				.takeIf { aggregateOwner == null },
			gsmCount = (if (radioType.equals(CELL_TECHNOLOGY_GSM, ignoreCase = true)) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			cdmaCount = (if (radioType.equals(CELL_TECHNOLOGY_CDMA, ignoreCase = true)) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			wcdmaCount = (if (radioType.equals(CELL_TECHNOLOGY_WCDMA, ignoreCase = true)) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			tdscdmaCount = (if (radioType.equals(CELL_TECHNOLOGY_TDSCDMA, ignoreCase = true)) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			lteCount = (if (radioType.equals(CELL_TECHNOLOGY_LTE, ignoreCase = true)) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			nrCount = (if (radioType.equals(CELL_TECHNOLOGY_NR, ignoreCase = true)) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			qualityUnknownCount = (if (signalLevelDbm == null || signalLevelDbm == Int.MAX_VALUE) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			qualityNoneOrUnknownCount = (if (signalLevelDbm != null && signalLevelDbm <= -120) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			qualityPoorCount = (if (signalLevelDbm != null && signalLevelDbm in -119..-110) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			qualityModerateCount = (if (signalLevelDbm != null && signalLevelDbm in -109..-100) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			qualityGoodCount = (if (signalLevelDbm != null && signalLevelDbm in -99..-90) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			qualityGreatCount = (if (signalLevelDbm != null && signalLevelDbm > -90 &&
				signalLevelDbm != Int.MAX_VALUE) acceptedChildCount else 0).takeIf { aggregateOwner == null },
			weakObservationCount = (if (signalLevelDbm != null && signalLevelDbm <= -110) {
				acceptedChildCount
			} else 0).takeIf { aggregateOwner == null },
			knownQualityObservationCount = (if (signalLevelDbm == null || signalLevelDbm == Int.MAX_VALUE) {
				0
			} else acceptedChildCount).takeIf { aggregateOwner == null },
			allKnownQualityIsWeak = (signalLevelDbm != null && signalLevelDbm != Int.MAX_VALUE &&
				signalLevelDbm <= -110).takeIf { aggregateOwner == null },
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
		val latest = revisions.last()
		if (persistProduct) {
			revisions.forEach { revision -> dao.insertRevision(revision) }
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
		}
		return latest
	}

	private suspend fun installPolicy(revokedCapture: Boolean) {
		val policies = mutableListOf(historicalPolicy())
		val consents = mutableListOf(historicalConsent(), historicalControlConsent())
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
		controlConsentEpoch = CONTROL_CONSENT_EPOCH,
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

	private fun historicalControlConsent() = SourceConsentEpochEntity(
		sourceKind = CELL_SOURCE,
		purpose = "CONTROL",
		epoch = CONTROL_CONSENT_EPOCH,
		eligible = true,
		persistenceEligible = false,
		policyRevision = POLICY_REVISION,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
		effectiveWallTimeMs = RUN_START_WALL_MS,
		changeReason = "TEST_CONTROL",
	)

	private fun demand(
		active: Boolean,
		serviceRunId: String = SERVICE_RUN_ID,
		manifestRevision: Long = MANIFEST_REVISION,
		requestedElapsedRealtimeNanos: Long = RUN_START_NANOS,
		requestedAtMs: Long = RUN_START_WALL_MS,
		consumerId: String = "session:$LOGICAL_TRACKING_ID",
	): SourceDemandEntity {
		return SourceDemandEntity(
			demandId = portableDemandId(consumerId, manifestRevision, requestedElapsedRealtimeNanos),
			consumerId = consumerId,
			sourceKind = CELL_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = serviceRunId,
			manifestRevision = manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			sourcePolicyRevision = POLICY_REVISION,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			minimumAcquisitionSpec = "cell:v1:required=CHANGE_CALLBACK",
			adaptiveReductionAllowed = true,
			maximumAgeMs = 5 * 60_000L,
			desiredLatencyMs = Long.MAX_VALUE,
			requestedDeliveryLatencyMs = null,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = requestedElapsedRealtimeNanos,
			requestedAtMs = requestedAtMs,
			status = if (active) SourceDemandEntity.STATUS_ACTIVE else SourceDemandEntity.STATUS_RETIRED,
			retireBootId = BOOT_ID.takeUnless { active },
			retireElapsedRealtimeNanos = SESSION_END_NANOS.takeUnless { active },
			retiredAtMs = SESSION_END_WALL_MS.takeUnless { active },
		)
	}

	private fun portableDemandId(
		consumerId: String,
		manifestRevision: Long,
		requestedElapsedRealtimeNanos: Long,
	): String = sha256(
		listOf(
			consumerId,
			CELL_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			POLICY_REVISION,
			CONSENT_EPOCH,
			manifestRevision,
			BOOT_ID,
			requestedElapsedRealtimeNanos,
		).joinToString("\u001f").toByteArray(Charsets.UTF_8),
	)

	private fun registration(
		generation: Long = REGISTRATION_GENERATION,
		sourceInstanceId: String = SOURCE_INSTANCE_ID,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = CELL_SOURCE,
		registrationGeneration = generation,
		sourceInstanceId = sourceInstanceId,
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
		retiredAtMs = REPLACEMENT_RUN_START_WALL_MS,
		retiredElapsedRealtimeNanos = REGISTRATION_END_NANOS,
		failureCode = "ORDERLY_STOP",
		captureCallbackBarrierAuthorizationRevision = 0L,
	)

	private suspend fun insertFailedCellRegistration(
		generation: Long,
		sourceInstanceId: String = SOURCE_INSTANCE_ID,
		reservedElapsedRealtimeNanos: Long,
	) {
		database.sourceBrokerDao().insertRegistration(
			registration(generation, sourceInstanceId).copy(
				status = ProviderRegistrationGenerationEntity.STATUS_FAILED,
				reservedAtMs = SESSION_END_WALL_MS + generation,
				reservedElapsedRealtimeNanos = reservedElapsedRealtimeNanos,
				acceptedAtMs = null,
				acceptedElapsedRealtimeNanos = null,
				retiredAtMs = SESSION_END_WALL_MS + generation + 1L,
				retiredElapsedRealtimeNanos = reservedElapsedRealtimeNanos + 1L,
				failureCode = "PROVIDER_REGISTRATION_FAILED",
			),
		)
	}

	private suspend fun insertAcceptedCellRegistration(
		generation: Long,
		sourceInstanceId: String = SOURCE_INSTANCE_ID,
		reservedElapsedRealtimeNanos: Long,
	) {
		database.sourceBrokerDao().insertRegistration(
			registration(generation, sourceInstanceId).copy(
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				reservedAtMs = SESSION_END_WALL_MS + generation,
				reservedElapsedRealtimeNanos = reservedElapsedRealtimeNanos,
				acceptedAtMs = SESSION_END_WALL_MS + generation + 1L,
				acceptedElapsedRealtimeNanos = reservedElapsedRealtimeNanos + 1L,
				retiredAtMs = SESSION_END_WALL_MS + generation + 2L,
				retiredElapsedRealtimeNanos = reservedElapsedRealtimeNanos + 2L,
				failureCode = "ORDERLY_STOP",
			),
		)
	}

	private suspend fun installActiveControlOnlyProvider(
		captureBarrierRevision: Long,
	): SourceDemandEntity {
		val captureHistory = demand(active = false)
		val control = demand(active = true).copy(
			demandId = CONTROL_DEMAND_ID,
			consumerId = "app:cell-control",
			purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			sourcePolicyRevision = REVOKED_POLICY_REVISION,
			consentEpoch = CONTROL_CONSENT_EPOCH,
			persistenceEligible = false,
			requestedElapsedRealtimeNanos = REVOKED_POLICY_NANOS,
			requestedAtMs = REVOKED_POLICY_WALL_MS,
		)
		database.sourceBrokerDao().insertDemands(listOf(control))
		database.sourceBrokerDao().insertRegistration(
			registration(
				generation = REPLACEMENT_REGISTRATION_GENERATION,
				sourceInstanceId = CONTROL_SOURCE_INSTANCE_ID,
			).copy(
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
				captureCallbackBarrierAuthorizationRevision = captureBarrierRevision,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = CELL_SOURCE,
				registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
				authorizationRevision = SECOND_AUTHORIZATION_REVISION,
				demands = listOf(captureHistory),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
				effectiveWallTimeMs = RUN_START_WALL_MS,
			) + SourceBrokerAuthorization.rows(
				sourceKind = CELL_SOURCE,
				registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
				authorizationRevision = CONTROL_AUTHORIZATION_REVISION,
				demands = listOf(control),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = REVOKED_POLICY_NANOS,
				effectiveWallTimeMs = REVOKED_POLICY_WALL_MS,
			),
		)
		return control
	}

	private suspend fun insertNewerControlOnlyWal(): SourceEventWalEntity {
		val authorization = database.cellCapturedFactDao().maintenanceAuthorizationMembers(
			CELL_SOURCE,
			REPLACEMENT_REGISTRATION_GENERATION,
			CONTROL_AUTHORIZATION_REVISION,
			2,
		).single()
		val observations = listOf(
			CellTestObservation(
				radioType = CELL_TECHNOLOGY_LTE,
				registered = true,
				signalLevelDbm = -95,
				providerTimestampNanos = CONTROL_WAL_OBSERVED_NANOS,
			),
		)
		val payload = cellPayload(observations)
		val unsigned = SourceEventWalEntity(
			eventId = CONTROL_WAL_EVENT_ID,
			providerDedupKey = null,
			deliveryIdentity = canonicalCellProviderDeliveryIdentity(
				BOOT_ID,
				observations.mapNotNull { it.toProviderDeliveryIdentityFactOrNull() },
			),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = CELL_SOURCE,
			sourceInstanceId = CONTROL_SOURCE_INSTANCE_ID,
			registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
			authorizationRevision = CONTROL_AUTHORIZATION_REVISION,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			authorizationFingerprint = authorization.authorizationFingerprint,
			sourceSequence = 1L,
			configRevision = PLAN_REVISION,
			planAttribution = RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = CONTROL_WAL_OBSERVED_NANOS,
			observedIntervalStartNanos = CONTROL_WAL_OBSERVED_NANOS,
			receivedElapsedNanos = CONTROL_WAL_OBSERVED_NANOS,
			wallTimeMs = CONTROL_WAL_CREATED_AT_MS,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			sessionManifestRevision = null,
			lifecycleLeaseGeneration = null,
			acquiredAtMs = CONTROL_WAL_CREATED_AT_MS,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = sha256(payload),
			createdAtMs = CONTROL_WAL_CREATED_AT_MS,
		)
		val wal = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		database.sourceEventWalDao().insertIgnoringDuplicate(wal)
		return wal
	}

	private suspend fun insertNewerAmbientOnlyWal(): SourceEventWalEntity {
		val observations = listOf(
			CellTestObservation(
				radioType = CELL_TECHNOLOGY_NR,
				registered = false,
				signalLevelDbm = -105,
				providerTimestampNanos = CONTROL_WAL_OBSERVED_NANOS + 1L,
			),
		)
		val payload = cellPayload(observations)
		val unsigned = SourceEventWalEntity(
			eventId = AMBIENT_WAL_EVENT_ID,
			providerDedupKey = null,
			deliveryIdentity = canonicalCellProviderDeliveryIdentity(
				BOOT_ID,
				observations.mapNotNull { it.toProviderDeliveryIdentityFactOrNull() },
			),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = CELL_SOURCE,
			sourceInstanceId = "cell-ambient-instance",
			registrationGeneration = REPLACEMENT_REGISTRATION_GENERATION + 1L,
			physicalConfigurationFingerprint = PHYSICAL_FINGERPRINT,
			authorizationRevision = CONTROL_AUTHORIZATION_REVISION + 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			authorizationFingerprint = sha256("cell-ambient-authorization".toByteArray()),
			sourceSequence = 1L,
			configRevision = PLAN_REVISION,
			planAttribution = RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = CONTROL_WAL_OBSERVED_NANOS + 1L,
			observedIntervalStartNanos = CONTROL_WAL_OBSERVED_NANOS + 1L,
			receivedElapsedNanos = CONTROL_WAL_OBSERVED_NANOS + 1L,
			wallTimeMs = CONTROL_WAL_CREATED_AT_MS + 1L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			sessionManifestRevision = null,
			lifecycleLeaseGeneration = null,
			acquiredAtMs = CONTROL_WAL_CREATED_AT_MS + 1L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = sha256(payload),
			createdAtMs = CONTROL_WAL_CREATED_AT_MS + 1L,
		)
		val wal = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		database.sourceEventWalDao().insertIgnoringDuplicate(wal)
		return wal
	}

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

	private fun cellPayload(observations: List<CellTestObservation>): ByteArray =
		ByteArrayOutputStream().use { buffer ->
			require(observations.isNotEmpty())
			DataOutputStream(buffer).use { output ->
				output.writeInt(CELL_PAYLOAD_TYPE)
				output.writeBoolean(false)
				output.writeInt(observations.size)
				observations.forEach { observation ->
					output.writeUTF("")
					output.writeUTF(observation.radioType)
					output.writeBoolean(observation.registered)
					output.writeBoolean(observation.signalLevelDbm != null)
					observation.signalLevelDbm?.let(output::writeInt)
					output.writeBoolean(observation.providerTimestampNanos != null)
					observation.providerTimestampNanos?.let(output::writeLong)
				}
				output.writeInt(CELL_REFRESH_OUTCOME_CALLBACK)
			}
			buffer.toByteArray()
		}

	private fun CellTestObservation.toProviderDeliveryIdentityFactOrNull() =
		providerTimestampNanos?.let { providerTime ->
			CellProviderDeliveryIdentityFact(
				radioType = radioType,
				registered = registered,
				signalLevelDbm = signalLevelDbm,
				providerTimestampNanos = providerTime,
			)
		}

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

	private data class CellTestObservation(
		val radioType: String,
		val registered: Boolean,
		val signalLevelDbm: Int?,
		val providerTimestampNanos: Long?,
	)

	private companion object {
		const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
		const val WRITER_ID = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
		const val LOGICAL_TRACKING_ID = "logical-cell-maintenance"
		const val SERVICE_RUN_ID = "run-cell-maintenance"
		const val REPLACEMENT_SERVICE_RUN_ID = "run-cell-maintenance-replacement"
		const val SOURCE_INSTANCE_ID = "cell-instance"
		const val CONTROL_SOURCE_INSTANCE_ID = "cell-control-instance"
		const val CONTROL_DEMAND_ID = "cell-control-demand"
		const val CONTROL_WAL_EVENT_ID = "cell-control-event"
		const val AMBIENT_WAL_EVENT_ID = "cell-ambient-event"
		const val REPLACEMENT_WAL_EVENT_ID = "cell-replacement-event"
		const val RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION = 2
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
		const val CONTROL_CONSENT_EPOCH = 1L
		const val REVOKED_CONSENT_EPOCH = 2L
		const val MANIFEST_REVISION = 1L
		const val REPLACEMENT_MANIFEST_REVISION = 2L
		const val LEASE_GENERATION = 1L
		const val REGISTRATION_GENERATION = 1L
		const val REPLACEMENT_REGISTRATION_GENERATION = 2L
		const val UNRELATED_REGISTRATION_GENERATION_BASE = 10_000L
		const val AUTHORIZATION_REVISION = 1L
		const val SECOND_AUTHORIZATION_REVISION = 2L
		const val CONTROL_AUTHORIZATION_REVISION = 3L
		const val SEGMENT_ID = 1L
		const val REPLACEMENT_SEGMENT_ID = 2L
		const val ORPHAN_SEGMENT_ID = 3L
		const val OVERFLOW_SEGMENT_ID_BASE = 100
		const val PORTABLE_AUDIT_CAP = 4_096
		const val QOS_CODE = 2
		const val RUN_START_NANOS = 100_000_000L
		const val REGISTRATION_START_NANOS = 110_000_000L
		const val AUTHORIZATION_START_NANOS = 150_000_000L
		const val SECOND_AUTHORIZATION_START_NANOS = 210_000_000L
		const val OBSERVED_NANOS = 200_000_000L
		const val REPLACEMENT_RUN_START_NANOS = 300_000_000L
		const val REPLACEMENT_REGISTRATION_START_NANOS = 310_000_000L
		const val REPLACEMENT_REGISTRATION_ACCEPTED_NANOS = 320_000_000L
		const val REPLACEMENT_WAL_OBSERVED_NANOS = 400_000_000L
		const val REGISTRATION_END_NANOS = REPLACEMENT_RUN_START_NANOS
		const val SESSION_END_NANOS = 600_000_000L
		const val REVOKED_POLICY_NANOS = 700_000_000L
		const val CONTROL_WAL_OBSERVED_NANOS = REVOKED_POLICY_NANOS + 1L
		const val POLICY_START_NANOS = 100_000_000L
		const val RUN_START_WALL_MS = 1_000L
		const val OBSERVED_WALL_MS = 2_000L
		const val NEWER_WALL_MS = 2_500L
		const val REPLACEMENT_RUN_START_WALL_MS = 2_500L
		const val REPLACEMENT_WAL_WALL_MS = 2_700L
		const val SESSION_END_WALL_MS = 3_000L
		const val REVOKED_POLICY_WALL_MS = 4_000L
		const val FLOOR_MS = 1_990L
		const val MAINTENANCE_TIME_MS = 5_000L
		const val DELETION_TIME_MS = 6_000L
		const val CONTROL_WAL_CREATED_AT_MS = DELETION_TIME_MS + 1L
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
		const val CELL_PAYLOAD_TYPE = 8
		const val CELL_REFRESH_OUTCOME_CALLBACK = 0
		const val CELL_TECHNOLOGY_GSM = "GSM"
		const val CELL_TECHNOLOGY_CDMA = "CDMA"
		const val CELL_TECHNOLOGY_WCDMA = "WCDMA"
		const val CELL_TECHNOLOGY_TDSCDMA = "TDSCDMA"
		const val CELL_TECHNOLOGY_LTE = "LTE"
		const val CELL_TECHNOLOGY_NR = "NR"
		const val ZERO_CHECKSUM =
			"0000000000000000000000000000000000000000000000000000000000000000"
	}
}
