package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.di.SourcePipelineModule
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
		val payload = requireNotNull(accumulator.accept("boot", 100L, 10L, 1L))

		event("baseline", 1L, payload).toEventTrackingFrame() shouldBe null
	}

	@Test
	fun `step windows preserve deltas boundaries and reset semantics`() {
		val newAccumulator = StepWindowAccumulator(null)

		newAccumulator.accept("boot", 100L, 10L, 1L)

		val projectedNormal = listOf(
			requireNotNull(newAccumulator.accept("boot", 105L, 20L, 2L)),
			requireNotNull(newAccumulator.accept("boot", 110L, 30L, 3L)),
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

		val resetPayload = requireNotNull(newAccumulator.accept("boot", 2L, 40L, 4L))
		resetPayload.deltaCount shouldBe 0L
		resetPayload.baselineReset shouldBe true
		resetPayload.firstCumulativeCount shouldBe 2L
		resetPayload.lastCumulativeCount shouldBe 2L
		event("reset", 4L, resetPayload).toEventTrackingFrame() shouldBe null
	}

	@Test
	fun `pressure sufficient statistics produce the expected Welford aggregate`() {
		val accumulator = PressureWindowAccumulator(
			windowNanos = 1_000_000L,
			effectiveSamplePeriodMicros = 1_000,
			effectiveMaximumReportLatencyMicros = 0,
		)
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
	fun `live production projections do not emit v2 frames for steps or pressure`() = runTest {
		val productionProjections = setOf(
			SourcePipelineModule.provideActivityAutomationProjection(ActivityAutomationProjection()),
		)
		val projections = ProjectionDispatcher(database, productionProjections)
		projections.registerAll(1L)
		projections.dispatch(
			event(
				"live-step",
				1L,
				StepCounterWindowPayload("boot", 100, 105, 5, 10, 20, 1, 2, false),
			),
		).complete shouldBe true
		projections.dispatch(
			event(
				"live-pressure",
				2L,
				PressureWindowPayload(
					sampleCount = 2,
					meanHectopascals = 1_000.0,
					sumSquaredDeviations = 0.5,
					minimumHectopascals = 999.5f,
					maximumHectopascals = 1_000.5f,
					windowStartElapsedRealtimeNanos = 20,
					windowEndElapsedRealtimeNanos = 30,
					firstProviderSequence = 1,
					lastProviderSequence = 2,
				),
			),
		).complete shouldBe true

		val dao = database.sourceProjectionStateDao()
		dao.registration(
			ActivityAutomationProjection.ID,
			ActivityAutomationProjection.VERSION,
		)?.status shouldBe "ACTIVE"
		dao.registration(
			EventTrackingFrameProjection.ID,
			EventTrackingFrameProjection.VERSION,
		) shouldBe null
		dao.registration(
			LocationDomainProjection.ID,
			LocationDomainProjection.VERSION,
		) shouldBe null
		dao.registration(
			ExplicitTrackingJoinProjection.ID,
			ExplicitTrackingJoinProjection.VERSION,
		) shouldBe null
		dao.pendingOutbox(10) shouldBe emptyList()
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
