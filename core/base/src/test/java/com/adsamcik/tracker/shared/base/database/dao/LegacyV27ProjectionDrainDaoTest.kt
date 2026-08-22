package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
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
class LegacyV27ProjectionDrainDaoTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `lease ownership uses boot elapsed generation and deletion epoch`() = runTest {
		val dao = database.legacyV27ProjectionDrainDao()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7))
		dao.saveDrain(drain(epoch = 7, cutoff = 10))
		dao.saveTarget(target(requiredThrough = 10))

		dao.acquireLease("boot-a", "owner-a", 100, 100, 1_000) shouldBe 0
		dao.acquireLease("boot-a", "owner-a", 100, 200, 1_000) shouldBe 1
		dao.get()?.leaseGeneration shouldBe 1L
		dao.acquireLease("boot-a", "owner-b", 150, 250, 1_001) shouldBe 0
		dao.renewLease("boot-a", "owner-a", 1, 150, 300) shouldBe 1
		dao.get()?.leaseGeneration shouldBe 1L

		// Elapsed time resets at reboot, so a different boot fences the old owner immediately.
		dao.acquireLease("boot-b", "owner-b", 10, 100, 1_002) shouldBe 1
		dao.get()?.leaseGeneration shouldBe 2L
		dao.advanceTarget("projection", 1, 0, 1, "boot-a", "owner-a", 1, 20) shouldBe 0
		dao.advanceTarget("projection", 1, 0, 1, "boot-b", "owner-b", 2, 20) shouldBe 1
		dao.markTargetPartial(
			"projection",
			1,
			"STALE_OWNER",
			"boot-a",
			"owner-a",
			1,
			20,
		) shouldBe 0
		dao.markTargetPartial("projection", 1, "FIRST_GAP", "boot-b", "owner-b", 2, 20) shouldBe 1
		dao.markTargetPartial("projection", 1, "LATER_GAP", "boot-b", "owner-b", 2, 20) shouldBe 1
		dao.target("projection", 1)?.failureCode shouldBe "FIRST_GAP"

		database.sourceEvidenceStateDao().updateLifecycle(8, null, 1_003) shouldBe 1
		dao.advanceTarget("projection", 1, 1, 2, "boot-b", "owner-b", 2, 21) shouldBe 0
		dao.markTargetPartial("projection", 1, "DELETED", "boot-b", "owner-b", 2, 21) shouldBe 0
		dao.target("projection", 1)?.failureCode shouldBe "FIRST_GAP"
		dao.markRetryableFailure("boot-b", "owner-b", 2, 21, "stale") shouldBe 0
	}

	@Test
	fun `bounded wal read and cursor support sparse released ordinals`() = runTest {
		val dao = database.legacyV27ProjectionDrainDao()
		database.sourceEvidenceStateDao().ensure()
		dao.saveDrain(drain(epoch = 0, cutoff = 10))
		dao.saveTarget(target(requiredThrough = 10))
		dao.acquireLease("boot", "owner", 100, 500, 1_000) shouldBe 1
		val wal = database.sourceEventWalDao()
		wal.insertIgnoringDuplicate(event("event-2", 2, 2)) shouldBe 2L
		wal.insertIgnoringDuplicate(event("event-7", 7, 7)) shouldBe 7L
		wal.insertIgnoringDuplicate(event("event-12", 12, 12)) shouldBe 12L

		wal.eventsAfterThrough(0, 10, 10).map { it.admissionOrdinal } shouldBe listOf(2L, 7L)
		dao.advanceTarget("projection", 1, 0, 7, "boot", "owner", 1, 101) shouldBe 1
		wal.eventsAfterThrough(7, 10, 10) shouldBe emptyList()
		dao.terminalizeTarget(
			"projection",
			1,
			7,
			LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
			1_099,
			"PREMATURE",
			"boot",
			"owner",
			1,
			102,
		) shouldBe 0
		// Empty does not mean ordinal 8 exists; the durable high-water cursor can jump to the cutoff.
		dao.markTargetPartial("projection", 1, "SPARSE_GAP", "boot", "owner", 1, 102) shouldBe 1
		dao.advanceTarget("projection", 1, 7, 10, "boot", "owner", 1, 102) shouldBe 1
		dao.terminalizeTarget(
			"projection",
			1,
			10,
			LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
			1_100,
			null,
			"boot",
			"owner",
			1,
			103,
		) shouldBe 1
		val completed = requireNotNull(dao.target("projection", 1))
		completed.lastCompletedOrdinal shouldBe 10L
		completed.failureCode shouldBe "SPARSE_GAP"
	}

	@Test
	fun `unsupported generation durably blocks startup under the active lease`() = runTest {
		val dao = database.legacyV27ProjectionDrainDao()
		database.sourceEvidenceStateDao().ensure()
		dao.saveDrain(drain(epoch = 0, cutoff = 10))
		dao.acquireLease("boot", "owner", 100, 500, 1_000) shouldBe 1

		dao.blockUnsupportedTarget("UNKNOWN_OUTBOX_GENERATION", "boot", "stale", 1, 101) shouldBe 0
		dao.blockUnsupportedTarget("UNKNOWN_OUTBOX_GENERATION", "boot", "owner", 1, 101) shouldBe 1

		dao.get()?.status shouldBe LegacyV27ProjectionDrainEntity.STATUS_BLOCKED_UNSUPPORTED_TARGET
		dao.get()?.failureCode shouldBe "UNKNOWN_OUTBOX_GENERATION"
		dao.get()?.ownerToken shouldBe null
		dao.acquireLease("boot", "owner", 102, 600, 1_001) shouldBe 0
	}

	@Test
	fun `outbox terminalization is generation scoped retained and idempotently accounted`() = runTest {
		val dao = database.legacyV27ProjectionDrainDao()
		database.sourceEvidenceStateDao().ensure()
		dao.saveDrain(drain(epoch = 0, cutoff = 10))
		dao.saveTarget(target(requiredThrough = 10, completedThrough = 10))
		dao.acquireLease("boot", "owner", 100, 500, 1_000) shouldBe 1
		val outbox = database.sourceProjectionStateDao()
		outbox.insertOutbox(outbox("known-v1", "projection", 1, 4)) shouldBe 1L
		outbox.insertOutbox(outbox("known-v1-bulk", "projection", 1, 8)) shouldBe 2L
		outbox.insertOutbox(outbox("known-v1-live", "projection", 1, 11)) shouldBe 3L
		outbox.insertOutbox(outbox("outbox-only-v2", "projection", 2, 5)) shouldBe 4L
		outbox.insertOutbox(outbox("outbox-only-other", "other", 9, 6)) shouldBe 5L

		dao.unknownPendingOutboxGenerations() shouldBe listOf(
			LegacyV27OutboxGenerationRow("other", 9),
			LegacyV27OutboxGenerationRow("projection", 2),
		)
		dao.pendingOutbox("projection", 1, 10).map { it.stableId } shouldBe
			listOf("known-v1", "known-v1-bulk")
		dao.terminalizePendingOutbox(
			"known-v1",
			OUTBOX_SUPPRESSED,
			1_099,
			"boot",
			"stale",
			1,
			101,
		) shouldBe 0
		dao.terminalizePendingOutbox(
			"known-v1",
			OUTBOX_SUPPRESSED,
			1_100,
			"boot",
			"owner",
			1,
			101,
		) shouldBe 1
		dao.terminalizePendingOutbox(
			"known-v1",
			OUTBOX_SUPPRESSED,
			1_101,
			"boot",
			"owner",
			1,
			102,
		) shouldBe 0
		dao.pendingOutboxCount("projection", 1) shouldBe 1L
		dao.terminalizePendingOutbox(
			"projection",
			1,
			OUTBOX_SUPPRESSED,
			1_100,
			"boot",
			"owner",
			1,
			101,
		) shouldBe 1
		dao.terminalizePendingOutbox(
			"projection",
			1,
			OUTBOX_SUPPRESSED,
			1_101,
			"boot",
			"owner",
			1,
			102,
		) shouldBe 0
		dao.terminalizedOutboxCount(listOf(OUTBOX_SUPPRESSED)) shouldBe 2L
		dao.setSuppressedOutboxCount(2, "boot", "owner", 1, 103) shouldBe 1
		dao.setSuppressedOutboxCount(2, "boot", "owner", 1, 104) shouldBe 1

		listOf("projection" to 2, "other" to 9).forEach { (projectionId, version) ->
			dao.terminalizePendingOutbox(
				projectionId,
				version,
				OUTBOX_SUPPRESSED,
				1_200,
				"boot",
				"owner",
				1,
				105,
			) shouldBe 1
		}
		dao.terminalizePendingOutbox(
			"known-v1-live",
			OUTBOX_SUPPRESSED,
			1_201,
			"boot",
			"owner",
			1,
			105,
		) shouldBe 0
		dao.terminalizeTarget(
			"projection",
			1,
			10,
			LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNWIRED_OUTPUT,
			1_300,
			null,
			"boot",
			"owner",
			1,
			106,
		) shouldBe 1
		dao.completeDrain(
			LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL,
			1_400,
			"UNWIRED_V27_OUTPUT",
			"boot",
			"owner",
			1,
			107,
		) shouldBe 1

		dao.get()?.status shouldBe LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL
		dao.get()?.suppressedOutboxCount shouldBe 2L
		// The post-cutoff row is live v28 work and the terminal v27 rows remain for retention.
		outbox.pendingOutbox(10).map { it.stableId } shouldBe listOf("known-v1-live")
		rawCount(
			"SELECT COUNT(*) FROM source_projection_outbox WHERE terminal_disposition = '$OUTBOX_SUPPRESSED'",
		) shouldBe 4L
	}

	@Test
	fun `legacy cleanup cannot touch a live projection generation`() = runTest {
		val dao = database.legacyV27ProjectionDrainDao()
		database.sourceEvidenceStateDao().ensure()
		dao.saveDrain(drain(epoch = 0, cutoff = 10))
		dao.acquireLease("boot", "owner", 100, 500, 1_000) shouldBe 1
		val state = database.sourceProjectionStateDao()
		listOf(1, 2).forEach { version ->
			state.register(
				SourceProjectionRegistrationEntity(
					"projection",
					version,
					1,
					true,
					if (version == 1) "LEGACY_V27_PENDING" else "ACTIVE",
					1_000,
				),
			)
			state.saveCheckpoint(SourceProjectionCheckpointEntity("projection", version, 5, 1, 1_000))
			state.saveFailure(SourceProjectionFailureEntity("projection", version, 5, 1, "failure", false, 1_000))
			state.saveJoinState(
				SourceProjectionJoinStateEntity(
					"projection",
					version,
					"join",
					null,
					5,
					1,
					byteArrayOf(1),
					1_000,
				),
			)
		}

		dao.deleteLegacyV1JoinState("projection", "boot", "owner", 1, 101) shouldBe 1
		dao.deleteLegacyV1Failures("projection", "boot", "owner", 1, 101) shouldBe 1
		dao.deleteLegacyV1Checkpoint("projection", "boot", "owner", 1, 101) shouldBe 1
		dao.retireLegacyV1Registration("projection", "boot", "owner", 1, 101) shouldBe 1

		state.registration("projection", 1)?.status shouldBe "RETIRED"
		state.checkpoint("projection", 1) shouldBe null
		state.failure("projection", 1, 5) shouldBe null
		state.joinStates("projection", 1) shouldBe emptyList()
		state.registration("projection", 2)?.status shouldBe "ACTIVE"
		state.checkpoint("projection", 2)?.contiguousAdmissionOrdinal shouldBe 5L
		state.failure("projection", 2, 5)?.failureCode shouldBe "failure"
		state.joinStates("projection", 2).size shouldBe 1
	}

	private fun drain(epoch: Long, cutoff: Long) = LegacyV27ProjectionDrainEntity(
		cutoffAdmissionOrdinal = cutoff,
		collectedDataEpoch = epoch,
		status = LegacyV27ProjectionDrainEntity.STATUS_PENDING,
		ownerBootId = null,
		ownerToken = null,
		leaseGeneration = 0,
		leaseExpiresElapsedNanos = null,
		startedAtMs = null,
		completedAtMs = null,
		suppressedOutboxCount = 0,
		failureCode = null,
	)

	private fun target(
		requiredThrough: Long,
		completedThrough: Long = 0,
	) = LegacyV27ProjectionTargetEntity(
		projectionId = "projection",
		projectionVersion = 1,
		initialActivationOrdinal = 1,
		initialCheckpointOrdinal = 0,
		requiredThroughOrdinal = requiredThrough,
		lastCompletedOrdinal = completedThrough,
		retentionRequired = true,
		initialRegistrationStatus = "ACTIVE",
		disposition = LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING,
		completedAtMs = null,
		failureCode = null,
	)

	private fun outbox(
		stableId: String,
		projectionId: String,
		projectionVersion: Int,
		ordinal: Long,
	) = SourceProjectionOutboxEntity(
		stableId = stableId,
		projectionId = projectionId,
		projectionVersion = projectionVersion,
		admissionOrdinal = ordinal,
		effectKind = "legacy-v27-test",
		payloadVersion = 1,
		payload = byteArrayOf(1),
		createdAtMs = 1_000,
		deliveredAtMs = null,
	)

	private fun event(
		eventId: String,
		ordinal: Long,
		sequence: Long,
	) = SourceEventWalEntity(
		admissionOrdinal = ordinal,
		eventId = eventId,
		providerDedupKey = null,
		logicalTrackingId = null,
		serviceRunId = null,
		sourceKind = 1,
		sourceInstanceId = "legacy-v27",
		registrationGeneration = 1,
		sourceSequence = sequence,
		configRevision = 1,
		planAttribution = 0,
		clockDomainId = "legacy-boot",
		observedElapsedNanos = ordinal,
		receivedElapsedNanos = ordinal,
		wallTimeMs = 1_000 + ordinal,
		wallTimeUncertaintyMs = 0,
		capturedCollectedDataEpoch = 0,
		acquiredAtMs = 1_000 + ordinal,
		qualityFlags = 0,
		qualityConfidence = null,
		payloadVersion = 1,
		payload = byteArrayOf(1),
		payloadChecksum = "legacy-checksum",
		createdAtMs = 1_000 + ordinal,
	)

	private fun rawCount(sql: String): Long = database.openHelper.writableDatabase
		.query(sql)
		.use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private companion object {
		const val OUTBOX_SUPPRESSED = "MIGRATION_SUPPRESSED_UNWIRED_OUTPUT"
	}
}
