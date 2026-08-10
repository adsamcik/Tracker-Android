package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionObservationEntity
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionPointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
	fun `required projections retain independent checkpoints`() = runTest {
		val dao = database.sourceProjectionStateDao()
		dao.register(SourceProjectionRegistrationEntity("raw", 1, 1, true, "ACTIVE", 0))
		dao.register(SourceProjectionRegistrationEntity("session", 1, 1, true, "ACTIVE", 0))
		dao.saveCheckpoint(SourceProjectionCheckpointEntity("raw", 1, 10, 1, 100))
		dao.saveCheckpoint(SourceProjectionCheckpointEntity("session", 1, 7, 1, 100))

		dao.minimumRequiredCheckpoint() shouldBe 7L
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
		database.trackingRolloutStateDao().get()?.revision shouldBe 2L
	}

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
