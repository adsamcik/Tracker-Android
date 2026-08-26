package com.adsamcik.tracker.tracker.source.projection.legacy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.data.collection.PressureReading
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameEffectCodec
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameProjection
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
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
class LegacyV27EventFrameBridgeTest {
	private lateinit var database: AppDatabase
	private lateinit var bridge: LegacyV27EventFrameBridge

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceDestinationOwnerDao().insertIfAbsent(legacyStepsOwner())
		bridge = LegacyV27EventFrameBridge(
			stepIntervalDao = database.stepIntervalDao(),
			pressureSampleDao = database.pressureSampleDao(),
			sourceDestinationOwnerDao = database.sourceDestinationOwnerDao(),
		)
	}

	private fun legacyStepsOwner() = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		owner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
		ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
		updatedAtMs = 0L,
	)

	@After
	fun tearDown() = database.close()

	@Test
	fun `released step golden preserves baseline reset and sequence behavior`() = runTest {
		val baseline = StepCounterWindowPayload(
			bootClockDomainId = "boot-provider",
			firstCumulativeCount = 100,
			lastCumulativeCount = 100,
			deltaCount = 0,
			windowStartElapsedRealtimeNanos = 10_000_000,
			windowEndElapsedRealtimeNanos = 10_000_000,
			firstProviderSequence = 1,
			lastProviderSequence = 1,
			baselineReset = true,
		)
		bridge.bridge(wal("baseline", baseline), baseline) shouldBe
			LegacyV27EventFrameBridgeResult.NoFact

		val unchanged = baseline.copy(
			windowEndElapsedRealtimeNanos = 20_000_000,
			lastProviderSequence = 2,
			boundaryKind = StepBoundaryKind.COVERED,
		)
		bridge.bridge(wal("unchanged", unchanged), unchanged) shouldBe
			LegacyV27EventFrameBridgeResult.NoFact

		val resetToZero = baseline.copy(
			firstCumulativeCount = 100,
			lastCumulativeCount = 0,
			windowStartElapsedRealtimeNanos = 30_000_000,
			windowEndElapsedRealtimeNanos = 40_000_000,
			firstProviderSequence = 3,
			lastProviderSequence = 4,
		)
		val resetWal = wal(
			eventId = "reset-zero",
			payload = resetToZero,
			observedElapsedNanos = 40_000_000,
			receivedElapsedNanos = 43_000_000,
			wallTimeMs = 5_000,
			acquiredAtMs = 5_003,
			wallTimeUncertaintyMs = 7,
		)
		bridge.bridge(resetWal, resetToZero) shouldBe LegacyV27EventFrameBridgeResult.Applied(
			destination = LegacyV27EventFrameDestination.STEP_INTERVAL,
			inserted = true,
		)

		val persistedReset = requireNotNull(
			database.stepIntervalDao().getBySourceSignalId("source-event:reset-zero"),
		)
		persistedReset shouldBe StepInterval(
			id = persistedReset.id,
			startTimeMs = 5_000,
			endTimeMs = 5_000,
			stepCount = 0,
			sensorValueStart = 100,
			sensorValueEnd = 0,
			sensorReset = true,
			createdAt = 5_000,
			sourceSignalId = "source-event:reset-zero",
			observationStamp = ObservationStampColumns(
				sourceTimeMs = 5_000,
				sourceElapsedRealtimeNanos = 40_000_000,
				sourceFirstElapsedRealtimeNanos = 40_000_000,
				receivedTimeMs = 5_003,
				receivedElapsedRealtimeNanos = 43_000_000,
				sourceSequence = 4,
				sourceFirstSequence = 4,
				clockDomainId = "boot-wal",
				bootClockDomainId = "boot-provider",
				sourceAgeMs = 3,
				timeUncertaintyMs = 7,
			),
		)

		val compacted = baseline.copy(
			firstCumulativeCount = 100,
			lastCumulativeCount = 112,
			deltaCount = 12,
			windowStartElapsedRealtimeNanos = 50_000_000,
			windowEndElapsedRealtimeNanos = 55_000_000,
			firstProviderSequence = 5,
			lastProviderSequence = 8,
			boundaryKind = StepBoundaryKind.COVERED,
		)
		bridge.bridge(
			wal(
				eventId = "compacted",
				payload = compacted,
				observedElapsedNanos = 55_000_000,
				wallTimeMs = 6_000,
			),
			compacted,
		) shouldBe LegacyV27EventFrameBridgeResult.Applied(
			LegacyV27EventFrameDestination.STEP_INTERVAL,
			inserted = true,
		)
		val persistedCompacted = requireNotNull(
			database.stepIntervalDao().getBySourceSignalId("source-event:compacted"),
		)
		persistedCompacted.startTimeMs shouldBe 5_995
		persistedCompacted.observationStamp.sourceFirstElapsedRealtimeNanos shouldBe 50_000_000
		persistedCompacted.observationStamp.sourceFirstSequence shouldBe 5
		persistedCompacted.observationStamp.sourceSequence shouldBe 8
	}

	@Test
	fun `released pressure golden preserves sufficient statistics and raw wal clocks`() = runTest {
		val payload = PressureWindowPayload(
			sampleCount = 4,
			meanHectopascals = 1_000.2,
			sumSquaredDeviations = 0.26,
			minimumHectopascals = 999.9f,
			maximumHectopascals = 1_000.5f,
			windowStartElapsedRealtimeNanos = 70_000_000,
			windowEndElapsedRealtimeNanos = 90_000_000,
			firstProviderSequence = 11,
			lastProviderSequence = 14,
		)
		val wal = wal(
			eventId = "pressure",
			payload = payload,
			observedElapsedNanos = 90_000_000,
			receivedElapsedNanos = 95_000_000,
			wallTimeMs = 7_000,
			acquiredAtMs = 7_005,
			wallTimeUncertaintyMs = 9,
		)

		bridge.bridge(wal, payload) shouldBe LegacyV27EventFrameBridgeResult.Applied(
			LegacyV27EventFrameDestination.PRESSURE_SAMPLE,
			inserted = true,
		)
		val persisted = requireNotNull(
			database.pressureSampleDao().getBySourceSignalId("source-event:pressure"),
		)

		persisted.timeMs shouldBe 7_000
		persisted.elapsedRealtimeNanos shouldBe 90_000_000
		persisted.pressureHpa shouldBe 1_000.2f
		persisted.altitudeM shouldBe (
			requireNotNull(BarometricAltitudeFormula.pressureToAltitudeM(1_000.2f)).toFloat() plusOrMinus 0.001f
		)
		persisted.sampleCount shouldBe 4
		persisted.minPressureHpa shouldBe 999.9f
		persisted.maxPressureHpa shouldBe 1_000.5f
		persisted.standardDeviationHpa shouldBe (kotlin.math.sqrt(0.26 / 3).toFloat() plusOrMinus 0.0001f)
		persisted.windowStartElapsedRealtimeNanos shouldBe 70_000_000
		persisted.windowEndElapsedRealtimeNanos shouldBe 90_000_000
		persisted.observationStamp shouldBe ObservationStampColumns(
			sourceTimeMs = 7_000,
			sourceElapsedRealtimeNanos = 90_000_000,
			sourceFirstElapsedRealtimeNanos = 70_000_000,
			receivedTimeMs = 7_005,
			receivedElapsedRealtimeNanos = 95_000_000,
			sourceSequence = 14,
			sourceFirstSequence = 11,
			clockDomainId = "boot-wal",
			bootClockDomainId = "boot-wal",
			sourceAgeMs = 5,
			timeUncertaintyMs = 9,
		)
	}

	@Test
	fun `insert ignore accepts exact replay and reports unequal identity without overwrite`() = runTest {
		val pressure = PressureWindowPayload(
			sampleCount = 1,
			meanHectopascals = 1_010.0,
			sumSquaredDeviations = 0.0,
			minimumHectopascals = 1_010f,
			maximumHectopascals = 1_010f,
			windowStartElapsedRealtimeNanos = 100,
			windowEndElapsedRealtimeNanos = 100,
			firstProviderSequence = 1,
			lastProviderSequence = 1,
		)
		val pressureWal = wal("duplicate", pressure, observedElapsedNanos = 100)
		bridge.bridge(pressureWal, pressure) shouldBe LegacyV27EventFrameBridgeResult.Applied(
			LegacyV27EventFrameDestination.PRESSURE_SAMPLE,
			inserted = true,
		)
		bridge.bridge(pressureWal, pressure) shouldBe LegacyV27EventFrameBridgeResult.Applied(
			LegacyV27EventFrameDestination.PRESSURE_SAMPLE,
			inserted = false,
		)
		database.pressureSampleDao().countBetween(0, Long.MAX_VALUE) shouldBe 1

		val steps = StepCounterWindowPayload(
			bootClockDomainId = "boot-provider",
			firstCumulativeCount = 10,
			lastCumulativeCount = 15,
			deltaCount = 5,
			windowStartElapsedRealtimeNanos = 100,
			windowEndElapsedRealtimeNanos = 200,
			firstProviderSequence = 1,
			lastProviderSequence = 2,
			baselineReset = false,
		)
		val stepWal = wal("collision", steps, observedElapsedNanos = 200)
		val existing = StepInterval(
			startTimeMs = 123,
			endTimeMs = 456,
			stepCount = 999,
			sensorValueStart = 1,
			sensorValueEnd = 1_000,
			sensorReset = false,
			createdAt = 456,
			sourceSignalId = "source-event:collision",
		)
		database.stepIntervalDao().insert(existing)

		bridge.bridge(stepWal, steps) shouldBe LegacyV27EventFrameBridgeResult.IdentityCollision(
			LegacyV27EventFrameDestination.STEP_INTERVAL,
			"source-event:collision",
		)
		val unchanged = requireNotNull(
			database.stepIntervalDao().getBySourceSignalId("source-event:collision"),
		)
		unchanged shouldBe existing.copy(id = unchanged.id)
	}

	@Test
	fun `released delivered audit stamp is compatible but changed pressure metric collides`() = runTest {
		val pressure = PressureWindowPayload(
			sampleCount = 3,
			meanHectopascals = 1_001.25,
			sumSquaredDeviations = 0.5,
			minimumHectopascals = 1_001f,
			maximumHectopascals = 1_001.5f,
			windowStartElapsedRealtimeNanos = 10_000_000,
			windowEndElapsedRealtimeNanos = 12_000_000,
			firstProviderSequence = 20,
			lastProviderSequence = 22,
		)
		val wal = wal(
			eventId = "legacy-delivered-pressure",
			payload = pressure,
			observedElapsedNanos = 12_000_000,
			receivedElapsedNanos = 14_000_000,
			wallTimeMs = 9_000,
			acquiredAtMs = 9_002,
			wallTimeUncertaintyMs = 4,
		)
		val pressureHpa = pressure.meanHectopascals.toFloat()
		val legacy = PressureSample(
			timeMs = 9_000,
			elapsedRealtimeNanos = 12_000_000,
			pressureHpa = pressureHpa,
			altitudeM = requireNotNull(
				BarometricAltitudeFormula.pressureToAltitudeM(pressureHpa),
			).toFloat(),
			bucketId = null,
			createdAt = 9_000,
			sourceSignalId = "source-event:legacy-delivered-pressure",
			sampleCount = 3,
			minPressureHpa = 1_001f,
			maxPressureHpa = 1_001.5f,
			standardDeviationHpa = 0.5f,
			windowStartElapsedRealtimeNanos = 10_000_000,
			windowEndElapsedRealtimeNanos = 12_000_000,
			observationStamp = releasedDeliveredStamp(
				cycleTimestampMs = 9_000,
				cycleElapsedRealtimeNanos = 12_000_000,
				sourceElapsedRealtimeNanos = 12_000_000,
				sourceFirstElapsedRealtimeNanos = 10_000_000,
				sourceSequence = 22,
				sourceFirstSequence = 20,
			),
		)
		database.pressureSampleDao().insert(legacy)

		bridge.bridge(wal, pressure) shouldBe LegacyV27EventFrameBridgeResult.Applied(
			LegacyV27EventFrameDestination.PRESSURE_SAMPLE,
			inserted = false,
		)
		val retainedLegacy = requireNotNull(
			database.pressureSampleDao().getBySourceSignalId(
				"source-event:legacy-delivered-pressure",
			),
		)
		retainedLegacy shouldBe legacy.copy(id = retainedLegacy.id)

		database.pressureSampleDao().delete(retainedLegacy)
		val changedMetric = legacy.copy(id = 0, pressureHpa = pressureHpa + 1f)
		database.pressureSampleDao().insert(changedMetric)
		bridge.bridge(wal, pressure) shouldBe LegacyV27EventFrameBridgeResult.IdentityCollision(
			LegacyV27EventFrameDestination.PRESSURE_SAMPLE,
			"source-event:legacy-delivered-pressure",
		)
		val retainedCollision = requireNotNull(
			database.pressureSampleDao().getBySourceSignalId(
				"source-event:legacy-delivered-pressure",
			),
		)
		retainedCollision.pressureHpa shouldBe (pressureHpa + 1f)
	}

	@Test
	fun `outbox only fallback writes typed facts with explicit partial clock provenance`() = runTest {
		val stepCycle = TrackingCycle(
			timestampMs = 10_000,
			elapsedRealtimeNanos = 30_000_000,
			stepDelta = 7,
			totalStepsSinceBoot = 107,
			stepSensorValueStart = 100,
			stepSensorValueEnd = 107,
			stepSensorReset = false,
			stepWindowStartElapsedRealtimeNanos = 25_000_000,
			stepWindowEndElapsedRealtimeNanos = 30_000_000,
			stepSourceFirstSequence = 30,
			stepSourceLastSequence = 33,
			persistenceSignalId = "source-event:outbox-step",
		)
		bridge.bridge(outbox("step-effect", 40, stepCycle)) shouldBe
			LegacyV27EventFrameBridgeResult.Applied(
				destination = LegacyV27EventFrameDestination.STEP_INTERVAL,
				inserted = true,
				auditProvenance = LegacyV27BridgeAuditProvenance.LEGACY_OUTBOX_PARTIAL,
			)
		bridge.bridge(outbox("step-effect", 40, stepCycle)) shouldBe
			LegacyV27EventFrameBridgeResult.Applied(
				destination = LegacyV27EventFrameDestination.STEP_INTERVAL,
				inserted = false,
				auditProvenance = LegacyV27BridgeAuditProvenance.LEGACY_OUTBOX_PARTIAL,
			)
		val step = requireNotNull(
			database.stepIntervalDao().getBySourceSignalId("source-event:outbox-step"),
		)
		step.startTimeMs shouldBe 9_995
		step.endTimeMs shouldBe 10_000
		step.stepCount shouldBe 7
		step.observationStamp shouldBe partialOutboxStamp(
			cycleTimestampMs = 10_000,
			cycleElapsedRealtimeNanos = 30_000_000,
			sourceElapsedRealtimeNanos = 30_000_000,
			sourceFirstElapsedRealtimeNanos = 25_000_000,
			sourceSequence = 33,
			sourceFirstSequence = 30,
		)

		val pressureCycle = TrackingCycle(
			timestampMs = 11_000,
			elapsedRealtimeNanos = 50_000_000,
			pressure = PressureReading(
				pressureHpa = 1_005f,
				altitudeM = 69.1f,
				sampleCount = 2,
				minPressureHpa = 1_004.5f,
				maxPressureHpa = 1_005.5f,
				standardDeviationHpa = 0.7f,
				windowStartElapsedRealtimeNanos = 45_000_000,
				windowEndElapsedRealtimeNanos = 50_000_000,
				sourceFirstSequence = 40,
				sourceLastSequence = 41,
			),
			persistenceSignalId = "source-event:outbox-pressure",
		)
		val pressureOutbox = outbox("pressure-effect", 41, pressureCycle)
		val previouslyDeliveredPressure = PressureSample(
			timeMs = 11_000,
			elapsedRealtimeNanos = 50_000_000,
			pressureHpa = 1_005f,
			altitudeM = 69.1f,
			bucketId = null,
			createdAt = 11_000,
			sourceSignalId = "source-event:outbox-pressure",
			sampleCount = 2,
			minPressureHpa = 1_004.5f,
			maxPressureHpa = 1_005.5f,
			standardDeviationHpa = 0.7f,
			windowStartElapsedRealtimeNanos = 45_000_000,
			windowEndElapsedRealtimeNanos = 50_000_000,
			observationStamp = releasedDeliveredStamp(
				cycleTimestampMs = 11_000,
				cycleElapsedRealtimeNanos = 50_000_000,
				sourceElapsedRealtimeNanos = 50_000_000,
				sourceFirstElapsedRealtimeNanos = 45_000_000,
				sourceSequence = 41,
				sourceFirstSequence = 40,
			),
		)
		database.pressureSampleDao().insert(previouslyDeliveredPressure)
		bridge.bridge(pressureOutbox) shouldBe LegacyV27EventFrameBridgeResult.Applied(
			destination = LegacyV27EventFrameDestination.PRESSURE_SAMPLE,
			inserted = false,
			auditProvenance = LegacyV27BridgeAuditProvenance.LEGACY_OUTBOX_PARTIAL,
		)
		val pressureSample = requireNotNull(
			database.pressureSampleDao().getBySourceSignalId("source-event:outbox-pressure"),
		)
		pressureSample.pressureHpa shouldBe 1_005f
		pressureSample.altitudeM shouldBe 69.1f
		pressureSample.observationStamp shouldBe releasedDeliveredStamp(
			cycleTimestampMs = 11_000,
			cycleElapsedRealtimeNanos = 50_000_000,
			sourceElapsedRealtimeNanos = 50_000_000,
			sourceFirstElapsedRealtimeNanos = 45_000_000,
			sourceSequence = 41,
			sourceFirstSequence = 40,
		)
	}

	@Test
	fun `empty pressure window produces no destination fact`() = runTest {
		val empty = PressureWindowPayload(
			sampleCount = 0,
			meanHectopascals = 0.0,
			sumSquaredDeviations = 0.0,
			minimumHectopascals = 0f,
			maximumHectopascals = 0f,
			windowStartElapsedRealtimeNanos = 0,
			windowEndElapsedRealtimeNanos = 0,
			firstProviderSequence = 0,
			lastProviderSequence = 0,
		)
		bridge.bridge(wal("empty-pressure", empty), empty) shouldBe
			LegacyV27EventFrameBridgeResult.NoFact
		database.pressureSampleDao().countBetween(0, Long.MAX_VALUE) shouldBe 0
	}

	private fun wal(
		eventId: String,
		payload: SourcePayload,
		observedElapsedNanos: Long = 1_000_000,
		receivedElapsedNanos: Long = observedElapsedNanos,
		wallTimeMs: Long? = 1_000,
		acquiredAtMs: Long = wallTimeMs ?: 1_000,
		wallTimeUncertaintyMs: Long? = 1,
	): SourceEventWalEntity {
		val encoded = byteArrayOf(payload.source.stableCode.toByte())
		return SourceEventWalEntity(
			admissionOrdinal = eventId.hashCode().toLong().and(Int.MAX_VALUE.toLong()) + 1,
			eventId = eventId,
			providerDedupKey = null,
			logicalTrackingId = "tracking",
			serviceRunId = "run",
			sourceKind = payload.source.stableCode,
			sourceInstanceId = "source-instance",
			registrationGeneration = 1,
			sourceSequence = when (payload.source) {
				SourceKind.STEPS -> (payload as StepCounterWindowPayload).lastProviderSequence
				SourceKind.PRESSURE -> (payload as PressureWindowPayload).lastProviderSequence
				else -> 1
			},
			configRevision = 1,
			planAttribution = 0,
			clockDomainId = "boot-wal",
			observedElapsedNanos = observedElapsedNanos,
			receivedElapsedNanos = receivedElapsedNanos,
			wallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			capturedCollectedDataEpoch = 0,
			acquiredAtMs = acquiredAtMs,
			qualityFlags = 0,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = encoded,
			payloadChecksum = encoded.sha256(),
			createdAtMs = acquiredAtMs,
		)
	}

	private fun outbox(
		stableId: String,
		admissionOrdinal: Long,
		cycle: TrackingCycle,
	): SourceProjectionOutboxEntity = SourceProjectionOutboxEntity(
		stableId = stableId,
		projectionId = EventTrackingFrameProjection.ID,
		projectionVersion = 1,
		admissionOrdinal = admissionOrdinal,
		effectKind = EventTrackingFrameProjection.OUTBOX_KIND,
		payloadVersion = EventTrackingFrameEffectCodec.VERSION,
		payload = EventTrackingFrameEffectCodec.encode("tracking", cycle),
		createdAtMs = cycle.timestampMs,
		deliveredAtMs = null,
	)

	private fun releasedDeliveredStamp(
		cycleTimestampMs: Long,
		cycleElapsedRealtimeNanos: Long,
		sourceElapsedRealtimeNanos: Long,
		sourceFirstElapsedRealtimeNanos: Long,
		sourceSequence: Long,
		sourceFirstSequence: Long,
	): ObservationStampColumns = ObservationStampColumns(
		sourceTimeMs = null,
		sourceElapsedRealtimeNanos = sourceElapsedRealtimeNanos,
		sourceFirstElapsedRealtimeNanos = sourceFirstElapsedRealtimeNanos,
		receivedTimeMs = cycleTimestampMs,
		receivedElapsedRealtimeNanos = cycleElapsedRealtimeNanos,
		sourceSequence = sourceSequence,
		sourceFirstSequence = sourceFirstSequence,
		clockDomainId = "released-process-domain",
		bootClockDomainId = "released-boot-domain",
		sourceAgeMs = 0,
		timeUncertaintyMs = 0,
	)

	private fun partialOutboxStamp(
		cycleTimestampMs: Long,
		cycleElapsedRealtimeNanos: Long,
		sourceElapsedRealtimeNanos: Long,
		sourceFirstElapsedRealtimeNanos: Long,
		sourceSequence: Long,
		sourceFirstSequence: Long,
	): ObservationStampColumns = ObservationStampColumns(
		sourceTimeMs = null,
		sourceElapsedRealtimeNanos = sourceElapsedRealtimeNanos,
		sourceFirstElapsedRealtimeNanos = sourceFirstElapsedRealtimeNanos,
		receivedTimeMs = cycleTimestampMs,
		receivedElapsedRealtimeNanos = cycleElapsedRealtimeNanos,
		sourceSequence = sourceSequence,
		sourceFirstSequence = sourceFirstSequence,
		clockDomainId = "LEGACY_UNKNOWN",
		bootClockDomainId = "LEGACY_UNKNOWN",
		sourceAgeMs = null,
		timeUncertaintyMs = null,
		capabilityFlags = "LEGACY_V27_OUTBOX_ONLY,PARTIAL_CLOCK_PROVENANCE",
	)

	private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
		.digest(this)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
