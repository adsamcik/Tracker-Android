package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceEventStorageMaintenanceTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `blocked Steps batch rolls back locally while every other source commits`() = runTest {
		val sourceKinds = listOf(
			SourceDestinationOwnerEntity.SOURCE_LOCATION,
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceDestinationOwnerEntity.SOURCE_CELL,
		)
		sourceKinds.forEachIndexed { index, sourceKind ->
			val ordinal = index + 1L
			insertEvent(
				sourceKind = sourceKind,
				admissionOrdinal = ordinal,
				authenticSteps = sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS,
			)
			insertDeliveredOutbox(sourceKind, ordinal)
		}

		val first = database.pruneSourceEventStorageBefore(createdBeforeMs = 100L)

		first shouldBe SourceEventStoragePruneResult.Deferred(
			walEventsDeleted = 5,
			deliveredEffectsDeleted = 5,
			orphanedDeliveredEffectsDeleted = 0,
			sourceOutcomes = listOf(
				SourceEventStorageSourcePruneOutcome.Applied(
					SourceDestinationOwnerEntity.SOURCE_LOCATION,
					1,
					1,
				),
				SourceEventStorageSourcePruneOutcome.Applied(
					SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
					1,
					1,
				),
				SourceEventStorageSourcePruneOutcome.Blocked(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
					0,
					0,
				),
				SourceEventStorageSourcePruneOutcome.Applied(
					SourceDestinationOwnerEntity.SOURCE_PRESSURE,
					1,
					1,
				),
				SourceEventStorageSourcePruneOutcome.Applied(
					SourceDestinationOwnerEntity.SOURCE_WIFI,
					1,
					1,
				),
				SourceEventStorageSourcePruneOutcome.Applied(
					SourceDestinationOwnerEntity.SOURCE_CELL,
					1,
					1,
				),
			),
			sourceDebt = listOf(
				SourceEventStorageSourcePruneOutcome.Blocked(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
					0,
					0,
				),
			),
		)
		sourceKinds.filterNot { it == SourceDestinationOwnerEntity.SOURCE_STEPS }.forEach {
			database.sourceEventWalDao().getByAdmissionOrdinal(it.toLong()) shouldBe null
			database.sourceProjectionStateDao().outbox("outbox-$it") shouldBe null
		}
		database.sourceEventWalDao().getByAdmissionOrdinal(3L)?.eventId shouldBe "event-3"
		database.sourceProjectionStateDao().outbox("outbox-3")?.stableId shouldBe "outbox-3"

		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE admission_ordinal = 3",
		)
		insertEvent(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			admissionOrdinal = 3L,
			authenticSteps = true,
		)

		val retry = database.pruneSourceEventStorageBefore(createdBeforeMs = 100L)

		retry shouldBe SourceEventStoragePruneResult.Complete(
			walEventsDeleted = 1,
			deliveredEffectsDeleted = 1,
			orphanedDeliveredEffectsDeleted = 0,
			sourceOutcomes = sourceKinds.map { sourceKind ->
				if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS) {
					SourceEventStorageSourcePruneOutcome.Applied(sourceKind, 1, 1)
				} else {
					SourceEventStorageSourcePruneOutcome.NoChange(sourceKind)
				}
			},
		)
		database.pruneSourceEventStorageBefore(createdBeforeMs = 100L) shouldBe
			SourceEventStoragePruneResult.Complete(
				walEventsDeleted = 0,
				deliveredEffectsDeleted = 0,
				orphanedDeliveredEffectsDeleted = 0,
				sourceOutcomes = sourceKinds.map {
					SourceEventStorageSourcePruneOutcome.NoChange(it)
				},
			)
	}

	@Test
	fun `Steps maintenance overflow is returned as exact source debt`() = runTest {
		insertEvent(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			admissionOrdinal = 1L,
			authenticSteps = true,
		)
		insertDeliveredOutbox(SourceDestinationOwnerEntity.SOURCE_STEPS, 1L)

		val result = database.pruneSourceEventStorageBefore(
			createdBeforeMs = 100L,
			batchSize = 2_049,
		)

		val deferred = result as SourceEventStoragePruneResult.Deferred
		deferred.sourceDebt shouldBe listOf(
			SourceEventStorageSourcePruneOutcome.Blocked(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				reason = SourceEventStoragePruneBlockedReason.MAINTENANCE_OVERFLOW,
				walEventsDeleted = 0,
				deliveredEffectsDeleted = 0,
			),
		)
		deferred.walEventsDeleted shouldBe 0
		deferred.deliveredEffectsDeleted shouldBe 0
		database.sourceEventWalDao().getByAdmissionOrdinal(1L)?.eventId shouldBe "event-1"
		database.sourceProjectionStateDao().outbox("outbox-3")?.stableId shouldBe "outbox-3"
	}

	@Test
	fun `multiple source debts aggregate while later source progress commits`() = runTest {
		listOf(
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		).forEachIndexed { index, sourceKind ->
			insertEvent(
				sourceKind = sourceKind,
				admissionOrdinal = index + 1L,
				authenticSteps = true,
			)
		}
		val attempted = mutableListOf<Int>()

		val result = database.pruneSourceEventStorageBeforeWithSourceOperations(
			createdBeforeMs = 100L,
			batchSize = 10,
			verifyCollectedDataAccess = {},
			sourceKinds = listOf(
				SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			),
			pruneSourceBatch = {
					sourceKind, globalSafeOrdinal, sourceCreatedBeforeMs, sourceBatchSize ->
				when (sourceKind) {
					SourceDestinationOwnerEntity.SOURCE_ACTIVITY -> {
						sourceEventWalDao().deleteProjectedSourceBatch(
							sourceKind,
							globalSafeOrdinal,
							sourceCreatedBeforeMs,
							sourceBatchSize,
						)
						SourceEventStoragePruneBatchResult.Blocked(
							SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
						)
					}
					SourceDestinationOwnerEntity.SOURCE_STEPS ->
						SourceEventStoragePruneBatchResult.Blocked(
							SourceEventStoragePruneBlockedReason.MAINTENANCE_OVERFLOW,
						)
					else -> SourceEventStoragePruneBatchResult.Applied(
						walEventsDeleted = sourceEventWalDao().deleteProjectedSourceBatch(
							sourceKind,
							globalSafeOrdinal,
							sourceCreatedBeforeMs,
							sourceBatchSize,
						),
						deliveredEffectsDeleted = 0,
					)
				}
			},
			afterSource = { attempted += it.sourceKind },
		)

		val deferred = result as SourceEventStoragePruneResult.Deferred
		attempted shouldBe listOf(
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		)
		deferred.sourceDebt.map { it.sourceKind to it.reason } shouldBe listOf(
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY to
				SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
			SourceDestinationOwnerEntity.SOURCE_STEPS to
				SourceEventStoragePruneBlockedReason.MAINTENANCE_OVERFLOW,
		)
		deferred.walEventsDeleted shouldBe 1
		database.sourceEventWalDao().getByAdmissionOrdinal(1L)?.eventId shouldBe "event-2"
		database.sourceEventWalDao().getByAdmissionOrdinal(2L)?.eventId shouldBe "event-3"
		database.sourceEventWalDao().getByAdmissionOrdinal(3L) shouldBe null
	}

	@Test
	fun `cancellation between sources preserves committed progress and skips later sources`() = runTest {
		listOf(
			SourceDestinationOwnerEntity.SOURCE_LOCATION,
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		).forEachIndexed { index, sourceKind ->
			insertEvent(
				sourceKind = sourceKind,
				admissionOrdinal = index + 1L,
				authenticSteps = true,
			)
		}

		assertFailsWith<CancellationException> {
			database.pruneSourceEventStorageBeforeWithSourceOperations(
				createdBeforeMs = 100L,
				batchSize = 10,
				verifyCollectedDataAccess = {},
				sourceKinds = listOf(
					SourceDestinationOwnerEntity.SOURCE_LOCATION,
					SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
					SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				),
				afterSource = { outcome ->
					if (outcome.sourceKind == SourceDestinationOwnerEntity.SOURCE_LOCATION) {
						throw CancellationException("cancel between source transactions")
					}
				},
			)
		}

		database.sourceEventWalDao().getByAdmissionOrdinal(1L) shouldBe null
		database.sourceEventWalDao().getByAdmissionOrdinal(2L)?.eventId shouldBe "event-2"
		database.sourceEventWalDao().getByAdmissionOrdinal(3L)?.eventId shouldBe "event-4"
	}

	private suspend fun insertEvent(
		sourceKind: Int,
		admissionOrdinal: Long,
		authenticSteps: Boolean,
	) {
		val payload = byteArrayOf(sourceKind.toByte())
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = admissionOrdinal,
			eventId = "event-$sourceKind",
			providerDedupKey = "dedup-$sourceKind",
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = sourceKind,
			sourceInstanceId = "instance-$sourceKind",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint =
				if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && authenticSteps) {
					"a".repeat(64)
				} else {
					null
				},
			authorizationRevision =
				if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && authenticSteps) 1L else null,
			authorizationPurposeEligibilityMask =
				if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && authenticSteps) {
					SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
				} else {
					0L
				},
			authorizationFingerprint =
				if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && authenticSteps) {
					"b".repeat(64)
				} else {
					null
				},
			sourceSequence = admissionOrdinal,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = "boot-$sourceKind",
			observedElapsedNanos = admissionOrdinal,
			receivedElapsedNanos = admissionOrdinal + 1L,
			receivedWallTimeMs = 10L,
			wallTimeMs = 10L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			sourcePolicyRevision =
				if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && authenticSteps) 1L else null,
			lifecycleLeaseGeneration =
				if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && authenticSteps) 1L else null,
			acquiredAtMs = 10L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS) 7 else 1,
			payload = payload,
			payloadChecksum = payload.sha256(),
			createdAtMs = 10L,
		)
		val row = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		database.sourceEventWalDao().insertIgnoringDuplicate(row) shouldBe admissionOrdinal
	}

	private suspend fun insertDeliveredOutbox(sourceKind: Int, admissionOrdinal: Long) {
		database.sourceProjectionStateDao().insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "outbox-$sourceKind",
				projectionId = "projection-$sourceKind",
				projectionVersion = 1,
				admissionOrdinal = admissionOrdinal,
				effectKind = "effect-$sourceKind",
				payloadVersion = 1,
				payload = byteArrayOf(sourceKind.toByte()),
				createdAtMs = 10L,
				deliveredAtMs = 20L,
			),
		) shouldBe 1L
	}

	private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
		.digest(this)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
