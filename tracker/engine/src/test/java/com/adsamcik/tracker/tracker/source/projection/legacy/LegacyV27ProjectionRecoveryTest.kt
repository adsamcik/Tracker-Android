package com.adsamcik.tracker.tracker.source.projection.legacy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionObservationEntity
import com.adsamcik.tracker.shared.base.database.data.LocationProjectionPointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameEffectCodec
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameProjection
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyV27ProjectionRecoveryTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: FakeLifecycleStore
	private lateinit var clock: FixedClock
	private lateinit var codec: DefaultSourcePayloadCodec
	private lateinit var recovery: LegacyV27ProjectionRecovery

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		lifecycle = FakeLifecycleStore(CollectedDataLifecycleSnapshot(epoch = 0, retainedFromMs = null))
		clock = FixedClock(fixedTimeMillis = 10_000, fixedRealtimeNanos = 1_000_000_000)
		codec = DefaultSourcePayloadCodec()
		recovery = LegacyV27ProjectionRecovery(
			database = database,
			lifecycleStore = lifecycle,
			bootClockDomainProvider = object : BootClockDomainProvider {
				override fun current(): String = "test-boot"
			},
			clock = clock,
			payloadCodec = codec,
			eventFrameBridge = LegacyV27EventFrameBridge(
				stepIntervalDao = database.stepIntervalDao(),
				pressureSampleDao = database.pressureSampleDao(),
			),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `fresh v28 database requires no legacy recovery`() = runTest {
		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.NotRequired
		database.stepIntervalDao().getAllBetween(0, Long.MAX_VALUE).size shouldBe 0
	}

	@Test
	fun `four target sparse recovery materializes a step exactly once and preserves location`() = runTest {
		seedDrain(cutoff = 5, locationCheckpoint = 5)
		val observation = locationObservation()
		val point = locationPoint()
		database.locationProjectionDao().upsertObservation(observation)
		database.locationProjectionDao().upsertPoints(listOf(point))
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(ordinal = 3, eventId = "sparse-step", payload = positiveStep(sequence = 3)),
		) shouldBe 3L

		recovery.recover(batchSize = 1) shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = false,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao().getAllBetween(0, Long.MAX_VALUE).size shouldBe 1
		requireNotNull(database.stepIntervalDao().getBySourceSignalId("source-event:sparse-step"))
			.stepCount shouldBe 5
		database.locationProjectionDao().observations(TRACKING_ID) shouldBe listOf(observation)
		database.locationProjectionDao().points(TRACKING_ID) shouldBe listOf(point)
		database.locationSampleDao().countAll() shouldBe 0L

		// A process restart/retry reads the terminal result and cannot contribute twice.
		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = false,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao().getAllBetween(0, Long.MAX_VALUE).size shouldBe 1
	}

	@Test
	fun `absent Location registration with complete checkpoint preserves retained shadow`() = runTest {
		seedDrain(cutoff = 2, locationCheckpoint = 2)
		markLocationNotRegistered(checkpoint = 2)
		val observation = locationObservation()
		val point = locationPoint()
		database.locationProjectionDao().upsertObservation(observation)
		database.locationProjectionDao().upsertPoints(listOf(point))

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = false,
			suppressedOutboxCount = 0,
		)

		database.legacyV27ProjectionDrainDao().target(LOCATION, 1)?.also { target ->
			target.disposition shouldBe
				LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_RETAINED
			target.failureCode shouldBe null
		}
		database.locationProjectionDao().observations(TRACKING_ID) shouldBe listOf(observation)
		database.locationProjectionDao().points(TRACKING_ID) shouldBe listOf(point)
		database.locationSampleDao().countAll() shouldBe 0L
	}

	@Test
	fun `retired Location writer with a behind checkpoint preserves shadow as partial`() = runTest {
		seedDrain(cutoff = 3, locationCheckpoint = 1)
		markLocationNotRegistered(checkpoint = 1)
		val observation = locationObservation()
		database.locationProjectionDao().upsertObservation(observation)

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)

		database.legacyV27ProjectionDrainDao().target(LOCATION, 1)?.also { target ->
			target.disposition shouldBe
				LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL
			target.failureCode shouldBe "LOCATION_SHADOW_PARTIAL"
		}
		database.locationProjectionDao().observations(TRACKING_ID) shouldBe listOf(observation)
		database.locationSampleDao().countAll() shouldBe 0L
	}

	@Test
	fun `missing Location checkpoint with pending outbox preserves bytes as partial shadow`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 0)
		markLocationNotRegistered(checkpoint = 0)
		val payload = byteArrayOf(7, 3, 1)
		database.sourceProjectionStateDao().insertOutbox(
			outbox("released-location-effect", LOCATION, version = 1).copy(
				effectKind = "LOCATION",
				payload = payload,
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 1,
		)

		database.legacyV27ProjectionDrainDao().target(LOCATION, 1)?.also { target ->
			target.disposition shouldBe
				LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL
			target.failureCode shouldBe "LOCATION_SHADOW_PARTIAL"
		}
		database.sourceProjectionStateDao().outbox("released-location-effect")?.also { retained ->
			retained.payload.toList() shouldBe payload.toList()
			retained.terminalDisposition shouldBe
				LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL
			retained.deliveredAtMs shouldBe null
		}
		database.locationSampleDao().countAll() shouldBe 0L
	}

	@Test
	fun `retired Location writer with complete checkpoint and only pending outbox remains partial`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		markLocationNotRegistered(checkpoint = 1)
		val payload = byteArrayOf(9, 4, 2)
		database.sourceProjectionStateDao().insertOutbox(
			outbox("released-location-effect-at-cutoff", LOCATION, version = 1).copy(
				effectKind = "LOCATION",
				payload = payload,
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 1,
		)

		database.legacyV27ProjectionDrainDao().target(LOCATION, 1)?.also { target ->
			target.disposition shouldBe
				LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL
			target.failureCode shouldBe "LOCATION_SHADOW_PARTIAL"
		}
		database.sourceProjectionStateDao().outbox("released-location-effect-at-cutoff")
			?.also { retained ->
				retained.payload.toList() shouldBe payload.toList()
				retained.terminalDisposition shouldBe
					LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL
				retained.deliveredAtMs shouldBe null
			}
		database.locationSampleDao().countAll() shouldBe 0L
	}

	@Test
	fun `retention expired wal is terminally skipped and recovery is partial`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1, retainedFromMs = 2_000)
		lifecycle.update(CollectedDataLifecycleSnapshot(epoch = 0, retainedFromMs = 2_000))
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(
				ordinal = 1,
				eventId = "expired-step",
				payload = positiveStep(sequence = 1),
				acquiredAtMs = 1_999,
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao().getAllBetween(0, Long.MAX_VALUE).size shouldBe 0
		database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 1)?.failureCode shouldBe
			"LEGACY_V27_RETENTION_REJECTED"
		database.legacyV27ProjectionDrainDao().target(EVENT_FRAME, 1)?.lastCompletedOrdinal shouldBe 1L
	}

	@Test
	fun `delayed wal with retained away destination time cannot resurrect a typed fact`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1, retainedFromMs = 2_000)
		lifecycle.update(CollectedDataLifecycleSnapshot(epoch = 0, retainedFromMs = 2_000))
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(
				ordinal = 1,
				eventId = "delayed-expired-step",
				payload = positiveStep(sequence = 1),
				acquiredAtMs = 5_000,
			).copy(wallTimeMs = 1_999),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao()
			.getBySourceSignalId("source-event:delayed-expired-step") shouldBe null
		database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 1)?.failureCode shouldBe
			"LEGACY_V27_RETENTION_REJECTED"
	}

	@Test
	fun `checksum poison advances while the next healthy row materializes`() = runTest {
		seedDrain(cutoff = 2, locationCheckpoint = 2)
		val poison = wal(1, "poison-step", positiveStep(sequence = 1)).copy(
			payloadChecksum = "not-the-payload-checksum",
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(poison) shouldBe 1L
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(2, "healthy-step", positiveStep(sequence = 2)),
		) shouldBe 2L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao().getBySourceSignalId("source-event:poison-step") shouldBe null
		requireNotNull(database.stepIntervalDao().getBySourceSignalId("source-event:healthy-step"))
			.stepCount shouldBe 5
		database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 1)?.failureCode shouldBe
			"LEGACY_V27_PAYLOAD_INTEGRITY"
		database.legacyV27ProjectionDrainDao().target(EVENT_FRAME, 1)?.lastCompletedOrdinal shouldBe 2L
	}

	@Test
	fun `unknown outbox generation blocks before suppressing known control outbox`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		val projectionState = database.sourceProjectionStateDao()
		projectionState.insertOutbox(outbox("known-activity", ACTIVITY, version = 1)) shouldBe 1L
		projectionState.insertOutbox(outbox("unknown-generation", "future-projection", version = 9)) shouldBe 2L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Blocked(
			"UNKNOWN_V27_OUTBOX_GENERATION",
		)
		projectionState.pendingOutbox(10).map { it.stableId } shouldBe
			listOf("known-activity", "unknown-generation")
		database.legacyV27ProjectionDrainDao().target(ACTIVITY, 1)?.disposition shouldBe
			LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING
		database.legacyV27ProjectionDrainDao().get()?.status shouldBe
			LegacyV27ProjectionDrainEntity.STATUS_BLOCKED_UNSUPPORTED_TARGET
	}

	@Test
	fun `lifecycle epoch mismatch opens no destination writer`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		lifecycle.update(CollectedDataLifecycleSnapshot(epoch = 1, retainedFromMs = 10_000))
		val observation = locationObservation()
		database.locationProjectionDao().upsertObservation(observation)
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(1, "superseded-step", positiveStep(sequence = 1)),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.LifecycleSuperseded
		database.stepIntervalDao().getAllBetween(0, Long.MAX_VALUE).size shouldBe 0
		database.pressureSampleDao().countBetween(0, Long.MAX_VALUE) shouldBe 0
		database.locationProjectionDao().observations(TRACKING_ID) shouldBe listOf(observation)
		database.locationSampleDao().countAll() shouldBe 0L
		database.legacyV27ProjectionDrainDao().get()?.let { drain ->
			drain.status shouldBe LegacyV27ProjectionDrainEntity.STATUS_PENDING
			drain.ownerToken shouldBe null
		}
	}

	@Test
	fun `outbox only event frame is recovered with explicit partial provenance`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		val cycle = TrackingCycle(
			timestampMs = 5_000,
			elapsedRealtimeNanos = 2_000_000,
			stepDelta = 4,
			totalStepsSinceBoot = 104,
			stepSensorValueStart = 100,
			stepSensorValueEnd = 104,
			stepSensorReset = false,
			stepWindowStartElapsedRealtimeNanos = 1_000_000,
			stepWindowEndElapsedRealtimeNanos = 2_000_000,
			stepSourceFirstSequence = 1,
			stepSourceLastSequence = 2,
			persistenceSignalId = "source-event:outbox-only-step",
		)
		database.sourceProjectionStateDao().insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = "event-tracking-frame:outbox-only-step",
				projectionId = EVENT_FRAME,
				projectionVersion = 1,
				admissionOrdinal = 1,
				effectKind = EventTrackingFrameProjection.OUTBOX_KIND,
				payloadVersion = EventTrackingFrameEffectCodec.VERSION,
				payload = EventTrackingFrameEffectCodec.encode(TRACKING_ID, cycle),
				createdAtMs = 5_000,
				deliveredAtMs = null,
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		val recovered = requireNotNull(
			database.stepIntervalDao().getBySourceSignalId("source-event:outbox-only-step"),
		)
		recovered.stepCount shouldBe 4
		recovered.observationStamp.capabilityFlags shouldBe
			"LEGACY_V27_OUTBOX_ONLY,PARTIAL_CLOCK_PROVENANCE"
		database.sourceProjectionStateDao().pendingOutbox(10) shouldBe emptyList()
	}

	@Test
	fun `outbox created after retention cannot resurrect a retained away cycle`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1, retainedFromMs = 2_000)
		lifecycle.update(CollectedDataLifecycleSnapshot(epoch = 0, retainedFromMs = 2_000))
		val cycle = stepCycle(
			eventId = "expired-outbox-step",
			timestampMs = 1_999,
		)
		database.sourceProjectionStateDao().insertOutbox(
			eventFrameOutbox(
				eventId = "expired-outbox-step",
				admissionOrdinal = 1,
				cycle = cycle,
				createdAtMs = 5_000,
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao()
			.getBySourceSignalId("source-event:expired-outbox-step") shouldBe null
		database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 1)?.failureCode shouldBe
			"LEGACY_V27_RETENTION_REJECTED"
	}

	@Test
	fun `not registered event frame raw wal is not materialized`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		markEventFrameNotRegistered(cutoff = 1)
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(1, "inactive-raw-step", positiveStep(sequence = 1)),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = false,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao()
			.getBySourceSignalId("source-event:inactive-raw-step") shouldBe null
	}

	@Test
	fun `not registered event frame exact outbox can recover only with partial provenance`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		markEventFrameNotRegistered(cutoff = 1)
		val eventId = "inactive-outbox-step"
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(1, eventId, positiveStep(sequence = 1)),
		) shouldBe 1L
		database.sourceProjectionStateDao().insertOutbox(
			eventFrameOutbox(
				eventId = eventId,
				admissionOrdinal = 1,
				cycle = stepCycle(eventId),
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		val recovered = requireNotNull(
			database.stepIntervalDao().getBySourceSignalId("source-event:$eventId"),
		)
		recovered.observationStamp.capabilityFlags shouldBe
			"LEGACY_V27_OUTBOX_ONLY,PARTIAL_CLOCK_PROVENANCE"
		database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 1)?.failureCode shouldBe
			"LEGACY_V27_OUTBOX_ONLY_PARTIAL"
	}

	@Test
	fun `outbox admission ordinal mismatch with raw wal is partial`() = runTest {
		seedDrain(cutoff = 2, locationCheckpoint = 2)
		val eventId = "ordinal-mismatch-step"
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(1, eventId, positiveStep(sequence = 1)),
		) shouldBe 1L
		database.sourceProjectionStateDao().insertOutbox(
			eventFrameOutbox(
				eventId = eventId,
				admissionOrdinal = 2,
				cycle = stepCycle(eventId),
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao().getAllBetween(0, Long.MAX_VALUE).size shouldBe 1
		database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 2)?.terminal shouldBe true
		database.legacyV27ProjectionDrainDao().target(EVENT_FRAME, 1)?.failureCode
			.isNullOrBlank() shouldBe false
	}

	@Test
	fun `outbox logical tracking mismatch with raw wal is partial`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		val eventId = "tracking-mismatch-step"
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(1, eventId, positiveStep(sequence = 1)).copy(
				logicalTrackingId = "different-v27-tracking",
			),
		) shouldBe 1L
		database.sourceProjectionStateDao().insertOutbox(
			eventFrameOutbox(
				eventId = eventId,
				admissionOrdinal = 1,
				cycle = stepCycle(eventId),
				logicalTrackingId = TRACKING_ID,
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao().getAllBetween(0, Long.MAX_VALUE).size shouldBe 1
		database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 1)?.terminal shouldBe true
		database.legacyV27ProjectionDrainDao().target(EVENT_FRAME, 1)?.failureCode
			.isNullOrBlank() shouldBe false
	}

	@Test
	fun `event frame outbox before active boundary is quarantined without a typed fact`() = runTest {
		seedDrain(cutoff = 5, locationCheckpoint = 5)
		markEventFrameActiveAt(activationOrdinal = 5)
		val eventId = "pre-activation-outbox-step"
		database.sourceProjectionStateDao().insertOutbox(
			eventFrameOutbox(
				eventId = eventId,
				admissionOrdinal = 4,
				cycle = stepCycle(eventId),
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao()
			.getBySourceSignalId("source-event:$eventId") shouldBe null
		val failure = requireNotNull(
			database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 4),
		)
		failure.failureCode shouldBe "LEGACY_V27_OUTBOX_OUTSIDE_ACTIVATION"
		failure.terminal shouldBe true
		database.sourceProjectionStateDao().pendingOutbox(10) shouldBe emptyList()
		database.legacyV27ProjectionDrainDao().target(EVENT_FRAME, 1)?.disposition shouldBe
			LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL
	}

	@Test
	fun `semantically changed outbox cannot overwrite its raw wal typed fact`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		val eventId = "changed-effect-step"
		database.sourceEventWalDao().insertIgnoringDuplicate(
			wal(1, eventId, positiveStep(sequence = 1)),
		) shouldBe 1L
		database.sourceProjectionStateDao().insertOutbox(
			eventFrameOutbox(
				eventId = eventId,
				admissionOrdinal = 1,
				cycle = stepCycle(eventId).copy(
					stepDelta = 9,
					totalStepsSinceBoot = 109,
					stepSensorValueEnd = 109,
				),
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		val rawFact = requireNotNull(
			database.stepIntervalDao().getBySourceSignalId("source-event:$eventId"),
		)
		rawFact.stepCount shouldBe 5
		database.stepIntervalDao().getAllBetween(0, Long.MAX_VALUE).size shouldBe 1
		val failure = requireNotNull(
			database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 1),
		)
		failure.failureCode shouldBe "LEGACY_V27_OUTBOX_SEMANTIC_MISMATCH"
		failure.terminal shouldBe true
		database.sourceProjectionStateDao().pendingOutbox(10) shouldBe emptyList()
		database.legacyV27ProjectionDrainDao().target(EVENT_FRAME, 1)?.disposition shouldBe
			LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL
	}

	@Test
	fun `nonpositive event frame outbox ordinal is quarantined without a typed fact`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		val eventId = "nonpositive-ordinal-outbox-step"
		database.sourceProjectionStateDao().insertOutbox(
			eventFrameOutbox(
				eventId = eventId,
				admissionOrdinal = 0,
				cycle = stepCycle(eventId),
			),
		) shouldBe 1L

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Complete(
			partial = true,
			suppressedOutboxCount = 0,
		)
		database.stepIntervalDao()
			.getBySourceSignalId("source-event:$eventId") shouldBe null
		val failure = requireNotNull(
			database.sourceProjectionStateDao().failure(EVENT_FRAME, 1, 0),
		)
		failure.failureCode shouldBe "LEGACY_V27_OUTBOX_OUTSIDE_ACTIVATION"
		failure.terminal shouldBe true
		database.sourceProjectionStateDao().pendingOutbox(10) shouldBe emptyList()
		database.legacyV27ProjectionDrainDao().target(EVENT_FRAME, 1)?.disposition shouldBe
			LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL
	}

	@Test
	fun `unknown target disposition fails closed`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		val dao = database.legacyV27ProjectionDrainDao()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE legacy_v27_projection_target SET disposition = ? " +
				"WHERE projection_id = ? AND projection_version = 1",
			arrayOf("MALFORMED", EVENT_FRAME),
		)

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Blocked(
			"UNSUPPORTED_LEGACY_PROJECTION",
		)
		dao.get()?.status shouldBe
			LegacyV27ProjectionDrainEntity.STATUS_BLOCKED_UNSUPPORTED_TARGET
	}

	@Test
	fun `invalid target activation fails closed`() = runTest {
		seedDrain(cutoff = 1, locationCheckpoint = 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE legacy_v27_projection_target SET initial_activation_ordinal = 0 " +
				"WHERE projection_id = ? AND projection_version = 1",
			arrayOf(EVENT_FRAME),
		)

		recovery.recover() shouldBe LegacyV27ProjectionRecoveryResult.Blocked(
			"UNSUPPORTED_LEGACY_PROJECTION",
		)
		database.stepIntervalDao().getAllBetween(0, Long.MAX_VALUE).size shouldBe 0
	}

	private suspend fun seedDrain(
		cutoff: Long,
		locationCheckpoint: Long,
		retainedFromMs: Long? = null,
	) {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 0, retainedFromMs = retainedFromMs),
		)
		val dao = database.legacyV27ProjectionDrainDao()
		dao.saveDrain(
			LegacyV27ProjectionDrainEntity(
				cutoffAdmissionOrdinal = cutoff,
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
		listOf(ACTIVITY, EVENT_FRAME, EXPLICIT_JOINS, LOCATION).forEach { projectionId ->
			val checkpoint = if (projectionId == LOCATION) locationCheckpoint else 0
			dao.saveTarget(
				LegacyV27ProjectionTargetEntity(
					projectionId = projectionId,
					projectionVersion = 1,
					initialActivationOrdinal = 1,
					initialCheckpointOrdinal = checkpoint,
					requiredThroughOrdinal = cutoff,
					lastCompletedOrdinal = checkpoint,
					retentionRequired = true,
					initialRegistrationStatus = "ACTIVE",
					disposition = LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING,
					completedAtMs = null,
					failureCode = null,
				),
			)
		}
	}

	private fun positiveStep(sequence: Long) = StepCounterWindowPayload(
		bootClockDomainId = "provider-boot",
		firstCumulativeCount = 100,
		lastCumulativeCount = 105,
		deltaCount = 5,
		windowStartElapsedRealtimeNanos = sequence * 1_000_000,
		windowEndElapsedRealtimeNanos = (sequence + 1) * 1_000_000,
		firstProviderSequence = sequence * 2,
		lastProviderSequence = sequence * 2 + 1,
		baselineReset = false,
	)

	private suspend fun markEventFrameNotRegistered(cutoff: Long) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE legacy_v27_projection_target SET initial_activation_ordinal = ?, " +
				"initial_checkpoint_ordinal = ?, last_completed_ordinal = ?, " +
				"initial_registration_status = ? " +
				"WHERE projection_id = ? AND projection_version = 1",
			arrayOf<Any>(
				cutoff + 1,
				cutoff,
				cutoff,
				"NOT_REGISTERED_AT_MIGRATION",
				EVENT_FRAME,
			),
		)
	}

	private suspend fun markLocationNotRegistered(checkpoint: Long) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE legacy_v27_projection_target SET initial_checkpoint_ordinal = ?, " +
				"last_completed_ordinal = ?, initial_registration_status = ? " +
				"WHERE projection_id = ? AND projection_version = 1",
			arrayOf<Any>(
				checkpoint,
				checkpoint,
				"NOT_REGISTERED_AT_MIGRATION",
				LOCATION,
			),
		)
	}

	private suspend fun markEventFrameActiveAt(activationOrdinal: Long) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE legacy_v27_projection_target SET initial_activation_ordinal = ?, " +
				"initial_checkpoint_ordinal = ?, last_completed_ordinal = ? " +
				"WHERE projection_id = ? AND projection_version = 1",
			arrayOf<Any>(
				activationOrdinal,
				activationOrdinal - 1,
				activationOrdinal - 1,
				EVENT_FRAME,
			),
		)
	}

	private fun stepCycle(
		eventId: String,
		timestampMs: Long = 5_000,
	) = TrackingCycle(
		timestampMs = timestampMs,
		elapsedRealtimeNanos = 2_000_000,
		stepDelta = 5,
		totalStepsSinceBoot = 105,
		stepSensorValueStart = 100,
		stepSensorValueEnd = 105,
		stepSensorReset = false,
		// Released v1 began an uncompacted single transition at its first delivered callback.
		stepWindowStartElapsedRealtimeNanos = 2_000_000,
		stepWindowEndElapsedRealtimeNanos = 2_000_000,
		stepSourceFirstSequence = 3,
		stepSourceLastSequence = 3,
		persistenceSignalId = "source-event:$eventId",
	)

	private fun eventFrameOutbox(
		eventId: String,
		admissionOrdinal: Long,
		cycle: TrackingCycle,
		logicalTrackingId: String = TRACKING_ID,
		createdAtMs: Long = 5_000,
	) = SourceProjectionOutboxEntity(
		stableId = "$EVENT_FRAME:$eventId",
		projectionId = EVENT_FRAME,
		projectionVersion = 1,
		admissionOrdinal = admissionOrdinal,
		effectKind = EventTrackingFrameProjection.OUTBOX_KIND,
		payloadVersion = EventTrackingFrameEffectCodec.VERSION,
		payload = EventTrackingFrameEffectCodec.encode(logicalTrackingId, cycle),
		createdAtMs = createdAtMs,
		deliveredAtMs = null,
	)

	private fun wal(
		ordinal: Long,
		eventId: String,
		payload: StepCounterWindowPayload,
		acquiredAtMs: Long = 5_000 + ordinal,
	): SourceEventWalEntity {
		val encoded = codec.encode(payload, payloadVersion = 1)
		return SourceEventWalEntity(
			admissionOrdinal = ordinal,
			eventId = eventId,
			providerDedupKey = null,
			logicalTrackingId = TRACKING_ID,
			serviceRunId = "released-v27-run",
			sourceKind = SourceKind.STEPS.stableCode,
			sourceInstanceId = "released-v27-steps",
			registrationGeneration = 1,
			sourceSequence = ordinal,
			configRevision = 1,
			planAttribution = 0,
			clockDomainId = "wal-boot",
			observedElapsedNanos = payload.windowEndElapsedRealtimeNanos,
			observedIntervalStartNanos = payload.windowStartElapsedRealtimeNanos,
			receivedElapsedNanos = payload.windowEndElapsedRealtimeNanos + 1_000_000,
			wallTimeMs = acquiredAtMs - 1,
			wallTimeUncertaintyMs = 1,
			capturedCollectedDataEpoch = 0,
			acquiredAtMs = acquiredAtMs,
			qualityFlags = 0,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = encoded.bytes,
			payloadChecksum = encoded.checksum,
			createdAtMs = acquiredAtMs,
		)
	}

	private fun outbox(
		stableId: String,
		projectionId: String,
		version: Int,
	) = SourceProjectionOutboxEntity(
		stableId = stableId,
		projectionId = projectionId,
		projectionVersion = version,
		admissionOrdinal = 1,
		effectKind = "test-effect",
		payloadVersion = 1,
		payload = byteArrayOf(1),
		createdAtMs = 5_000,
		deliveredAtMs = null,
	)

	private fun locationObservation() = LocationProjectionObservationEntity(
		eventId = "existing-location",
		logicalTrackingId = TRACKING_ID,
		admissionOrdinal = 100,
		elapsedRealtimeNanos = 100,
		wallTimeMs = 5_000,
		latitudeDegrees = 50.0,
		longitudeDegrees = 14.0,
		horizontalAccuracyMeters = 5f,
		altitudeMeters = 300.0,
		verticalAccuracyMeters = 8f,
		speedMetersPerSecond = 1f,
	)

	private fun locationPoint() = LocationProjectionPointEntity(
		eventId = "existing-location",
		logicalTrackingId = TRACKING_ID,
		revision = 1,
		accepted = true,
		rejection = null,
		latitudeDegrees = 50.0,
		longitudeDegrees = 14.0,
		segmentDistanceMeters = 0.0,
		cumulativeDistanceMeters = 0.0,
		estimatedSpeedMetersPerSecond = null,
		rawWgs84AltitudeMeters = 300.0,
		verticalAccuracyMeters = 8f,
		elapsedRealtimeNanos = 100,
	)

	private class FakeLifecycleStore(
		initial: CollectedDataLifecycleSnapshot,
	) : CollectedDataLifecycleStore {
		private val state = MutableStateFlow(initial)
		override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
		override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
		override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
			state.value.copy(epoch = state.value.epoch + 1, retainedFromMs = deletedAtMs)
				.also { state.value = it }
		override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
			state.value.copy(
				retainedFromMs = maxOf(state.value.retainedFromMs ?: Long.MIN_VALUE, retainedFromMs),
			).also { state.value = it }
		fun update(snapshot: CollectedDataLifecycleSnapshot) {
			state.value = snapshot
		}
	}

	private companion object {
		const val ACTIVITY = "activity-automation"
		const val EVENT_FRAME = "event-tracking-frame"
		const val EXPLICIT_JOINS = "explicit-tracking-joins"
		const val LOCATION = "location-domain"
		const val TRACKING_ID = "released-v27-tracking"
	}
}
