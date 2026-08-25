package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionObservationEntity
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionPointEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceEventWalDaoTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `source instance sequence is an idempotent admission identity`() = runTest {
		val dao = database.sourceEventWalDao()
		val first = dao.insertIgnoringDuplicate(event("event-a", sourceSequence = 5L))
		val duplicate = dao.insertIgnoringDuplicate(event("event-b", sourceSequence = 5L))

		first shouldBe 1L
		duplicate shouldBe -1L
		dao.countAll() shouldBe 1L
		dao.getBySourceSequence(1, "instance", 5L)?.eventId shouldBe "event-a"
	}

	@Test
	fun `source scoped recovery read excludes unrelated ordinals and honors upper bound`() = runTest {
		val dao = database.sourceEventWalDao()
		dao.insertIgnoringDuplicate(event("activity-old", 1L).copy(sourceKind = 2)) shouldBe 1L
		dao.insertIgnoringDuplicate(event("pressure-poison", 1L).copy(sourceKind = 4)) shouldBe 2L
		dao.insertIgnoringDuplicate(event("activity-live", 2L).copy(sourceKind = 2)) shouldBe 3L
		dao.insertIgnoringDuplicate(event("activity-later", 3L).copy(sourceKind = 2)) shouldBe 4L

		dao.sourceEventsAfterThrough(
			sourceKind = 2,
			afterOrdinal = 1L,
			throughOrdinal = 3L,
			limit = 10,
		).map { it.eventId } shouldBe listOf("activity-live")
	}

	@Test
	fun `required projections retain independent checkpoints`() = runTest {
		val dao = database.sourceProjectionStateDao()
		dao.register(SourceProjectionRegistrationEntity("raw", 1, 1, true, "ACTIVE", 0))
		dao.register(SourceProjectionRegistrationEntity("session", 1, 1, true, "ACTIVE", 0))
		dao.saveCheckpoint(SourceProjectionCheckpointEntity("raw", 1, 10, 1, 100))
		dao.saveCheckpoint(SourceProjectionCheckpointEntity("session", 1, 7, 1, 100))

		dao.minimumRequiredCheckpoint() shouldBe 7L
	}

	@Test
	fun `new source lane activates after pruned wal autoincrement high water`() = runTest {
		val projection = database.sourceProjectionStateDao()
		projection.register(SourceProjectionRegistrationEntity("existing", 2, 1, true, "ACTIVE", 0))
		projection.saveCheckpoint(SourceProjectionCheckpointEntity("existing", 2, 3, 1, 100))
		val wal = database.sourceEventWalDao()
		(1L..3L).forEach { ordinal ->
			wal.insertIgnoringDuplicate(event("old-$ordinal", ordinal).copy(createdAtMs = 10)) shouldBe ordinal
		}
		database.pruneSourceEventStorageBefore(createdBeforeMs = 100).walEventsDeleted shouldBe 3
		wal.countAll() shouldBe 0L

		database.liveSourceProjectionActivationOrdinal() shouldBe 4L
	}

	@Test
	fun `global activity progress cannot release steps wal while source lane cursor lags`() = runTest {
		val projection = database.sourceProjectionStateDao()
		projection.register(
			SourceProjectionRegistrationEntity(
				projectionId = "activity-global",
				projectionVersion = 2,
				activationOrdinal = 1,
				retentionRequired = true,
				status = "ACTIVE",
				createdAtMs = 0,
			),
		)
		projection.saveCheckpoint(
			SourceProjectionCheckpointEntity(
				projectionId = "activity-global",
				projectionVersion = 2,
				contiguousAdmissionOrdinal = 3,
				stateVersion = 1,
				updatedAtMs = 100,
			),
		)
		projection.installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = 3,
				projectionId = "steps-interval",
				projectionVersion = 1,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = 3,
				activationOrdinal = 1,
				contiguousAdmissionOrdinal = 0,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 0,
				updatedAtMs = 0,
			),
		)
		val wal = database.sourceEventWalDao()
		wal.insertIgnoringDuplicate(event("steps-1", 1).copy(sourceKind = 3, createdAtMs = 10)) shouldBe 1L
		wal.insertIgnoringDuplicate(event("activity-2", 2).copy(sourceKind = 2, createdAtMs = 10)) shouldBe 2L
		wal.insertIgnoringDuplicate(event("activity-3", 3).copy(sourceKind = 2, createdAtMs = 10)) shouldBe 3L

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100).walEventsDeleted shouldBe 0
		wal.getByEventId("steps-1")?.eventId shouldBe "steps-1"
		projection.advanceProductLaneCursor(
			sourceKind = 3,
			projectionId = "steps-interval",
			projectionVersion = 1,
			expectedCurrentOrdinal = 1,
			throughOrdinal = 3,
			updatedAtMs = 150,
		) shouldBe 0
		database.pruneSourceEventStorageBefore(createdBeforeMs = 100).walEventsDeleted shouldBe 0

		projection.advanceProductLaneCursor(
			sourceKind = 3,
			projectionId = "steps-interval",
			projectionVersion = 1,
			expectedCurrentOrdinal = 0,
			throughOrdinal = 1,
			updatedAtMs = 200,
		) shouldBe 1
		database.pruneSourceEventStorageBefore(createdBeforeMs = 100).walEventsDeleted shouldBe 1
		wal.getByEventId("steps-1") shouldBe null
		wal.getByEventId("activity-2")?.eventId shouldBe "activity-2"
	}

	@Test
	fun `live outbox delivery cannot consume a released v27 projection generation`() = runTest {
		val dao = database.sourceProjectionStateDao()
		dao.insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "legacy-effect",
				projectionId = "activity-automation",
				projectionVersion = 1,
				admissionOrdinal = 1,
				effectKind = "activity-automation-v1",
				payloadVersion = 1,
				payload = byteArrayOf(1),
				createdAtMs = 10,
				deliveredAtMs = null,
			),
		)
		dao.insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "live-effect",
				projectionId = "activity-automation",
				projectionVersion = 2,
				admissionOrdinal = 2,
				effectKind = "activity-automation-v1",
				payloadVersion = 1,
				payload = byteArrayOf(2),
				createdAtMs = 20,
				deliveredAtMs = null,
			),
		)

		dao.pendingOutbox("activity-automation", 2, "activity-automation-v1", 10)
			.map { it.stableId } shouldBe listOf("live-effect")
		dao.terminalizeUndeliveredThrough(1, "MIGRATION_SUPPRESSED_CONTROL", 30) shouldBe 1
		dao.pendingOutbox("activity-automation-v1", 10)
			.map { it.stableId } shouldBe listOf("live-effect")
		dao.markOutboxDelivered("legacy-effect", 40) shouldBe 0
	}

	@Test
	fun `single effect terminal suppression is durable and mutually exclusive with delivery`() = runTest {
		val dao = database.sourceProjectionStateDao()
		dao.insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "policy-suppressed",
				projectionId = "activity-automation",
				projectionVersion = 2,
				admissionOrdinal = 1,
				effectKind = "activity-automation-v1",
				payloadVersion = 1,
				payload = byteArrayOf(1),
				createdAtMs = 10,
				deliveredAtMs = null,
			),
		)

		dao.markOutboxTerminal(
			"policy-suppressed",
			"CURRENT_POLICY_SUPPRESSED_AUTOMATION",
			20,
		) shouldBe 1
		dao.markOutboxDelivered("policy-suppressed", 30) shouldBe 0
		dao.outbox("policy-suppressed")?.terminalDisposition shouldBe
			"CURRENT_POLICY_SUPPRESSED_AUTOMATION"
		dao.outbox("policy-suppressed")?.terminalAtMs shouldBe 20
	}

	@Test
	fun `pending v27 target pins wal retention until its required cutoff is dispositioned`() = runTest {
		val legacy = database.legacyV27ProjectionDrainDao()
		database.sourceEvidenceStateDao().ensure()
		legacy.saveDrain(
			LegacyV27ProjectionDrainEntity(
				cutoffAdmissionOrdinal = 2,
				collectedDataEpoch = 0,
				status = LegacyV27ProjectionDrainEntity.STATUS_PENDING,
				ownerBootId = null,
				ownerToken = null,
				leaseGeneration = 0,
				leaseExpiresElapsedNanos = null,
				startedAtMs = null,
				completedAtMs = null,
				suppressedOutboxCount = 0,
				failureCode = null,
			),
		)
		val target = LegacyV27ProjectionTargetEntity(
			projectionId = "location-domain",
			projectionVersion = 1,
			initialActivationOrdinal = 1,
			initialCheckpointOrdinal = 0,
			requiredThroughOrdinal = 2,
			lastCompletedOrdinal = 0,
			retentionRequired = true,
			initialRegistrationStatus = "ACTIVE",
			disposition = LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING,
			completedAtMs = null,
			failureCode = null,
		)
		legacy.saveTarget(target)
		val projection = database.sourceProjectionStateDao()
		projection.register(SourceProjectionRegistrationEntity("location-domain", 2, 3, true, "ACTIVE", 0))
		projection.saveCheckpoint(SourceProjectionCheckpointEntity("location-domain", 2, 3, 1, 100))
		val wal = database.sourceEventWalDao()
		(1L..3L).forEach { ordinal ->
			wal.insertIgnoringDuplicate(event("event-$ordinal", ordinal).copy(createdAtMs = 10))
		}
		projection.insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "legacy-event-frame-2",
				projectionId = "event-tracking-frame",
				projectionVersion = 1,
				admissionOrdinal = 2,
				effectKind = "event-tracking-frame-v1",
				payloadVersion = 1,
				payload = byteArrayOf(2),
				createdAtMs = 10,
				deliveredAtMs = null,
			),
		)

		database.pruneSourceEventStorageBefore(createdBeforeMs = 100).walEventsDeleted shouldBe 0
		wal.countAll() shouldBe 3

		legacy.acquireLease("boot", "owner", 100, 1_000, 100) shouldBe 1
		legacy.advanceTarget(
			"location-domain",
			1,
			0,
			2,
			"boot",
			"owner",
			1,
			101,
		) shouldBe 1
		legacy.terminalizeTarget(
			"location-domain",
			1,
			2,
			LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS,
			200,
			null,
			"boot",
			"owner",
			1,
			102,
		) shouldBe 1
		database.pruneSourceEventStorageBefore(createdBeforeMs = 100).walEventsDeleted shouldBe 1
		wal.getByEventId("event-1") shouldBe null
		wal.getByEventId("event-2")?.eventId shouldBe "event-2"
		wal.getByEventId("event-3")?.eventId shouldBe "event-3"

		projection.terminalizeUndeliveredThrough(2, "MIGRATION_BRIDGED", 20) shouldBe 1
		val finalPrune = database.pruneSourceEventStorageBefore(createdBeforeMs = 100)
		finalPrune.walEventsDeleted shouldBe 2
		finalPrune.deliveredEffectsDeleted shouldBe 1
		wal.countAll() shouldBe 0
		projection.pendingOutbox(10) shouldBe emptyList()
	}

	@Test
	fun `source maintenance prunes only checkpointed rows outside durable join retention`() = runTest {
		val wal = database.sourceEventWalDao()
		val projection = database.sourceProjectionStateDao()
		projection.register(SourceProjectionRegistrationEntity("raw", 1, 1, true, "ACTIVE", 0))
		projection.saveCheckpoint(SourceProjectionCheckpointEntity("raw", 1, 3, 1, 100))
		wal.insertIgnoringDuplicate(event("event-a", 1L))
		wal.insertIgnoringDuplicate(event("event-b", 2L))
		wal.insertIgnoringDuplicate(event("event-c", 3L))
		projection.saveJoinState(
			SourceProjectionJoinStateEntity(
				projectionId = "raw",
				projectionVersion = 1,
				stateKey = "pending",
				logicalTrackingId = "track",
				minimumRequiredOrdinal = 3,
				payloadVersion = 1,
				payload = byteArrayOf(1),
				updatedAtMs = 10,
			),
		)
		listOf(1L, 3L).forEach { ordinal ->
			projection.insertOutbox(
				SourceProjectionOutboxEntity(
					stableId = "effect-$ordinal",
					projectionId = "raw",
					projectionVersion = 1,
					admissionOrdinal = ordinal,
					effectKind = "test",
					payloadVersion = 1,
					payload = byteArrayOf(1),
					createdAtMs = 10,
					deliveredAtMs = 20,
				),
			)
		}

		val result = database.pruneSourceEventStorageBefore(createdBeforeMs = 100, batchSize = 1)

		result.walEventsDeleted shouldBe 2
		result.deliveredEffectsDeleted shouldBe 1
		wal.getByEventId("event-a") shouldBe null
		wal.getByEventId("event-b") shouldBe null
		wal.getByEventId("event-c")?.eventId shouldBe "event-c"
		projection.pendingOutbox(10).map { it.stableId } shouldBe emptyList()
		projection.deleteDeliveredOutboxBatch(10, 100, 10) shouldBe 1
	}

	@Test
	fun `source maintenance never advances retained floor across a non-expired ordinal`() = runTest {
		val wal = database.sourceEventWalDao()
		val projection = database.sourceProjectionStateDao()
		projection.register(SourceProjectionRegistrationEntity("raw", 1, 1, true, "ACTIVE", 0))
		projection.saveCheckpoint(SourceProjectionCheckpointEntity("raw", 1, 3, 1, 100))
		wal.insertIgnoringDuplicate(event("old-prefix", 1L).copy(createdAtMs = 10))
		wal.insertIgnoringDuplicate(event("retained-middle", 2L).copy(createdAtMs = 200))
		wal.insertIgnoringDuplicate(event("old-tail", 3L).copy(createdAtMs = 10))

		val result = database.pruneSourceEventStorageBefore(createdBeforeMs = 100, batchSize = 10)

		result.walEventsDeleted shouldBe 1
		wal.getByEventId("old-prefix") shouldBe null
		wal.getByEventId("retained-middle")?.eventId shouldBe "retained-middle"
		wal.getByEventId("old-tail")?.eventId shouldBe "old-tail"
	}

	@Test
	fun `source storage hot paths use their composite indexes`() {
		queryPlan(
			"SELECT event_id, admission_ordinal, payload_checksum FROM source_event_wal " +
				"WHERE source_kind = 1 AND provider_dedup_key = 'provider-key' LIMIT 1",
		) shouldContain "idx_source_event_wal_provider_dedup"
		queryPlan(
			"SELECT admission_ordinal FROM source_event_wal " +
				"WHERE admission_ordinal <= 100 AND created_at_ms < 1000 " +
				"ORDER BY created_at_ms, admission_ordinal LIMIT 1000",
		) shouldContain "idx_source_event_wal_retention"
		queryPlan(
			"SELECT * FROM source_projection_outbox WHERE effect_kind = 'test' " +
				"AND delivered_at_ms IS NULL ORDER BY admission_ordinal LIMIT 100",
		) shouldContain "idx_source_projection_outbox_kind_pending"
		queryPlan(
			"SELECT MIN(contiguous_admission_ordinal) FROM source_product_projection_lane " +
				"WHERE retention_required = 1 AND status = 'ACTIVE'",
		) shouldContain "idx_source_product_projection_lane_retention"
		queryPlan(
			"SELECT * FROM location_projection_observation WHERE logical_tracking_id = 'track' " +
				"ORDER BY elapsed_realtime_nanos, wall_time_ms, event_id",
		) shouldContain "idx_location_projection_observation_order"
		queryPlan(
			"SELECT * FROM location_sample WHERE time_ms BETWEEN 1 AND 100 " +
				"ORDER BY time_ms, id LIMIT 100",
		) shouldContain "idx_location_sample_time_id"
	}

	@Test
	fun `registration sequences are allocated monotonically in their source scope`() = runTest {
		val dao = database.sourceRegistrationStateDao()
		dao.insertIfAbsent(
			SourceRegistrationStateEntity(
				sourceKind = 2,
				ownerScope = "automatic-start-monitor",
				sourceInstanceId = "activity-instance",
				clockDomainId = "android-boot-count:4",
				registrationGeneration = 3,
				nextSequence = 0,
				appliedRevision = null,
				collectedDataEpoch = 1,
				updatedAtMs = 100,
			),
		)

		dao.allocateSequence(2, "automatic-start-monitor", 101).nextSequence shouldBe 0L
		dao.allocateSequence(2, "automatic-start-monitor", 102).nextSequence shouldBe 1L
		dao.get(2, "automatic-start-monitor")?.nextSequence shouldBe 2L
	}

	@Test
	fun `registration sequence range is contiguous and advances once by its unit count`() = runTest {
		val dao = database.sourceRegistrationStateDao()
		dao.insertIfAbsent(
			SourceRegistrationStateEntity(
				sourceKind = 3,
				ownerScope = "source-broker:3",
				sourceInstanceId = "wifi-instance",
				clockDomainId = "android-boot-count:4",
				registrationGeneration = 5,
				nextSequence = 11,
				appliedRevision = null,
				collectedDataEpoch = 1,
				updatedAtMs = 100,
			),
		)

		dao.allocateSequenceRange(3, "source-broker:3", 3, 101) shouldBe 11L..13L
		dao.get(3, "source-broker:3")?.nextSequence shouldBe 14L
	}

	@Test
	fun `failed delivery insert rolls back its entire sequence range and wal batch`() = runTest {
		val stateDao = database.sourceRegistrationStateDao()
		val walDao = database.sourceEventWalDao()
		stateDao.insertIfAbsent(
			SourceRegistrationStateEntity(
				sourceKind = 1,
				ownerScope = "source-broker:1",
				sourceInstanceId = "instance",
				clockDomainId = "boot",
				registrationGeneration = 1,
				nextSequence = 11,
				appliedRevision = null,
				collectedDataEpoch = 0,
				updatedAtMs = 100,
			),
		)
		walDao.insertIgnoringDuplicate(event("existing-event", 99L))

		shouldThrow<SQLiteConstraintException> {
			database.withTransaction {
				val sequences = stateDao.allocateSequenceRange(1, "source-broker:1", 2, 101).toList()
				walDao.insertDeliveryUnits(
					listOf(
						event("new-event", sequences[0]),
						event("existing-event", sequences[1]),
					),
				)
			}
		}

		stateDao.get(1, "source-broker:1")?.nextSequence shouldBe 11L
		walDao.countAll() shouldBe 1L
	}

	@Test
	fun `cancelled delivery transaction rolls back its sequence range and wal batch`() = runTest {
		val stateDao = database.sourceRegistrationStateDao()
		val walDao = database.sourceEventWalDao()
		stateDao.insertIfAbsent(
			SourceRegistrationStateEntity(
				sourceKind = 1,
				ownerScope = "source-broker:1",
				sourceInstanceId = "instance",
				clockDomainId = "boot",
				registrationGeneration = 1,
				nextSequence = 11,
				appliedRevision = null,
				collectedDataEpoch = 0,
				updatedAtMs = 100,
			),
		)

		shouldThrow<CancellationException> {
			database.withTransaction {
				val sequence = stateDao.allocateSequenceRange(1, "source-broker:1", 1, 101).first
				walDao.insertDeliveryUnits(listOf(event("cancelled-event", sequence)))
				throw CancellationException("test cancellation")
			}
		}

		stateDao.get(1, "source-broker:1")?.nextSequence shouldBe 11L
		walDao.countAll() shouldBe 0L
	}

	private fun queryPlan(sql: String): String = buildString {
		database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
			while (cursor.moveToNext()) {
				if (isNotEmpty()) append('\n')
				append(cursor.getString(3))
			}
		}
	}

	@Test
	fun `collected data deletion clears source evidence but preserves rollout configuration`() = runTest {
		database.sourceEventWalDao().insertIgnoringDuplicate(event("event-a", 1L))
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = 3,
				projectionId = "steps-interval",
				projectionVersion = 1,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = 2,
				activationOrdinal = 1,
				contiguousAdmissionOrdinal = 0,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 100,
				updatedAtMs = 100,
			),
		)
		database.locationProjectionDao().upsertObservation(
			LocationProjectionObservationEntity(
				eventId = "event-a",
				logicalTrackingId = "track",
				admissionOrdinal = 1,
				elapsedRealtimeNanos = 1,
				wallTimeMs = 10,
				latitudeDegrees = 50.0,
				longitudeDegrees = 14.0,
				horizontalAccuracyMeters = 5f,
				altitudeMeters = null,
				verticalAccuracyMeters = null,
				speedMetersPerSecond = null,
			),
		)
		database.locationProjectionDao().upsertPoints(
			listOf(
				LocationProjectionPointEntity(
					eventId = "event-a",
					logicalTrackingId = "track",
					revision = 1,
					accepted = true,
					rejection = null,
					latitudeDegrees = 50.0,
					longitudeDegrees = 14.0,
					segmentDistanceMeters = 0.0,
					cumulativeDistanceMeters = 0.0,
					estimatedSpeedMetersPerSecond = null,
					rawWgs84AltitudeMeters = null,
					verticalAccuracyMeters = null,
					elapsedRealtimeNanos = 1,
				),
			),
		)
		database.trackingRolloutStateDao().save(
			TrackingRolloutStateEntity(
				revision = 2,
				schemaVersion = 1,
				coordinatorMode = "EVENT",
				projectionMode = "SHADOW_READ_ONLY",
				sourceOwners = "1:EVENT",
				semanticSettingsEnabled = false,
				batteryEstimateMode = "SOURCE_PLAN_QUALITATIVE",
				updatedAtMs = 100,
			),
		)

		AppDatabase.deleteAllCollectedData(database, 1L, 50L, 100L)

		database.sourceEventWalDao().countAll() shouldBe 0L
		database.locationProjectionDao().observations("track") shouldBe emptyList()
		database.locationProjectionDao().points("track") shouldBe emptyList()
		database.sourceProjectionStateDao().activeProductLanes() shouldBe emptyList()
		database.sourceProjectionStateDao().productLaneByProjection("steps-interval", 1) shouldBe null
		database.trackingRolloutStateDao().get()?.revision shouldBe 2L
	}

	@Test
	fun `full deletion persists wal autoincrement high water before clearing rows`() = runTest {
		val wal = database.sourceEventWalDao()
		(1L..3L).forEach { ordinal ->
			wal.insertIgnoringDuplicate(event("old-$ordinal", ordinal)) shouldBe ordinal
		}

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = 1L,
			retainedFromMs = null,
			updatedAtMs = 100L,
		)

		wal.countAll() shouldBe 0L
		walSequenceHighWater() shouldBe 3L
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.collectedDataEpoch shouldBe 1L
			state.deletedSourceEventHighWaterOrdinal shouldBe 3L
		}
		wal.insertIgnoringDuplicate(event("new-epoch", 1L)) shouldBe 4L
		wal.getByEventId("old-1") shouldBe null
		wal.getByEventId("new-epoch")?.admissionOrdinal shouldBe 4L
	}

	@Test
	fun `synchronous full deletion also persists wal autoincrement high water`() = runTest {
		val wal = database.sourceEventWalDao()
		wal.insertIgnoringDuplicate(event("old", 1L)) shouldBe 1L

		AppDatabase.deleteAllCollectedData(database)

		wal.countAll() shouldBe 0L
		walSequenceHighWater() shouldBe 1L
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.collectedDataEpoch shouldBe 1L
			state.deletedSourceEventHighWaterOrdinal shouldBe 1L
		}
		wal.insertIgnoringDuplicate(event("new-epoch", 1L)) shouldBe 2L
	}

	private fun walSequenceHighWater(): Long? = database.openHelper.writableDatabase.query(
		"SELECT seq FROM sqlite_sequence WHERE name = 'source_event_wal'",
	).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

	private fun event(eventId: String, sourceSequence: Long) = SourceEventWalEntity(
		eventId = eventId,
		providerDedupKey = null,
		logicalTrackingId = null,
		serviceRunId = null,
		sourceKind = 1,
		sourceInstanceId = "instance",
		registrationGeneration = 1,
		sourceSequence = sourceSequence,
		configRevision = 1,
		planAttribution = 0,
		clockDomainId = "boot",
		observedElapsedNanos = 1,
		receivedElapsedNanos = 2,
		wallTimeMs = 10,
		wallTimeUncertaintyMs = 1,
		capturedCollectedDataEpoch = 0,
		acquiredAtMs = 10,
		qualityFlags = 0,
		qualityConfidence = null,
		payloadVersion = 1,
		payload = byteArrayOf(1),
		payloadChecksum = "checksum-$eventId",
		createdAtMs = 10,
	)
}
