package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.ingress.PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PressureSourceRuntimeTest {
	@Test
	fun `earlier Pressure End during prefix retry preserves original capture and the expanded ambient tail`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 500L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true,
			includeAmbient = true)))
		val attempted = mutableListOf<PressureWindowPayload>()
		val accepted = mutableListOf<SourceDeliveryCandidate>()
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure requires atomic admission")
			override suspend fun admit(delivery: SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint): SourceDeliveryAdmissionHandoff {
				attempted += delivery.units.single().evidence.payload as PressureWindowPayload
				return if (attempted.size == 1) {
					SourceDeliveryAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
				} else {
					accepted += delivery
					SourceDeliveryAdmissionHandoff.Durable(listOf(accepted.size.toLong()))
				}
			}
		}
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("progressing-End"), activePlan, sink))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		val samples = listOf(0L to 1_000f, 100L to 1_002f, 200L to 1_004f,
			300L to 1_100f, 500L to 1_200f)
		var previousOffset = 0L
		for ((offset, pressure) in samples) {
			ShadowSystemClock.advanceBy(Duration.ofMillis(offset - previousOffset))
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + offset * 1_000_000L, pressure))
			previousOffset = offset
		}
		val laterCutoff = pressureCutoff().copy(elapsedRealtimeNanos = first + 200_000_000L)
		fixture.runtime.fenceSessionCutoff(laterCutoff, "run-1", "boot-1")
		testScheduler.runCurrent()
		assertEquals(1, attempted.size)
		assertEquals(3, attempted.single().sampleCount)
		assertTrue(accepted.isEmpty())
		val earlierCutoff = laterCutoff.copy(elapsedRealtimeNanos = first + 100_000_000L)
		fixture.runtime.fenceSessionCutoff(earlierCutoff, "run-1", "boot-1")
		testScheduler.advanceTimeBy(100L)
		testScheduler.runCurrent()

		assertEquals(2, accepted.size)
		val prefix = accepted.first().units.single()
		val tail = accepted.last().units.single()
		val prefixPayload = prefix.evidence.payload as PressureWindowPayload
		val tailPayload = tail.evidence.payload as PressureWindowPayload
		assertEquals(2, prefixPayload.sampleCount)
		assertEquals(1_001.0, prefixPayload.meanHectopascals)
		assertEquals(2.0, prefixPayload.sumSquaredDeviations)
		assertEquals(1L..2L, prefixPayload.firstProviderSequence..prefixPayload.lastProviderSequence)
		assertNull(prefix.excludedPressureCapture)
		assertEquals(2, tailPayload.sampleCount)
		assertEquals(1_052.0, tailPayload.meanHectopascals)
		assertEquals(4_608.0, tailPayload.sumSquaredDeviations)
		assertEquals(3L..4L, tailPayload.firstProviderSequence..tailPayload.lastProviderSequence)
		assertEquals("run-1", tail.excludedPressureCapture?.serviceRunId)
		val ack = fixture.runtime.quiesceShared(earlierCutoff)
		val saved = accepted.map { it.units.single().evidence.payload as PressureWindowPayload }
		assertEquals(5, saved.sumOf { it.sampleCount })
		assertEquals(listOf(1L, 2L, 3L, 4L, 5L), saved.flatMap {
			(it.firstProviderSequence..it.lastProviderSequence).toList()
		})
		assertEquals(0L, ack.failedAdmissionCount)
		assertTrue(ack.appDrainComplete)
	}

	@Test
	fun `early Pressure End preserves the true prefix and ambient callbacks before Room Stop commits`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 300L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true,
			includeAmbient = true)))
		val sink = RecordingPressureSink()
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("early-End"), activePlan, sink))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 100_000_000L, 1_002f))
		val cutoff = pressureCutoff().copy(elapsedRealtimeNanos = first + 100_000_000L)
		assertEquals(cutoff, fixture.runtime.fenceSessionCutoff(cutoff, "run-1", "boot-1"))
		for (offset in listOf(200L, 300L, 400L)) {
			ShadowSystemClock.advanceBy(Duration.ofMillis(100))
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + offset * 1_000_000L, 1_100f))
		}
		testScheduler.runCurrent()

		val prefix = sink.windows.first()
		assertEquals(2, prefix.sampleCount)
		assertEquals(1_001.0, prefix.meanHectopascals)
		assertEquals(2.0, prefix.sumSquaredDeviations)
		assertEquals(first + 100_000_000L, prefix.windowEndElapsedRealtimeNanos)
		assertNull(sink.deliveries.first().units.single().excludedPressureCapture)
		val tail = sink.windows[1]
		assertEquals(1, tail.sampleCount)
		assertEquals(1_100.0, tail.meanHectopascals)
		assertEquals(first + 200_000_000L, tail.windowStartElapsedRealtimeNanos)
		assertEquals("run-1", sink.deliveries[1].units.single().excludedPressureCapture?.serviceRunId)
		assertTrue(sink.candidates.all { it.planAttribution == PlanAttribution.CAPTURED_REGISTRATION })
		coVerify(exactly = 0) { fixture.registrations.endedCaptureCutoffElapsedNanos(any(), any(), any()) }
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		val ack = fixture.runtime.quiesceShared(cutoff)
		assertEquals(5L, ack.callbackEntryBarrierSequence)
		assertEquals(0L, ack.failedAdmissionCount)
		assertTrue(ack.appDrainComplete)
		assertEquals(5, sink.windows.sumOf { it.sampleCount })
	}

	@Test
	fun `a cached Pressure retry applies a newly arrived earlier End before admitting ambient data`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 100L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true,
			includeAmbient = true)))
		val deliveries = mutableListOf<SourceDeliveryCandidate>()
		val checkpoints = mutableListOf<SensorAdmissionCheckpoint>()
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure requires atomic admission")
			override suspend fun admit(delivery: SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint): SourceDeliveryAdmissionHandoff {
				deliveries += delivery
				checkpoints += checkpoint
				return if (deliveries.size == 1) {
					SourceDeliveryAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
				} else {
					SourceDeliveryAdmissionHandoff.Durable(listOf(deliveries.size.toLong()))
				}
			}
		}
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("cached-End"), activePlan, sink))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		for (offset in listOf(0L, 50L, 100L)) {
			if (offset > 0L) {
				ShadowSystemClock.advanceBy(Duration.ofMillis(50))
			}
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + offset * 1_000_000L, 1_000f))
		}
		testScheduler.runCurrent()
		assertEquals(1, deliveries.size)
		assertNull(deliveries.single().units.single().excludedPressureCapture)
		val cutoff = pressureCutoff().copy(elapsedRealtimeNanos = first - 1L)
		fixture.runtime.fenceSessionCutoff(cutoff, "run-1", "boot-1")
		testScheduler.advanceTimeBy(100L)
		testScheduler.runCurrent()

		assertEquals(2, deliveries.size)
		assertEquals(deliveries.first().identity, deliveries.last().identity)
		assertSame(deliveries.first().units.single().evidence, deliveries.last().units.single().evidence)
		assertSame(checkpoints.first(), checkpoints.last())
		assertEquals("run-1", deliveries.last().units.single().excludedPressureCapture?.serviceRunId)
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		assertTrue(fixture.runtime.quiesceShared(cutoff).appDrainComplete)
	}

	@Test
	fun `Pressure cutoff ignores stale identity and isolates a compatible successor`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			compatibleRefresh = { effective ->
				registration(activePlan, 2L, effectiveElapsedNanos = effective, serviceRunId = "run-2")
			})
		val sink = RecordingPressureSink()
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("first-run"), activePlan, sink))
		val firstCutoff = pressureCutoff().copy(elapsedRealtimeNanos = 100L)
		assertNull(fixture.runtime.fenceSessionCutoff(firstCutoff.copy(logicalTrackingId = "old-session"),
			"run-1", "boot-1"))
		assertNull(fixture.runtime.fenceSessionCutoff(firstCutoff, "old-run", "boot-1"))
		assertNull(fixture.runtime.fenceSessionCutoff(firstCutoff, "run-1", "old-boot"))
		assertEquals(firstCutoff, fixture.runtime.fenceSessionCutoff(firstCutoff, "run-1", "boot-1"))
		assertIs<SourceApplyResult.Applied>(fixture.runtime.reconfigure(
			runtimeClaim("second-run").copy(serviceRunId = "run-2"), activePlan, sink))
		val nextCutoff = firstCutoff.copy(elapsedRealtimeNanos = 200L)
		assertNull(fixture.runtime.fenceSessionCutoff(firstCutoff, "run-1", "boot-1"))
		assertEquals(nextCutoff, fixture.runtime.fenceSessionCutoff(nextCutoff, "run-2", "boot-1"))
		assertEquals(nextCutoff, fixture.runtime.fenceSessionCutoff(nextCutoff.copy(elapsedRealtimeNanos = 300L),
			"run-2", "boot-1"))
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>())
		}
		fixture.runtime.close()
	}

	@Test
	fun `equal ambient and capture revision numbers retain the queued windows original applied caller`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val sameRevisionPlan = plan(1L)
		val initial = registration(sameRevisionPlan, 1L, requiresAcceptance = true,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT)
		val fixture = fixture(this, listOf(initial), compatibleRefresh = { effective ->
			registration(sameRevisionPlan, 2L, effectiveElapsedNanos = effective)
		})
		val sink = RecordingPressureSink()
		assertIs<SourceStartResult.Started>(fixture.runtime.start(sameRevisionPlan, sink))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor,
			android.os.SystemClock.elapsedRealtimeNanos(), 1_000f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(1))

		assertIs<SourceApplyResult.Applied>(fixture.runtime.reconfigure(
			runtimeClaim("equal-revision-capture"), sameRevisionPlan, sink))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor,
			android.os.SystemClock.elapsedRealtimeNanos(), 1_001f))
		assertTrue(sink.candidates.isEmpty())
		fixture.runtime.close()

		assertEquals(listOf(1L, 1L), sink.candidates.map { it.configRevision })
		assertEquals(listOf(1L, 2L), sink.candidates.map { it.authorizationRevision })
		assertEquals(listOf(PlanAttribution.RECEIVE_TIME_ONLY, PlanAttribution.CAPTURED_REGISTRATION),
			sink.candidates.map { it.planAttribution })
		assertEquals(listOf(1, 1), sink.windows.map { it.sampleCount })
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>())
		}
	}

	@Test
	fun `cancellation after physical Pressure drain retains the actual acknowledgement before returning`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true)))
		val sink = RecordingPressureSink()
		val claim = runtimeClaim("cancelled-End")
		assertIs<SourceStartResult.Started>(fixture.runtime.start(claim, activePlan, sink))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor,
			android.os.SystemClock.elapsedRealtimeNanos(), 1_000f))
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		var persisted: SourceStopAck? = null
		coEvery { fixture.registrations.savePressureRetirementCompleteness(any(), claim, any()) } coAnswers {
			persisted = thirdArg()
			entered.complete(Unit)
			release.await()
			true
		}
		val stopping = async { fixture.runtime.quiesce(pressureCutoff()) }
		entered.await()
		stopping.cancel()
		release.complete(Unit)
		stopping.join()
		assertFailsWith<CancellationException> { stopping.await() }

		val replay = fixture.runtime.quiesce(pressureCutoff())
		assertEquals(persisted, replay)
		assertEquals(1L, replay.lastAdmissionOrdinal)
		assertEquals(1L, replay.lastDurablyAdmittedSequence)
		assertEquals(0L, replay.failedAdmissionCount)
		assertTrue(replay.appDrainComplete)
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(fixture.listeners.single()) }
	}

	@Test
	fun `failed Pressure acknowledgement save retains ownership and blocks replacement until retry succeeds`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true)))
		val sink = RecordingPressureSink()
		val claim = runtimeClaim("save-failed-End")
		assertIs<SourceStartResult.Started>(fixture.runtime.start(claim, activePlan, sink))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor,
			android.os.SystemClock.elapsedRealtimeNanos(), 1_000f))
		coEvery { fixture.registrations.savePressureRetirementCompleteness(any(), claim, any()) } returns false

		val pending = fixture.runtime.quiesce(pressureCutoff())
		assertFalse(pending.appDrainComplete)
		assertIs<SourceStartResult.Failed>(fixture.runtime.start(claim, plan(2L), sink))
		coVerify(exactly = 1) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		coEvery { fixture.registrations.savePressureRetirementCompleteness(any(), claim, any()) } returns true

		fixture.runtime.close()
		val replay = fixture.runtime.quiesce(pressureCutoff())
		assertTrue(replay.appDrainComplete)
		assertEquals(SourceStopStatus.COMPLETE, replay.status)
		assertEquals(ProviderFlushOutcome.NOT_SUPPORTED, replay.providerFlushOutcome)
		assertEquals(1L, replay.lastAdmissionOrdinal)
		assertEquals(0L, replay.failedAdmissionCount)
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(fixture.listeners.single()) }
	}

	@Test
	fun `durable End resolves rejected post cutoff Pressure before runtime receives End`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 100L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true)))
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("delayed-End"), activePlan,
			rejectingPressureSink()))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		coEvery { fixture.registrations.endedCaptureCutoffElapsedNanos(any(), "pressure-test", "run-1") } returns first - 1L
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(50))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 50_000_000L, 1_001f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(50))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 100_000_000L, 1_002f))
		testScheduler.runCurrent()
		coVerify(atLeast = 1) { fixture.registrations.endedCaptureCutoffElapsedNanos(any(), "pressure-test", "run-1") }
		val ack = fixture.runtime.quiesce(pressureCutoff().copy(elapsedRealtimeNanos = first - 1L))
		assertTrue(ack.appDrainComplete)
		assertEquals(0L, ack.failedAdmissionCount)
		assertNull(ack.unresolvedSequenceStart)
		assertNull(ack.lastAdmissionOrdinal)
	}

	@Test
	fun `rejected Pressure wholly after End resolves without invented capture loss or a saved fact`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true)))
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("ended-capture"), activePlan,
			rejectingPressureSink()))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor,
			android.os.SystemClock.elapsedRealtimeNanos(), 1_001f))

		val ack = fixture.runtime.quiesce(pressureCutoff().copy(elapsedRealtimeNanos = first - 1L))
		assertTrue(ack.appDrainComplete)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(2L, ack.callbackEntryBarrierSequence)
		assertEquals(0L, ack.failedAdmissionCount)
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.lastAdmissionOrdinal)
		assertNull(ack.unresolvedSequenceStart)
		coVerify(atLeast = 1) {
			fixture.registrations.saveRuntimeState(any(), 2L, null, null, any(), any(), any(), any())
		}
	}

	@Test
	fun `rejected eligible Pressure retains exact loss while post-End samples are outside capture`() = runTest {
		val cases = listOf(
			Triple(SourceBrokerPurpose.SESSION_CAPTURE, 50_000_000L, "pressure-test"),
			Triple(SourceBrokerPurpose.AMBIENT_PRODUCT, -1L, "pressure-test"),
			Triple(SourceBrokerPurpose.SESSION_CAPTURE, -1L, "another-session"),
		)
		for ((purpose, offset, logicalId) in cases) {
			ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
			val activePlan = plan(1L)
			val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true, purpose = purpose)))
			assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("retained-loss"), activePlan,
				rejectingPressureSink()))
			val first = android.os.SystemClock.elapsedRealtimeNanos()
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
			ShadowSystemClock.advanceBy(Duration.ofMillis(100))
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor,
				android.os.SystemClock.elapsedRealtimeNanos(), 1_001f))
			val ack = fixture.runtime.quiesce(pressureCutoff().copy(logicalTrackingId = logicalId,
				elapsedRealtimeNanos = first + offset))
			assertTrue(ack.appDrainComplete)
			val eligibleLastSequence = if (purpose == SourceBrokerPurpose.SESSION_CAPTURE && offset > 0L) 1L else 2L
			assertEquals(eligibleLastSequence, ack.failedAdmissionCount)
			assertEquals(1L, ack.unresolvedSequenceStart)
			assertEquals(eligibleLastSequence, ack.unresolvedSequenceEndInclusive)
		}
	}

	@Test
	fun `crossing End admits the original Pressure prefix once with exact statistics and no tail loss`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true)))
		val sink = CutoffPressureSink(prefixFailures = 1)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("prefix"), activePlan, sink))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		sink.cutoff = first + 100_000_000L
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 100_000_000L, 1_002f,
			SensorManager.SENSOR_STATUS_ACCURACY_LOW))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 200_000_000L, 1_100f,
			SensorManager.SENSOR_STATUS_UNRELIABLE))

		val ack = fixture.runtime.quiesce(pressureCutoff().copy(elapsedRealtimeNanos = sink.cutoff))
		val saved = sink.accepted.single()
		assertEquals(2, saved.sampleCount)
		assertEquals(1L, saved.firstProviderSequence)
		assertEquals(2L, saved.lastProviderSequence)
		assertEquals(first, saved.windowStartElapsedRealtimeNanos)
		assertEquals(first + 100_000_000L, saved.windowEndElapsedRealtimeNanos)
		assertEquals(1_001.0, saved.meanHectopascals)
		assertEquals(2.0, saved.sumSquaredDeviations)
		assertEquals(1_000f, saved.minimumHectopascals)
		assertEquals(1_002f, saved.maximumHectopascals)
		assertEquals(PressureSensorAccuracy.LOW, saved.sensorAccuracy)
		assertEquals(PressureWindowClosureKind.SOURCE_BOUNDARY, saved.closureKind)
		// The local fence rebuilds before the sink; only the exact prefix retry reaches it.
		assertEquals(2, sink.deliveries.size)
		assertSame(sink.deliveries[0], sink.deliveries[1])
		assertSame(sink.checkpoints[0], sink.checkpoints[1])
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertTrue(ack.appDrainComplete)
		assertEquals(3L, ack.callbackEntryBarrierSequence)
		assertEquals(2L, ack.lastDurablyAdmittedSequence)
		assertEquals(41L, ack.lastAdmissionOrdinal)
		assertEquals(0L, ack.failedAdmissionCount)
		assertNull(ack.unresolvedSequenceStart)
		coVerify(atLeast = 1) {
			fixture.registrations.saveRuntimeState(any(), 3L, 2L, 41L, any(), any(), any(), any())
		}
	}

	@Test
	fun `shared Pressure crossing End preserves disjoint exact capture prefix and ambient tail`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true,
			includeAmbient = true)))
		val sink = CutoffPressureSink(prefixFailures = 1, preserveAmbientTail = true)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("shared-prefix"), activePlan, sink))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		sink.cutoff = first + 100_000_000L
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 100_000_000L, 1_002f,
			SensorManager.SENSOR_STATUS_ACCURACY_LOW))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 200_000_000L, 1_100f,
			SensorManager.SENSOR_STATUS_UNRELIABLE))

		val ack = fixture.runtime.quiesceShared(pressureCutoff().copy(elapsedRealtimeNanos = sink.cutoff))
		assertEquals(2, sink.accepted.size)
		val (prefix, tail) = sink.accepted
		assertEquals(2, prefix.sampleCount)
		assertEquals(1_001.0, prefix.meanHectopascals)
		assertEquals(2.0, prefix.sumSquaredDeviations)
		assertEquals(1_000f, prefix.minimumHectopascals)
		assertEquals(1_002f, prefix.maximumHectopascals)
		assertEquals(PressureSensorAccuracy.LOW, prefix.sensorAccuracy)
		assertEquals(first, prefix.windowStartElapsedRealtimeNanos)
		assertEquals(sink.cutoff, prefix.windowEndElapsedRealtimeNanos)
		assertEquals(1L, prefix.firstProviderSequence)
		assertEquals(2L, prefix.lastProviderSequence)
		assertEquals(1, tail.sampleCount)
		assertEquals(1_100.0, tail.meanHectopascals)
		assertEquals(0.0, tail.sumSquaredDeviations)
		assertEquals(PressureSensorAccuracy.UNRELIABLE, tail.sensorAccuracy)
		assertEquals(first + 200_000_000L, tail.windowStartElapsedRealtimeNanos)
		assertEquals(tail.windowStartElapsedRealtimeNanos, tail.windowEndElapsedRealtimeNanos)
		assertEquals(3L, tail.firstProviderSequence)
		assertEquals(3L, tail.lastProviderSequence)
		assertTrue(sink.accepted.all { it.closureKind == PressureWindowClosureKind.SOURCE_BOUNDARY })
		assertEquals(3, sink.deliveries.size)
		assertSame(sink.deliveries[0], sink.deliveries[1])
		assertSame(sink.checkpoints[0], sink.checkpoints[1])
		assertTrue(sink.deliveries[1].identity != sink.deliveries[2].identity)
		assertEquals(listOf(2L, 2L, 3L), sink.checkpoints.map { it.providerSequenceThrough })
		assertEquals(3L, ack.callbackEntryBarrierSequence)
		assertEquals(3L, ack.lastDurablyAdmittedSequence)
		assertEquals(42L, ack.lastAdmissionOrdinal)
		assertEquals(0L, ack.failedAdmissionCount)
		assertNull(ack.unresolvedSequenceStart)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertTrue(ack.appDrainComplete)
	}

	@Test
	fun `rejected shared Pressure prefix does not discard independently eligible ambient tail`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true,
			includeAmbient = true)))
		val sink = CutoffPressureSink(preserveAmbientTail = true, rejectPrefix = true)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("shared-rejection"), activePlan, sink))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		sink.cutoff = first
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 100_000_000L, 1_100f))
		val ack = fixture.runtime.quiesceShared(pressureCutoff().copy(elapsedRealtimeNanos = sink.cutoff))
		assertEquals(1, sink.accepted.single().sampleCount)
		assertEquals(2L, sink.accepted.single().firstProviderSequence)
		assertEquals(1_100.0, sink.accepted.single().meanHectopascals)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		assertEquals(2L, ack.lastDurablyAdmittedSequence)
		assertTrue(ack.appDrainComplete)
	}

	@Test
	fun `cancelled shared Pressure tail leaves only its samples unresolved after the prefix commits`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true,
			includeAmbient = true)))
		val sink = CutoffPressureSink(preserveAmbientTail = true, cancelTail = true)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("shared-tail-cancel"), activePlan, sink))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		sink.cutoff = first + 100_000_000L
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 100_000_000L, 1_002f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 200_000_000L, 1_100f))

		val ack = fixture.runtime.quiesceShared(pressureCutoff().copy(elapsedRealtimeNanos = sink.cutoff))
		assertEquals(2, sink.accepted.single().sampleCount)
		assertEquals(2L, sink.accepted.single().lastProviderSequence)
		assertEquals(2, sink.deliveries.size)
		assertEquals(3L, ack.callbackEntryBarrierSequence)
		assertEquals(2L, ack.lastDurablyAdmittedSequence)
		assertEquals(41L, ack.lastAdmissionOrdinal)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(3L, ack.unresolvedSequenceStart)
		assertEquals(3L, ack.unresolvedSequenceEndInclusive)
		assertFalse(ack.appDrainComplete)
		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertEquals(RegistrationRemovalOutcome.REMOVED, ack.registrationRemovalOutcome)
		fixture.runtime.close()
		assertEquals(2, sink.deliveries.size)
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `an already published End authorization can still admit its valid Pressure prefix`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 200L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true)))
		val sink = CutoffPressureSink(rejectWholeWithPolicy = true)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("published-End"), activePlan, sink))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		sink.cutoff = first + 50_000_000L
		coEvery {
			fixture.registrations.endedCaptureCutoffElapsedNanos(any(), "pressure-test", "run-1")
		} returns sink.cutoff
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 100_000_000L, 1_100f))

		// Close and drain the crossing window before local End, so SOURCE_POLICY_STALE really occurs.
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first + 200_000_000L, 1_200f))
		testScheduler.runCurrent()
		assertEquals(2, sink.deliveries.size)
		coVerify(atLeast = 1) {
			fixture.registrations.endedCaptureCutoffElapsedNanos(any(), "pressure-test", "run-1")
		}
		val ack = fixture.runtime.quiesce(pressureCutoff().copy(elapsedRealtimeNanos = sink.cutoff))
		assertEquals(1, sink.accepted.single().sampleCount)
		assertEquals(first, sink.accepted.single().windowEndElapsedRealtimeNanos)
		assertEquals(1_000.0, sink.accepted.single().meanHectopascals)
		assertEquals(0L, ack.failedAdmissionCount)
		assertTrue(ack.appDrainComplete)
		assertEquals(2, sink.deliveries.size)
	}

	@Test
	fun `structured cutoff wholly before a Pressure window creates neither saved data nor a false gap`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true)))
		val sink = CutoffPressureSink()
		assertIs<SourceStartResult.Started>(fixture.runtime.start(runtimeClaim("outside-End"), activePlan, sink))
		val first = android.os.SystemClock.elapsedRealtimeNanos()
		sink.cutoff = first - 1L
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, first, 1_000f))
		val ack = fixture.runtime.quiesce(pressureCutoff().copy(elapsedRealtimeNanos = sink.cutoff))
		assertEquals(emptyList(), sink.accepted)
		assertEquals(0, sink.deliveries.size)
		assertEquals(0L, ack.failedAdmissionCount)
		assertNull(ack.lastAdmissionOrdinal)
		assertNull(ack.unresolvedSequenceStart)
		assertTrue(ack.appDrainComplete)
	}

	@Test
	fun `retained original Pressure samples close bounded partial windows without dropping observations`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 60_000L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT)))
		val sink = RecordingPressureSink()
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, sink))
		repeat(1_025) {
			ShadowSystemClock.advanceBy(Duration.ofMillis(1))
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor,
				android.os.SystemClock.elapsedRealtimeNanos(), 1_000f))
		}
		val ack = fixture.runtime.quiesce(pressureCutoff())
		assertEquals(listOf(1_024, 1), sink.windows.map { it.sampleCount })
		assertEquals(listOf(1L, 1_025L), sink.windows.map { it.firstProviderSequence })
		assertEquals(listOf(1_024L, 1_025L), sink.windows.map { it.lastProviderSequence })
		assertEquals(0L, ack.failedAdmissionCount)
		assertTrue(ack.appDrainComplete)
	}

	private fun rejectingPressureSink() = object : SourceEventSink {
		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			error("Pressure must keep atomic window admission")
		override suspend fun admit(delivery: SourceDeliveryCandidate,
			checkpoint: SensorAdmissionCheckpoint): SourceDeliveryAdmissionHandoff =
			SourceDeliveryAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.SOURCE_POLICY_STALE)
	}

	@Test
	fun `shared End preserves an authorized all-day sample delivered during the final flush`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, maximumReportLatencyMicros = 1_000_000)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT)), fifoMaxEventCount = 100)
		val sink = RecordingPressureSink()
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, sink))
		val sampleTime = android.os.SystemClock.elapsedRealtimeNanos()
		every { fixture.sensorManager.flush(any<SensorEventListener>()) } answers {
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, sampleTime, 1_001f))
			(fixture.listener() as SensorEventListener2).onFlushCompleted(fixture.sensor)
			true
		}
		val ack = fixture.runtime.quiesceShared(SessionCutoff("session", sampleTime - 1L, 1L,
			sampleTime + 1_000_000_000L))
		assertTrue(ack.appDrainComplete)
		assertEquals(ProviderFlushOutcome.COMPLETE, ack.providerFlushOutcome)
		assertEquals(RegistrationRemovalOutcome.REMOVED, ack.registrationRemovalOutcome)
		assertEquals(sampleTime, sink.windows.single().windowEndElapsedRealtimeNanos)
		assertEquals(1_001f.toDouble(), sink.windows.single().meanHectopascals)
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `source erase barrier does not fabricate a provider for import-only maintenance`() = runTest {
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
			),
		)

		assertEquals(
			PressureProviderEraseSettlement.NoLocalProvider,
			fixture.runtime.establishSourceEraseBarrier(1L),
		)
		verify(exactly = 0) {
			fixture.sensorManager.registerListener(
				any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>(),
			)
		}
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `source erase barrier rejects stale lifecycle before touching the active provider`() = runTest {
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
			),
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, RecordingPressureSink()))

		assertEquals(
			PressureProviderEraseSettlement.StaleLifecycle,
			fixture.runtime.establishSourceEraseBarrier(2L),
		)

		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		fixture.runtime.close()
	}

	@Test
	fun `active capture authorization blocks source erase without creating a cutoff`() = runTest {
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
			),
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, RecordingPressureSink()))

		assertEquals(
			PressureProviderEraseSettlement.CaptureAuthorizationActive,
			fixture.runtime.establishSourceEraseBarrier(1L),
		)

		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		fixture.runtime.close()
	}

	@Test
	fun `exact retained STOPPING retirement may finish despite historical capture authorization`() = runTest {
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
			),
			unregisterFailures = listOf(IllegalStateException("removal failed"), null),
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, RecordingPressureSink()))

		fixture.runtime.close()
		assertEquals(
			PressureProviderEraseSettlement.Settled(9L),
			fixture.runtime.establishSourceEraseBarrier(1L),
		)
		assertEquals(
			PressureProviderEraseVerification.Verified,
			fixture.runtime.verifySourceEraseProviderSettled(1L, 9L),
		)

		verify(exactly = 2) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `source erase barrier reports provider removal failure without claiming settlement`() = runTest {
		val activePlan = plan(revision = 1L)
		val controlRegistration = registration(
			activePlan,
			authorizationRevision = 1L,
			requiresAcceptance = true,
			purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(controlRegistration),
			unregisterFailures = listOf(IllegalStateException("removal unavailable"), null),
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, RecordingPressureSink()))

		assertEquals(
			PressureProviderEraseSettlement.ProviderRemovalFailed,
			fixture.runtime.establishSourceEraseBarrier(1L),
		)

		assertEquals(
			PressureProviderEraseSettlement.Settled(9L),
			fixture.runtime.establishSourceEraseBarrier(1L),
		)
	}

	@Test
	fun `source erase barrier reports callback drain timeout at its observed cutoff`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(
					activePlan,
					authorizationRevision = 1L,
					requiresAcceptance = true,
					purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
				),
			),
		)
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val blockingSink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure must use atomic admission")

			override suspend fun admit(
				delivery: SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceDeliveryAdmissionHandoff {
				withContext(NonCancellable) {
					admissionEntered.complete(Unit)
					releaseAdmission.await()
				}
				return SourceDeliveryAdmissionHandoff.Durable(listOf(1L))
			}
		}
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, blockingSink))
		fixture.listener().onSensorChanged(
			pressureEvent(
				fixture.sensor,
				android.os.SystemClock.elapsedRealtimeNanos(),
				1_000f,
			),
		)

		val barrier = async { fixture.runtime.establishSourceEraseBarrier(1L) }
		admissionEntered.await()

		assertEquals(
			PressureProviderEraseSettlement.CallbackDrainTimedOut,
			barrier.await(),
		)
		releaseAdmission.complete(Unit)
		testScheduler.runCurrent()
		assertEquals(
			PressureProviderEraseSettlement.Settled(9L),
			fixture.runtime.establishSourceEraseBarrier(1L),
		)
	}

	@Test
	fun `suspended authorization refresh latches post-boundary callback and replays revoke without second listener`() =
		runTest {
			ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
			val initialPlan = plan(revision = 1L)
			val refreshedPlan = plan(revision = 2L)
			val refreshEntered = CompletableDeferred<Long>()
			val releaseRefresh = CompletableDeferred<Unit>()
			val fixture = fixture(
				scope = this,
				registrationsToReturn = listOf(
					registration(initialPlan, 1L, requiresAcceptance = true, effectiveElapsedNanos = 1L),
				),
				compatibleRefresh = { effectiveElapsedNanos ->
					refreshEntered.complete(effectiveElapsedNanos)
					releaseRefresh.await()
					registration(
						refreshedPlan,
						authorizationRevision = 2L,
						effectiveElapsedNanos = effectiveElapsedNanos,
						purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
					)
				},
			)
			val oldSink = RecordingPressureSink()
			val revokedSink = RecordingPressureSink()
			assertTrue(fixture.runtime.start(initialPlan, oldSink) is SourceStartResult.Started)

			val refresh = async { fixture.runtime.reconfigure(refreshedPlan, revokedSink) }
			val boundary = refreshEntered.await()
			fixture.listener().onSensorChanged(
				pressureEvent(
					fixture.sensor,
					boundary - 1L,
					1_000f,
					SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
				),
			)
			fixture.listener().onSensorChanged(
				pressureEvent(
					fixture.sensor,
					boundary,
					1_001f,
					SensorManager.SENSOR_STATUS_ACCURACY_LOW,
				),
			)
			releaseRefresh.complete(Unit)
			assertTrue(refresh.await() is SourceApplyResult.Applied)
			fixture.runtime.close()

			assertEquals(listOf(1L), oldSink.candidates.map { it.authorizationRevision })
			assertEquals(listOf(2L), revokedSink.candidates.map { it.authorizationRevision })
			assertTrue(oldSink.windows.single().windowEndElapsedRealtimeNanos < boundary)
			assertTrue(revokedSink.windows.single().windowStartElapsedRealtimeNanos >= boundary)
			assertEquals(PressureSensorAccuracy.HIGH, oldSink.windows.single().sensorAccuracy)
			assertEquals(PressureSensorAccuracy.LOW, revokedSink.windows.single().sensorAccuracy)
			verify(exactly = 1) {
				fixture.sensorManager.registerListener(
					any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>(),
				)
			}
		}

	@Test
	fun `dense callbacks survive suspended refresh with changed aggregation without capacity gap`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val initialPlan = plan(1L, aggregationWindowMs = 10_000L)
		val refreshedPlan = plan(2L, aggregationWindowMs = 5_000L)
		val entered = CompletableDeferred<Long>()
		val release = CompletableDeferred<Unit>()
		val fixture = fixture(this,
			registrationsToReturn = listOf(registration(initialPlan, 1L, requiresAcceptance = true,
				effectiveElapsedNanos = 1L)),
			compatibleRefresh = { boundary ->
				entered.complete(boundary)
				release.await()
				registration(refreshedPlan, 2L, effectiveElapsedNanos = boundary)
			})
		val oldSink = RecordingPressureSink()
		val newSink = RecordingPressureSink()
		assertIs<SourceStartResult.Started>(fixture.runtime.start(initialPlan, oldSink))
		val refresh = async { fixture.runtime.reconfigure(refreshedPlan, newSink) }
		val boundary = entered.await()
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, boundary - 1L, 1_000f))
		repeat(400) { index ->
			ShadowSystemClock.advanceBy(Duration.ofMillis(100))
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor,
				boundary + index * 100_000_000L, 1_001f))
		}
		release.complete(Unit)
		assertIs<SourceApplyResult.Applied>(refresh.await())
		val stopped = fixture.runtime.quiesce(pressureCutoff())
		assertEquals(0L, stopped.failedAdmissionCount)
		assertEquals(1, oldSink.windows.sumOf { it.sampleCount })
		assertEquals(400, newSink.windows.sumOf { it.sampleCount })
		assertEquals(8, newSink.windows.size)
		assertTrue(newSink.candidates.all { it.authorizationRevision == 2L })
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `failed suspended authorization refresh returns buffered callback to old authority`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val initialPlan = plan(revision = 1L)
		val refreshedPlan = plan(revision = 2L)
		val refreshEntered = CompletableDeferred<Long>()
		val releaseRefresh = CompletableDeferred<Unit>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, 1L, requiresAcceptance = true, effectiveElapsedNanos = 1L),
			),
			compatibleRefresh = { effectiveElapsedNanos ->
				refreshEntered.complete(effectiveElapsedNanos)
				releaseRefresh.await()
				throw IllegalStateException("Room unavailable")
			},
		)
		val oldSink = RecordingPressureSink()
		val unusedSink = RecordingPressureSink()
		assertTrue(fixture.runtime.start(initialPlan, oldSink) is SourceStartResult.Started)

		val refresh = async { fixture.runtime.reconfigure(refreshedPlan, unusedSink) }
		val boundary = refreshEntered.await()
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, boundary - 1L, 1_000f))
		fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, boundary, 1_001f))
		releaseRefresh.complete(Unit)
		assertTrue(refresh.await() is SourceApplyResult.Failed)
		fixture.runtime.close()

		assertEquals(listOf(1L, 1L), oldSink.candidates.map { it.authorizationRevision })
		assertTrue(oldSink.windows[0].windowEndElapsedRealtimeNanos < boundary)
		assertTrue(oldSink.windows[1].windowStartElapsedRealtimeNanos >= boundary)
		assertTrue(unusedSink.candidates.isEmpty())
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(
				any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>(),
			)
		}
	}

	@Test
	fun `Pressure window delegates Room sequence and retains exact checkpoint identity`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, authorizationRevision = 1L, requiresAcceptance = true),
			),
		)
		val sink = RecordingPressureSink()
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val observedEnd = android.os.SystemClock.elapsedRealtimeNanos()
		fixture.listener().onSensorChanged(
			pressureEvent(
				fixture.sensor,
				observedEnd - 1_000_000L,
				1_000f,
				SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
			),
		)
		fixture.listener().onSensorChanged(
			pressureEvent(
				fixture.sensor,
				observedEnd,
				1_001f,
				SensorManager.SENSOR_STATUS_ACCURACY_LOW,
			),
		)

		fixture.runtime.close()

		val delivery = sink.deliveries.single()
		val evidence = delivery.units.single().evidence
		val payload = evidence.payload as PressureWindowPayload
		assertNull(evidence.providerDedupKey)
		assertEquals(0L, evidence.sourceSequence)
		assertEquals(PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION, evidence.payloadVersion)
		assertQualifiedPressureWindow(payload)
		assertTrue(SourceQualityFlag.INCOMPLETE_WINDOW in evidence.quality.flags)
		assertPressureDeliveryIdentity(delivery, evidence.clockDomainId, payload)
		coVerify(exactly = 0) { fixture.registrations.allocateSequence(any(), any()) }
	}

	@Test
	fun `delayed Pressure callback keeps its original reception wall time through later close`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
		)
		val sink = RecordingPressureSink()
		try {
			assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
			val callbackElapsed = android.os.SystemClock.elapsedRealtimeNanos()
			val observedElapsed = callbackElapsed - 6_000_000_000L
			val beforeCallbackWall = System.currentTimeMillis()
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, observedElapsed, 1_000f))
			val afterCallbackWall = System.currentTimeMillis()
			ShadowSystemClock.advanceBy(Duration.ofSeconds(3))

			fixture.runtime.close()

			val evidence = sink.candidates.single()
			val receivedWall = requireNotNull(evidence.receivedWallTimeMs)
			assertTrue(receivedWall in beforeCallbackWall..afterCallbackWall)
			assertEquals(observedElapsed, evidence.observedElapsedRealtimeNanos)
			assertTrue(evidence.receivedElapsedRealtimeNanos >= callbackElapsed + 3_000_000_000L)
			assertEquals(receivedWall - 6_000L, evidence.wallTimeMs)
			assertEquals(evidence.wallTimeMs, evidence.acquiredAtMs)
			assertPressureDeliveryIdentity(
				sink.deliveries.single(), evidence.clockDomainId, evidence.payload as PressureWindowPayload,
			)
		} finally {
			fixture.runtime.close()
		}
	}

	@Test
	fun `target closure retains the completed windows last reception rather than the triggering callback`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val activePlan = plan(revision = 1L, aggregationWindowMs = 100L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
		)
		val sink = RecordingPressureSink()
		try {
			assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
			val callbackElapsed = android.os.SystemClock.elapsedRealtimeNanos()
			val completedEnd = callbackElapsed - 200_000_000L
			fixture.listener().onSensorChanged(
				pressureEvent(fixture.sensor, completedEnd - 50_000_000L, 1_000f),
			)
			val beforeCompletedReceptionWall = System.currentTimeMillis()
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, completedEnd, 1_001f))
			val afterCompletedReceptionWall = System.currentTimeMillis()
			ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
			fixture.listener().onSensorChanged(
				pressureEvent(fixture.sensor, completedEnd + 100_000_000L, 1_002f),
			)

			fixture.runtime.close()

			val evidence = sink.candidates.single { candidate ->
				(candidate.payload as PressureWindowPayload).closureKind == PressureWindowClosureKind.TARGET_ELAPSED
			}
			val receivedWall = requireNotNull(evidence.receivedWallTimeMs)
			assertTrue(receivedWall in beforeCompletedReceptionWall..afterCompletedReceptionWall)
			assertEquals(completedEnd, evidence.observedElapsedRealtimeNanos)
			assertTrue(evidence.receivedElapsedRealtimeNanos >= callbackElapsed + 1_000_000_000L)
			assertEquals(receivedWall - 200L, evidence.wallTimeMs)
			assertEquals(evidence.wallTimeMs, evidence.acquiredAtMs)
			assertEquals(2, (evidence.payload as PressureWindowPayload).sampleCount)
		} finally {
			fixture.runtime.close()
		}
	}

	private fun assertQualifiedPressureWindow(payload: PressureWindowPayload) {
		assertEquals(2, payload.sampleCount)
		assertEquals(1_000f, payload.firstHectopascals)
		assertEquals(1_001f, payload.lastHectopascals)
		assertEquals(1_000.0, requireNotNull(payload.slopeHectopascalsPerSecond), 0.000_000_001)
		assertEquals(1.0, requireNotNull(payload.rSquared), 0.000_000_000_001)
		assertEquals(PressureSensorAccuracy.LOW, payload.sensorAccuracy)
		assertEquals(50_000, payload.effectiveSamplePeriodMicros)
		assertEquals(0, payload.effectiveMaximumReportLatencyMicros)
		assertEquals(5_000_000_000L, payload.targetWindowDurationNanos)
		assertEquals(100, payload.expectedSampleCount)
		assertEquals(1_000_000L, payload.maximumInterSampleGapNanos)
		assertEquals(PressureWindowClosureKind.SOURCE_BOUNDARY, payload.closureKind)
	}

	private fun assertPressureDeliveryIdentity(
		delivery: SourceDeliveryCandidate,
		clockDomainId: String,
		payload: PressureWindowPayload,
	) {
		assertEquals(
			pressureProviderDeliveryIdentity(clockDomainId, payload),
			delivery.identity,
		)
		assertEquals(
			delivery.identity,
			pressureProviderDeliveryIdentity(
				clockDomainId,
				payload.copy(firstProviderSequence = 99L, lastProviderSequence = 99L),
			),
		)
		assertFalse(
			delivery.identity == pressureProviderDeliveryIdentity(
				clockDomainId,
				payload.copy(meanHectopascals = payload.meanHectopascals + 1.0),
			),
		)
		assertFalse(
			delivery.identity == pressureProviderDeliveryIdentity(
				"$clockDomainId-replacement",
				payload,
			),
		)
		assertFalse(
			delivery.identity == pressureProviderDeliveryIdentity(
				clockDomainId,
				payload.copy(
					windowEndElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos + 1L,
				),
			),
		)
	}

	@Test
	fun `target completion uses only the prior windows observed coverage`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		listOf(
			1_000_000L to true,
			50_000_000L to false,
		).forEachIndexed { index, (priorObservedSpanNanos, expectedIncomplete) ->
			val revision = index.toLong() + 1L
			val activePlan = plan(
				revision = revision,
				hardwareSamplePeriodMicros = 50_000,
				aggregationWindowMs = 100L,
			)
			val fixture = fixture(
				scope = this,
				registrationsToReturn = listOf(
					registration(activePlan, authorizationRevision = revision, requiresAcceptance = true),
				),
			)
			val sink = RecordingPressureSink()
			assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
			val firstObserved = android.os.SystemClock.elapsedRealtimeNanos() - 200_000_000L
			fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, firstObserved, 1_000f))
			fixture.listener().onSensorChanged(
				pressureEvent(fixture.sensor, firstObserved + priorObservedSpanNanos, 1_001f),
			)
			fixture.listener().onSensorChanged(
				pressureEvent(fixture.sensor, firstObserved + 100_000_000L, 1_002f),
			)

			fixture.runtime.close()

			val targetEvidence = sink.candidates.first { candidate ->
				(candidate.payload as PressureWindowPayload).closureKind ==
					PressureWindowClosureKind.TARGET_ELAPSED
			}
			val targetWindow = targetEvidence.payload as PressureWindowPayload
			assertEquals(2, targetWindow.sampleCount)
			assertEquals(2, targetWindow.expectedSampleCount)
			assertEquals(priorObservedSpanNanos, targetWindow.maximumInterSampleGapNanos)
			assertEquals(
				expectedIncomplete,
				SourceQualityFlag.INCOMPLETE_WINDOW in targetEvidence.quality.flags,
			)
		}
	}

	@Test
	fun `target completion remains incomplete when bunched samples hide a cadence gap`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val activePlan = plan(
			revision = 1L,
			hardwareSamplePeriodMicros = 50_000,
			aggregationWindowMs = 200L,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, authorizationRevision = 1L, requiresAcceptance = true),
			),
		)
		val sink = RecordingPressureSink()
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val firstObserved = android.os.SystemClock.elapsedRealtimeNanos() - 300_000_000L
		listOf(0L, 5_000_000L, 10_000_000L, 150_000_000L, 200_000_000L).forEachIndexed { index, offset ->
			fixture.listener().onSensorChanged(
				pressureEvent(fixture.sensor, firstObserved + offset, 1_000f + index),
			)
		}

		fixture.runtime.close()

		val targetEvidence = sink.candidates.first { candidate ->
			(candidate.payload as PressureWindowPayload).closureKind ==
				PressureWindowClosureKind.TARGET_ELAPSED
		}
		val targetWindow = targetEvidence.payload as PressureWindowPayload
		assertEquals(4, targetWindow.sampleCount)
		assertEquals(4, targetWindow.expectedSampleCount)
		assertEquals(140_000_000L, targetWindow.maximumInterSampleGapNanos)
		assertTrue(SourceQualityFlag.INCOMPLETE_WINDOW in targetEvidence.quality.flags)
	}

	@Test
	fun `observed time keeps delayed sample old and splits first refreshed window`() {
		val oldPlan = plan(revision = 1L)
		val refreshedPlan = plan(revision = 2L)
		val oldRegistration = registration(
			oldPlan,
			authorizationRevision = 1L,
			effectiveElapsedNanos = 100L,
		)
		val refreshedRegistration = registration(
			refreshedPlan,
			authorizationRevision = 2L,
			effectiveElapsedNanos = 300L,
		)
		val oldSink = mockk<SourceEventSink>()
		val refreshedSink = mockk<SourceEventSink>()
		val timeline = PressureObservedAuthorizationTimeline(oldRegistration, oldSink)
		timeline.refresh(refreshedRegistration, refreshedSink)

		val delayed = requireNotNull(timeline.atObservedTime(250L))
		assertSame(oldRegistration, delayed.registration)
		assertSame(oldSink, delayed.sink)
		val oldAccumulator = PressureWindowAccumulator(
			1_000L,
			1,
			0,
			oldRegistration.pressureAccumulatorBoundary(),
		)
		oldAccumulator.add(1_000f, 250L, 1L)

		val refreshed = requireNotNull(timeline.atObservedTime(300L))
		assertSame(refreshedRegistration, refreshed.registration)
		assertFalse(delayed.samePressureBoundary(refreshed))
		val oldWindow = requireNotNull(oldAccumulator.drain())
		val refreshedAccumulator = PressureWindowAccumulator(
			1_000L,
			1,
			0,
			refreshedRegistration.pressureAccumulatorBoundary(),
		)
		refreshedAccumulator.add(1_001f, 300L, 2L)
		val refreshedWindow = requireNotNull(refreshedAccumulator.drain())

		assertEquals(250L, oldWindow.windowEndElapsedRealtimeNanos)
		assertEquals(300L, refreshedWindow.windowStartElapsedRealtimeNanos)
		assertNull(timeline.atObservedTime(299L))
	}

	@Test
	fun `recovery checkpoint failure drains early queued and partial callbacks before replacement`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val activePlan = plan(revision = 1L, aggregationWindowMs = 100L)
		val fixture = fixture(this, listOf(
			registration(activePlan, 1L, requiresAcceptance = true),
			registration(activePlan, 1L, requiresAcceptance = true, generation = 10L),
		))
		coEvery { fixture.registrations.loadRuntimeState(any()) } returns interruptedPressureCheckpoint()
		var checkpointAttempts = 0
		coEvery {
			fixture.registrations.saveRuntimeState(any(), any(), any(), any(), any(), any(), any(), any())
		} coAnswers {
			if (++checkpointAttempts == 1) {
				val now = android.os.SystemClock.elapsedRealtimeNanos()
				fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, now - 200_000_000L, 1_000f))
				fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, now - 150_000_000L, 1_001f))
				fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, now - 50_000_000L, 1_002f))
				throw IllegalStateException("checkpoint unavailable")
			}
			SourceRuntimeStateSaveResult.Saved
		}
		val sink = RecordingPressureSink()

		assertIs<SourceStartResult.Failed>(fixture.runtime.start(activePlan, sink))
		assertEquals(listOf(2, 1), sink.windows.map { it.sampleCount })
		assertEquals(listOf(1L, 3L), sink.windows.map { it.firstProviderSequence })
		assertEquals(listOf(2L, 3L), sink.windows.map { it.lastProviderSequence })
		assertTrue(sink.candidates.all { it.registrationGeneration == 9L })
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(fixture.listeners.single()) }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, RecordingPressureSink()))
		fixture.runtime.close()
	}

	@Test
	fun `restarted replacement saves its own checkpoint only after provider acceptance`() = runTest {
		val activePlan = plan(revision = 1L)
		val replacement = registration(activePlan, authorizationRevision = 1L, requiresAcceptance = true)
		val fixture = fixture(this, listOf(replacement))
		val previous = interruptedPressureCheckpoint()
		coEvery { fixture.registrations.loadRuntimeState(any()) } returns previous
		var acceptedGeneration: Long? = null
		var saved: SourceRuntimeStateEntity? = null
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } coAnswers {
			acceptedGeneration = firstArg<SourceRegistration>().state.registrationGeneration
			null
		}
		coEvery {
			fixture.registrations.saveRuntimeState(any(), any(), any(), any(), any(), any(), any(), any())
		} coAnswers {
			val incoming = firstArg<SourceRegistration>()
			check(acceptedGeneration == incoming.state.registrationGeneration)
			saved = previous.copy(registrationGeneration = incoming.state.registrationGeneration,
				lastProviderSequence = arg(1), lastAdmittedSourceSequence = arg(2), lastAdmissionOrdinal = arg(3),
				stateVersion = arg(4), payload = arg(5), updatedAtMs = arg(6))
			SourceRuntimeStateSaveResult.Saved
		}
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, RecordingPressureSink()))
		val restored = requireNotNull(decodeSensorRuntimeCheckpoint(saved, PRESSURE_RUNTIME_COMPONENT_VERSION))
		assertNull(restored.metrics.lastDurablyAdmittedSequence)
		assertNull(restored.metrics.lastAdmissionOrdinal)
		assertEquals(0L, restored.metrics.failedAdmissionCount)
		assertNull(restored.metrics.unresolvedSequenceStart)
		assertNull(restored.metrics.unresolvedSequenceEndInclusive)
		assertEquals(emptySet(), restored.metrics.gapClassifications)
		fixture.runtime.close()
	}

	@Test
	fun `same registration recovery preserves its original admission endpoint and gaps`() {
		val previous = interruptedPressureCheckpoint()
		val recovered = recoverPressureRuntimeState(previous, previous.registrationGeneration, true)
		val metrics = requireNotNull(recovered.metrics)
		assertEquals(7L, metrics.lastDurablyAdmittedSequence)
		assertEquals(77L, metrics.lastAdmissionOrdinal)
		assertEquals(9L, recovered.callbackEntrySequence)
		assertEquals(2L, metrics.failedAdmissionCount)
		assertEquals(8L, metrics.unresolvedSequenceStart)
		assertEquals(9L, metrics.unresolvedSequenceEndInclusive)
	}

	@Test
	fun `cancelled recovery checkpoint drains early partial callback before propagating cancellation`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val activePlan = plan(revision = 1L)
		val fixture = fixture(this, listOf(
			registration(activePlan, 1L, requiresAcceptance = true),
			registration(activePlan, 1L, requiresAcceptance = true, generation = 10L),
		))
		coEvery { fixture.registrations.loadRuntimeState(any()) } returns interruptedPressureCheckpoint()
		var checkpointAttempts = 0
		coEvery {
			fixture.registrations.saveRuntimeState(any(), any(), any(), any(), any(), any(), any(), any())
		} coAnswers {
			if (++checkpointAttempts == 1) {
				fixture.listener().onSensorChanged(pressureEvent(fixture.sensor,
					android.os.SystemClock.elapsedRealtimeNanos(), 1_000f))
				throw CancellationException("checkpoint cancelled")
			}
			SourceRuntimeStateSaveResult.Saved
		}
		val sink = RecordingPressureSink()
		assertFailsWith<CancellationException> { fixture.runtime.start(activePlan, sink) }
		assertEquals(1, sink.windows.single().sampleCount)
		assertEquals(9L, sink.candidates.single().registrationGeneration)
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(fixture.listeners.single()) }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, RecordingPressureSink()))
		fixture.runtime.close()
	}

	@Test
	fun `failed acceptance rejects early queued callbacks without blocking replacement`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
		val activePlan = plan(revision = 1L, aggregationWindowMs = 100L)
		val fixture = fixture(this, listOf(
			registration(activePlan, 1L, requiresAcceptance = true),
			registration(activePlan, 1L, requiresAcceptance = true, generation = 10L),
		))
		var acceptanceAttempts = 0
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } coAnswers {
			if (++acceptanceAttempts == 1) {
				val now = android.os.SystemClock.elapsedRealtimeNanos()
				fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, now - 200_000_000L, 1_000f))
				fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, now - 150_000_000L, 1_001f))
				fixture.listener().onSensorChanged(pressureEvent(fixture.sensor, now - 50_000_000L, 1_002f))
				throw IllegalStateException("registration no longer current")
			}
			null
		}
		val sink = RecordingPressureSink()
		assertIs<SourceStartResult.Failed>(fixture.runtime.start(activePlan, sink))
		assertTrue(sink.deliveries.isEmpty())
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(fixture.listeners.single()) }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, sink))
		fixture.runtime.close()
		assertTrue(sink.deliveries.isEmpty())
	}

	@Test
	fun `initial registration exception performs defensive listener cleanup`() = runTest {
		val activePlan = plan(revision = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, authorizationRevision = 1L, requiresAcceptance = true),
			),
			registerFailure = IllegalStateException("sensor service unavailable"),
		)

		val result = fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(result is SourceStartResult.Failed)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `acceptance failure retains exact listener and blocks replacement until removal retry completes`() = runTest {
		val activePlan = plan(revision = 1L)
		val activeRegistration = registration(
			activePlan,
			authorizationRevision = 1L,
			requiresAcceptance = true,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				activeRegistration,
				registration(activePlan, authorizationRevision = 2L, requiresAcceptance = true, generation = 10L),
			),
			markAcceptanceFailure = IllegalStateException("acceptance stale"),
			unregisterFailures = listOf(IllegalStateException("SensorManager removal failed"), null),
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Failed)
		val retainedListener = fixture.listeners.single()
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)

		verify(exactly = 2) {
			fixture.sensorManager.registerListener(
				any<SensorEventListener>(), fixture.sensor, any<Int>(), any<Int>(),
			)
		}
		verify(exactly = 2) { fixture.sensorManager.unregisterListener(retainedListener) }
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `terminal retirement is durable before exact listener removal and token completion`() = runTest {
		val events = mutableListOf<String>()
		val activePlan = plan(1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			operationEvents = events,
		)
		assertTrue(fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) } is SourceStartResult.Started)
		events.clear()

		fixture.runtime.close()

		assertEquals(listOf("begin-retirement", "unregister", "complete-retirement"), events)
	}

	@Test
	fun `stop acknowledgement reports provider failed while exact removal remains incomplete`() = runTest {
		val activePlan = plan(1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			unregisterFailures = listOf(IllegalStateException("removal unavailable")),
		)
		assertTrue(fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) } is SourceStartResult.Started)
		val now = android.os.SystemClock.elapsedRealtimeNanos()

		val ack = fixture.runtime.quiesce(
			SessionCutoff("pressure-test", Long.MAX_VALUE, 1L, now + 1_000_000_000L),
		)

		assertEquals(RegistrationRemovalOutcome.FAILED, ack.registrationRemovalOutcome)
		assertEquals(SourceStopStatus.PROVIDER_FAILED, ack.status)
		coVerify(exactly = 0) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `ambiguous completion retries the same immutable token without removing listener twice`() = runTest {
		val activePlan = plan(1L)
		val replacementPlan = activePlan.copy(revision = 2L)
		var completions = 0
		val completionTokens = mutableListOf<SourceRegistrationRetirementToken>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
				registration(replacementPlan, 2L, requiresAcceptance = true, generation = 10L),
			),
			completeRetirement = { token ->
				completionTokens += token
				completions++
				if (completions == 1) throw IllegalStateException("commit acknowledgement lost")
				true
			},
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val exactListener = fixture.listeners.single()
		fixture.runtime.close()

		assertTrue(fixture.runtime.start(replacementPlan, sink) is SourceStartResult.Started)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 2) { fixture.registrations.completeRetirement(any()) }
		assertSame(completionTokens.first(), completionTokens.last())
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
		fixture.runtime.close()
	}

	@Test
	fun `retirement persistence failure fences refresh and replacement and preserves boundary`() = runTest {
		val activePlan = plan(1L)
		val attemptedBoundaries = mutableListOf<Long>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			beginRetirement = { _, _, _, boundary ->
				attemptedBoundaries += boundary
				throw IllegalStateException("retirement store unavailable")
			},
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		fixture.runtime.close()

		assertTrue(fixture.runtime.reconfigure(activePlan.copy(revision = 2L), sink) is SourceApplyResult.Failed)

		assertEquals(2, attemptedBoundaries.size)
		assertEquals(attemptedBoundaries.first(), attemptedBoundaries.last())
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		coVerify(exactly = 0) {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		}
	}

	@Test
	fun `slow retirement begin waits for Room before removing the exact listener`() = runTest {
		val activePlan = plan(1L)
		val beginEntered = CompletableDeferred<Unit>()
		val releaseBegin = CompletableDeferred<Unit>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			beginRetirement = { registration, reason, retiredAtMs, boundary ->
				beginEntered.complete(Unit)
				releaseBegin.await()
				retirementToken(registration, reason, retiredAtMs, boundary)
			},
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) })
		val exactListener = fixture.listener()
		val cutoff = SessionCutoff("pressure-slow-begin", Long.MAX_VALUE, 1L,
			android.os.SystemClock.elapsedRealtimeNanos() + 10_000_000_000L)
		val stopping = async { fixture.runtime.quiesce(cutoff) }
		beginEntered.await()
		try {
			testScheduler.advanceTimeBy(2_000L)
			testScheduler.runCurrent()
			assertFalse(stopping.isCompleted)
			verify(exactly = 0) { fixture.sensorManager.unregisterListener(exactListener) }
		} finally {
			releaseBegin.complete(Unit)
		}
		assertEquals(SourceStopStatus.COMPLETE, stopping.await().status)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
	}

	@Test
	fun `slow retirement completion keeps its exact token and does not remove the listener twice`() = runTest {
		val activePlan = plan(1L)
		val completionEntered = CompletableDeferred<Unit>()
		val releaseCompletion = CompletableDeferred<Unit>()
		val completionTokens = mutableListOf<SourceRegistrationRetirementToken>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			completeRetirement = { token ->
				completionTokens += token
				completionEntered.complete(Unit)
				releaseCompletion.await()
				true
			},
		)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) })
		val exactListener = fixture.listener()
		val cutoff = SessionCutoff("pressure-slow-completion", Long.MAX_VALUE, 1L,
			android.os.SystemClock.elapsedRealtimeNanos() + 10_000_000_000L)
		val stopping = async { fixture.runtime.quiesce(cutoff) }
		completionEntered.await()
		try {
			testScheduler.advanceTimeBy(2_000L)
			testScheduler.runCurrent()
			assertFalse(stopping.isCompleted)
			verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
		} finally {
			releaseCompletion.complete(Unit)
		}
		assertEquals(SourceStopStatus.COMPLETE, stopping.await().status)
		assertEquals(1, completionTokens.size)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(completionTokens.single()) }
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
	}

	@Test
	fun `timed out Pressure drain resumes pending atomic window on the next Stop`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val fixture = fixture(this, listOf(registration(activePlan, 1L, requiresAcceptance = true)))
		var storageAvailable = false
		val deliveries = mutableListOf<SourceDeliveryCandidate>()
		val checkpoints = mutableListOf<SensorAdmissionCheckpoint>()
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure requires atomic admission")
			override suspend fun admit(delivery: SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint): SourceDeliveryAdmissionHandoff {
				deliveries += delivery
				checkpoints += checkpoint
				return if (storageAvailable) {
					SourceDeliveryAdmissionHandoff.Durable(listOf(91L))
				} else {
					SourceDeliveryAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
				}
			}
		}
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, sink))
		val listener = fixture.listener()
		val now = android.os.SystemClock.elapsedRealtimeNanos()
		listener.onSensorChanged(pressureEvent(fixture.sensor, now, 1_000f))
		val cutoff = SessionCutoff("pressure-retry", Long.MAX_VALUE, 1L, now + 1_000_000L)
		val first = fixture.runtime.quiesce(cutoff)
		assertEquals(SourceStopStatus.TIMED_OUT, first.status)
		assertFalse(first.appDrainComplete)
		assertEquals(0L, first.failedAdmissionCount)
		coVerify(exactly = 0) {
			fixture.registrations.saveRuntimeState(any(), any(), any(), any(), any(), any(), any(), any())
		}

		storageAvailable = true
		ShadowSystemClock.advanceBy(Duration.ofMillis(10))
		val retried = fixture.runtime.quiesce(cutoff.copy(deadlineElapsedRealtimeNanos =
			android.os.SystemClock.elapsedRealtimeNanos() + 1_000_000_000L))
		assertEquals(SourceStopStatus.COMPLETE, retried.status)
		assertTrue(retried.appDrainComplete)
		assertEquals(91L, retried.lastAdmissionOrdinal)
		assertEquals(0L, retried.failedAdmissionCount)
		assertTrue(deliveries.size >= 2)
		deliveries.forEach { assertSame(deliveries.first(), it) }
		checkpoints.forEach { assertSame(checkpoints.first(), it) }
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(listener) }
		fixture.runtime.close()
	}

	@Test
	fun `non cooperative sink times out and fences replacement until retained actor settles`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L)
		val replacementPlan = activePlan.copy(revision = 2L)
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(activePlan, 1L, requiresAcceptance = true),
				registration(replacementPlan, 2L, requiresAcceptance = true, generation = 10L),
			),
		)
		val blockingSink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure must use atomic admission")

			override suspend fun admit(
				delivery: SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceDeliveryAdmissionHandoff {
				withContext(NonCancellable) {
					admissionEntered.complete(Unit)
					releaseAdmission.await()
				}
				return SourceDeliveryAdmissionHandoff.Durable(listOf(1L))
			}
		}
		val replacementSink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		assertTrue(fixture.runtime.start(activePlan, blockingSink) is SourceStartResult.Started)
		fixture.listener().onSensorChanged(
			pressureEvent(fixture.sensor, android.os.SystemClock.elapsedRealtimeNanos(), 1_000f),
		)
		val now = android.os.SystemClock.elapsedRealtimeNanos()
		val stopping = async {
			fixture.runtime.quiesce(
				SessionCutoff("pressure-blocked-sink", Long.MAX_VALUE, 1L, now + 10_000_000L),
			)
		}
		admissionEntered.await()

		val ack = stopping.await()

		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertFalse(ack.appDrainComplete)
		assertTrue(fixture.runtime.start(replacementPlan, replacementSink) is SourceStartResult.Failed)
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}

		releaseAdmission.complete(Unit)
		testScheduler.runCurrent()
		assertTrue(fixture.runtime.start(replacementPlan, replacementSink) is SourceStartResult.Started)
		fixture.runtime.close()
	}

	@Test
	fun `fatal and cancellation registration failures clean up durably then rethrow`() = runTest {
		val activePlan = plan(1L)
		for (failure in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
			val fixture = fixture(
				scope = this,
				registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
				registerFailure = failure,
			)
			if (failure is CancellationException) {
				assertFailsWith<CancellationException> {
					fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) }
				}
			} else {
				assertFailsWith<AssertionError> {
					fixture.runtime.start(activePlan) { SourceAdmissionHandoff.Durable(1L) }
				}
			}
			coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
			verify(exactly = 1) { fixture.sensorManager.unregisterListener(fixture.listeners.single()) }
			coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		}
	}

	@Test
	fun `fatal and actor local cancellation durably contain the failed actor and replay terminal ack`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		for (failure in listOf(AssertionError("fatal actor"), CancellationException("actor-local"))) {
			val sourceJob = SupervisorJob()
			val uncaught = mutableListOf<Throwable>()
			val sourceScope = CoroutineScope(
				sourceJob + StandardTestDispatcher(testScheduler) +
					CoroutineExceptionHandler { _, thrown -> uncaught += thrown },
			)
			val activePlan = plan(1L, aggregationWindowMs = 1L)
			val activeRegistration = registration(activePlan, 1L, requiresAcceptance = true)
			val fixture = fixture(
				scope = sourceScope,
				registrationsToReturn = listOf(activeRegistration),
			)
			val sink = object : SourceEventSink {
				override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
					error("Pressure must use atomic admission")

				override suspend fun admit(
					delivery: SourceDeliveryCandidate,
					checkpoint: SensorAdmissionCheckpoint,
				): SourceDeliveryAdmissionHandoff = throw failure
			}
			assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
			val exactListener = fixture.listener()
			val now = android.os.SystemClock.elapsedRealtimeNanos()
			exactListener.onSensorChanged(pressureEvent(fixture.sensor, now - 2_000_000L, 1_000f))
			exactListener.onSensorChanged(pressureEvent(fixture.sensor, now, 1_001f))

			testScheduler.runCurrent()

			coVerify(exactly = 1) {
				fixture.registrations.beginRetirement(activeRegistration, any(), any(), any())
			}
			verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
			coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
			val cutoff = SessionCutoff("pressure-actor-failure", Long.MAX_VALUE, 1L, now + 1_000_000_000L)
			val firstAck = fixture.runtime.quiesce(cutoff)
			ShadowSystemClock.advanceBy(Duration.ofMillis(1))
			exactListener.onSensorChanged(
				pressureEvent(fixture.sensor, android.os.SystemClock.elapsedRealtimeNanos(), 1_002f),
			)
			val replayedAck = fixture.runtime.quiesce(cutoff)
			assertEquals(firstAck, replayedAck)
			assertEquals(2L, firstAck.callbackEntryBarrierSequence)
			assertEquals(2L, firstAck.failedAdmissionCount)
			assertFalse(firstAck.appDrainComplete)
			assertEquals(SourceStopStatus.TIMED_OUT, firstAck.status)
			if (failure is AssertionError) {
				assertEquals(1, uncaught.size)
				assertSame(failure, uncaught.single())
			} else assertTrue(uncaught.isEmpty())
			sourceScope.cancel()
		}
	}

	@Test
	fun `exact failure coverage neither double counts overlap nor omits sparse holes`() {
		val overlapping = PressureFailureRangeCoverage()
		assertEquals(listOf(4L..4L), overlapping.recordAndReturnUnaccounted(4L..4L))
		assertEquals(listOf(2L..3L, 5L..5L), overlapping.recordAndReturnUnaccounted(2L..5L))
		assertTrue(overlapping.recordAndReturnUnaccounted(2L..5L).isEmpty())

		val sparse = PressureFailureRangeCoverage()
		assertEquals(listOf(2L..2L), sparse.recordAndReturnUnaccounted(2L..2L))
		assertEquals(listOf(5L..5L), sparse.recordAndReturnUnaccounted(5L..5L))
		assertEquals(listOf(3L..4L), sparse.recordAndReturnUnaccounted(2L..5L))
		assertTrue(sparse.recordAndReturnUnaccounted(2L..5L).isEmpty())
	}

	@Test
	fun `refresh boundary at acquisition limit accounts for the already entered callback`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 1L)
		val nextPlan = plan(2L, aggregationWindowMs = 1L)
		val entered = CompletableDeferred<Long>()
		val releaseRefresh = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val recorded = RecordingPressureSink()
		val sink = object : SourceEventSink by recorded {
			override suspend fun admit(delivery: SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint): SourceDeliveryAdmissionHandoff {
				releaseAdmission.await()
				return recorded.admit(delivery, checkpoint)
			}
		}
		val fixture = fixture(this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			compatibleRefresh = { boundary ->
				entered.complete(boundary)
				releaseRefresh.await()
				registration(nextPlan, 2L, effectiveElapsedNanos = boundary)
			})
		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, sink))
		val listener = fixture.listener()
		repeat(63) { index ->
			listener.onSensorChanged(pressureEvent(fixture.sensor, (index + 1L) * 1_000_001L, 1_000f))
		}
		val refresh = async { fixture.runtime.reconfigure(nextPlan, sink) }
		val boundary = entered.await()
		listener.onSensorChanged(pressureEvent(fixture.sensor, boundary, 1_001f))
		releaseRefresh.complete(Unit)
		refresh.await()
		val stop = async { fixture.runtime.quiesce(pressureCutoff()) }
		testScheduler.runCurrent()
		releaseAdmission.complete(Unit)
		val ack = stop.await()
		assertEquals(63, recorded.windows.size)
		assertEquals((1L..63L).toList(), recorded.windows.map { it.firstProviderSequence })
		assertEquals(65L, ack.callbackEntryBarrierSequence)
		assertEquals(2L, ack.failedAdmissionCount)
		assertEquals(64L, ack.unresolvedSequenceStart)
		assertEquals(65L, ack.unresolvedSequenceEndInclusive)
		assertEquals(ack.callbackEntryBarrierSequence,
			recorded.windows.sumOf { it.sampleCount }.toLong() + ack.failedAdmissionCount)
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(listener) }
	}

	@Test
	fun `capacity backpressure pause is nonterminal and never retires repository registration`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 1L)
		val releaseAdmission = CompletableDeferred<Unit>()
		val admitted = mutableListOf<SourceDeliveryCandidate>()
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
		)
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure must use atomic admission")

			override suspend fun admit(
				delivery: SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceDeliveryAdmissionHandoff {
				releaseAdmission.await()
				admitted += delivery
				return SourceDeliveryAdmissionHandoff.Durable(listOf(admitted.size.toLong()))
			}
		}
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val exactListener = fixture.listener()
		repeat(64) { index ->
			exactListener.onSensorChanged(
				pressureEvent(fixture.sensor, (index + 1L) * 1_000_001L, 1_000f),
			)
		}

		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactListener) }
		coVerify(exactly = 0) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.registrations.completeRetirement(any()) }

		releaseAdmission.complete(Unit)
		testScheduler.runCurrent()
		assertEquals(2, fixture.listeners.size)
		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, 50_000, 0)
		}
		val resumedListener = fixture.listener()
		exactListener.onSensorChanged(
			pressureEvent(fixture.sensor, 65L * 1_000_001L, 1_001f),
		)
		resumedListener.onSensorChanged(
			pressureEvent(fixture.sensor, 66L * 1_000_001L, 1_002f),
		)
		fixture.runtime.close()

		assertEquals(65, admitted.size)
	}

	@Test
	fun `failed resume cleanup retains exact provisional listener and blocks another candidate`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 1L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(activePlan, 1L, requiresAcceptance = true)),
			registerResults = listOf(true, false),
			unregisterFailures = listOf(null, IllegalStateException("candidate removal uncertain"), null),
		)
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure must use atomic admission")

			override suspend fun admit(
				delivery: SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceDeliveryAdmissionHandoff = SourceDeliveryAdmissionHandoff.Durable(listOf(1L))
		}
		assertTrue(
			fixture.runtime.start(activePlan, sink) is SourceStartResult.Started,
		)
		val initialListener = fixture.listener()
		repeat(64) { index ->
			initialListener.onSensorChanged(
				pressureEvent(fixture.sensor, (index + 1L) * 1_000_001L, 1_000f),
			)
		}
		testScheduler.runCurrent()

		assertEquals(2, fixture.listeners.size)
		val retainedCandidate = fixture.listeners.last()
		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(retainedCandidate) }
		coVerify(exactly = 0) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }

		fixture.runtime.close()

		verify(exactly = 2) { fixture.sensorManager.unregisterListener(retainedCandidate) }
	}

	@Test
	fun `stop during successful failed resume cleanup retires exact owner and permits replacement`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		val activePlan = plan(1L, aggregationWindowMs = 1L)
		val replacementPlan = activePlan.copy(revision = 2L)
		val activeRegistration = registration(activePlan, 1L, requiresAcceptance = true)
		val replacementRegistration = registration(
			replacementPlan,
			2L,
			requiresAcceptance = true,
			generation = 10L,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(activeRegistration, replacementRegistration),
			registerResults = listOf(true, false),
			unregisterFailures = listOf(null, null),
		)
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Pressure must use atomic admission")

			override suspend fun admit(
				delivery: SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceDeliveryAdmissionHandoff = SourceDeliveryAdmissionHandoff.Durable(listOf(1L))
		}
		assertTrue(fixture.runtime.start(activePlan, sink) is SourceStartResult.Started)
		val initialListener = fixture.listener()
		repeat(64) { index ->
			initialListener.onSensorChanged(
				pressureEvent(fixture.sensor, (index + 1L) * 1_000_001L, 1_000f),
			)
		}
		testScheduler.runCurrent()

		assertEquals(2, fixture.listeners.size)
		val exactRemovedCandidate = fixture.listeners.last()
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactRemovedCandidate) }
		val now = android.os.SystemClock.elapsedRealtimeNanos()
		val ack = fixture.runtime.quiesce(
			SessionCutoff("pressure-failed-resume-stop", Long.MAX_VALUE, 1L, now + 1_000_000_000L),
		)

		assertEquals(activeRegistration.state.sourceInstanceId, ack.sourceInstanceId.value)
		assertEquals(activeRegistration.state.registrationGeneration, ack.registrationGeneration)
		assertEquals(65L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(65L, ack.unresolvedSequenceStart)
		assertEquals(65L, ack.unresolvedSequenceEndInclusive)
		assertTrue(ack.appDrainComplete)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(RegistrationRemovalOutcome.REMOVED, ack.registrationRemovalOutcome)
		coVerify(exactly = 1) {
			fixture.registrations.beginRetirement(activeRegistration, any(), any(), any())
		}
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(exactRemovedCandidate) }

		assertTrue(fixture.runtime.start(replacementPlan, sink) is SourceStartResult.Started)
		verify(exactly = 3) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		fixture.runtime.close()
	}

	@Test
	fun `minimum delay clamps start and reports an unsatisfied demand floor`() = runTest {
		val activePlan = plan(
			revision = 1L,
			hardwareSamplePeriodMicros = 50_000,
			maximumReportLatencyMicros = 0,
		)
		val providerRequest = activePlan.toPressureProviderRequest(
			sensorMinimumDelayMicros = 200_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 0,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(
					activePlan,
					authorizationRevision = 1L,
					requiresAcceptance = true,
					providerFingerprint = providerRequest.physicalConfigurationFingerprint,
				),
			),
			sensorMinimumDelayMicros = 200_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 0,
		)
		val sink = RecordingPressureSink()

		val result = assertIs<SourceStartResult.Degraded>(fixture.runtime.start(activePlan, sink))
		assertEquals(SourceApplyStatus.DEGRADED, result.applied.status)
		assertEquals(
			setOf(SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED),
			result.applied.degradedReasons,
		)
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, 200_000, 0)
		}
		coVerify(exactly = 1) {
			fixture.registrations.begin(
				SourceKind.PRESSURE,
				activePlan.revision,
				providerRequest.physicalConfigurationFingerprint,
				any(),
				any(),
			)
		}
		fixture.listener().onSensorChanged(
			pressureEvent(fixture.sensor, android.os.SystemClock.elapsedRealtimeNanos(), 1_000f),
		)

		fixture.runtime.close()
		val payload = sink.windows.single()
		assertEquals(200_000, payload.effectiveSamplePeriodMicros)
		assertEquals(0, payload.effectiveMaximumReportLatencyMicros)
		assertEquals(25, payload.expectedSampleCount)
	}

	@Test
	fun `requested batching without a FIFO is explicitly degraded and does not flush`() = runTest {
		val activePlan = plan(
			revision = 1L,
			hardwareSamplePeriodMicros = 200_000,
			maximumReportLatencyMicros = 60_000_000,
		)
		val providerRequest = activePlan.toPressureProviderRequest(
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 0,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(
					activePlan,
					authorizationRevision = 1L,
					requiresAcceptance = true,
					providerFingerprint = providerRequest.physicalConfigurationFingerprint,
				),
			),
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 0,
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		val result = assertIs<SourceStartResult.Degraded>(fixture.runtime.start(activePlan, sink))
		assertEquals(SourceApplyStatus.DEGRADED, result.applied.status)
		assertEquals(
			setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
			result.applied.degradedReasons,
		)
		val exactListener = fixture.listener()
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(exactListener, fixture.sensor, 200_000, 0)
		}

		val acknowledgement = fixture.runtime.quiesce(pressureCutoff())
		assertEquals(ProviderFlushOutcome.NOT_SUPPORTED, acknowledgement.providerFlushOutcome)
		assertEquals(ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER, acknowledgement.providerCoverage)
		verify(exactly = 0) { fixture.sensorManager.flush(any<SensorEventListener>()) }
	}

	@Test
	fun `FIFO preserves requested latency and attempts an exact flush`() = runTest {
		val activePlan = plan(
			revision = 1L,
			hardwareSamplePeriodMicros = 200_000,
			maximumReportLatencyMicros = 10_000_000,
		)
		val providerRequest = activePlan.toPressureProviderRequest(
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 60_000_000,
			fifoMaxEventCount = 3,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(
					activePlan,
					authorizationRevision = 1L,
					requiresAcceptance = true,
					providerFingerprint = providerRequest.physicalConfigurationFingerprint,
				),
			),
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 60_000_000,
			fifoMaxEventCount = 3,
			flushResult = false,
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertIs<SourceStartResult.Started>(fixture.runtime.start(activePlan, sink))
		val exactListener = fixture.listener()
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(exactListener, fixture.sensor, 200_000, 10_000_000)
		}

		val acknowledgement = fixture.runtime.quiesce(pressureCutoff())
		assertEquals(ProviderFlushOutcome.FAILED, acknowledgement.providerFlushOutcome)
		assertEquals(ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE, acknowledgement.providerCoverage)
		verify(exactly = 1) { fixture.sensorManager.flush(exactListener) }
	}

	@Test
	fun `maximum-delay equivalent plan refresh is degraded without replacing the listener`() = runTest {
		val initialPlan = plan(revision = 1L, hardwareSamplePeriodMicros = 1_000_000)
		val refreshedPlan = plan(revision = 2L, hardwareSamplePeriodMicros = 60_000_000)
		val providerRequest = initialPlan.toPressureProviderRequest(
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 1_000_000,
			fifoMaxEventCount = 0,
		)
		assertEquals(
			providerRequest.physicalConfigurationFingerprint,
			refreshedPlan.toPressureProviderRequest(
				sensorMinimumDelayMicros = 50_000,
				sensorMaximumDelayMicros = 1_000_000,
				fifoMaxEventCount = 0,
			).physicalConfigurationFingerprint,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, 1L, requiresAcceptance = true,
					providerFingerprint = providerRequest.physicalConfigurationFingerprint),
				registration(refreshedPlan, 2L,
					providerFingerprint = providerRequest.physicalConfigurationFingerprint),
			),
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 1_000_000,
			fifoMaxEventCount = 0,
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertIs<SourceStartResult.Started>(fixture.runtime.start(initialPlan, sink))
		val refreshed = assertIs<SourceApplyResult.Degraded>(fixture.runtime.reconfigure(refreshedPlan, sink))
		assertEquals(SourceApplyStatus.DEGRADED, refreshed.state.status)
		assertEquals(
			setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
			refreshed.state.degradedReasons,
		)

		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, 1_000_000, 0)
		}
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		coVerify(exactly = 1) {
			fixture.registrations.refreshActiveAuthorization(
				SourceKind.PRESSURE,
				any(),
				refreshedPlan.revision,
				providerRequest.physicalConfigurationFingerprint,
				any(),
				any(),
			)
		}

		fixture.runtime.close()
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `maximum-delay replacement reports degraded while collecting at the supported cadence`() = runTest {
		val initialPlan = plan(revision = 1L, hardwareSamplePeriodMicros = 50_000)
		val replacementPlan = plan(revision = 2L, hardwareSamplePeriodMicros = 2_000_000)
		val replacementRequest = replacementPlan.toPressureProviderRequest(
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 1_000_000,
			fifoMaxEventCount = 0,
		)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true),
				registration(
					replacementPlan,
					authorizationRevision = 2L,
					requiresAcceptance = true,
					providerFingerprint = replacementRequest.physicalConfigurationFingerprint,
				),
			),
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 1_000_000,
			fifoMaxEventCount = 0,
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertIs<SourceStartResult.Started>(fixture.runtime.start(initialPlan, sink))
		val replacement = assertIs<SourceApplyResult.Degraded>(
			fixture.runtime.reconfigure(replacementPlan, sink),
		)
		assertEquals(SourceApplyStatus.DEGRADED, replacement.state.status)
		assertEquals(
			setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
			replacement.state.degradedReasons,
		)
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, 50_000, 0)
		}
		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, 1_000_000, 0)
		}
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }

		fixture.runtime.close()
	}

	@Test
	fun `compatible authorization refresh keeps one SensorManager registration`() = runTest {
		val initialPlan = plan(revision = 1L, aggregationWindowMs = 5_000L)
		val refreshedPlan = plan(revision = 2L, aggregationWindowMs = 1_000L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true),
				registration(refreshedPlan, authorizationRevision = 2L),
			),
		)
		val initialSink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		val refreshedSink = SourceEventSink { SourceAdmissionHandoff.Durable(2L) }

		assertTrue(fixture.runtime.start(initialPlan, initialSink) is SourceStartResult.Started)
		assertTrue(fixture.runtime.reconfigure(refreshedPlan, refreshedSink) is SourceApplyResult.Applied)

		verify(exactly = 1) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, 50_000, 0)
		}
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		coVerify(exactly = 1) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 1) {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		}

		fixture.runtime.close()
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `compatible successor claim fences stale shutdown and exact owner releases`() = runTest {
		val initialPlan = plan(revision = 1L, aggregationWindowMs = 5_000L)
		val refreshedPlan = plan(revision = 2L, aggregationWindowMs = 1_000L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true),
				registration(refreshedPlan, authorizationRevision = 2L),
			),
		)
		val predecessor = runtimeClaim("pressure-predecessor")
		val successor = runtimeClaim("pressure-successor")
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertIs<SourceStartResult.Started>(fixture.runtime.start(predecessor, initialPlan, sink))
		assertIs<SourceApplyResult.Applied>(fixture.runtime.reconfigure(successor, refreshedPlan, sink))
		assertEquals(
			OwnedSourceShutdown.NotOwned,
			fixture.runtime.shutdownIfOwned(predecessor, pressureCutoff()),
		)
		verify(exactly = 0) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }

		val released = assertIs<OwnedSourceShutdown.Released>(
			fixture.runtime.shutdownIfOwned(successor, pressureCutoff()),
		)
		assertEquals(9L, released.provider?.registrationGeneration)
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `physical provider change performs normal stop and replacement start`() = runTest {
		val initialPlan = plan(revision = 1L, hardwareSamplePeriodMicros = 50_000)
		val replacementPlan = plan(revision = 2L, hardwareSamplePeriodMicros = 200_000)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true),
				registration(
					replacementPlan,
					authorizationRevision = 2L,
					requiresAcceptance = true,
					generation = 10L,
				),
			),
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(initialPlan, sink) is SourceStartResult.Started)
		assertTrue(fixture.runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Applied)

		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any(), 0)
		}
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }

		fixture.runtime.close()
		verify(exactly = 2) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
	}

	@Test
	fun `physical replacement returns the predecessor stop acknowledgement`() = runTest {
		val initialPlan = plan(revision = 1L, hardwareSamplePeriodMicros = 50_000)
		val replacementPlan = plan(revision = 2L, hardwareSamplePeriodMicros = 200_000)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true),
				registration(
					replacementPlan,
					authorizationRevision = 2L,
					requiresAcceptance = true,
					generation = 10L,
				),
			),
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		val predecessor = runtimeClaim("pressure-predecessor")
		val successor = runtimeClaim("pressure-successor")

		assertIs<SourceStartResult.Started>(fixture.runtime.start(predecessor, initialPlan, sink))
		val replaced = assertIs<SourceApplyResult.Applied>(
			fixture.runtime.reconfigure(successor, replacementPlan, sink),
		)

		assertEquals(9L, replaced.stopAck?.registrationGeneration)
		assertEquals(SourceStopStatus.COMPLETE, replaced.stopAck?.status)
		val released = assertIs<OwnedSourceShutdown.Released>(
			fixture.runtime.shutdownIfOwned(successor, pressureCutoff()),
		)
		assertEquals(10L, released.provider?.registrationGeneration)
	}

	@Test
	fun `incompatible authorization check reserves only one pressure replacement`() = runTest {
		val initialPlan = plan(revision = 1L, aggregationWindowMs = 5_000L)
		val replacementPlan = plan(revision = 2L, aggregationWindowMs = 1_000L)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(
				registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true),
				registration(
					replacementPlan,
					authorizationRevision = 2L,
					requiresAcceptance = true,
					generation = 10L,
				),
			),
			compatibleRefresh = { null },
		)
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(initialPlan, sink) is SourceStartResult.Started)
		assertTrue(fixture.runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Applied)

		coVerify(exactly = 1) {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		}
		// Initial acquisition plus the orderly post-stop replacement; no speculative reservation.
		coVerify(exactly = 2) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		verify(exactly = 2) {
			fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>())
		}
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		fixture.runtime.close()
	}

	@Test
	fun `failed successor reservation keeps exact predecessor acknowledgement for End`() = runTest {
		val initialPlan = plan(revision = 1L, hardwareSamplePeriodMicros = 50_000)
		val replacementPlan = plan(revision = 2L, hardwareSamplePeriodMicros = 200_000)
		val fixture = fixture(
			scope = this,
			registrationsToReturn = listOf(registration(initialPlan, authorizationRevision = 1L, requiresAcceptance = true)),
		)
		val predecessor = runtimeClaim("pressure-predecessor")
		val successor = runtimeClaim("pressure-successor")
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		assertIs<SourceStartResult.Started>(fixture.runtime.start(predecessor, initialPlan, sink))
		coEvery { fixture.registrations.begin(any(), any(), any(), any(), any()) } throws
			IllegalStateException("reservation unavailable")

		val failed = assertIs<SourceApplyResult.Failed>(
			fixture.runtime.reconfigure(successor, replacementPlan, sink),
		)
		val retired = requireNotNull(failed.stopAck)
		assertEquals(SourceStopStatus.COMPLETE, retired.status)
		assertEquals(9L, retired.registrationGeneration)
		assertEquals(predecessor.logicalTrackingId, retired.logicalTrackingId)
		assertEquals(retired, fixture.runtime.quiesce(pressureCutoff()))
		assertEquals(retired, fixture.runtime.quiesce(pressureCutoff()))
		verify(exactly = 1) { fixture.sensorManager.unregisterListener(any<SensorEventListener>()) }
		verify(exactly = 1) { fixture.sensorManager.registerListener(any(), fixture.sensor, any<Int>(), any<Int>()) }
	}

	private data class Fixture(
		val runtime: PressureSourceRuntime,
		val registrations: SourceRegistrationRepository,
		val sensorManager: SensorManager,
		val sensor: Sensor,
		val listener: () -> SensorEventListener,
		val listeners: List<SensorEventListener>,
	)

	private fun fixture(
		scope: kotlinx.coroutines.CoroutineScope,
		registrationsToReturn: List<SourceRegistration>,
		registerFailure: Throwable? = null,
		registerResults: List<Boolean> = emptyList(),
		failRuntimeCheckpoint: Boolean = false,
		markAcceptanceFailure: Throwable? = null,
		unregisterFailures: List<Throwable?> = emptyList(),
		compatibleRefresh: (suspend (Long) -> SourceRegistration?)? = null,
		beginRetirement: suspend (SourceRegistration, String, Long, Long) -> SourceRegistrationRetirementToken =
			{ registration, reason, retiredAtMs, retiredElapsedNanos ->
				retirementToken(registration, reason, retiredAtMs, retiredElapsedNanos)
			},
		completeRetirement: suspend (SourceRegistrationRetirementToken) -> Boolean = { true },
		operationEvents: MutableList<String>? = null,
		sensorMinimumDelayMicros: Int = 50_000,
		sensorMaximumDelayMicros: Int = 60_000_000,
		fifoMaxEventCount: Int = 0,
		flushResult: Boolean = false,
	): Fixture {
		val context = mockk<Context>()
		val sensorManager = mockk<SensorManager>()
		val sensor = mockk<Sensor>()
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		coEvery { registrations.endedCaptureCutoffElapsedNanos(any(), any(), any()) } returns null
		coEvery { registrations.savePressureRetirementCompleteness(any(), any(), any()) } returns true
		val listenerSlot = slot<SensorEventListener>()
		val listeners = mutableListOf<SensorEventListener>()
		var registerIndex = 0
		every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
		every { sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) } returns sensor
		every { sensor.type } returns Sensor.TYPE_PRESSURE
		every { sensor.fifoMaxEventCount } returns fifoMaxEventCount
		every { sensor.minDelay } returns sensorMinimumDelayMicros
		every { sensor.maxDelay } returns sensorMaximumDelayMicros
		every { sensorManager.flush(any<SensorEventListener>()) } returns flushResult
		val registrationCall = every {
			sensorManager.registerListener(
				capture(listenerSlot),
				sensor,
				any<Int>(),
				any<Int>(),
			)
		}
		registrationCall answers {
			listeners += listenerSlot.captured
			operationEvents?.add("register")
			registerFailure?.let { throw it }
			registerResults.getOrNull(registerIndex++) ?: true
		}
		var unregisterIndex = 0
		every { sensorManager.unregisterListener(any<SensorEventListener>()) } answers {
			operationEvents?.add("unregister")
			unregisterFailures.getOrNull(unregisterIndex++)?.let { throw it }
		}
		var beginCallIndex = 0
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } coAnswers {
			val callIndex = beginCallIndex++
			registrationsToReturn[callIndex.coerceAtMost(registrationsToReturn.lastIndex)]
		}
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} coAnswers {
			if (compatibleRefresh != null) {
				compatibleRefresh.invoke(arg<Long>(5))
			} else {
				registrationsToReturn.getOrNull(1)
			}
		}
		coEvery { registrations.loadRuntimeState(any()) } returns null
		coEvery {
			registrations.saveRuntimeState(any(), any(), any(), any(), any(), any(), any(), any())
		} returns SourceRuntimeStateSaveResult.Saved
		coEvery {
			registrations.verifySourceEraseProviderSettled(SourceKind.PRESSURE, any(), any())
		} returns true
		coEvery { registrations.beginRetirement(any(), any(), any(), any()) } coAnswers {
			operationEvents?.add("begin-retirement")
			beginRetirement(firstArg(), secondArg(), thirdArg(), arg(3))
		}
		coEvery { registrations.completeRetirement(any()) } coAnswers {
			operationEvents?.add("complete-retirement")
			completeRetirement(firstArg())
		}
		var acceptanceCalls = 0
		coEvery { registrations.markAccepted(any(), any(), any()) } coAnswers {
			acceptanceCalls++
			if (acceptanceCalls == 1) markAcceptanceFailure?.let { throw it }
			null
		}
		if (failRuntimeCheckpoint) {
			coEvery {
				registrations.saveRuntimeState(any(), any(), any(), any(), any(), any(), any(), any())
			} throws IllegalStateException("checkpoint unavailable")
		}
		return Fixture(
			PressureSourceRuntime(context, scope, registrations),
			registrations,
			sensorManager,
			sensor,
			listener = { listenerSlot.captured },
			listeners = listeners,
		)
	}

	private fun interruptedPressureCheckpoint(): SourceRuntimeStateEntity {
		val metrics = RuntimeAdmissionSnapshot(7L, 77L, 1L, 8L, 8L,
			setOf(RuntimeGapClassification.ADMISSION_FAILED))
		return SourceRuntimeStateEntity(SourceKind.PRESSURE.stableCode, "source-broker:4", "pressure-1",
			"boot-1", registrationGeneration = 8L, lastProviderSequence = 8L,
			lastAdmittedSourceSequence = 7L, lastAdmissionOrdinal = 77L,
			stateVersion = SENSOR_RUNTIME_CHECKPOINT_VERSION,
			payload = encodeSensorRuntimeCheckpoint(SensorRuntimeCheckpoint(RuntimeCheckpointLifecycle.ACTIVE,
				metrics, PRESSURE_RUNTIME_COMPONENT_VERSION, ByteArray(0))), updatedAtMs = 1L)
	}

	private fun plan(
		revision: Long,
		hardwareSamplePeriodMicros: Int = 50_000,
		maximumReportLatencyMicros: Int = 0,
		aggregationWindowMs: Long = 5_000L,
	) = PressurePlan(
		revision = revision,
		enabled = true,
		hardwareSamplePeriodMicros = hardwareSamplePeriodMicros,
		maximumReportLatencyMicros = maximumReportLatencyMicros,
		aggregationWindowMs = aggregationWindowMs,
	)

	private fun runtimeClaim(actionId: String) = SourceRuntimeClaim(
		source = SourceKind.PRESSURE,
		actionId = actionId,
		attemptCount = 1,
		leaseGeneration = 1L,
		logicalTrackingId = "pressure-test",
		serviceRunId = "run-1",
	)

	private fun pressureCutoff() = SessionCutoff(
		logicalTrackingId = "pressure-test",
		elapsedRealtimeNanos = Long.MAX_VALUE,
		wallTimeMs = 1L,
		deadlineElapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() + 1_000_000_000L,
	)

	private fun registration(
		plan: PressurePlan,
		authorizationRevision: Long,
		requiresAcceptance: Boolean = false,
		generation: Long = 9L,
		effectiveElapsedNanos: Long = authorizationRevision,
		purpose: String = SourceBrokerPurpose.SESSION_CAPTURE,
		includeAmbient: Boolean = false,
		providerFingerprint: String = plan.physicalConfigurationFingerprint(),
		serviceRunId: String = "run-1",
	): SourceRegistration {
		val capturedPurpose = purpose in setOf(
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceBrokerPurpose.CONTROL_CONTINUATION,
		)
		val demand = SourceDemandEntity(
			demandId = "pressure-demand-$authorizationRevision",
			consumerId = "session:pressure-test",
			sourceKind = SourceKind.PRESSURE.stableCode,
			purpose = purpose,
			logicalTrackingId = "pressure-test".takeIf { capturedPurpose },
			serviceRunId = serviceRunId.takeIf { capturedPurpose },
			manifestRevision = authorizationRevision.takeIf { capturedPurpose },
			lifecycleLeaseGeneration = 1L.takeIf { capturedPurpose },
			sourcePolicyRevision = authorizationRevision,
			consentEpoch = 1L,
			persistenceEligible = capturedPurpose || purpose == SourceBrokerPurpose.AMBIENT_PRODUCT,
			qosCode = 0,
			maximumAgeMs = 60_000L,
			desiredLatencyMs = 5_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = authorizationRevision,
			requestedAtMs = 1L,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
		val authorization = requireNotNull(
			SourceBrokerAuthorization.rows(
				SourceKind.PRESSURE.stableCode,
				registrationGeneration = generation,
				authorizationRevision = authorizationRevision,
				demands = listOf(demand) + if (includeAmbient) { listOf(demand.asAmbientPressureDemand()) } else { emptyList() },
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = effectiveElapsedNanos,
				effectiveWallTimeMs = 1L,
			).toAuthorizationSnapshotOrNull(),
		)
		val ownerScope = "source-broker:${SourceKind.PRESSURE.stableCode}"
		return SourceRegistration(
			ownerScope = ownerScope,
			state = SourceRegistrationStateEntity(
				sourceKind = SourceKind.PRESSURE.stableCode,
				ownerScope = ownerScope,
				sourceInstanceId = "pressure-1",
				clockDomainId = "boot-1",
				registrationGeneration = generation,
				nextSequence = 0L,
				appliedRevision = plan.revision,
				collectedDataEpoch = 1L,
				updatedAtMs = 1L,
			),
			physicalConfigurationFingerprint = providerFingerprint,
			authorization = authorization,
			requiresProviderAcceptance = requiresAcceptance,
		)
	}

	private fun SourceDemandEntity.asAmbientPressureDemand(): SourceDemandEntity = copy(
		demandId = "ambient-pressure", consumerId = "all-day:pressure",
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		logicalTrackingId = null, serviceRunId = null,
		manifestRevision = null, lifecycleLeaseGeneration = null,
	)

	private fun retirementToken(
		registration: SourceRegistration,
		reason: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
	) = SourceRegistrationRetirementToken(
		source = SourceKind.PRESSURE,
		sourceInstanceId = com.adsamcik.tracker.tracker.source.model.SourceInstanceId(
			registration.state.sourceInstanceId,
		),
		registrationGeneration = registration.state.registrationGeneration,
		processIncarnationId = "pressure-test-process",
		retiredAtMs = retiredAtMs,
		retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
		reason = reason,
	)

	private class CutoffPressureSink(
		private var prefixFailures: Int = 0,
		private val rejectWholeWithPolicy: Boolean = false,
		private val preserveAmbientTail: Boolean = false,
		private val rejectPrefix: Boolean = false,
		private val cancelTail: Boolean = false,
	) : SourceEventSink {
		var cutoff: Long = Long.MAX_VALUE
		val deliveries = mutableListOf<SourceDeliveryCandidate>()
		val checkpoints = mutableListOf<SensorAdmissionCheckpoint>()
		val accepted = mutableListOf<PressureWindowPayload>()
		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			error("Pressure prefixes require the existing atomic delivery path")
		override suspend fun admit(delivery: SourceDeliveryCandidate,
			checkpoint: SensorAdmissionCheckpoint): SourceDeliveryAdmissionHandoff {
			deliveries += delivery
			checkpoints += checkpoint
			val payload = delivery.units.single().evidence.payload as PressureWindowPayload
			if (payload.windowEndElapsedRealtimeNanos > cutoff &&
				(!preserveAmbientTail || payload.windowStartElapsedRealtimeNanos <= cutoff)) {
				return if (rejectWholeWithPolicy) {
					SourceDeliveryAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.SOURCE_POLICY_STALE)
				} else { SourceDeliveryAdmissionHandoff.SessionCutoff(cutoff) }
			}
			if (rejectPrefix && payload.windowEndElapsedRealtimeNanos <= cutoff) {
				return SourceDeliveryAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.SOURCE_POLICY_STALE)
			}
			if (cancelTail && payload.windowStartElapsedRealtimeNanos > cutoff) {
				throw CancellationException("ambient tail admission cancelled")
			}
			if (prefixFailures > 0) {
				prefixFailures--
				return SourceDeliveryAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
			}
			accepted += payload
			return SourceDeliveryAdmissionHandoff.Durable(listOf(40L + accepted.size))
		}
	}

	private class RecordingPressureSink : SourceEventSink {
		val deliveries = mutableListOf<SourceDeliveryCandidate>()
		val candidates = mutableListOf<SourceEvidenceCandidate<*>>()
		val windows: List<PressureWindowPayload>
			get() = candidates.map { candidate -> candidate.payload as PressureWindowPayload }
		private var nextOrdinal = 1L

		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			error("Pressure must not fall back to non-atomic admission")

		override suspend fun admit(
			delivery: SourceDeliveryCandidate,
			checkpoint: SensorAdmissionCheckpoint,
		): SourceDeliveryAdmissionHandoff {
			deliveries += delivery
			candidates += delivery.units.single().evidence
			return SourceDeliveryAdmissionHandoff.Durable(listOf(nextOrdinal++))
		}
	}

	private fun pressureEvent(
		sensor: Sensor,
		observedElapsedNanos: Long,
		pressure: Float,
		accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
	): SensorEvent {
		val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType!!)
		constructor.isAccessible = true
		return constructor.newInstance(1).also { event ->
			event.sensor = sensor
			event.timestamp = observedElapsedNanos
			event.values[0] = pressure
			event.accuracy = accuracy
		}
	}
}
