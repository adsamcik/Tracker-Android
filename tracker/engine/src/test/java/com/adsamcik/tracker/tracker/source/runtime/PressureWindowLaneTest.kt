package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PressureWindowLaneTest {
	@Test
	fun `authorization refresh latch is observed-time FIFO and bounded`() {
		val latch = PressureAuthorizationRefreshLatch(
			effectiveElapsedRealtimeNanos = 300L,
			maximumBufferedSamples = 2,
		)
		assertFalse(latch.shouldBuffer(299L))
		assertTrue(latch.shouldBuffer(300L))
		assertTrue(latch.markBoundaryClosed())
		assertFalse(latch.markBoundaryClosed())
		assertEquals(PressureRefreshLatchOffer.BUFFERED, latch.offer(pendingSample(300L, 2L), 2))
		assertEquals(PressureRefreshLatchOffer.BUFFERED, latch.offer(pendingSample(301L, 3L), 2))
		assertEquals(PressureRefreshLatchOffer.OVERFLOW, latch.offer(pendingSample(302L, 4L), 2))
		assertEquals(listOf(2L, 3L), latch.drainBufferedSamples().map { it.reception.providerSequence })
		assertEquals(0, latch.bufferedSampleCount)
	}

	@Test
	fun `default resume retry grows to a power-safe saturated in-process delay`() = runTest {
		var attempts = 0
		val waits = mutableListOf<Long>()

		val result = reconcilePressureResumeUntilSettled(
			attempt = {
				attempts++
				if (attempts < 7) PressureResumeAttempt.RETRY else PressureResumeAttempt.RESUMED
			},
			retryDelaysMs = PRESSURE_RESUME_RETRY_DELAYS_MS,
			waitBeforeRetry = { waits += it },
		)

		assertEquals(PressureResumeAttempt.RESUMED, result)
		assertEquals(listOf(100L, 1_000L, 5_000L, 30_000L, 60_000L, 60_000L), waits)
	}

	@Test
	fun `automatic resume signal requires a successful post-gap checkpoint at low water`() {
		val gate = PressureCapacityResumeGate(lowWaterWindowCount = 2)

		gate.onCapacityPause(gapSequence = 71L)
		assertFalse(gate.postGapCheckpointDurable)
		assertFalse(gate.onSuccessfulPostGapCheckpoint(
			pendingWindowCount = 2,
			checkpointedGapSequence = null,
		))
		assertFalse(gate.onSuccessfulPostGapCheckpoint(
			pendingWindowCount = 3,
			checkpointedGapSequence = 71L,
		))
		assertTrue(gate.postGapCheckpointDurable)
		assertTrue(gate.onSuccessfulPostGapCheckpoint(2, checkpointedGapSequence = 71L))
		assertFalse(gate.onSuccessfulPostGapCheckpoint(1, checkpointedGapSequence = 71L))
	}

	@Test
	fun `fresh listener waits for the pre-pause partial checkpoint`() = runTest {
		val partialSettlement = CompletableDeferred<Unit>()
		val ordering = mutableListOf<String>()
		val resume = async {
			resumePressureAfterPartialSettlement(partialSettlement) {
				ordering += "listener-registered"
			}
		}

		testScheduler.runCurrent()
		assertEquals(emptyList(), ordering)
		ordering += "partial-checkpointed"
		partialSettlement.complete(Unit)
		resume.await()

		assertEquals(listOf("partial-checkpointed", "listener-registered"), ordering)
	}

	@Test
	fun `resume registration retries saturate without overlapping listeners`() = runTest {
		var attempts = 0
		var activeListeners = 0
		var maximumActiveListeners = 0
		val waits = mutableListOf<Long>()

		val result = reconcilePressureResumeUntilSettled(
			attempt = {
				assertEquals(0, activeListeners)
				activeListeners++
				maximumActiveListeners = maxOf(maximumActiveListeners, activeListeners)
				attempts++
				if (attempts <= 5) {
					activeListeners-- // Failed registrations are defensively unregistered before retry.
					PressureResumeAttempt.RETRY
				} else {
					PressureResumeAttempt.RESUMED
				}
			},
			retryDelaysMs = longArrayOf(10L, 50L, 250L),
			waitBeforeRetry = { waits += it },
		)

		assertEquals(PressureResumeAttempt.RESUMED, result)
		assertEquals(6, attempts)
		assertEquals(1, activeListeners)
		assertEquals(1, maximumActiveListeners)
		assertEquals(listOf(10L, 50L, 250L, 250L, 250L), waits)
	}

	@Test
	fun `stop or reconfigure fence rejects and removes a late registered listener`() = runTest {
		var identityCurrent = true
		var activeListeners = 0
		var attempts = 0

		val result = reconcilePressureResumeUntilSettled(
			attempt = {
				attempts++
				activeListeners++
				identityCurrent = false
				if (!identityCurrent) {
					activeListeners--
					PressureResumeAttempt.STALE
				} else {
					PressureResumeAttempt.RESUMED
				}
			},
			waitBeforeRetry = { error("A stale lifecycle identity must not retry") },
		)

		assertEquals(PressureResumeAttempt.STALE, result)
		assertEquals(1, attempts)
		assertEquals(0, activeListeners)
	}

	@Test
	fun `FIFO head survives more than three transient retries and then settles atomic durable`() = runTest {
		var admissionAttempts = 0
		var checkpointAttempts = 0
		var preparedCount = 0
		val waits = mutableListOf<Long>()
		val resolved = mutableListOf<SourceAdmissionHandoff>()

		val result = processPressureWindowHead(
			deadlineElapsedRealtimeNanos = { null },
			nowElapsedRealtimeNanos = { 0L },
			prepare = { (++preparedCount).toString() },
			admit = {
				admissionAttempts++
				if (admissionAttempts <= 4) {
					SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
				} else {
					SourceAdmissionHandoff.Durable(91L)
				}
			},
			onAdmissionResolved = { _, handoff -> resolved += handoff },
			persistTerminalOutcome = { checkpointAttempts++; true },
			retryDelaysMs = longArrayOf(10L, 50L, 250L),
			waitBeforeRetry = { waits += it },
		)

		assertEquals(PressureWindowHeadResolution.SETTLED, result)
		assertEquals(1, preparedCount)
		assertEquals(5, admissionAttempts)
		assertEquals(listOf(10L, 50L, 250L, 250L), waits)
		assertEquals(1, resolved.size)
		assertEquals<SourceAdmissionHandoff>(SourceAdmissionHandoff.Durable(91L), resolved.single())
		assertEquals(0, checkpointAttempts)
	}

	@Test
	fun `transient sequence preparation cannot advance or checkpoint the head`() = runTest {
		var prepareAttempts = 0
		var admissionAttempts = 0
		var checkpointAttempts = 0

		val result = processPressureWindowHead(
			deadlineElapsedRealtimeNanos = { null },
			nowElapsedRealtimeNanos = { 0L },
			prepare = {
				prepareAttempts++
				if (prepareAttempts <= 4) null else "allocated-sequence"
			},
			admit = { admissionAttempts++; SourceAdmissionHandoff.Durable(92L) },
			onAdmissionResolved = { _, _ -> },
			persistTerminalOutcome = { checkpointAttempts++; true },
			waitBeforeRetry = {},
		)

		assertEquals(PressureWindowHeadResolution.SETTLED, result)
		assertEquals(5, prepareAttempts)
		assertEquals(1, admissionAttempts)
		assertEquals(0, checkpointAttempts)
	}

	@Test
	fun `atomic checkpoint unsupported retains exact head and never falls back to legacy admit`() = runTest {
		val prepared = PreparedPressureAdmission(
			delivery = mockk(),
			checkpoint = mockk(),
			checkpointedCapacityGapSequence = null,
			checkpointOrderElapsedRealtimeNanos = 123_456_789L,
		)
		var legacyAdmissions = 0
		var atomicAdmissions = 0
		val seenDeliveries = mutableListOf<com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate>()
		val seenCheckpoints = mutableListOf<SensorAdmissionCheckpoint>()
		val sink = object : SourceEventSink {
			override suspend fun admit(
				candidate: com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate<*>,
			): SourceAdmissionHandoff {
				legacyAdmissions++
				return SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.UNKNOWN)
			}

			override suspend fun admit(
				delivery: com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate,
				checkpoint: SensorAdmissionCheckpoint,
			): SourceDeliveryAdmissionHandoff {
				atomicAdmissions++
				seenDeliveries += delivery
				seenCheckpoints += checkpoint
				return if (atomicAdmissions <= 4) {
					SourceDeliveryAdmissionHandoff.RetryableFailure(
						SourceAdmissionFailureCode.ATOMIC_CHECKPOINT_UNSUPPORTED,
					)
				} else {
					SourceDeliveryAdmissionHandoff.Duplicate(existingAdmissionOrdinals = listOf(73L))
				}
			}
		}
		val resolved = mutableListOf<SourceAdmissionHandoff>()

		val result = processPressureWindowHead(
			deadlineElapsedRealtimeNanos = { null },
			nowElapsedRealtimeNanos = { 0L },
			prepare = { prepared },
			admit = { exact ->
				sink.admit(exact.delivery, exact.checkpoint).toPressureWindowHandoff()
			},
			onAdmissionResolved = { exact, handoff ->
				assertSame(prepared, exact)
				resolved += handoff
			},
			persistTerminalOutcome = { error("An exact duplicate already repaired/used its atomic checkpoint") },
			waitBeforeRetry = {},
		)

		assertEquals(PressureWindowHeadResolution.SETTLED, result)
		assertEquals(0, legacyAdmissions)
		assertEquals(5, atomicAdmissions)
		assertTrue(seenDeliveries.all { it === prepared.delivery })
		assertTrue(seenCheckpoints.all { it === prepared.checkpoint })
		assertEquals(1, resolved.size)
		assertEquals<SourceAdmissionHandoff>(SourceAdmissionHandoff.Duplicate(73L), resolved.single())
	}

	@Test
	fun `durable session cutoff fails closed without reinterpreting an atomic pressure window`() {
		val mapped = SourceDeliveryAdmissionHandoff.SessionCutoff(
			cutoffElapsedRealtimeNanos = 123L,
		).toPressureWindowHandoff()

		assertEquals<SourceAdmissionHandoff>(
			SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.SOURCE_POLICY_STALE),
			mapped,
		)
	}

	@Test
	fun `terminal no-fact result persists standalone once admission is resolved`() = runTest {
		var admissions = 0
		var terminalRecords = 0
		var checkpointAttempts = 0

		val result = processPressureWindowHead(
			deadlineElapsedRealtimeNanos = { null },
			nowElapsedRealtimeNanos = { 0L },
			prepare = { "exact-head" },
			admit = {
				admissions++
				SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.INVALID_EVIDENCE)
			},
			onAdmissionResolved = { _, _ -> terminalRecords++ },
			persistTerminalOutcome = { ++checkpointAttempts >= 5 },
			waitBeforeRetry = {},
		)

		assertEquals(PressureWindowHeadResolution.SETTLED, result)
		assertEquals(1, admissions)
		assertEquals(1, terminalRecords)
		assertEquals(5, checkpointAttempts)
	}

	@Test
	fun `default pressure head retry reaches a power-safe saturated tail`() = runTest {
		var admissions = 0
		val waits = mutableListOf<Long>()

		val result = processPressureWindowHead(
			deadlineElapsedRealtimeNanos = { null },
			nowElapsedRealtimeNanos = { 0L },
			prepare = { "exact-head" },
			admit = {
				admissions++
				if (admissions < 7) {
					SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
				} else {
					SourceAdmissionHandoff.Durable(74L)
				}
			},
			onAdmissionResolved = { _, _ -> },
			persistTerminalOutcome = { error("Durable atomic admission needs no standalone checkpoint") },
			retryDelaysMs = PRESSURE_HEAD_RETRY_DELAYS_MS,
			waitBeforeRetry = { waits += it },
		)

		assertEquals(PressureWindowHeadResolution.SETTLED, result)
		assertEquals(listOf(100L, 1_000L, 5_000L, 30_000L, 60_000L, 60_000L), waits)
	}

	@Test
	fun `deadline leaves unresolved FIFO head in place and does not advance checkpoint`() = runTest {
		val registration = mockk<SourceRegistration>()
		val sink = mockk<SourceEventSink>()
		val lane = Channel<PressureCompletedWindow>(2)
		val accumulator = PressureWindowAccumulator(1L, 1, 0)
		accumulator.add(1_000f, 1L, 1L)
		val first = requireNotNull(accumulator.add(1_001f, 2L, 2L))
		val second = requireNotNull(accumulator.drain())
		assertTrue(lane.trySend(window(first, 2L, 2L, registration, sink)).isSuccess)
		assertTrue(lane.trySend(window(second, 2L, 2L, registration, sink)).isSuccess)
		lane.close()
		var nowNanos = 0L
		var admissionAttempts = 0
		var checkpointAdvances = 0
		var secondHeadVisited = false

		val drain = consumePressureWindowLane(lane, processHead = { head ->
			if (head.payload.firstProviderSequence != first.firstProviderSequence) {
				secondHeadVisited = true
			}
			processPressureWindowHead(
				deadlineElapsedRealtimeNanos = { 60_000_000L },
				nowElapsedRealtimeNanos = { nowNanos },
				prepare = { "prepared-head" },
				admit = {
					admissionAttempts++
					SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
				},
				onAdmissionResolved = { _, _ -> error("A retryable head must not resolve") },
				persistTerminalOutcome = { checkpointAdvances++; true },
				retryDelaysMs = longArrayOf(10L, 50L, 250L),
				waitBeforeRetry = { nowNanos += it * 1_000_000L },
			)
		})

		assertEquals(PressureWindowLaneDrain.DEADLINE_UNRESOLVED, drain)
		assertEquals(2, admissionAttempts)
		assertEquals(0, checkpointAdvances)
		assertFalse(secondHeadVisited)
	}

	@Test
	fun `normal bursts settle without reaching the pause threshold`() {
		val guard = PressureWindowCapacityGuard(maximumPendingWindows = 8)
		val resumeGate = PressureCapacityResumeGate(lowWaterWindowCount = 2)

		repeat(1_000) {
			assertFalse(guard.acquisitionWindowEnqueued().pauseAcquisition)
			guard.windowSettled()
			assertFalse(resumeGate.onSuccessfulPostGapCheckpoint(
				guard.pendingWindowCount,
				checkpointedGapSequence = null,
			))
		}

		assertEquals(0, guard.pendingWindowCount)
		assertFalse(resumeGate.capacityPaused)
	}

	@Test
	fun `ten thousand callback samples cause only window proportional admission and checkpoint work`() = runTest {
		val accumulator = PressureWindowAccumulator(100L, 1, 0)
		val registration = mockk<SourceRegistration>()
		val sink = mockk<SourceEventSink>()
		val lane = Channel<PressureCompletedWindow>(100)
		var lastElapsed = 0L
		var lastSequence = 0L
		repeat(10_000) { index ->
			val elapsed = index.toLong()
			val sequence = index + 1L
			val completed = accumulator.add(1_000f + index % 100, elapsed, sequence)
			if (completed != null) {
				assertTrue(lane.trySend(window(completed, lastElapsed, lastSequence, registration, sink)).isSuccess)
			}
			lastElapsed = elapsed
			lastSequence = sequence
		}
		assertTrue(lane.trySend(
			window(requireNotNull(accumulator.drain()), lastElapsed, lastSequence, registration, sink),
		).isSuccess)
		lane.close()

		val admissions = mutableListOf<Long>()
		val checkpoints = mutableListOf<Long>()
		consumePressureWindowLane(
			windows = lane,
			processHead = {
				admissions += it.payload.lastProviderSequence
				checkpoints += it.payload.lastProviderSequence
				PressureWindowHeadResolution.SETTLED
			},
		)

		assertEquals(100, admissions.size)
		assertEquals(100, checkpoints.size)
		assertEquals((100L..10_000L step 100).toList(), admissions)
		assertEquals(admissions, checkpoints)
	}

	private fun window(
		payload: com.adsamcik.tracker.tracker.source.model.PressureWindowPayload,
		receivedElapsed: Long,
		providerSequence: Long,
		registration: SourceRegistration,
		sink: SourceEventSink,
	) = PressureCompletedWindow(
		payload = payload,
		reception = PressureReception(
			observedElapsedNanos = payload.windowEndElapsedRealtimeNanos,
			receivedElapsedNanos = receivedElapsed,
			receivedWallTimeMs = receivedElapsed,
			providerSequence = providerSequence,
		),
		registration = registration,
		sink = sink,
		lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
	)

	private fun pendingSample(
		observedElapsedNanos: Long,
		providerSequence: Long,
	) = PendingPressureSample(
		pressureHectopascals = 1_000f,
		sensorAccuracy = PressureSensorAccuracy.HIGH,
		reception = PressureReception(
			observedElapsedNanos = observedElapsedNanos,
			receivedElapsedNanos = observedElapsedNanos,
			receivedWallTimeMs = 1L,
			providerSequence = providerSequence,
		),
	)
}
