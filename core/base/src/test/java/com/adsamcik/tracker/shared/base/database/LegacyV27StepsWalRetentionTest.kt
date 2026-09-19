package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyV27StepsWalRetentionTest {
	private lateinit var database: AppDatabase
	private val context: Application
		get() = ApplicationProvider.getApplicationContext()

	@Before
	fun setUp() {
		context.deleteDatabase(REOPEN_DATABASE)
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
		context.deleteDatabase(REOPEN_DATABASE)
	}

	@Test
	fun `drained migrated Steps WAL deletes without v28 capture authority`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-drained", 1L)

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 1

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal) shouldBe null
		database.openHelper.writableDatabase.scalar(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision",
		) shouldBe 0L
	}

	@Test
	fun `undrained migrated Steps WAL is preserved as maintenance debt`() = runTest {
		seedPendingLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-undrained", 1L)

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 0

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
		database.legacyV27ProjectionDrainDao().minimumPendingOrdinal() shouldBe 1L
	}

	@Test
	fun `retryable failed legacy drain cannot authorize ordinary pruning`() = runTest {
		seedFailedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-failed-retention", 1L)

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 0

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
		database.legacyV27ProjectionDrainDao().minimumBlockedWalOrdinal() shouldBe 1L
	}

	@Test
	fun `nonlegacy full clear permits an epoch skip`() = runTest {
		insertCurrentControlStepsWal("current-epoch-skip", 1L)
		insertLocationWal("location-epoch-skip", 2L)

		AppDatabase.deleteAllCollectedData(
			database = database,
			operationId = "nonlegacy-epoch-skip",
			collectedDataEpoch = 5L,
			retainedFromMs = null,
			updatedAtMs = 100L,
		)

		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 5L
	}

	@Test
	fun `legacy full clear requires the next epoch`() = runTest {
		seedPendingLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-epoch-skip", 1L)

		shouldThrow<IllegalStateException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "legacy-epoch-skip",
				collectedDataEpoch = 2L,
				retainedFromMs = null,
				updatedAtMs = 100L,
			)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
		database.legacyV27ProjectionDrainDao().get()?.status shouldBe
			LegacyV27ProjectionDrainEntity.STATUS_PENDING
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 0L
	}

	@Test
	fun `full clear deletes pending legacy Steps WAL and drain state`() = runTest {
		seedPendingLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal(
			eventId = "legacy-pending-full-clear",
			admissionOrdinal = 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		database.sourceProjectionStateDao().insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "pending-legacy-full-clear-outbox",
				projectionId = "event-tracking-frame",
				projectionVersion = 1,
				admissionOrdinal = 1L,
				effectKind = "event-tracking-frame-v1",
				payloadVersion = 1,
				payload = byteArrayOf(1),
				createdAtMs = 10L,
				deliveredAtMs = null,
			),
		)

		AppDatabase.deleteAllCollectedData(
			database = database,
			operationId = "pending-legacy-full-clear",
			collectedDataEpoch = 1L,
			retainedFromMs = null,
			updatedAtMs = 100L,
		)

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal) shouldBe null
		database.legacyV27ProjectionDrainDao().get() shouldBe null
		database.legacyV27ProjectionDrainDao().targets() shouldBe emptyList()
		database.sourceProjectionStateDao()
			.outbox("pending-legacy-full-clear-outbox") shouldBe null
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 1L
	}

	@Test
	fun `full clear deletes retryable failed legacy Steps WAL and drain state`() = runTest {
		seedFailedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal(
			eventId = "legacy-failed-full-clear",
			admissionOrdinal = 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)

		AppDatabase.deleteAllCollectedData(
			database = database,
			operationId = "failed-legacy-full-clear",
			collectedDataEpoch = 1L,
			retainedFromMs = null,
			updatedAtMs = 100L,
		)

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal) shouldBe null
		database.legacyV27ProjectionDrainDao().get() shouldBe null
		database.legacyV27ProjectionDrainDao().targets() shouldBe emptyList()
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 1L
	}

	@Test
	fun `full clear deletes blocked unsupported legacy drain`() = runTest {
		seedBlockedUnsupportedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal(
			eventId = "legacy-blocked-full-clear",
			admissionOrdinal = 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)

		AppDatabase.deleteAllCollectedData(
			database = database,
			operationId = "blocked-unsupported-full-clear",
			collectedDataEpoch = 1L,
			retainedFromMs = null,
			updatedAtMs = 100L,
		)

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal) shouldBe null
		database.legacyV27ProjectionDrainDao().get() shouldBe null
		database.legacyV27ProjectionDrainDao().targets() shouldBe emptyList()
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 1L
	}

	@Test
	fun `ordinary retention cannot delete blocked unsupported legacy WAL`() = runTest {
		seedBlockedUnsupportedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-blocked-retention", 1L)

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 0

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
		database.legacyV27ProjectionDrainDao().get()?.status shouldBe
			LegacyV27ProjectionDrainEntity.STATUS_BLOCKED_UNSUPPORTED_TARGET
	}

	@Test
	fun `malformed blocked unsupported metadata rolls back full clear`() = runTest {
		seedBlockedUnsupportedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-blocked-malformed-target", 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE legacy_v27_projection_target SET required_through_ordinal = 2 " +
				"WHERE projection_id = ? AND projection_version = ?",
			arrayOf(UNSUPPORTED_PROJECTION_ID, UNSUPPORTED_PROJECTION_VERSION),
		)

		shouldThrow<IllegalStateException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "blocked-malformed-target-full-clear",
				collectedDataEpoch = 1L,
				retainedFromMs = null,
				updatedAtMs = 100L,
			)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
		database.legacyV27ProjectionDrainDao().targets().size shouldBe 5
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 0L
	}

	@Test
	fun `blocked legacy authentication failure rolls back mixed-source full clear`() = runTest {
		seedBlockedUnsupportedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-blocked-mixed", 1L)
		val current = insertCurrentControlStepsWal("current-blocked-mixed", 2L)
		val location = insertLocationWal("location-blocked-mixed", 3L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload_checksum = ? WHERE admission_ordinal = ?",
			arrayOf("0".repeat(64), legacy.admissionOrdinal),
		)

		shouldThrow<IllegalStateException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "blocked-mixed-source-full-clear",
				collectedDataEpoch = 1L,
				retainedFromMs = null,
				updatedAtMs = 100L,
			)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
		database.sourceEventWalDao().getByAdmissionOrdinal(current.admissionOrdinal)?.eventId shouldBe
			current.eventId
		database.sourceEventWalDao().getByAdmissionOrdinal(location.admissionOrdinal)?.eventId shouldBe
			location.eventId
		database.legacyV27ProjectionDrainDao().targets().size shouldBe 5
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 0L
	}

	@Test
	fun `full clear rejects malformed zero-mask row and preserves old epoch`() = runTest {
		seedPendingLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-malformed-full-clear", 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET source_policy_revision = 1 WHERE admission_ordinal = ?",
			arrayOf(legacy.admissionOrdinal),
		)

		shouldThrow<IllegalStateException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "malformed-legacy-full-clear",
				collectedDataEpoch = 1L,
				retainedFromMs = null,
				updatedAtMs = 100L,
			)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
		database.legacyV27ProjectionDrainDao().get()?.status shouldBe
			LegacyV27ProjectionDrainEntity.STATUS_PENDING
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 0L
	}

	@Test
	fun `full clear rejects a negative legacy admission ordinal and rolls back`() = runTest {
		seedPendingLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		insertLegacyStepsWal("legacy-negative-ordinal", 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET admission_ordinal = -1 WHERE admission_ordinal = 1",
		)

		shouldThrow<IllegalStateException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "negative-legacy-ordinal-full-clear",
				collectedDataEpoch = 1L,
				retainedFromMs = null,
				updatedAtMs = 100L,
			)
		}

		database.sourceEventWalDao().countAll() shouldBe 1L
		database.collectedDataDeletionOperationDao()
			.get("negative-legacy-ordinal-full-clear") shouldBe null
		database.legacyV27ProjectionDrainDao().get()?.status shouldBe
			LegacyV27ProjectionDrainEntity.STATUS_PENDING
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 0L
	}

	@Test
	fun `full clear rejects a zero legacy admission ordinal and rolls back`() = runTest {
		seedPendingLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		insertLegacyStepsWal("legacy-zero-ordinal", 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET admission_ordinal = 0 WHERE admission_ordinal = 1",
		)

		shouldThrow<IllegalStateException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "zero-legacy-ordinal-full-clear",
				collectedDataEpoch = 1L,
				retainedFromMs = null,
				updatedAtMs = 100L,
			)
		}

		database.sourceEventWalDao().countAll() shouldBe 1L
		database.collectedDataDeletionOperationDao()
			.get("zero-legacy-ordinal-full-clear") shouldBe null
		database.legacyV27ProjectionDrainDao().get()?.status shouldBe
			LegacyV27ProjectionDrainEntity.STATUS_PENDING
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 0L
	}

	@Test
	fun `full clear authenticates every positive legacy ordinal across pages`() = runTest {
		val rowCount = 129
		seedPendingLegacyDrain(cutoff = rowCount.toLong())
		insertMigratedTerminalRun()
		repeat(rowCount) { index ->
			val ordinal = index + 1L
			insertLegacyStepsWal(
				eventId = "legacy-positive-page-$ordinal",
				admissionOrdinal = ordinal,
				integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
			)
		}

		AppDatabase.deleteAllCollectedData(
			database = database,
			operationId = "positive-legacy-pagination-full-clear",
			collectedDataEpoch = 1L,
			retainedFromMs = null,
			updatedAtMs = 100L,
		)

		database.sourceEventWalDao().countAll() shouldBe 0L
		database.legacyV27ProjectionDrainDao().get() shouldBe null
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 1L
	}

	@Test
	fun `completed unregistered event frame authenticates pending legacy checksum inline`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L, eventFrameRegistered = false)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal(
			eventId = "legacy-unregistered-pending-checksum",
			admissionOrdinal = 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 1

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal) shouldBe null
	}

	@Test
	fun `completed unregistered event frame rejects tampered pending legacy checksum`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L, eventFrameRegistered = false)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal(
			eventId = "legacy-unregistered-tampered",
			admissionOrdinal = 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ? WHERE admission_ordinal = ?",
			arrayOf(byteArrayOf(9), legacy.admissionOrdinal),
		)

		shouldThrow<IllegalStateException> {
			database.pruneSourceEventStorageBefore(createdBeforeMs = 100L)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
	}

	@Test
	fun `completed registered event frame cannot retain pending legacy checksum`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal(
			eventId = "legacy-registered-pending-checksum",
			admissionOrdinal = 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)

		shouldThrow<IllegalStateException> {
			database.pruneSourceEventStorageBefore(createdBeforeMs = 100L)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
	}

	@Test
	fun `sessionless migrated Steps WAL is valid legacy attribution`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L)
		val legacy = insertLegacyStepsWal(
			eventId = "legacy-sessionless",
			admissionOrdinal = 1L,
			logicalTrackingId = null,
			serviceRunId = null,
		)

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 1

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal) shouldBe null
	}

	@Test
	fun `exact pre-migration terminal run shapes authorize legacy pruning`() = runTest {
		seedCompletedLegacyDrain(cutoff = 3L)
		listOf("CLOSED", "FAILED", "FINALIZED").forEachIndexed { index, state ->
			val ordinal = index + 1L
			val logicalTrackingId = "legacy-$state-tracking"
			val serviceRunId = "legacy-$state-run"
			insertPreMigrationTerminalRun(state, logicalTrackingId, serviceRunId)
			insertLegacyStepsWal(
				eventId = "legacy-$state",
				admissionOrdinal = ordinal,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			)
		}

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 3
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `mixed-null legacy attribution fails closed`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L)
		val legacy = insertLegacyStepsWal(
			eventId = "legacy-mixed-null",
			admissionOrdinal = 1L,
			logicalTrackingId = LEGACY_TRACKING_ID,
			serviceRunId = null,
		)

		shouldThrow<IllegalStateException> {
			database.pruneSourceEventStorageBefore(createdBeforeMs = 100L)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
	}

	@Test
	fun `nonlegacy zero-mask Steps WAL fails closed`() = runTest {
		val zeroMask = insertLegacyStepsWal("not-migrated", 1L)

		shouldThrow<IllegalStateException> {
			database.pruneSourceEventStorageBefore(createdBeforeMs = 100L)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(zeroMask.admissionOrdinal)?.eventId shouldBe
			zeroMask.eventId
	}

	@Test
	fun `active run cannot use the legacy zero-mask retention path`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-active-run", 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET state = 'ACTIVE', runtime_acknowledgement = " +
				"'LEGACY_ACTIVE', runtime_failure_code = NULL, run_revision = 0 " +
				"WHERE service_run_id = ?",
			arrayOf(LEGACY_RUN_ID),
		)

		shouldThrow<IllegalStateException> {
			database.pruneSourceEventStorageBefore(createdBeforeMs = 100L)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
	}

	@Test
	fun `active run cannot use the full-clear legacy authentication mode`() = runTest {
		seedPendingLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal(
			eventId = "legacy-active-full-clear",
			admissionOrdinal = 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET state = 'ACTIVE', runtime_acknowledgement = " +
				"'LEGACY_ACTIVE', runtime_failure_code = NULL, run_revision = 0 " +
				"WHERE service_run_id = ?",
			arrayOf(LEGACY_RUN_ID),
		)

		shouldThrow<IllegalStateException> {
			AppDatabase.deleteAllCollectedData(
				database = database,
				operationId = "active-legacy-full-clear",
				collectedDataEpoch = 1L,
				retainedFromMs = null,
				updatedAtMs = 100L,
			)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 0L
	}

	@Test
	fun `ambiguous legacy drain marker fails closed`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-ambiguous-marker", 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE legacy_v27_projection_drain SET contract_version = 2 WHERE id = 1",
		)

		shouldThrow<IllegalStateException> {
			database.pruneSourceEventStorageBefore(createdBeforeMs = 100L)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
	}

	@Test
	fun `malformed migrated Steps WAL cannot be deleted as legacy`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-malformed", 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET integrity_identity = ? WHERE admission_ordinal = ?",
			arrayOf(SourceEventWalEntity.LEGACY_CHECKSUM_MISMATCH, legacy.admissionOrdinal),
		)

		shouldThrow<IllegalStateException> {
			database.pruneSourceEventStorageBefore(createdBeforeMs = 100L)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal)?.eventId shouldBe
			legacy.eventId
	}

	@Test
	fun `mixed legacy current and other-source WAL prune in one global pass`() = runTest {
		seedCompletedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		val legacy = insertLegacyStepsWal("legacy-mixed", 1L)
		val current = insertCurrentControlStepsWal("current-mixed", 2L)
		val location = insertLocationWal("location-mixed", 3L)

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 3

		database.sourceEventWalDao().getByAdmissionOrdinal(legacy.admissionOrdinal) shouldBe null
		database.sourceEventWalDao().getByAdmissionOrdinal(current.admissionOrdinal) shouldBe null
		database.sourceEventWalDao().getByAdmissionOrdinal(location.admissionOrdinal) shouldBe null
	}

	@Test
	fun `full clear atomically deletes pending legacy and current mixed-source WAL`() = runTest {
		seedPendingLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		insertLegacyStepsWal(
			eventId = "legacy-full-clear-mixed",
			admissionOrdinal = 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		insertCurrentControlStepsWal("current-full-clear-mixed", 2L)
		insertLocationWal("location-full-clear-mixed", 3L)

		AppDatabase.deleteAllCollectedData(
			database = database,
			operationId = "mixed-source-full-clear",
			collectedDataEpoch = 1L,
			retainedFromMs = null,
			updatedAtMs = 100L,
		)

		database.sourceEventWalDao().countAll() shouldBe 0L
		database.legacyV27ProjectionDrainDao().get() shouldBe null
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 1L
	}

	@Test
	fun `drained legacy pruning remains idempotent after reopen`() = runTest {
		database.close()
		database = openFileDatabase()
		seedCompletedLegacyDrain(cutoff = 1L)
		insertMigratedTerminalRun()
		insertLegacyStepsWal("legacy-reopen", 1L)

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 1
		database.close()
		database = openFileDatabase()

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L).walEventsDeleted shouldBe 0
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	private fun openFileDatabase(): AppDatabase =
		AppDatabase.fileBuilder(context, REOPEN_DATABASE)
			.allowMainThreadQueries()
			.build()

	private suspend fun seedPendingLegacyDrain(cutoff: Long) {
		database.sourceEvidenceStateDao().ensure()
		database.legacyV27ProjectionDrainDao().saveDrain(
			LegacyV27ProjectionDrainEntity(
				cutoffAdmissionOrdinal = cutoff,
				collectedDataEpoch = 0L,
				status = LegacyV27ProjectionDrainEntity.STATUS_PENDING,
				ownerBootId = null,
				ownerToken = null,
				leaseGeneration = 0L,
				leaseExpiresElapsedNanos = null,
				startedAtMs = null,
				completedAtMs = null,
				suppressedOutboxCount = 0L,
				failureCode = null,
			),
		)
		legacyTargets(cutoff, pending = true).forEach(
			database.legacyV27ProjectionDrainDao()::saveTarget,
		)
		registerPendingEventFrame()
	}

	private suspend fun seedFailedLegacyDrain(cutoff: Long) {
		database.sourceEvidenceStateDao().ensure()
		database.legacyV27ProjectionDrainDao().saveDrain(
			LegacyV27ProjectionDrainEntity(
				cutoffAdmissionOrdinal = cutoff,
				collectedDataEpoch = 0L,
				status = LegacyV27ProjectionDrainEntity.STATUS_FAILED_RETRYABLE,
				ownerBootId = null,
				ownerToken = null,
				leaseGeneration = 1L,
				leaseExpiresElapsedNanos = null,
				startedAtMs = 1L,
				completedAtMs = null,
				suppressedOutboxCount = 0L,
				failureCode = "LEGACY_V27_STORAGE_RETRYABLE",
			),
		)
		legacyTargets(cutoff, pending = true).forEach(
			database.legacyV27ProjectionDrainDao()::saveTarget,
		)
		registerPendingEventFrame()
	}

	private suspend fun seedBlockedUnsupportedLegacyDrain(cutoff: Long) {
		database.sourceEvidenceStateDao().ensure()
		database.legacyV27ProjectionDrainDao().saveDrain(
			LegacyV27ProjectionDrainEntity(
				cutoffAdmissionOrdinal = cutoff,
				collectedDataEpoch = 0L,
				status = LegacyV27ProjectionDrainEntity.STATUS_BLOCKED_UNSUPPORTED_TARGET,
				ownerBootId = null,
				ownerToken = null,
				leaseGeneration = 0L,
				leaseExpiresElapsedNanos = null,
				startedAtMs = null,
				completedAtMs = null,
				suppressedOutboxCount = 0L,
				failureCode = "UNSUPPORTED_LEGACY_PROJECTION",
			),
		)
		legacyTargets(cutoff, pending = true).forEach(
			database.legacyV27ProjectionDrainDao()::saveTarget,
		)
		database.legacyV27ProjectionDrainDao().saveTarget(
			LegacyV27ProjectionTargetEntity(
				projectionId = UNSUPPORTED_PROJECTION_ID,
				projectionVersion = UNSUPPORTED_PROJECTION_VERSION,
				initialActivationOrdinal = 1L,
				initialCheckpointOrdinal = 0L,
				requiredThroughOrdinal = cutoff,
				lastCompletedOrdinal = 0L,
				retentionRequired = true,
				initialRegistrationStatus = "ACTIVE",
				disposition =
					LegacyV27ProjectionTargetEntity.DISPOSITION_BLOCKED_UNSUPPORTED,
				completedAtMs = null,
				failureCode = "UNSUPPORTED_LEGACY_PROJECTION",
			),
		)
		registerPendingEventFrame()
		database.sourceProjectionStateDao().register(
			SourceProjectionRegistrationEntity(
				projectionId = UNSUPPORTED_PROJECTION_ID,
				projectionVersion = UNSUPPORTED_PROJECTION_VERSION,
				activationOrdinal = 1L,
				retentionRequired = true,
				status = "LEGACY_V27_PENDING",
				createdAtMs = 0L,
			),
		)
	}

	private suspend fun registerPendingEventFrame() {
		database.sourceProjectionStateDao().register(
			SourceProjectionRegistrationEntity(
				projectionId = "event-tracking-frame",
				projectionVersion = 1,
				activationOrdinal = 1L,
				retentionRequired = true,
				status = "LEGACY_V27_PENDING",
				createdAtMs = 0L,
			),
		)
	}

	private suspend fun seedCompletedLegacyDrain(
		cutoff: Long,
		eventFrameRegistered: Boolean = true,
	) {
		database.sourceEvidenceStateDao().ensure()
		database.legacyV27ProjectionDrainDao().saveDrain(
			LegacyV27ProjectionDrainEntity(
				cutoffAdmissionOrdinal = cutoff,
				collectedDataEpoch = 0L,
				status = LegacyV27ProjectionDrainEntity.STATUS_COMPLETE,
				ownerBootId = null,
				ownerToken = null,
				leaseGeneration = 1L,
				leaseExpiresElapsedNanos = null,
				startedAtMs = 1L,
				completedAtMs = 2L,
				suppressedOutboxCount = 0L,
				failureCode = null,
			),
		)
		legacyTargets(
			cutoff = cutoff,
			pending = false,
			eventFrameRegistered = eventFrameRegistered,
		).forEach(
			database.legacyV27ProjectionDrainDao()::saveTarget,
		)
		if (eventFrameRegistered) {
			database.sourceProjectionStateDao().register(
				SourceProjectionRegistrationEntity(
					projectionId = "event-tracking-frame",
					projectionVersion = 1,
					activationOrdinal = 1L,
					retentionRequired = true,
					status = "RETIRED",
					createdAtMs = 0L,
				),
			)
		}
	}

	private fun legacyTargets(
		cutoff: Long,
		pending: Boolean,
		eventFrameRegistered: Boolean = true,
	): List<LegacyV27ProjectionTargetEntity> = listOf(
		"activity-automation" to
			LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_STALE_CONTROL,
		"event-tracking-frame" to
			if (eventFrameRegistered) {
				LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS
			} else {
				LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT
			},
		"explicit-tracking-joins" to
			LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNWIRED_OUTPUT,
		"location-domain" to
			LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT,
	).map { (projectionId, terminalDisposition) ->
		LegacyV27ProjectionTargetEntity(
			projectionId = projectionId,
			projectionVersion = 1,
			initialActivationOrdinal = 1L,
			initialCheckpointOrdinal = 0L,
			requiredThroughOrdinal = cutoff,
			lastCompletedOrdinal = if (pending) 0L else cutoff,
			retentionRequired = true,
			initialRegistrationStatus = if (
				projectionId == "event-tracking-frame" &&
				eventFrameRegistered
			) {
				"ACTIVE"
			} else {
				"NOT_REGISTERED_AT_MIGRATION"
			},
			disposition = if (pending) {
				LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING
			} else {
				terminalDisposition
			},
			completedAtMs = if (pending) null else 2L,
			failureCode = null,
		)
	}

	private suspend fun insertMigratedTerminalRun(
		logicalTrackingId: String = LEGACY_TRACKING_ID,
		serviceRunId: String = LEGACY_RUN_ID,
	) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				state = "FINALIZED",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				completedAtMs = null,
				completionReason = "V27_RUNTIME_STOP_REQUESTED",
				bootId = "LEGACY_UNKNOWN",
				leaseGeneration = 0L,
				startOrigin = "AUTOMATIC_BACKGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "TERMINAL_FAILURE",
				runtimeFailureCode = V28_MIGRATION_INTERRUPTION_REASON,
				runRevision = 1L,
				startDeliveryToken = null,
				startCommandGeneration = 0L,
				preparedManifestRevision = 0L,
				preparedIntentRevision = 0L,
				androidDeliveryState = "LEGACY_UNKNOWN",
				androidDeliveryUpdatedAtMs = null,
				startIsUserInitiated = false,
				startIsAmbient = false,
				sessionSegmentId = null,
				presentationAcknowledgement =
					SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE,
				presentationAcknowledgedAtMs = null,
			),
		)
	}

	private suspend fun insertPreMigrationTerminalRun(
		state: String,
		logicalTrackingId: String,
		serviceRunId: String,
	) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				state = state,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				completedAtMs = 2L,
				completionReason = "V27_$state",
				bootId = "LEGACY_UNKNOWN",
				leaseGeneration = 0L,
				startOrigin = "AUTOMATIC_BACKGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = null,
				runtimeAcknowledgement = if (state == "FINALIZED") {
					"PENDING"
				} else {
					"LEGACY_TERMINAL"
				},
				runtimeFailureCode = null,
				runRevision = 0L,
				startDeliveryToken = null,
				startCommandGeneration = 0L,
				preparedManifestRevision = 0L,
				preparedIntentRevision = 0L,
				androidDeliveryState = "LEGACY_UNKNOWN",
				androidDeliveryUpdatedAtMs = null,
				startIsUserInitiated = false,
				startIsAmbient = false,
				sessionSegmentId = null,
				presentationAcknowledgement =
					SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE,
				presentationAcknowledgedAtMs = null,
			),
		)
	}

	private suspend fun insertLegacyStepsWal(
		eventId: String,
		admissionOrdinal: Long,
		logicalTrackingId: String? = LEGACY_TRACKING_ID,
		serviceRunId: String? = LEGACY_RUN_ID,
		integrityIdentity: String = SourceEventWalEntity.LEGACY_CHECKSUM_VERIFIED,
	): SourceEventWalEntity {
		val payload = legacyStepsPayload()
		val row = SourceEventWalEntity(
			admissionOrdinal = admissionOrdinal,
			eventId = eventId,
			providerDedupKey = "legacy-dedup-$eventId",
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			sourceInstanceId = "legacy-steps",
			registrationGeneration = 1L,
			sourceSequence = admissionOrdinal,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = "legacy-boot",
			observedElapsedNanos = admissionOrdinal,
			receivedElapsedNanos = admissionOrdinal + 1L,
			wallTimeMs = 10L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			acquiredAtMs = 10L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = payload.sha256(),
			integrityIdentity = integrityIdentity,
			createdAtMs = 10L,
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(row) shouldBe admissionOrdinal
		return row
	}

	private suspend fun insertCurrentControlStepsWal(
		eventId: String,
		admissionOrdinal: Long,
	): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = admissionOrdinal,
			eventId = eventId,
			providerDedupKey = "current-dedup-$eventId",
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			sourceInstanceId = "current-steps",
			registrationGeneration = 2L,
			physicalConfigurationFingerprint = "a".repeat(64),
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			authorizationFingerprint = "b".repeat(64),
			sourceSequence = admissionOrdinal,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = "current-boot",
			observedElapsedNanos = admissionOrdinal,
			receivedElapsedNanos = admissionOrdinal + 1L,
			receivedWallTimeMs = 20L,
			wallTimeMs = 20L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			sourcePolicyRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 20L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 7,
			payload = byteArrayOf(7),
			payloadChecksum = byteArrayOf(7).sha256(),
			createdAtMs = 20L,
		)
		val row = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		database.sourceEventWalDao().insertIgnoringDuplicate(row) shouldBe admissionOrdinal
		return row
	}

	private suspend fun insertLocationWal(
		eventId: String,
		admissionOrdinal: Long,
	): SourceEventWalEntity {
		val payload = byteArrayOf(1)
		val row = SourceEventWalEntity(
			admissionOrdinal = admissionOrdinal,
			eventId = eventId,
			providerDedupKey = "location-dedup-$eventId",
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
			sourceInstanceId = "location-instance",
			registrationGeneration = 1L,
			sourceSequence = admissionOrdinal,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = "location-boot",
			observedElapsedNanos = admissionOrdinal,
			receivedElapsedNanos = admissionOrdinal + 1L,
			wallTimeMs = 30L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			acquiredAtMs = 30L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = payload.sha256(),
			createdAtMs = 30L,
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(row) shouldBe admissionOrdinal
		return row
	}

	private fun legacyStepsPayload(): ByteArray = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeInt(4)
			output.writeUTF("legacy-boot")
			output.writeLong(100L)
			output.writeLong(104L)
			output.writeLong(4L)
			output.writeLong(1L)
			output.writeLong(2L)
			output.writeLong(1L)
			output.writeLong(2L)
			output.writeBoolean(false)
		}
		buffer.toByteArray()
	}

	private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
		.digest(this)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }

	private fun androidx.sqlite.db.SupportSQLiteDatabase.scalar(sql: String): Long =
		query(sql).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private companion object {
		const val REOPEN_DATABASE = "legacy-v27-steps-wal-retention.db"
		const val LEGACY_TRACKING_ID = "legacy-tracking"
		const val LEGACY_RUN_ID = "legacy-run"
		const val UNSUPPORTED_PROJECTION_ID = "unknown-release-projection"
		const val UNSUPPORTED_PROJECTION_VERSION = 9
	}
}
