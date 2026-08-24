package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StepSourceRuntimeDurabilityTest {
	@Test
	fun `drain tail subtracts an already accounted overflow sequence exactly`() {
		assertEquals(
			listOf(6L..7L, 9L..10L),
			unaccountedStepDrainRanges(processedThrough = 5L, barrier = 10L, alreadyAccountedOverflowSequence = 8L),
		)
		assertEquals(
			listOf(6L..10L),
			unaccountedStepDrainRanges(processedThrough = 5L, barrier = 10L, alreadyAccountedOverflowSequence = 11L),
		)
		assertEquals(emptyList(), unaccountedStepDrainRanges(10L, 10L, 8L))
	}
	@Test
	fun `bounded callback lane pauses on overflow records one gap and preserves durable baseline`() = runTest {
		val registration = mockk<SourceRegistration>()
		val sink = mockk<SourceEventSink>()
		val lane = newStepCallbackLane(capacity = 2)
		val metrics = RuntimeAdmissionMetrics()
		assertTrue(lane.offer(rawStep(1L, registration, sink)))
		assertTrue(lane.offer(rawStep(2L, registration, sink)))
		assertFalse(lane.offer(rawStep(3L, registration, sink)))
		assertFalse(lane.offer(rawStep(4L, registration, sink)))
		assertEquals(3L, lane.overflowCausalOrderElapsedRealtimeNanos)
		metrics.recordFailure(
			requireNotNull(lane.overflowSequence),
			classification = RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
		)

		val consumed = mutableListOf<Long>()
		val processed = mutableListOf<Long>()
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(1L))
		assertTrue(consumeStepCallbackLane(
			events = lane.events,
			consumeHead = { event ->
				consumed += event.providerSequence
				requireNotNull(accumulator.accept(
					"boot", event.cumulativeCount, event.observedElapsedNanos,
					event.providerSequence, event.receivedElapsedNanos,
				))
				true
			},
			markProcessed = { processed += it },
		))

		assertEquals(listOf(1L, 2L), consumed)
		assertEquals(listOf(1L, 2L), processed)
		assertEquals(2L, accumulator.snapshot()?.cumulativeCount)
		assertEquals(1L, metrics.snapshot().failedAdmissionCount)
		assertEquals(setOf(RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW),
			metrics.snapshot().gapClassifications)
	}

	@Test
	fun `an unresolved head stops FIFO progress and is not reported processed`() = runTest {
		val registration = mockk<SourceRegistration>()
		val sink = mockk<SourceEventSink>()
		val lane = newStepCallbackLane()
		(1L..3L).forEach { assertTrue(lane.offer(rawStep(it, registration, sink))) }
		lane.close()
		val attempted = mutableListOf<Long>()
		val processed = mutableListOf<Long>()

		assertFalse(consumeStepCallbackLane(
			events = lane.events,
			consumeHead = { event -> attempted += event.providerSequence; false },
			markProcessed = { processed += it },
		))

		assertEquals(listOf(1L), attempted)
		assertTrue(processed.isEmpty())
	}

	@Test
	fun `sequence preparation retry retains the exact immutable FIFO head`() = runTest {
		val registration = mockk<SourceRegistration>()
		val sink = mockk<SourceEventSink>()
		val head = rawStep(7L, registration, sink)
		val lane = newStepCallbackLane()
		assertTrue(lane.offer(head))
		lane.close()
		val attemptedHeads = mutableListOf<RawStep>()
		val processed = mutableListOf<Long>()

		assertTrue(consumeStepCallbackLane(
			events = lane.events,
			consumeHead = { event ->
				val prepared = retryStepPhaseUntilResolved(
					attempt = {
						attemptedHeads += event
						if (attemptedHeads.size < 3) null else event.providerSequence
					},
					deadlineElapsedRealtimeNanos = { null },
					elapsedRealtimeNanos = { 0L },
					retryDelay = {},
				)
				prepared is StepPhaseResolution.Resolved<*>
			},
			markProcessed = { processed += it },
		))

		assertEquals(3, attemptedHeads.size)
		assertTrue(attemptedHeads.all { it === head })
		assertEquals(listOf(7L), processed)
	}

	@Test
	fun `atomic unsupported retries exact prepared head without legacy fallback`() = runTest {
		val expectedCandidate = mockk<SourceEvidenceCandidate<*>>()
		val expectedCheckpoint = mockk<SensorAdmissionCheckpoint>()
		var legacyAdmissions = 0
		var atomicAdmissions = 0
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff {
				legacyAdmissions++
				return SourceAdmissionHandoff.Durable(999L)
			}

			override suspend fun admit(
				actualCandidate: SourceEvidenceCandidate<*>,
				actualCheckpoint: SensorAdmissionCheckpoint,
			): SourceAdmissionHandoff {
				assertSame(expectedCandidate, actualCandidate)
				assertSame(expectedCheckpoint, actualCheckpoint)
				atomicAdmissions++
				return if (atomicAdmissions < 3) {
					SourceAdmissionHandoff.RetryableFailure(
						SourceAdmissionFailureCode.ATOMIC_CHECKPOINT_UNSUPPORTED,
					)
				} else {
					SourceAdmissionHandoff.Durable(91L)
				}
			}
		}

		val handoff = assertIs<StepAdmissionResolution.Resolved>(
			sink.admitStepHeadAtomicallyUntilResolved(
				candidate = expectedCandidate,
				checkpoint = expectedCheckpoint,
				deadlineElapsedRealtimeNanos = { null },
				elapsedRealtimeNanos = { 0L },
				retryDelay = {},
			),
		).handoff

		assertEquals(SourceAdmissionHandoff.Durable(91L), handoff)
		assertEquals(3, atomicAdmissions)
		assertEquals(0, legacyAdmissions)
	}

	@Test
	fun `atomic checkpoint carries next baseline while memory waits for durable handoff`() {
		val boundary = StepBaselineBoundary(1L)
		val accumulator = StepWindowAccumulator(null, boundary)
		requireNotNull(accumulator.accept("boot", 100L, 100L, 1L, 100L))
		val prior = accumulator.snapshot()
		val preview = requireNotNull(accumulator.preview("boot", 105L, 200L, 2L, 200L))
		val gapMetrics = RuntimeAdmissionMetrics().also {
			it.recordFailure(7L, classification = RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW)
		}.snapshot()
		val callbackEntryReceivedElapsedNanos = 200_987_654L
		val checkpoint = stepAtomicRuntimeCheckpoint(
			gapMetrics,
			preview,
			callbackEntryReceivedElapsedNanos,
		)

		assertEquals(prior, accumulator.snapshot())
		assertEquals(gapMetrics, checkpoint.metrics)
		assertEquals(callbackEntryReceivedElapsedNanos, checkpoint.causalOrderElapsedRealtimeNanos)
		assertEquals(
			preview.nextBaseline,
			decodeStepBaseline(checkpoint.componentPayload, checkpoint.componentStateVersion, boundary),
		)
		assertTrue(commitDurableStepPreview(
			accumulator,
			preview,
			SourceAdmissionHandoff.Duplicate(92L),
		))
		assertEquals(preview.nextBaseline, accumulator.snapshot())
	}

	@Test
	fun `atomic duplicate uses checkpoint overload once`() = runTest {
		var atomicAdmissions = 0
		val result = atomicSink { _, _ ->
			atomicAdmissions++
			SourceAdmissionHandoff.Duplicate(92L)
		}.admitStepHeadAtomicallyUntilResolved(
			candidate = mockk<SourceEvidenceCandidate<*>>(),
			checkpoint = mockk<SensorAdmissionCheckpoint>(),
			deadlineElapsedRealtimeNanos = { null },
			elapsedRealtimeNanos = { 0L },
			retryDelay = {},
		)

		assertEquals(
			StepAdmissionResolution.Resolved(SourceAdmissionHandoff.Duplicate(92L)),
			result,
		)
		assertEquals(1, atomicAdmissions)
	}

	@Test
	fun `terminal atomic handoff leaves prior baseline for standalone gap checkpoint`() = runTest {
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(1L))
		requireNotNull(accumulator.accept("boot", 10L, 100L, 1L, 100L))
		val priorBaseline = accumulator.snapshot()
		val metrics = RuntimeAdmissionMetrics()
		val handoff = assertIs<StepAdmissionResolution.Resolved>(
			atomicSink { _, _ ->
				SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.INVALID_EVIDENCE)
			}.admitStepHeadAtomicallyUntilResolved(
				candidate = mockk<SourceEvidenceCandidate<*>>(),
				checkpoint = mockk<SensorAdmissionCheckpoint>(),
				deadlineElapsedRealtimeNanos = { null },
				elapsedRealtimeNanos = { 0L },
				retryDelay = {},
			),
		).handoff
		metrics.recordFailure(2L)

		assertFalse(commitDurableStepPreview(
			accumulator,
			requireNotNull(accumulator.preview("boot", 12L, 200L, 2L, 200L)),
			handoff,
		))
		val standaloneCheckpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = metrics.snapshot(),
			componentStateVersion = STEP_BASELINE_VERSION,
			componentPayload = requireNotNull(accumulator.snapshot()).encode(),
		)

		assertEquals(priorBaseline, accumulator.snapshot())
		assertEquals(2L, standaloneCheckpoint.metrics.unresolvedSequenceEndInclusive)
		assertEquals(
			priorBaseline,
			decodeStepBaseline(
				standaloneCheckpoint.componentPayload,
				standaloneCheckpoint.componentStateVersion,
				StepBaselineBoundary(1L),
			),
		)
	}

	@Test
	fun `transient retries use capped coroutine delay schedule`() = runTest {
		var attempts = 0
		val delays = mutableListOf<Long>()

		val result = retryStepPhaseUntilResolved(
			attempt = { if (++attempts == 9) Unit else null },
			deadlineElapsedRealtimeNanos = { null },
			elapsedRealtimeNanos = { 0L },
			retryDelay = { delays += it },
		)

		assertIs<StepPhaseResolution.Resolved<*>>(result)
		assertEquals(
			listOf(10L, 50L, 250L, 1_000L, 5_000L, 30_000L, 30_000L, 30_000L),
			delays,
		)
	}

	@Test
	fun `provider registration false or exception always rolls back provisional state`() {
		var rollbacks = 0

		assertFalse(attemptStepProviderRegistration(
			register = { false },
			rollback = { rollbacks++ },
		))
		assertFalse(attemptStepProviderRegistration(
			register = { throw IllegalStateException("sensor failure") },
			rollback = { rollbacks++ },
		))
		assertTrue(attemptStepProviderRegistration(
			register = { true },
			rollback = { rollbacks++ },
		))
		assertEquals(2, rollbacks)
	}

	@Test
	fun `overflow recovery persists gap and reset before provider resume`() = runTest {
		val calls = mutableListOf<String>()

		val outcome = recoverStepOverflowAttempt(
			canRecover = { true },
			persistGapAndReset = {
				calls += "persist-gap-reset"
				true
			},
			resumeProvider = {
				calls += "resume-provider"
				true
			},
		)

		assertEquals(StepOverflowRecoveryOutcome.RESUMED, outcome)
		assertEquals(listOf("persist-gap-reset", "resume-provider"), calls)
	}

	@Test
	fun `overflow recovery starts a new baseline before durable deltas`() = runTest {
		lateinit var first: StepCounterWindowPayload
		lateinit var second: StepCounterWindowPayload

		val outcome = recoverStepOverflowAttempt(
			canRecover = { true },
			persistGapAndReset = { true },
			resumeProvider = {
				val resumed = StepWindowAccumulator(null, StepBaselineBoundary(1L))
				first = requireNotNull(resumed.accept("boot", 50L, 500L, 4L, 500L))
				second = requireNotNull(resumed.accept("boot", 54L, 600L, 5L, 600L))
				true
			},
		)

		assertEquals(StepOverflowRecoveryOutcome.RESUMED, outcome)
		assertEquals(0L, first.deltaCount)
		assertTrue(first.baselineReset)
		assertEquals(4L, second.deltaCount)
		assertFalse(second.baselineReset)
	}

	@Test
	fun `overflow recovery remains paused until gap checkpoint retry succeeds`() = runTest {
		var checkpointDurable = false
		var resumeAttempts = 0
		val attempt = suspend {
			recoverStepOverflowAttempt(
				canRecover = { true },
				persistGapAndReset = { checkpointDurable },
				resumeProvider = { resumeAttempts++; true },
			)
		}

		assertEquals(StepOverflowRecoveryOutcome.RETRY, attempt())
		assertEquals(0, resumeAttempts)
		checkpointDurable = true
		assertEquals(StepOverflowRecoveryOutcome.RESUMED, attempt())
		assertEquals(1, resumeAttempts)
	}

	@Test
	fun `permission revoke or shutdown deadline supersedes overflow recovery`() = runTest {
		var canRecover = false
		var persistenceAttempts = 0
		var resumeAttempts = 0

		assertEquals(
			StepOverflowRecoveryOutcome.SUPERSEDED,
			recoverStepOverflowAttempt(
				canRecover = { canRecover },
				persistGapAndReset = { persistenceAttempts++; true },
				resumeProvider = { resumeAttempts++; true },
			),
		)
		assertEquals(0, persistenceAttempts)

		canRecover = true
		assertEquals(
			StepOverflowRecoveryOutcome.SUPERSEDED,
			recoverStepOverflowAttempt(
				canRecover = { canRecover },
				persistGapAndReset = {
					persistenceAttempts++
					canRecover = false
					true
				},
				resumeProvider = { resumeAttempts++; true },
			),
		)
		assertEquals(1, persistenceAttempts)
		assertEquals(0, resumeAttempts)
	}

	@Test
	fun `delayed pre-boundary callback keeps old authorization and sink after compatible refresh`() {
		val oldRegistration = registration(1L, "old", effectiveElapsedNanos = 100L)
		val newRegistration = registration(2L, "new", effectiveElapsedNanos = 300L)
		val oldSink = mockk<SourceEventSink>()
		val newSink = mockk<SourceEventSink>()
		val timeline = StepObservedAuthorizationTimeline(oldRegistration, oldSink)
		timeline.refresh(newRegistration, newSink)

		val delayed = requireNotNull(timeline.atObservedTime(250L))
		assertEquals(oldRegistration, delayed.registration)
		assertEquals(oldSink, delayed.sink)
		val current = requireNotNull(timeline.atObservedTime(300L))
		assertEquals(newRegistration, current.registration)
		assertEquals(newSink, current.sink)
		assertNull(timeline.atObservedTime(299L))
	}

	@Test
	fun `post-boundary callback latches until compatible begin publishes new authorization`() = runTest {
		val oldRegistration = registration(1L, "old", effectiveElapsedNanos = 100L)
		val newRegistration = registration(2L, "new", effectiveElapsedNanos = 300L)
		val oldSink = mockk<SourceEventSink>()
		val newSink = mockk<SourceEventSink>()
		val timeline = StepObservedAuthorizationTimeline(oldRegistration, oldSink)
		val pending = PendingStepAuthorizationRefresh(effectiveElapsedRealtimeNanos = 300L)

		val preBoundary = requireNotNull(stepCallbackAttributionReference(timeline, pending, 299L))
		val postBoundary = requireNotNull(stepCallbackAttributionReference(timeline, pending, 300L))
		assertEquals(oldRegistration, preBoundary.resolve().registration)
		assertSame(pending, postBoundary)
		assertFalse(pending.isResolved)

		timeline.refresh(newRegistration, newSink)
		pending.complete(StepCallbackAttribution(newRegistration, newSink))
		val resolved = postBoundary.resolve()
		assertEquals(newRegistration, resolved.registration)
		assertEquals(newSink, resolved.sink)
	}

	@Test
	fun `failed compatible begin releases latched callback to prior authorization`() = runTest {
		val oldRegistration = registration(1L, "old", effectiveElapsedNanos = 100L)
		val oldSink = mockk<SourceEventSink>()
		val timeline = StepObservedAuthorizationTimeline(oldRegistration, oldSink)
		val pending = PendingStepAuthorizationRefresh(effectiveElapsedRealtimeNanos = 300L)
		val latched = requireNotNull(stepCallbackAttributionReference(timeline, pending, 301L))

		pending.complete(StepCallbackAttribution(oldRegistration, oldSink))

		val resolved = latched.resolve()
		assertEquals(oldRegistration, resolved.registration)
		assertEquals(oldSink, resolved.sink)
	}

	@Test
	fun `retryable admission retains the same head until it is durable`() = runTest {
		val candidate = mockk<SourceEvidenceCandidate<*>>()
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(1L))
		requireNotNull(accumulator.accept("boot", 100L, 100L, 1L, 100L))
		val preview = requireNotNull(accumulator.preview("boot", 104L, 200L, 2L, 200L))
		var attempts = 0
		val sink = atomicSink { _, _ ->
			attempts++
			if (attempts < 4) {
				SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
			} else {
				SourceAdmissionHandoff.Durable(91L)
			}
		}

		val result = sink.admitStepHeadAtomicallyUntilResolved(
			candidate = candidate,
			checkpoint = mockk(),
			deadlineElapsedRealtimeNanos = { null },
			elapsedRealtimeNanos = { 0L },
			retryDelay = {},
		)

		assertEquals(4, attempts)
		val resolved = assertIs<StepAdmissionResolution.Resolved>(result)
		assertEquals(SourceAdmissionHandoff.Durable(91L), resolved.handoff)
		assertTrue(commitDurableStepPreview(accumulator, preview, resolved.handoff))
		assertFalse(commitDurableStepPreview(accumulator, preview, resolved.handoff))
		assertEquals(104L, accumulator.snapshot()?.cumulativeCount)
	}

	@Test
	fun `valid pre-revoke head settles durably after permission revoke before retry`() = runTest {
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(1L))
		requireNotNull(accumulator.accept("boot", 100L, 100L, 1L, 100L))
		val preview = requireNotNull(accumulator.preview("boot", 105L, 200L, 2L, 200L))
		var permissionGranted = true
		var attempts = 0
		val sink = atomicSink { _, _ ->
			attempts++
			if (attempts == 1) {
				permissionGranted = false
				SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
			} else {
				SourceAdmissionHandoff.Durable(1L)
			}
		}

		val resolution = assertIs<StepAdmissionResolution.Resolved>(
			sink.admitStepHeadAtomicallyUntilResolved(
				candidate = mockk<SourceEvidenceCandidate<*>>(),
				checkpoint = mockk(),
				deadlineElapsedRealtimeNanos = { null },
				elapsedRealtimeNanos = { 0L },
				retryDelay = {},
			),
		)

		assertFalse(permissionGranted)
		assertEquals(2, attempts)
		assertEquals(SourceAdmissionHandoff.Durable(1L), resolution.handoff)
		assertTrue(commitDurableStepPreview(accumulator, preview, resolution.handoff))
		assertEquals(105L, accumulator.snapshot()?.cumulativeCount)
	}

	@Test
	fun `terminal ingress rejection leaves the prior baseline unchanged`() = runTest {
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(1L))
		requireNotNull(accumulator.accept("boot", 200L, 100L, 1L, 100L))
		val priorBaseline = accumulator.snapshot()
		val preview = requireNotNull(accumulator.preview("boot", 207L, 200L, 2L, 200L))
		val sink = atomicSink { _, _ ->
			SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.INVALID_EVIDENCE)
		}

		val resolution = assertIs<StepAdmissionResolution.Resolved>(
			sink.admitStepHeadAtomicallyUntilResolved(
				candidate = mockk<SourceEvidenceCandidate<*>>(),
				checkpoint = mockk(),
				deadlineElapsedRealtimeNanos = { null },
				elapsedRealtimeNanos = { 0L },
				retryDelay = {},
			),
		)

		assertFalse(commitDurableStepPreview(accumulator, preview, resolution.handoff))
		assertEquals(priorBaseline, accumulator.snapshot())
		assertEquals(
			12L,
			requireNotNull(accumulator.preview("boot", 212L, 300L, 3L, 300L)).payload.deltaCount,
		)
	}

	@Test
	fun `retryable head remains unresolved when the quiesce deadline expires`() = runTest {
		val candidate = mockk<SourceEvidenceCandidate<*>>()
		var nowNanos = 0L
		var attempts = 0
		val sink = atomicSink { _, _ ->
			attempts++
			SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
		}

		val result = sink.admitStepHeadAtomicallyUntilResolved(
			candidate = candidate,
			checkpoint = mockk(),
			deadlineElapsedRealtimeNanos = { 15_000_000L },
			elapsedRealtimeNanos = { nowNanos },
			retryDelay = { delayMs -> nowNanos += delayMs * 1_000_000L },
		)

		assertEquals(2, attempts)
		assertIs<StepAdmissionResolution.DeadlineExceeded>(result)
	}

	@Test
	fun `process restart records a gap and never restores the cumulative baseline`() {
		val boundary = StepBaselineBoundary(7L)
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = RuntimeAdmissionSnapshot(7L, 42L, 0L, null, null, emptySet()),
			componentStateVersion = STEP_BASELINE_VERSION,
			componentPayload = StepBaseline(100L, 1_000L, 7L, boundary).encode(),
		)
		val recovery = recoverStepRuntimeState(
			saved = runtimeState(7L, 7L, checkpoint),
			currentRegistrationGeneration = 7L,
			reusedPhysicalRegistration = true,
		)

		assertNull(recovery.baseline)
		assertEquals(8L, recovery.callbackEntrySequence)
		assertEquals(1L, recovery.metrics?.failedAdmissionCount)
		assertEquals(8L, recovery.metrics?.unresolvedSequenceStart)
		assertEquals(8L, recovery.metrics?.unresolvedSequenceEndInclusive)
		assertEquals(
			setOf(RuntimeGapClassification.PROCESS_RESTARTED),
			recovery.metrics?.gapClassifications,
		)
		val firstAfterRestart = requireNotNull(
			StepWindowAccumulator(recovery.baseline, boundary)
				.accept("boot-1", 108L, 2_000L, 9L, 2_000L),
		)
		assertEquals(0L, firstAfterRestart.deltaCount)
		assertTrue(firstAfterRestart.baselineReset)
	}

	@Test
	fun `overflow checkpoint remains an explicit gap through process recovery`() {
		val boundary = StepBaselineBoundary(7L)
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = RuntimeAdmissionSnapshot(
				2L,
				42L,
				1L,
				3L,
				3L,
				setOf(RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW),
			),
			componentStateVersion = STEP_BASELINE_VERSION,
			componentPayload = StepBaseline(2L, 2L, 2L, boundary).encode(),
		)

		val recovery = recoverStepRuntimeState(
			saved = runtimeState(7L, 3L, checkpoint),
			currentRegistrationGeneration = 7L,
			reusedPhysicalRegistration = true,
		)

		assertNull(recovery.baseline)
		assertEquals(4L, recovery.callbackEntrySequence)
		assertEquals(2L, recovery.metrics?.failedAdmissionCount)
		assertEquals(3L, recovery.metrics?.unresolvedSequenceStart)
		assertEquals(4L, recovery.metrics?.unresolvedSequenceEndInclusive)
		assertEquals(
			setOf(
				RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
				RuntimeGapClassification.PROCESS_RESTARTED,
			),
			recovery.metrics?.gapClassifications,
		)
	}

	@Test
	fun `crash recovery gap begins after atomic durable provider high water`() {
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = RuntimeAdmissionSnapshot(
				lastDurablyAdmittedSequence = 12L,
				lastAdmissionOrdinal = 9L,
				failedAdmissionCount = 1L,
				unresolvedSequenceStart = 8L,
				unresolvedSequenceEndInclusive = 8L,
				gapClassifications = setOf(RuntimeGapClassification.ADMISSION_FAILED),
			),
			componentStateVersion = STEP_BASELINE_VERSION,
			componentPayload = ByteArray(0),
		)

		val recovery = recoverStepRuntimeState(
			saved = runtimeState(7L, 12L, checkpoint),
			currentRegistrationGeneration = 7L,
			reusedPhysicalRegistration = true,
		)

		assertEquals(13L, recovery.callbackEntrySequence)
		assertEquals(13L, recovery.metrics?.unresolvedSequenceEndInclusive)
		assertEquals(2L, recovery.metrics?.failedAdmissionCount)
	}

	@Test
	fun `process recovery also advances above unresolved callback high water`() {
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.TIMED_OUT,
			metrics = RuntimeAdmissionSnapshot(
				lastDurablyAdmittedSequence = 4L,
				lastAdmissionOrdinal = 9L,
				failedAdmissionCount = 1L,
				unresolvedSequenceStart = 8L,
				unresolvedSequenceEndInclusive = 12L,
				gapClassifications = setOf(RuntimeGapClassification.DRAIN_TIMED_OUT),
			),
			componentStateVersion = STEP_BASELINE_VERSION,
			componentPayload = ByteArray(0),
		)

		val recovery = recoverStepRuntimeState(
			saved = runtimeState(7L, 4L, checkpoint),
			currentRegistrationGeneration = 7L,
			reusedPhysicalRegistration = true,
		)

		assertEquals(13L, recovery.callbackEntrySequence)
		assertEquals(13L, recovery.metrics?.unresolvedSequenceEndInclusive)
	}

	@Test
	fun `step provider identity is stable across callback redelivery and process recreation`() {
		val beforeDeath = stepProviderDedupKey(
			bootClockDomainId = "android-boot-count:42",
			observedElapsedRealtimeNanos = 123_456_789L,
			cumulativeCount = 987L,
		)
		val recreated = stepProviderDedupKey(
			bootClockDomainId = "android-boot-count:42",
			observedElapsedRealtimeNanos = 123_456_789L,
			cumulativeCount = 987L,
		)

		assertEquals(beforeDeath, recreated)
	}

	@Test
	fun `step provider identity cannot collide when any source-native field differs`() {
		val canonical = stepProviderDedupKey("boot:1", 100L, 10L)
		val identities = setOf(
			canonical,
			stepProviderDedupKey("boot", 100L, 10L),
			stepProviderDedupKey("boot:1", 101L, 10L),
			stepProviderDedupKey("boot:1", 100L, 11L),
			stepProviderDedupKey("boot:10", 100L, 10L),
		)

		assertEquals(5, identities.size)
		assertNotEquals(canonical, stepProviderDedupKey("boot:10", 100L, 10L))
	}

	@Test
	fun `v3 checkpoint preserves full monotonic causal order through wall clock jumps`() {
		val receivedElapsedNanos = 123_456_789L
		val wallBeforeJump = 9_000_000L
		val wallAfterJump = 1_000L
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = RuntimeAdmissionSnapshot(null, null, 0L, null, null, emptySet()),
			componentStateVersion = STEP_BASELINE_VERSION,
			componentPayload = ByteArray(0),
			causalOrderElapsedRealtimeNanos = receivedElapsedNanos,
		)

		val before = stepCheckpointOrderMillis(receivedElapsedNanos)
		val after = stepCheckpointOrderMillis(receivedElapsedNanos)
		val restored = requireNotNull(decodeSensorRuntimeCheckpoint(
			runtimeState(7L, 0L, checkpoint),
			STEP_BASELINE_VERSION,
		))

		assertTrue(wallAfterJump < wallBeforeJump)
		assertEquals(123L, before)
		assertEquals(before, after)
		assertEquals(124L, stepCheckpointOrderMillis(124_000_000L))
		assertEquals(receivedElapsedNanos, restored.causalOrderElapsedRealtimeNanos)
	}

	@Test
	fun `fresh physical generation has an explicit reset without inheriting prior metrics`() {
		val priorBoundary = StepBaselineBoundary(7L)
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = RuntimeAdmissionSnapshot(3L, 9L, 0L, null, null, emptySet()),
			componentStateVersion = STEP_BASELINE_VERSION,
			componentPayload = StepBaseline(50L, 500L, 3L, priorBoundary).encode(),
		)
		val recovery = recoverStepRuntimeState(
			saved = runtimeState(7L, 3L, checkpoint),
			currentRegistrationGeneration = 8L,
			reusedPhysicalRegistration = false,
		)

		assertNull(recovery.metrics)
		assertEquals(0L, recovery.callbackEntrySequence)
		val first = requireNotNull(
			StepWindowAccumulator(recovery.baseline, StepBaselineBoundary(8L))
				.accept("boot-1", 55L, 600L, 1L, 600L),
		)
		assertEquals(0L, first.deltaCount)
		assertTrue(first.baselineReset)
		assertEquals(first.firstCumulativeCount, first.lastCumulativeCount)
	}

	private fun atomicSink(
		admitAtomically: suspend (
			SourceEvidenceCandidate<*>,
			SensorAdmissionCheckpoint,
		) -> SourceAdmissionHandoff,
	) = object : SourceEventSink {
		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			error("Steps must not fall back to non-atomic admission")

		override suspend fun admit(
			candidate: SourceEvidenceCandidate<*>,
			checkpoint: SensorAdmissionCheckpoint,
		): SourceAdmissionHandoff = admitAtomically(candidate, checkpoint)
	}

	private fun rawStep(
		sequence: Long,
		registration: SourceRegistration,
		sink: SourceEventSink,
	) = RawStep(
		cumulativeCount = sequence,
		observedElapsedNanos = sequence,
		receivedElapsedNanos = sequence,
		receivedWallTimeMs = sequence,
		providerSequence = sequence,
		attribution = ResolvedStepCallbackAttribution(
			StepCallbackAttribution(registration, sink),
		),
	)

	private fun registration(
		revision: Long,
		fingerprint: String,
		effectiveElapsedNanos: Long,
	): SourceRegistration {
		val authorization = mockk<SourceAuthorizationSnapshot>()
		every { authorization.authorizationRevision } returns revision
		every { authorization.authorizationFingerprint } returns fingerprint
		every { authorization.effectiveElapsedRealtimeNanos } returns effectiveElapsedNanos
		val registration = mockk<SourceRegistration>()
		every { registration.authorization } returns authorization
		return registration
	}

	private fun runtimeState(
		generation: Long,
		lastProviderSequence: Long,
		checkpoint: SensorRuntimeCheckpoint,
	) = SourceRuntimeStateEntity(
		sourceKind = SourceKind.STEPS.stableCode,
		ownerScope = "source-broker:steps",
		sourceInstanceId = "steps-1",
		clockDomainId = "boot-1",
		registrationGeneration = generation,
		lastProviderSequence = lastProviderSequence,
		lastAdmittedSourceSequence = checkpoint.metrics.lastDurablyAdmittedSequence,
		lastAdmissionOrdinal = checkpoint.metrics.lastAdmissionOrdinal,
		stateVersion = SENSOR_RUNTIME_CHECKPOINT_VERSION,
		payload = encodeSensorRuntimeCheckpoint(checkpoint),
		updatedAtMs = 10L,
	)
}
