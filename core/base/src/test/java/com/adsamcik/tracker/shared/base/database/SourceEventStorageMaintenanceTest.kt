package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
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

// One real WAL and retention fixture keeps source-isolated pruning and retained proofs comparable.
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceEventStorageMaintenanceTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `pending all-day sensor checkpoint retains its own WAL while independently projected siblings prune`() = runTest {
		val activity = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		val pressure = SourceDestinationOwnerEntity.SOURCE_PRESSURE
		insertEvent(activity, 1L, false)
		insertEvent(pressure, 2L, false)
		insertEvent(SourceDestinationOwnerEntity.SOURCE_CELL, 3L, false)
		registerRetainingProjection(AllDaySensorObservationIdentity.ACTIVITY_PROJECTION_ID,
			AllDaySensorObservationIdentity.PROJECTION_VERSION, 0L)
		registerRetainingProjection(AllDaySensorObservationIdentity.PRESSURE_PROJECTION_ID,
			AllDaySensorObservationIdentity.PROJECTION_VERSION, 3L)
		database.pruneSourceEventStorageBefore(100L)
		database.sourceEventWalDao().getByAdmissionOrdinal(1L)?.sourceKind shouldBe activity
		database.sourceEventWalDao().getByAdmissionOrdinal(2L) shouldBe null
		database.sourceEventWalDao().getByAdmissionOrdinal(3L) shouldBe null
	}

	@Test
	fun `idle all-day Pressure retains unprojected Pressure without pinning Activity cleanup`() = runTest {
		insertEvent(SourceDestinationOwnerEntity.SOURCE_ACTIVITY, 1L, false)
		insertEvent(SourceDestinationOwnerEntity.SOURCE_PRESSURE, 2L, false)
		registerRetainingProjection(AllDaySensorObservationIdentity.PRESSURE_PROJECTION_ID,
			AllDaySensorObservationIdentity.PROJECTION_VERSION, null)
		database.pruneSourceEventStorageBefore(100L)
		database.sourceEventWalDao().getByAdmissionOrdinal(1L) shouldBe null
		database.sourceEventWalDao().getByAdmissionOrdinal(2L)?.sourceKind shouldBe SourceDestinationOwnerEntity.SOURCE_PRESSURE
	}

	@Test
	fun `unknown all-day sensor version retains conservative global WAL boundary`() = runTest {
		insertEvent(SourceDestinationOwnerEntity.SOURCE_ACTIVITY, 1L, false)
		insertEvent(SourceDestinationOwnerEntity.SOURCE_CELL, 2L, false)
		registerRetainingProjection(AllDaySensorObservationIdentity.ACTIVITY_PROJECTION_ID,
			AllDaySensorObservationIdentity.PROJECTION_VERSION + 1, 0L)
		database.pruneSourceEventStorageBefore(100L)
		database.sourceEventWalDao().countAll() shouldBe 2L
	}

	@Test
	fun `pending background Wi-Fi protects its own unsaved data while Cell and Pressure can prune`() = runTest {
		insertEvent(SourceDestinationOwnerEntity.SOURCE_WIFI, 1L, false)
		insertEvent(SourceDestinationOwnerEntity.SOURCE_CELL, 2L, false)
		insertEvent(SourceDestinationOwnerEntity.SOURCE_PRESSURE, 3L, false)
		registerRetainingProjection(AmbientWifiFactRevisionEntity.WRITER_ID, 1, 0L)
		registerRetainingProjection(AmbientCellFactRevisionEntity.WRITER_ID, 1, 3L)

		database.pruneSourceEventStorageBefore(100L)

		database.sourceEventWalDao().getByAdmissionOrdinal(1L)?.sourceKind shouldBe SourceDestinationOwnerEntity.SOURCE_WIFI
		database.sourceEventWalDao().getByAdmissionOrdinal(2L) shouldBe null
		database.sourceEventWalDao().getByAdmissionOrdinal(3L) shouldBe null
	}

	@Test
	fun `pending background Cell protects its own unsaved data without retaining Wi-Fi`() = runTest {
		insertEvent(SourceDestinationOwnerEntity.SOURCE_WIFI, 1L, false)
		insertEvent(SourceDestinationOwnerEntity.SOURCE_CELL, 2L, false)
		registerRetainingProjection(AmbientWifiFactRevisionEntity.WRITER_ID, 1, 2L)
		registerRetainingProjection(AmbientCellFactRevisionEntity.WRITER_ID, 1, 0L)

		database.pruneSourceEventStorageBefore(100L)

		database.sourceEventWalDao().getByAdmissionOrdinal(1L) shouldBe null
		database.sourceEventWalDao().getByAdmissionOrdinal(2L)?.sourceKind shouldBe SourceDestinationOwnerEntity.SOURCE_CELL
	}

	@Test
	fun `missing background checkpoint protects the source from its registration boundary`() = runTest {
		insertEvent(SourceDestinationOwnerEntity.SOURCE_WIFI, 1L, false)
		insertEvent(SourceDestinationOwnerEntity.SOURCE_CELL, 2L, false)
		registerRetainingProjection(AmbientWifiFactRevisionEntity.WRITER_ID, 1, null)

		database.pruneSourceEventStorageBefore(100L)

		database.sourceEventWalDao().getByAdmissionOrdinal(1L)?.sourceKind shouldBe SourceDestinationOwnerEntity.SOURCE_WIFI
		database.sourceEventWalDao().getByAdmissionOrdinal(2L) shouldBe null
	}

	@Test
	fun `unknown projection and unknown background writer version keep conservative global retention`() = runTest {
		insertEvent(SourceDestinationOwnerEntity.SOURCE_WIFI, 1L, false)
		insertEvent(SourceDestinationOwnerEntity.SOURCE_CELL, 2L, false)
		registerRetainingProjection("unknown-reader", 1, 0L)
		registerRetainingProjection(AmbientWifiFactRevisionEntity.WRITER_ID, 2, 0L)

		database.pruneSourceEventStorageBefore(100L)
		database.sourceEventWalDao().countAll() shouldBe 2L
		database.sourceProjectionStateDao().deleteAllRegistrations()
		registerRetainingProjection(AmbientWifiFactRevisionEntity.WRITER_ID, 2, 0L)
		database.pruneSourceEventStorageBefore(100L)
		database.sourceEventWalDao().countAll() shouldBe 2L
	}

	private suspend fun registerRetainingProjection(id: String, version: Int, checkpoint: Long?) {
		database.sourceProjectionStateDao().register(SourceProjectionRegistrationEntity(
			id, version, 1L, true, "ACTIVE", 1L,
		))
		if (checkpoint != null) {
			database.sourceProjectionStateDao().saveCheckpoint(SourceProjectionCheckpointEntity(
			id, version, checkpoint, 1, 1L,
		))
		}
	}

	@Test
	// Expiry must wait for capture drain while preserving fresh Control and independent ambient data.
	@Suppress("LongMethod")
	fun `control expiry waits for the exact capture lane before retiring shared raw Activity`() = runTest {
		val source = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		fun signed(row: SourceEventWalEntity) =
			row.copy(integrityIdentity = row.calculatedIntegrityIdentity())
		val expired = signed(event(source, 1L, true, "expired-control", false).copy(
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			clockDomainId = "previous-boot",
		))
		val capturedSibling = signed(event(source, 2L, true, "captured-sibling", true).copy(
			authorizationPurposeEligibilityMask =
				SourceBrokerPurpose.MASK_CONTROL_AUTOSTART or
					SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			clockDomainId = "previous-boot",
		))
		val now = ACTIVITY_CONTROL_RETENTION_NANOS + 100L
		val fresh = signed(event(source, 3L, true, "fresh-control", false).copy(
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			clockDomainId = "current-boot",
			observedElapsedNanos = now - 2L,
			receivedElapsedNanos = now - 1L,
		))
		val ambientSibling = signed(event(source, 4L, true, "ambient-sibling", false).copy(
			authorizationPurposeEligibilityMask =
				SourceBrokerPurpose.MASK_CONTROL_AUTOSTART or
					SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			clockDomainId = "previous-boot",
		))
		listOf(expired, capturedSibling, fresh, ambientSibling).forEach { row ->
			database.sourceEventWalDao().insertIgnoringDuplicate(row) shouldBe row.admissionOrdinal
		}
		database.pruneExpiredControlActivityWalBatch("current-boot", now, 100L) shouldBe 1
		database.sourceEventWalDao().getByAdmissionOrdinal(1L) shouldBe null
		database.sourceEventWalDao().getByAdmissionOrdinal(2L)?.eventId shouldBe "captured-sibling"
		database.sourceEventWalDao().getByAdmissionOrdinal(3L)?.eventId shouldBe "fresh-control"
		database.sourceEventWalDao().getByAdmissionOrdinal(4L)?.eventId shouldBe "ambient-sibling"
		database.sourceEventRetentionProofDao().get(source, "expired-control")?.hasValidStoredIntegrity() shouldBe true
		database.sourceProjectionStateDao().installProductLane(SourceProductProjectionLaneEntity(
			sourceKind = source,
			bindingGeneration = 1L,
			projectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			captureModeMask = 1L,
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			activatedRolloutRevision = 1L,
			activationOrdinal = 1L,
			contiguousAdmissionOrdinal = 1L,
			retentionRequired = true,
			status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
			installedAtMs = 10L,
			updatedAtMs = 10L,
		))
		database.pruneExpiredControlActivityWalBatch("current-boot", now, 100L) shouldBe 0
		database.sourceEventWalDao().getByAdmissionOrdinal(2L)?.eventId shouldBe "captured-sibling"
		database.sourceProjectionStateDao().advanceProductLaneCursor(
			source, 1L, SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			1L, 2L, 20L,
		) shouldBe 1
		database.sourceProjectionStateDao().fenceProductLaneCaptureAdmission(
			source, 1L, SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			2L, 21L,
		) shouldBe 1
		database.sourceProjectionStateDao().retireFencedProductLane(
			source, 1L, SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			2L, 2L, SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN, 22L,
		) shouldBe 1
		database.pruneExpiredControlActivityWalBatch("current-boot", now, 100L) shouldBe 1
		database.sourceEventWalDao().getByAdmissionOrdinal(2L) shouldBe null
		database.sourceEventWalDao().getByAdmissionOrdinal(4L)?.eventId shouldBe "ambient-sibling"
		val capturedProof = requireNotNull(database.sourceEventRetentionProofDao().get(source, "captured-sibling"))
		capturedProof.hasValidStoredIntegrity() shouldBe true
		capturedProof.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE shouldBe
			SourceBrokerPurpose.MASK_SESSION_CAPTURE
	}

	@Test
	fun `unknown Activity effect version keeps its raw carrier for a newer reader`() = runTest {
		val source = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		val row = event(source, 1L, true, "future-effect", false).copy(
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			clockDomainId = "previous-boot",
		).let { it.copy(integrityIdentity = it.calculatedIntegrityIdentity()) }
		database.sourceEventWalDao().insertIgnoringDuplicate(row) shouldBe 1L
		database.sourceProjectionStateDao().insertOutbox(SourceProjectionOutboxEntity(
			stableId = "future-effect",
			projectionId = "activity-automation",
			projectionVersion = 5,
			admissionOrdinal = 1L,
			effectKind = "activity-automation-v1",
			payloadVersion = 5,
			payload = byteArrayOf(1),
			createdAtMs = 10L,
			deliveredAtMs = null,
		)) shouldBe 1L

		database.pruneExpiredControlActivityWalBatch("current-boot", 100L, 100L) shouldBe 0
		database.sourceEventWalDao().getByAdmissionOrdinal(1L)?.eventId shouldBe "future-effect"
	}

	@Test
	// The failed source, healthy siblings and retry belong to one source-isolated pruning journey.
	@Suppress("LongMethod")
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
		database.sourceEventWalDao().getByAdmissionOrdinal(1L)?.eventId shouldBe "event-3"
		database.sourceProjectionStateDao().outbox("outbox-3")?.stableId shouldBe "outbox-3"
	}

	@Test
	// One prune call must preserve both debts and still commit the later healthy source.
	@Suppress("LongMethod")
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

	@Test
	fun `retention proof is exact idempotent and bounded per source`() = runTest {
		repeat(3) { index ->
			insertEvent(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
				admissionOrdinal = index + 1L,
				authenticSteps = true,
				eventId = "location-event-$index",
			)
		}

		database.pruneSourceEventStorageBeforeWithSourceOperations(
			createdBeforeMs = 100L,
			batchSize = 10,
			verifyCollectedDataAccess = {},
			sourceKinds = listOf(SourceDestinationOwnerEntity.SOURCE_LOCATION),
			maximumRetentionProofsPerSource = 2,
		)
		val proofs = database.sourceEventRetentionProofDao()
			.allForSource(SourceDestinationOwnerEntity.SOURCE_LOCATION)
		proofs.map(SourceEventRetentionProofEntity::sourceAdmissionOrdinal) shouldBe listOf(2L, 3L)
		proofs.all(SourceEventRetentionProofEntity::hasValidStoredIntegrity) shouldBe true

		insertEvent(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
			admissionOrdinal = 3L,
			authenticSteps = true,
			eventId = "location-event-2",
		)
		database.pruneSourceEventStorageBeforeWithSourceOperations(
			createdBeforeMs = 100L,
			batchSize = 10,
			verifyCollectedDataAccess = {},
			sourceKinds = listOf(SourceDestinationOwnerEntity.SOURCE_LOCATION),
			maximumRetentionProofsPerSource = 2,
		)
		database.sourceEventRetentionProofDao()
			.allForSource(SourceDestinationOwnerEntity.SOURCE_LOCATION) shouldBe proofs
		database.sourceEventWalDao().getByEventId("location-event-2") shouldBe null
	}

	@Test
	// The retained proof must preserve every original capture field after its WAL is removed.
	@Suppress("LongMethod")
	fun `retention proof binds the complete capture authority before WAL deletion`() = runTest {
		insertEvent(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
			admissionOrdinal = 1L,
			authenticSteps = false,
			eventId = "capture-retention-event",
			captureAuthority = true,
		)
		val retainedWal = requireNotNull(
			database.sourceEventWalDao().getByEventId("capture-retention-event"),
		)

		database.pruneSourceEventStorageBeforeWithSourceOperations(
			createdBeforeMs = 100L,
			batchSize = 10,
			verifyCollectedDataAccess = {},
			sourceKinds = listOf(SourceDestinationOwnerEntity.SOURCE_WIFI),
		)

		val proof = requireNotNull(
			database.sourceEventRetentionProofDao().get(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				"capture-retention-event",
			),
		)
		proof.hasValidStoredIntegrity() shouldBe true
		proof.authenticates(
			expectedSourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
			expectedEventId = "capture-retention-event",
			expectedPurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			expectedSourcePolicyRevision = 1L,
			expectedCaptureConsentEpoch = 1L,
			expectedCollectedDataEpoch = 0L,
			expectedScopeDeletionGeneration = 0L,
			expectedLogicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "logical-capture-retention-event",
				serviceRunId = "run-capture-retention-event",
			),
		) shouldBe true
		proof.logicalTrackingId shouldBe "logical-capture-retention-event"
		proof.serviceRunId shouldBe "run-capture-retention-event"
		proof.scopeDeletionGeneration shouldBe 0L
		proof.walPayloadChecksum shouldBe retainedWal.payloadChecksum
		proof.authenticatedCaptureScope(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			0L,
		)?.logicalRunScopeDigest shouldBe proof.logicalRunScopeDigest
		val scopeProof = requireNotNull(
			database.sourceEventRetentionScopeProofDao().get(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				requireNotNull(proof.logicalRunScopeDigest),
			),
		)
		scopeProof.sourcePolicyRevision shouldBe 1L
		scopeProof.captureConsentEpoch shouldBe 1L
		scopeProof.collectedDataEpoch shouldBe 0L
		scopeProof.authenticatesRetainedEvent(proof) shouldBe true
		val reboundWal = retainedWal.copy(
			clockDomainId = "other-boot",
			sessionManifestRevision = 2L,
			lifecycleLeaseGeneration = 2L,
			sourceInstanceId = "other-provider",
			registrationGeneration = 2L,
			physicalConfigurationFingerprint = "c".repeat(64),
			authorizationRevision = 2L,
			authorizationFingerprint = "d".repeat(64),
			configRevision = 2L,
			integrityIdentity = "",
		)
		val rebound = SourceEventRetentionProofEntity.createFromWal(
			reboundWal.copy(integrityIdentity = reboundWal.calculatedIntegrityIdentity()),
		)
		(proof.proofAuthorityFingerprint == rebound.proofAuthorityFingerprint) shouldBe false
		database.sourceEventWalDao().getByEventId("capture-retention-event") shouldBe null
	}

	@Test
	// Proof persistence, reopening and the later full clear require the same physical database.
	@Suppress("LongMethod")
	fun `retention proof survives reopen and full clear removes it behind the new epoch`() = runTest {
		val name = "retention-proof"
		context.deleteDatabase(name)
		var fileDatabase = AppDatabase.fileBuilder(context, name).allowMainThreadQueries().build()
		try {
			val payload = byteArrayOf(1)
			val unsigned = SourceEventWalEntity(
				admissionOrdinal = 1L,
				eventId = "reopen-event",
				providerDedupKey = "reopen-dedup",
				logicalTrackingId = "reopen-logical",
				serviceRunId = "reopen-run",
				sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
				sourceInstanceId = "reopen-instance",
				registrationGeneration = 1L,
				physicalConfigurationFingerprint = "a".repeat(64),
				authorizationRevision = 1L,
				authorizationPurposeEligibilityMask =
					SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				authorizationFingerprint = "b".repeat(64),
				sourceSequence = 1L,
				configRevision = 1L,
				planAttribution = 0,
				clockDomainId = "reopen-boot",
				observedElapsedNanos = 1L,
				receivedElapsedNanos = 2L,
				receivedWallTimeMs = 10L,
				wallTimeMs = 10L,
				wallTimeUncertaintyMs = 0L,
				capturedCollectedDataEpoch = 0L,
				sourcePolicyRevision = 1L,
				captureConsentEpoch = 1L,
				sessionManifestRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				acquiredAtMs = 10L,
				qualityFlags = 0L,
				qualityConfidence = null,
				payloadVersion = 1,
				payload = payload,
				payloadChecksum = payload.sha256(),
				createdAtMs = 10L,
			)
			fileDatabase.sourceEventWalDao().insertIgnoringDuplicate(
				unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity()),
			)
			fileDatabase.pruneSourceEventStorageBeforeWithSourceOperations(
				createdBeforeMs = 100L,
				batchSize = 10,
				verifyCollectedDataAccess = {},
				sourceKinds = listOf(SourceDestinationOwnerEntity.SOURCE_WIFI),
			)
			fileDatabase.close()
			fileDatabase = AppDatabase.fileBuilder(context, name).allowMainThreadQueries().build()

			fileDatabase.sourceEventRetentionProofDao().get(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				"reopen-event",
			)?.hasValidStoredIntegrity() shouldBe true
			fileDatabase.sourceEventRetentionScopeProofDao()
				.countForSource(SourceDestinationOwnerEntity.SOURCE_WIFI) shouldBe 1L

			AppDatabase.deleteAllCollectedData(
				database = fileDatabase,
				operationId = "retention-proof-full-clear",
				collectedDataEpoch = 1L,
				retainedFromMs = null,
				updatedAtMs = 200L,
			)
			fileDatabase.sourceEventRetentionProofDao().countAll() shouldBe 0L
			fileDatabase.sourceEventRetentionScopeProofDao().countAll() shouldBe 0L
			fileDatabase.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe 1L
		} finally {
			fileDatabase.close()
			context.deleteDatabase(name)
		}
	}

	@Test
	fun `corrupt rehashed and stale WAL snapshots cannot mint retention proof`() = runTest {
		insertEvent(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			admissionOrdinal = 1L,
			authenticSteps = false,
			eventId = "snapshot-event",
			captureAuthority = true,
		)
		val wal = requireNotNull(database.sourceEventWalDao().getByEventId("snapshot-event"))
		val candidate = database.sourceEventWalDao().retentionProofCandidatesForProjectedSourceBatch(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			safeOrdinal = 1L,
			createdBeforeMs = 100L,
			limit = 1,
		).single()

		candidate.authenticatesRetentionWal(wal) shouldBe true
		val corrupt = wal.copy(payload = byteArrayOf(9))
		candidate.authenticatesRetentionWal(corrupt) shouldBe false
		assertFailsWith<IllegalArgumentException> {
			SourceEventRetentionProofEntity.createFromWal(corrupt)
		}
		val checksumRehashed = corrupt.copy(payloadChecksum = corrupt.calculatedPayloadChecksum())
		candidate.authenticatesRetentionWal(checksumRehashed) shouldBe false
		assertFailsWith<IllegalArgumentException> {
			SourceEventRetentionProofEntity.createFromWal(checksumRehashed)
		}
		val fullyRehashed = checksumRehashed.copy(
			integrityIdentity = checksumRehashed.calculatedIntegrityIdentity(),
		)
		fullyRehashed.hasQualifiedIntegrity() shouldBe true
		candidate.authenticatesRetentionWal(fullyRehashed) shouldBe false
		assertFailsWith<IllegalArgumentException> {
			candidate.createAuthenticatedRetentionProof(fullyRehashed)
		}
		val staleAuthority = wal.copy(captureConsentEpoch = 2L)
		candidate.authenticatesRetentionWal(staleAuthority) shouldBe false
		assertFailsWith<IllegalArgumentException> {
			SourceEventRetentionProofEntity.createFromWal(staleAuthority)
		}

		database.sourceEventRetentionProofDao().countAll() shouldBe 0L
	}

	@Test
	fun `event proof trimming retains every capture run scope beyond 4096 events`() = runTest {
		val rows = (1L..4_097L).map { ordinal ->
			event(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
				admissionOrdinal = ordinal,
				authenticSteps = false,
				eventId = "scope-event-$ordinal",
				captureAuthority = true,
			)
		}
		rows.chunked(256).forEach { batch ->
			database.sourceEventWalDao().insertDeliveryUnits(batch).size shouldBe batch.size
		}

		database.pruneSourceEventStorageBeforeWithSourceOperations(
			createdBeforeMs = 100L,
			batchSize = 256,
			verifyCollectedDataAccess = {},
			sourceKinds = listOf(SourceDestinationOwnerEntity.SOURCE_WIFI),
		)

		database.sourceEventRetentionProofDao()
			.allForSource(SourceDestinationOwnerEntity.SOURCE_WIFI).size shouldBe 4_096
		database.sourceEventRetentionScopeProofDao()
			.countForSource(SourceDestinationOwnerEntity.SOURCE_WIFI) shouldBe 4_097L
		val firstScope = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceBrokerPurpose.SESSION_CAPTURE,
			"logical-scope-event-1",
			"run-scope-event-1",
		)
		database.sourceEventRetentionScopeProofDao().get(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			firstScope,
		)?.hasValidStoredIntegrity() shouldBe true

		database.sourceEventWalDao().insertIgnoringDuplicate(rows.first()) shouldBe 1L
		database.pruneSourceEventStorageBeforeWithSourceOperations(
			createdBeforeMs = 100L,
			batchSize = 1,
			verifyCollectedDataAccess = {},
			sourceKinds = listOf(SourceDestinationOwnerEntity.SOURCE_WIFI),
		)
		database.sourceEventRetentionScopeProofDao()
			.countForSource(SourceDestinationOwnerEntity.SOURCE_WIFI) shouldBe 4_097L
		database.sourceEventRetentionScopeProofDao().get(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			firstScope,
		)?.hasValidStoredIntegrity() shouldBe true
	}

	@Test
	fun `conflicting capture authority for one retained run scope fails closed`() = runTest {
		val first = event(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
			admissionOrdinal = 1L,
			authenticSteps = false,
			eventId = "scope-conflict-first",
			captureAuthority = true,
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(first) shouldBe 1L
		database.pruneSourceEventStorageBeforeWithSourceOperations(
			createdBeforeMs = 100L,
			batchSize = 10,
			verifyCollectedDataAccess = {},
			sourceKinds = listOf(SourceDestinationOwnerEntity.SOURCE_WIFI),
		)
		val conflictingUnsigned = first.copy(
			admissionOrdinal = 2L,
			eventId = "scope-conflict-second",
			providerDedupKey = "dedup-scope-conflict-second",
			sourceInstanceId = "instance-scope-conflict-second",
			sourceSequence = 2L,
			sourcePolicyRevision = 2L,
			integrityIdentity = "",
		)
		val conflicting = conflictingUnsigned.copy(
			integrityIdentity = conflictingUnsigned.calculatedIntegrityIdentity(),
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(conflicting) shouldBe 2L

		val result = database.pruneSourceEventStorageBeforeWithSourceOperations(
			createdBeforeMs = 100L,
			batchSize = 10,
			verifyCollectedDataAccess = {},
			sourceKinds = listOf(SourceDestinationOwnerEntity.SOURCE_WIFI),
		)

		(result as SourceEventStoragePruneResult.Deferred).sourceDebt.single().reason shouldBe
			SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE
		database.sourceEventWalDao().getByEventId("scope-conflict-second") shouldBe conflicting
		database.sourceEventRetentionProofDao().get(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			"scope-conflict-second",
		) shouldBe null
		database.sourceEventRetentionScopeProofDao()
			.countForSource(SourceDestinationOwnerEntity.SOURCE_WIFI) shouldBe 1L
	}

	@Test
	fun `conflicting retention proof rolls back its source WAL deletion`() = runTest {
		insertEvent(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
			admissionOrdinal = 1L,
			authenticSteps = true,
			eventId = "conflicting-proof-event",
		)
		val wal = requireNotNull(
			database.sourceEventWalDao().getByEventId("conflicting-proof-event"),
		)
		database.sourceEventRetentionProofDao().insertAll(
			listOf(
				SourceEventRetentionProofEntity.createFromWal(
					wal.copy(
						admissionOrdinal = 2L,
						sourceSequence = 2L,
						integrityIdentity = "",
					).let { conflicting ->
						conflicting.copy(
							integrityIdentity = conflicting.calculatedIntegrityIdentity(),
						)
					},
				),
			),
		)

		val result = database.pruneSourceEventStorageBeforeWithSourceOperations(
			createdBeforeMs = 100L,
			batchSize = 10,
			verifyCollectedDataAccess = {},
			sourceKinds = listOf(SourceDestinationOwnerEntity.SOURCE_LOCATION),
		)

		result shouldBe SourceEventStoragePruneResult.Deferred(
			walEventsDeleted = 0,
			deliveredEffectsDeleted = 0,
			orphanedDeliveredEffectsDeleted = 0,
			sourceOutcomes = listOf(
				SourceEventStorageSourcePruneOutcome.Blocked(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
					reason =
						SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
					walEventsDeleted = 0,
					deliveredEffectsDeleted = 0,
				),
			),
			sourceDebt = listOf(
				SourceEventStorageSourcePruneOutcome.Blocked(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
					reason =
						SourceEventStoragePruneBlockedReason.STORED_EVIDENCE_UNVERIFIABLE,
					walEventsDeleted = 0,
					deliveredEffectsDeleted = 0,
				),
			),
		)
		database.sourceEventWalDao().getByEventId("conflicting-proof-event")?.admissionOrdinal shouldBe
			1L
	}

	private suspend fun insertEvent(
		sourceKind: Int,
		admissionOrdinal: Long,
		authenticSteps: Boolean,
		eventId: String = "event-$sourceKind",
		captureAuthority: Boolean = false,
	) {
		val row = event(
			sourceKind = sourceKind,
			admissionOrdinal = admissionOrdinal,
			authenticSteps = authenticSteps,
			eventId = eventId,
			captureAuthority = captureAuthority,
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(row) shouldBe admissionOrdinal
	}

	// Keep the complete WAL row and its integrity checksum together as one fixture value.
	@Suppress("LongMethod")
	private fun event(
		sourceKind: Int,
		admissionOrdinal: Long,
		authenticSteps: Boolean,
		eventId: String,
		captureAuthority: Boolean,
	): SourceEventWalEntity {
		val payload = byteArrayOf(sourceKind.toByte())
		val authenticStepSource = sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && authenticSteps
		val hasAuthority = captureAuthority || authenticStepSource
		val authorityRevision = if (hasAuthority) {
			1L
		} else {
			null
		}
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = admissionOrdinal,
			eventId = eventId,
			providerDedupKey = "dedup-$eventId",
			logicalTrackingId = "logical-$eventId".takeIf { captureAuthority },
			serviceRunId = "run-$eventId".takeIf { captureAuthority },
			sourceKind = sourceKind,
			sourceInstanceId = "instance-$eventId",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint =
				if (hasAuthority) {
					"a".repeat(64)
				} else {
					null
				},
			authorizationRevision = authorityRevision,
			authorizationPurposeEligibilityMask =
				if (captureAuthority) {
					SourceBrokerPurpose.MASK_SESSION_CAPTURE
				} else if (authenticStepSource) {
					SourceBrokerPurpose.MASK_CONTROL_AUTOSTART
				} else {
					0L
				},
			authorizationFingerprint =
				if (hasAuthority) {
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
			sourcePolicyRevision = authorityRevision,
			captureConsentEpoch = 1L.takeIf { captureAuthority },
			sessionManifestRevision = 1L.takeIf { captureAuthority },
			lifecycleLeaseGeneration = authorityRevision,
			acquiredAtMs = 10L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS) {
				7
			} else {
				1
			},
			payload = payload,
			payloadChecksum = payload.sha256(),
			createdAtMs = 10L,
		)
		return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
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
		).let { insertedId -> check(insertedId > 0L) }
	}

	private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
		.digest(this)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
