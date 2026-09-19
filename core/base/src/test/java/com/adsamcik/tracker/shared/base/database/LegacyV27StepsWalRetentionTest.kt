package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
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
	}

	private suspend fun seedCompletedLegacyDrain(cutoff: Long) {
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
		legacyTargets(cutoff, pending = false).forEach(
			database.legacyV27ProjectionDrainDao()::saveTarget,
		)
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

	private fun legacyTargets(
		cutoff: Long,
		pending: Boolean,
	): List<LegacyV27ProjectionTargetEntity> = listOf(
		"activity-automation" to
			LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_STALE_CONTROL,
		"event-tracking-frame" to
			LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS,
		"explicit-tracking-joins" to
			LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNWIRED_OUTPUT,
		"location-domain" to
			LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_RETAINED,
	).map { (projectionId, terminalDisposition) ->
		LegacyV27ProjectionTargetEntity(
			projectionId = projectionId,
			projectionVersion = 1,
			initialActivationOrdinal = 1L,
			initialCheckpointOrdinal = 0L,
			requiredThroughOrdinal = cutoff,
			lastCompletedOrdinal = if (pending) 0L else cutoff,
			retentionRequired = true,
			initialRegistrationStatus = if (projectionId == "event-tracking-frame") {
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

	private suspend fun insertMigratedTerminalRun() {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = LEGACY_RUN_ID,
				logicalTrackingId = LEGACY_TRACKING_ID,
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

	private suspend fun insertLegacyStepsWal(
		eventId: String,
		admissionOrdinal: Long,
	): SourceEventWalEntity {
		val payload = legacyStepsPayload()
		val row = SourceEventWalEntity(
			admissionOrdinal = admissionOrdinal,
			eventId = eventId,
			providerDedupKey = "legacy-dedup-$eventId",
			logicalTrackingId = LEGACY_TRACKING_ID,
			serviceRunId = LEGACY_RUN_ID,
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
			integrityIdentity = SourceEventWalEntity.LEGACY_CHECKSUM_VERIFIED,
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
	}
}
