package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.runtime.PressureWindowAccumulator
import com.adsamcik.tracker.tracker.source.runtime.StepWindowAccumulator
import io.kotest.matchers.floats.plusOrMinus
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
class EventTrackingFrameProjectionTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `first step counter callback remains a baseline-only event`() {
		val accumulator = StepWindowAccumulator(null)
		val payload = accumulator.accept("boot", 100L, 10L, 1L)

		event("baseline", 1L, payload).toEventTrackingFrame() shouldBe null
	}

	@Test
	fun `step windows preserve deltas boundaries and reset semantics`() {
		val newAccumulator = StepWindowAccumulator(null)

		newAccumulator.accept("boot", 100L, 10L, 1L)

		val projectedNormal = listOf(
			newAccumulator.accept("boot", 105L, 20L, 2L),
			newAccumulator.accept("boot", 110L, 30L, 3L),
		).mapIndexedNotNull { index, payload ->
			event("normal-$index", index + 2L, payload).toEventTrackingFrame()
		}

		projectedNormal.sumOf { requireNotNull(it.stepDelta) } shouldBe 10
		projectedNormal.first().stepSensorValueStart shouldBe 100
		projectedNormal.last().stepSensorValueEnd shouldBe 110
		projectedNormal.first().stepWindowStartElapsedRealtimeNanos shouldBe 20L
		projectedNormal.last().stepWindowEndElapsedRealtimeNanos shouldBe 30L
		projectedNormal.first().stepSourceFirstSequence shouldBe 2L
		projectedNormal.last().stepSourceLastSequence shouldBe 3L

		val projectedReset = event(
			"reset",
			4L,
			newAccumulator.accept("boot", 2L, 40L, 4L),
		).toEventTrackingFrame()

		projectedReset?.stepDelta shouldBe 2
		projectedReset?.stepSensorReset shouldBe true
		projectedReset?.totalStepsSinceBoot shouldBe 2L
	}

	@Test
	fun `pressure sufficient statistics produce the expected Welford aggregate`() {
		val accumulator = PressureWindowAccumulator(windowNanos = 1_000_000L)
		val samples = listOf(1000.15f, 1000.45f, 999.95f, 1000.25f)
		samples.forEachIndexed { index, value ->
			val timestamp = (index + 1L) * 100L
			accumulator.add(value, timestamp, index + 1L) shouldBe null
		}
		val projected = event("pressure", 1L, requireNotNull(accumulator.drain()))
			.toEventTrackingFrame()
		val actual = requireNotNull(projected?.pressure)
		val mean = samples.average().toFloat()
		val sampleVariance = samples.sumOf { value ->
			val difference = value - mean
			(difference * difference).toDouble()
		} / (samples.size - 1)

		actual.sampleCount shouldBe samples.size
		actual.pressureHpa shouldBe (mean plusOrMinus 0.0001f)
		actual.standardDeviationHpa shouldBe (kotlin.math.sqrt(sampleVariance).toFloat() plusOrMinus 0.0001f)
		actual.altitudeM shouldBe (
			requireNotNull(BarometricAltitudeFormula.pressureToAltitudeM(mean)).toFloat() plusOrMinus 0.001f
		)
		actual.minPressureHpa shouldBe samples.min()
		actual.maxPressureHpa shouldBe samples.max()
	}

	@Test
	fun `all event-owned source payloads round trip into terminal tracking cycles`() {
		val payloads = listOf(
			LocationFixPayload(50.0, 14.0, 5f, 250.0, 4f, 2f, 90f, "gps"),
			ActivityRecognitionPayload(activityType = 1, confidencePercent = 88, providerElapsedRealtimeNanos = 10L),
			WifiResultSnapshotPayload(
				listOf(WifiAccessPointEvidence("0123456789abcdef", 5_200, -60)),
				platformTimestampMs = 1L,
				resultAgeMs = 0L,
			),
			CellSnapshotPayload(
				subscriptionId = 1,
				observations = listOf(
					CellObservationEvidence("fedcba9876543210", "LTE", true, -90, 10L),
				),
				refreshOutcome = CellRefreshOutcome.CALLBACK,
			),
		)

		val cycles = payloads.mapIndexed { index, payload ->
			val cycle = requireNotNull(event("source-$index", index + 1L, payload).toEventTrackingFrame())
			val encoded = EventTrackingFrameEffectCodec.encode("tracking", cycle)
			EventTrackingFrameEffectCodec.decode(encoded, EventTrackingFrameEffectCodec.VERSION).cycle
		}

		cycles[0].location?.lastLocation?.latitude shouldBe 50.0
		cycles[0].location?.lastFixMetadata?.sourceEventId shouldBe "source-0"
		cycles[1].activity?.activityType shouldBe 7
		cycles[1].activity?.confidence shouldBe 88
		cycles[2].normalizedWifiScan?.networks?.single()?.bssid shouldBe "0123456789abcdef"
		cycles[2].normalizedWifiScan?.networks?.single()?.level shouldBe -60
		cycles[3].normalizedCellScan?.towers?.single()?.networkType shouldBe 4
		cycles[3].normalizedCellScan?.towers?.single()?.signalStrength shouldBe -90
	}

	@Test
	fun `committed event frame is delivered once and retains stable identity`() = runTest {
		val projection = EventTrackingFrameProjection(database)
		val projections = ProjectionDispatcher(database, setOf(projection))
		projections.registerAll(1L)
		val payload = StepCounterWindowPayload("boot", 100, 105, 5, 10, 20, 1, 2, false)
		projections.dispatch(event("durable-step", 1L, payload)).complete shouldBe true

		val received = mutableListOf<com.adsamcik.tracker.tracker.data.collection.TrackingCycle>()
		val outbox = EventTrackingFrameOutboxDispatcher(database)
		outbox.attach("wrong", "another-session", EventTrackingFrameConsumer(received::add))
		outbox.drain() shouldBe 0
		outbox.attach("test", "tracking", EventTrackingFrameConsumer(received::add))
		outbox.drain() shouldBe 1
		outbox.drain() shouldBe 0

		received.single().persistenceSignalId shouldBe "source-event:durable-step"
		received.single().stepDelta shouldBe 5
	}

	private fun event(
		id: String,
		ordinal: Long,
		payload: SourcePayload,
	) = AdmittedSourceEvent(
		eventId = SourceEventId(id),
		admissionOrdinal = ordinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = LogicalTrackingId("tracking"),
			serviceRunId = ServiceRunId("run"),
			source = payload.source,
			sourceInstanceId = SourceInstanceId("source-instance"),
			registrationGeneration = 1,
			sourceSequence = ordinal,
			configRevision = 1,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot",
			observedElapsedRealtimeNanos = ordinal * 10,
			receivedElapsedRealtimeNanos = ordinal * 10,
			wallTimeMs = 1_000L + ordinal,
			wallTimeUncertaintyMs = 0,
			capturedCollectedDataEpoch = 0,
			acquiredAtMs = 1_000L + ordinal,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = payload,
		),
	)
}
