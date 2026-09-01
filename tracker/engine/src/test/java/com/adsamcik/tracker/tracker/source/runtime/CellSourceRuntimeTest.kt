package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class CellSourceRuntimeTest {
	@Test
	fun `ambiguous retirement completion reuses exact token before replacement`() = runTest {
		val initialPlan = cellPlan(revision = 1L)
		val replacementPlan = cellPlan(
			revision = 2L,
			mode = CellMode.OBSERVE_AND_SPARSE_REFRESH,
		)
		val initial = registration(initialPlan, authorizationRevision = 1L, generation = 8L)
		val replacement = registration(replacementPlan, authorizationRevision = 2L, generation = 9L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, replacement))
		fixture.start(SourceEventSink { SourceAdmissionHandoff.Durable(1L) })
		var completionAttempt = 0
		var exactToken: SourceRegistrationRetirementToken? = null
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			val token = firstArg<SourceRegistrationRetirementToken>()
			if (token.registrationGeneration == initial.state.registrationGeneration) {
				if (exactToken == null) exactToken = token else assertEquals(exactToken, token)
				completionAttempt++
				if (completionAttempt == 1) {
					throw IllegalStateException("commit succeeded but result was lost")
				}
			}
			true
		}

		val firstStop = fixture.quiesce()
		assertEquals(SourceStopStatus.PROVIDER_FAILED, firstStop.status)

		val applied = fixture.runtime.reconfigure(
			replacementPlan,
			SourceEventSink { SourceAdmissionHandoff.Durable(2L) },
		)

		assertTrue(applied is SourceApplyResult.Applied)
		assertEquals(2, completionAttempt)
		coVerify(exactly = 1) {
			fixture.registrations.beginRetirement(initial, any(), any(), any())
		}
		fixture.runtime.close()
	}

	@Test
	fun `cancellation during drain settles retirement then propagates and permits replacement`() = runTest {
		val initialPlan = cellPlan(revision = 1L)
		val replacementPlan = cellPlan(
			revision = 2L,
			mode = CellMode.OBSERVE_AND_SPARSE_REFRESH,
		)
		val initial = registration(initialPlan, authorizationRevision = 1L, generation = 8L)
		val replacement = registration(replacementPlan, authorizationRevision = 2L, generation = 9L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, replacement))
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		fixture.start(cellCandidateSink {
			admissionEntered.complete(Unit)
			releaseAdmission.await()
			SourceAdmissionHandoff.Durable(1L)
		})
		fixture.emit(freshProviderDelivery())
		runCurrent()
		admissionEntered.await()
		val stopping = async { fixture.quiesce(deadlineOffsetNanos = 10_000_000_000L) }
		runCurrent()

		stopping.cancel()
		releaseAdmission.complete(Unit)
		runCurrent()
		assertFailsWith<CancellationException> { stopping.await() }

		val applied = fixture.runtime.reconfigure(
			replacementPlan,
			SourceEventSink { SourceAdmissionHandoff.Durable(2L) },
		)
		assertTrue(applied is SourceApplyResult.Applied)
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		fixture.runtime.close()
	}

	@Test
	fun `retirement begin timeout never removes provider and later retries immutable intent`() = runTest {
		val initialPlan = cellPlan(revision = 1L)
		val replacementPlan = cellPlan(
			revision = 2L,
			mode = CellMode.OBSERVE_AND_SPARSE_REFRESH,
		)
		val initial = registration(initialPlan, authorizationRevision = 1L, generation = 8L)
		val replacement = registration(replacementPlan, authorizationRevision = 2L, generation = 9L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, replacement))
		fixture.start(SourceEventSink { SourceAdmissionHandoff.Durable(1L) })
		var firstBoundary: Pair<Long, Long>? = null
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} coAnswers {
			firstBoundary = thirdArg<Long>() to arg<Long>(3)
			awaitCancellation()
		}

		val firstStop = fixture.quiesce(deadlineOffsetNanos = 10_000_000_000L)

		assertEquals(SourceStopStatus.PROVIDER_FAILED, firstStop.status)
		verify(exactly = 0) { fixture.backend.stop() }
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			if (firstArg<SourceRegistration>().state.registrationGeneration ==
				initial.state.registrationGeneration
			) {
				assertEquals(firstBoundary, thirdArg<Long>() to arg<Long>(3))
			}
			retirementToken(firstArg(), secondArg(), thirdArg(), arg(3))
		}

		val applied = fixture.runtime.reconfigure(
			replacementPlan,
			SourceEventSink { SourceAdmissionHandoff.Durable(2L) },
		)
		assertTrue(applied is SourceApplyResult.Applied)
		verify(exactly = 1) { fixture.backend.stop() }
		fixture.runtime.close()
	}

	@Test
	fun `acceptance cancellation cleans exact provider and still propagates`() = runTest {
		val plan = cellPlan()
		val fixture = runtimeFixture(
			this,
			plan,
			listOf(registration(plan, authorizationRevision = 1L).copy(requiresProviderAcceptance = true)),
		)
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } throws
			CancellationException("cancel acceptance")

		assertFailsWith<CancellationException> {
			fixture.runtime.start(fixture.plan, SourceEventSink { error("unused") })
		}

		verify(exactly = 1) { fixture.backend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `callbacks before durable acceptance are dropped and post acceptance callbacks are admitted`() = runTest {
		val plan = cellPlan()
		val fixture = runtimeFixture(
			this,
			plan,
			listOf(registration(plan, authorizationRevision = 1L).copy(requiresProviderAcceptance = true)),
		)
		var providerCallback: ((CellBackendSnapshot) -> Unit)? = null
		val admitted = mutableListOf<SourceEvidenceCandidate<*>>()
		every { fixture.backend.start(any(), any()) } answers {
			providerCallback = secondArg()
			true
		}
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } answers {
			requireNotNull(providerCallback)(freshProviderDelivery())
			null
		}
		fixture.start(cellCandidateSink { candidate ->
			admitted += candidate
			SourceAdmissionHandoff.Durable(1L)
		})
		advanceUntilIdle()
		assertTrue(admitted.isEmpty())

		requireNotNull(providerCallback)(freshProviderDelivery())
		advanceUntilIdle()
		assertEquals(1, admitted.size)
		fixture.runtime.close()
	}

	@Test
	fun `stale provider acceptance exception retires exact handle and fails closed`() = runTest {
		val plan = cellPlan()
		val fixture = runtimeFixture(
			this,
			plan,
			listOf(registration(plan, authorizationRevision = 1L).copy(requiresProviderAcceptance = true)),
		)
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } throws
			IllegalStateException("stale acceptance")

		assertTrue(fixture.runtime.start(plan, SourceEventSink { error("unused") }) is SourceStartResult.Failed)

		verify(exactly = 1) { fixture.backend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `fatal provider start retires possible exact handle before propagating`() = runTest {
		val fixture = runtimeFixture(this)
		every { fixture.backend.start(any(), any()) } throws AssertionError("fatal provider start")

		assertFailsWith<AssertionError> {
			fixture.runtime.start(fixture.plan, SourceEventSink { error("unused") })
		}

		coVerify(exactly = 1) {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		}
		verify(exactly = 1) { fixture.backend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `fatal acceptance error is never converted to retryable start failure`() = runTest {
		val plan = cellPlan()
		val fixture = runtimeFixture(
			this,
			plan,
			listOf(registration(plan, authorizationRevision = 1L).copy(requiresProviderAcceptance = true)),
		)
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } throws
			AssertionError("fatal acceptance")

		assertFailsWith<AssertionError> {
			fixture.runtime.start(fixture.plan, SourceEventSink { error("unused") })
		}
		coVerify(exactly = 1) {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		}
		verify(exactly = 1) { fixture.backend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `fatal callback reports sparse overflow and unprocessed tail exactly once`() = runTest {
		val fatal = AssertionError("fatal cell admission")
		val observedFailure = CompletableDeferred<Throwable>()
		val sourceScope = CoroutineScope(
			SupervisorJob() + StandardTestDispatcher(testScheduler) +
				CoroutineExceptionHandler { _, failure -> observedFailure.complete(failure) },
		)
		val fixture = runtimeFixture(sourceScope)
		fixture.start(cellCandidateSink { throw fatal })
		val callbackCount = CELL_CALLBACK_BUFFER_CAPACITY + 1
		repeat(callbackCount) { offset ->
			fixture.emit(snapshot(android.os.SystemClock.elapsedRealtimeNanos() + offset))
		}

		runCurrent()
		assertSame(fatal, observedFailure.await())
		fixture.emit(snapshot(android.os.SystemClock.elapsedRealtimeNanos() + callbackCount))
		val ack = fixture.quiesce()

		assertFalse(ack.appDrainComplete)
		assertEquals(callbackCount.toLong(), ack.callbackEntryBarrierSequence)
		assertEquals(callbackCount.toLong(), ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(callbackCount.toLong(), ack.unresolvedSequenceEndInclusive)
		verify(exactly = 1) { fixture.backend.stop() }
		sourceScope.cancel()
	}

	@Test
	fun `actor local cancellation fences callbacks and retires the provider`() = runTest {
		val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		val fixture = runtimeFixture(sourceScope)
		fixture.start(cellCandidateSink { throw CancellationException("local admission cancellation") })
		fixture.emit(snapshot(android.os.SystemClock.elapsedRealtimeNanos()))

		runCurrent()
		fixture.emit(snapshot(android.os.SystemClock.elapsedRealtimeNanos() + 1L))
		val ack = fixture.quiesce()

		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		verify(exactly = 1) { fixture.backend.stop() }
		sourceScope.cancel()
	}

	@Test
	fun `automatic actor cleanup never caches a failed provider retirement`() = runTest {
		val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		val fixture = runtimeFixture(sourceScope)
		every { fixture.backend.stop() } returnsMany listOf(false, true)
		fixture.start(cellCandidateSink { throw CancellationException("local admission cancellation") })
		fixture.emit(snapshot(android.os.SystemClock.elapsedRealtimeNanos()))

		runCurrent()
		val ack = fixture.quiesce()

		assertTrue(ack.appDrainComplete)
		assertEquals(RegistrationRemovalOutcome.REMOVED, ack.registrationRemovalOutcome)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		verify(exactly = 2) { fixture.backend.stop() }
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		sourceScope.cancel()
	}

	@Test
	fun `provider start failure persists retirement before exact handle cleanup`() = runTest {
		val fixture = runtimeFixture(this)
		val events = mutableListOf<String>()
		every { fixture.backend.start(any(), any()) } answers {
			events += "provider-start"
			false
		}
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			events += "retiring"
			retirementToken(firstArg(), secondArg(), thirdArg(), arg(3))
		}
		every { fixture.backend.stop() } answers {
			events += "provider-stop"
			true
		}
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			events += "retired"
			true
		}

		assertTrue(
			fixture.runtime.start(fixture.plan, SourceEventSink { error("unused") }) is
				SourceStartResult.Failed,
		)

		assertEquals(listOf("provider-start", "retiring", "provider-stop", "retired"), events)
		coVerify(exactly = 0) { fixture.registrations.markFailed(any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.registrations.markRetired(any(), any(), any(), any()) }
	}

	@Test
	fun `failed removal remains pending and replacement waits for exact retirement completion`() = runTest {
		val initialPlan = cellPlan(revision = 1L)
		val replacementPlan = cellPlan(revision = 2L, mode = CellMode.OBSERVE_AND_SPARSE_REFRESH)
		val initial = registration(initialPlan, authorizationRevision = 1L, generation = 8L)
		val replacement = registration(replacementPlan, authorizationRevision = 2L, generation = 9L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, replacement))
		fixture.start(SourceEventSink { SourceAdmissionHandoff.Durable(1L) })
		val initialToken = retirementToken(initial, "ORDERLY_STOP", 200L, 190L)
		val events = mutableListOf<String>()
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			events += "retiring:${firstArg<SourceRegistration>().state.registrationGeneration}"
			retirementToken(firstArg(), secondArg(), thirdArg(), arg(3))
		}
		var stopAttempt = 0
		every { fixture.backend.stop() } answers {
			stopAttempt++
			val removed = stopAttempt > 1
			events += "provider-stop:$removed"
			removed
		}
		var retirementPending = true
		coEvery { fixture.registrations.pendingRetirements(SourceKind.CELL) } answers {
			events += "pending:$retirementPending"
			if (retirementPending) listOf(initialToken) else emptyList()
		}
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			events += "retired:${firstArg<SourceRegistrationRetirementToken>().registrationGeneration}"
			retirementPending = false
			true
		}
		coEvery { fixture.registrations.begin(any(), any(), any(), any(), any()) } answers {
			events += "reserve:${replacement.state.registrationGeneration}"
			replacement
		}

		val firstStop = fixture.quiesce()
		assertEquals(RegistrationRemovalOutcome.FAILED, firstStop.registrationRemovalOutcome)
		assertEquals(SourceStopStatus.PROVIDER_FAILED, firstStop.status)
		assertEquals(listOf("retiring:8", "provider-stop:false"), events)

		assertTrue(fixture.runtime.reconfigure(
			replacementPlan,
			SourceEventSink { SourceAdmissionHandoff.Durable(2L) },
		) is SourceApplyResult.Applied)
		assertEquals(
			listOf(
				"retiring:8",
				"provider-stop:false",
				"provider-stop:true",
				"retired:8",
				"pending:false",
				"reserve:9",
			),
			events,
		)
		fixture.runtime.close()
	}

	@Test
	fun `retirement persistence failure leaves provider owned and prevents replacement`() = runTest {
		val fixture = runtimeFixture(this)
		fixture.start(SourceEventSink { SourceAdmissionHandoff.Durable(1L) })
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} throws IllegalStateException("injected database outage")

		val firstStop = fixture.quiesce()
		assertEquals(RegistrationRemovalOutcome.FAILED, firstStop.registrationRemovalOutcome)
		assertEquals(SourceStopStatus.PROVIDER_FAILED, firstStop.status)
		verify(exactly = 0) { fixture.backend.stop() }

		val replacement = fixture.runtime.reconfigure(
			cellPlan(revision = 2L),
			SourceEventSink { SourceAdmissionHandoff.Durable(2L) },
		)
		assertTrue(replacement is SourceApplyResult.Failed)
		coVerify(exactly = 0) {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		}
		verify(exactly = 1) { fixture.backend.start(any(), any()) }
		verify(exactly = 0) { fixture.backend.stop() }
	}

	@Test
	fun `prerequisite gate sees revoke without waiting for reconciliation`() {
		var state = cellState()
		val gate = CellPrerequisiteGate { state }

		assertTrue(gate.allows(cellPlan()))
		state = cellState(fineLocationPermission = false)
		assertFalse(gate.allows(cellPlan()))
	}

	@Test
	fun `bounded callback lane rejects overflow and preserves accepted FIFO`() = runTest {
		val lane = CellCallbackLane<Long>(capacity = 2)
		val received = mutableListOf<Long>()

		assertTrue(lane.offer(1L))
		assertTrue(lane.offer(2L))
		assertFalse(lane.offer(3L))
		lane.close()
		val consumer = launch { lane.consume(received::add) }
		consumer.join()

		assertEquals(listOf(1L, 2L), received)
	}

	@Test
	fun `retryable head stops after the finite retry budget`() = runTest {
		var attempts = 0
		val result = retryCellDeliveryAdmissionWithinBudget(
			prerequisiteAllows = { true },
			admit = {
				attempts++
				SourceDeliveryAdmissionHandoff.RetryableFailure(
					SourceAdmissionFailureCode.STORAGE_UNAVAILABLE,
				)
			},
			retryDelaysMs = longArrayOf(0L, 0L, 0L),
			waitBeforeRetry = {},
		)

		assertEquals(4, attempts)
		assertEquals(
			SourceDeliveryAdmissionHandoff.RetryableFailure(
				SourceAdmissionFailureCode.STORAGE_UNAVAILABLE,
			),
			result,
		)
	}

	@Test
	fun `live prerequisite is checked again before an admission retry`() = runTest {
		var allowed = true
		var gateReads = 0
		var attempts = 0
		val result = retryCellDeliveryAdmissionWithinBudget(
			prerequisiteAllows = {
				gateReads++
				allowed
			},
			admit = {
				attempts++
				SourceDeliveryAdmissionHandoff.RetryableFailure(
					SourceAdmissionFailureCode.STORAGE_UNAVAILABLE,
				)
			},
			retryDelaysMs = longArrayOf(0L),
			waitBeforeRetry = { allowed = false },
		)

		assertNull(result)
		assertEquals(1, attempts)
		assertEquals(2, gateReads)
	}

	@Test
	fun `a stop cutoff installed during retry prevents a second WAL admission`() = runTest {
		val fixture = runtimeFixture(this)
		val firstAttempt = CompletableDeferred<Unit>()
		var attempts = 0
		fixture.start(cellCandidateSink {
			attempts++
			firstAttempt.complete(Unit)
			if (attempts == 1) {
				SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
			} else {
				SourceAdmissionHandoff.Durable(2L)
			}
		})
		ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
		fixture.emit(freshProviderDelivery())
		runCurrent()
		firstAttempt.await()

		val now = android.os.SystemClock.elapsedRealtimeNanos()
		val stopping = async {
			fixture.runtime.quiesce(
				SessionCutoff(
					logicalTrackingId = "cell-cutoff-test",
					elapsedRealtimeNanos = 0L,
					wallTimeMs = 1L,
					deadlineElapsedRealtimeNanos = now + 10_000_000_000L,
				),
			)
		}
		advanceUntilIdle()

		assertEquals(1, attempts)
		assertEquals(1L, stopping.await().failedAdmissionCount)
	}

	@Test
	fun `stale callback observations are rejected`() {
		val now = 100L * NANOS_PER_MILLISECOND
		val stale = snapshot(providerTimestampNanos = 80L * NANOS_PER_MILLISECOND)

		assertNull(qualifyCellSnapshot(stale, CellRefreshOutcome.CALLBACK, now, 10L))
	}

	@Test
	fun `cached and operational outcomes never qualify as durable evidence`() {
		val now = 100L * NANOS_PER_MILLISECOND
		val fresh = snapshot(providerTimestampNanos = 95L * NANOS_PER_MILLISECOND)

		CellRefreshOutcome.entries.filterNot { it == CellRefreshOutcome.CALLBACK }.forEach { outcome ->
			assertNull(qualifyCellSnapshot(fresh, outcome, now, 10L), outcome.name)
		}
	}

	@Test
	fun `repeated empty callbacks remain deferred`() {
		val now = 100L * NANOS_PER_MILLISECOND
		val empty = emptyProviderDelivery()

		assertNull(qualifyCellSnapshot(empty, CellRefreshOutcome.CALLBACK, now, 10L))
		assertNull(qualifyCellSnapshot(empty, CellRefreshOutcome.CALLBACK, now + 1L, 10L))
	}

	@Test
	fun `cell delivery identity is stable across order and raw provider identifiers`() {
		val firstQualified = qualifiedCellSnapshot("raw-cell-a", "raw-cell-b")
		val renamedAndReordered = qualifiedCellSnapshot(
			"different-raw-a",
			"different-raw-b",
			reverseProviderOrder = true,
		)
		val first = cellProviderDeliveryIdentity(
			"boot-1",
			firstQualified.observations,
		)
		val sameProviderFact = cellProviderDeliveryIdentity(
			"boot-1",
			renamedAndReordered.observations,
		)

		assertEquals(first, sameProviderFact)
		assertEquals(firstQualified.snapshotIdentity, renamedAndReordered.snapshotIdentity)
		assertTrue(first.value.matches(Regex("[0-9a-f]{64}")))
		assertFalse(firstQualified.observations.toString().contains("raw-cell-a"))
		assertFalse(renamedAndReordered.observations.toString().contains("different-raw-a"))
	}

	@Test
	fun `cell delivery identity changes with provider fact boundaries`() {
		val qualified = qualifiedCellSnapshot("raw-cell-a", "raw-cell-b")
		val first = cellProviderDeliveryIdentity(
			"boot-1",
			qualified.observations,
		)
		val changedProviderTime = qualified.observations.mapIndexed { index, observation ->
			if (index == 0) {
				observation.copy(providerTimestampNanos = requireNotNull(
					observation.providerTimestampNanos,
				) + 1L)
			} else observation
		}

		assertFalse(
			first == cellProviderDeliveryIdentity(
				"boot-2",
				qualified.observations,
			),
		)
		assertFalse(
			first == cellProviderDeliveryIdentity(
				"boot-1",
				changedProviderTime,
			),
		)
	}

	@Test
	fun `cell delivery identity includes minimized content and rejects raw identity`() {
		val qualified = qualifiedCellSnapshot("raw-cell-a", "raw-cell-b")
		val first = cellProviderDeliveryIdentity(
			"boot-1",
			qualified.observations,
		)
		val changedSignal = qualified.observations.mapIndexed { index, observation ->
			if (index == 0) observation.copy(signalLevelDbm = -42) else observation
		}

		assertFalse(
			first == cellProviderDeliveryIdentity(
				"boot-1",
				changedSignal,
			),
		)
		assertFailsWith<IllegalArgumentException> {
			cellProviderDeliveryIdentity(
				"boot-1",
				qualified.observations.map { it.copy(identifierToken = "raw-cell") },
			)
		}
	}

	@Test
	fun `same cell provider delivery is duplicate safe across runtime envelopes`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
		val activePlan = cellPlan()
		val first = runtimeFixture(
			this,
			activePlan,
			listOf(registration(activePlan, 1L, generation = 9L, sourceInstanceId = "cell-before")),
		)
		val afterRestart = runtimeFixture(
			this,
			activePlan,
			listOf(registration(activePlan, 2L, generation = 10L, sourceInstanceId = "cell-after")),
		)
		val deliveries = mutableListOf<SourceDeliveryCandidate>()
		val sink = cellDeliverySink { delivery ->
			deliveries += delivery
			if (deliveries.size == 1) {
				SourceDeliveryAdmissionHandoff.Durable(listOf(41L))
			} else {
				SourceDeliveryAdmissionHandoff.Duplicate(listOf(41L))
			}
		}
		val providerTime = android.os.SystemClock.elapsedRealtimeNanos()
		val snapshot = snapshot(providerTime)

		first.start(sink)
		first.emit(snapshot)
		advanceUntilIdle()
		val firstAck = first.quiesce()
		ShadowSystemClock.advanceBy(Duration.ofMillis(10))
		afterRestart.start(sink)
		afterRestart.emit(snapshot)
		advanceUntilIdle()
		val duplicateAck = afterRestart.quiesce()

		assertEquals(2, deliveries.size)
		assertEquals(deliveries[0].identity, deliveries[1].identity)
		val units = deliveries.map { it.units.single() }
		assertTrue(units.all { it.unitIndex == 0 })
		assertTrue(units.all { it.observedIntervalStartElapsedRealtimeNanos == providerTime })
		val candidates = units.map { it.evidence }
		assertTrue(candidates.all { it.providerDedupKey == null })
		assertTrue(candidates.all { it.sourceSequence == 0L })
		assertFalse(candidates[0].sourceInstanceId == candidates[1].sourceInstanceId)
		assertFalse(candidates[0].registrationGeneration == candidates[1].registrationGeneration)
		assertFalse(candidates[0].authorizationRevision == candidates[1].authorizationRevision)
		assertFalse(
			candidates[0].receivedElapsedRealtimeNanos ==
				candidates[1].receivedElapsedRealtimeNanos,
		)
		assertEquals(41L, firstAck.lastAdmissionOrdinal)
		assertEquals(41L, duplicateAck.lastAdmissionOrdinal)
		assertEquals(1L, firstAck.lastDurablyAdmittedSequence)
		assertEquals(1L, duplicateAck.lastDurablyAdmittedSequence)
		coVerify(exactly = 0) { first.registrations.allocateSequence(any(), any()) }
		coVerify(exactly = 0) { afterRestart.registrations.allocateSequence(any(), any()) }
	}

	@Test
	fun `older duplicate ordinal advances callback resolution without regressing stop ack`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
		val fixture = runtimeFixture(this)
		var admissionCount = 0
		fixture.start(cellDeliverySink {
			admissionCount++
			when (admissionCount) {
				1 -> SourceDeliveryAdmissionHandoff.Durable(listOf(100L))
				2 -> SourceDeliveryAdmissionHandoff.Duplicate(listOf(10L))
				else -> error("Unexpected Cell admission")
			}
		})

		fixture.emit(freshProviderDelivery())
		advanceUntilIdle()
		ShadowSystemClock.advanceBy(Duration.ofMillis(1))
		fixture.emit(freshProviderDelivery())
		advanceUntilIdle()
		val ack = fixture.quiesce()

		assertEquals(2, admissionCount)
		assertEquals(2L, ack.lastDurablyAdmittedSequence)
		assertEquals(100L, ack.lastAdmissionOrdinal)
	}

	@Test
	fun `timeout accounting covers only callback tail not internal controls`() {
		assertEquals(4L..9L, unprocessedCellCallbackRange(3L, 9L))
		assertNull(unprocessedCellCallbackRange(9L, 9L))
	}

	@Test
	fun `cell capability makes no wake reliable cadence claim`() {
		val capability = cellCapabilities(cellState(refreshApiAvailable = true))

		assertTrue(capability.available)
		assertNull(capability.minimumDelayMs)
		assertFalse(capability.batchingSupported)
		assertFalse(capability.flushSupported)

		val revoked = cellCapabilities(cellState(readPhoneStatePermission = false))
		assertFalse(revoked.available)
		assertTrue(SourceDegradedReason.PERMISSION_MISSING in revoked.degradedReasons)
	}

	@Test
	fun `policy only revision is physically compatible but provider changes are not`() {
		val active = cellPlan(revision = 1L, maximumAgeMs = 60_000L)
		val policyOnly = cellPlan(revision = 2L, maximumAgeMs = 10_000L)
		val providerChange = cellPlan(
			revision = 2L,
			maximumAgeMs = 10_000L,
			mode = CellMode.OBSERVE_AND_SPARSE_REFRESH,
		)

		assertTrue(cellPlansSharePhysicalRegistration(active, policyOnly))
		assertFalse(cellPlansSharePhysicalRegistration(active, providerChange))
		assertFalse(cellPlansSharePhysicalRegistration(active, policyOnly.copy(mode = CellMode.OFF)))
	}

	@Test
	fun `revoke after callback entry blocks atomic WAL admission`() = runTest {
		val fixture = runtimeFixture(this)
		val admitted = mutableListOf<SourceEvidenceCandidate<*>>()
		fixture.start(cellCandidateSink { candidate ->
			admitted += candidate
			SourceAdmissionHandoff.Durable(1L)
		})
		runCurrent()

		fixture.emit(freshProviderDelivery())
		fixture.state = fixture.state.copy(fineLocationPermission = false)
		advanceUntilIdle()

		coVerify(exactly = 0) { fixture.registrations.allocateSequence(any(), any()) }
		assertTrue(admitted.isEmpty())
		val ack = fixture.quiesce()
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
	}

	@Test
	fun `retry exhaustion is unresolved but the callback actor drains`() = runTest {
		val fixture = runtimeFixture(this)
		var attempts = 0
		fixture.start(cellCandidateSink {
			attempts++
			SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_FULL)
		})
		fixture.emit(freshProviderDelivery())
		advanceUntilIdle()

		val ack = fixture.quiesce()

		assertEquals(4, attempts)
		assertTrue(ack.appDrainComplete)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
	}

	@Test
	fun `durable session cutoff fails Cell atomic delivery closed`() = runTest {
		val fixture = runtimeFixture(this)
		fixture.start(cellDeliverySink { SourceDeliveryAdmissionHandoff.SessionCutoff(8L) })
		fixture.emit(freshProviderDelivery())
		advanceUntilIdle()

		val ack = fixture.quiesce()

		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		assertNull(ack.lastDurablyAdmittedSequence)
	}

	@Test
	fun `bounded callback overflow is visible in an orderly stop acknowledgement`() = runTest {
		val fixture = runtimeFixture(this)
		val firstAdmissionEntered = CompletableDeferred<Unit>()
		val releaseFirstAdmission = CompletableDeferred<Unit>()
		var admissions = 0
		fixture.start(cellCandidateSink {
			admissions++
			if (admissions == 1) {
				firstAdmissionEntered.complete(Unit)
				releaseFirstAdmission.await()
			}
			SourceAdmissionHandoff.Durable(admissions.toLong())
		})
		runCurrent()

		fixture.emit(freshProviderDelivery())
		runCurrent()
		firstAdmissionEntered.await()
		repeat(CELL_CALLBACK_BUFFER_CAPACITY) { fixture.emit(freshProviderDelivery()) }
		fixture.emit(freshProviderDelivery())
		releaseFirstAdmission.complete(Unit)
		advanceUntilIdle()

		val ack = fixture.quiesce()

		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(66L, ack.unresolvedSequenceStart)
		assertEquals(66L, ack.unresolvedSequenceEndInclusive)
	}

	@Test
	fun `compatible revision refreshes authorization without restarting Telephony provider`() = runTest {
		val initialPlan = cellPlan(revision = 1L, maximumAgeMs = 60_000L)
		val refreshedPlan = cellPlan(revision = 2L, maximumAgeMs = 10_000L)
		val initial = registration(initialPlan, authorizationRevision = 1L)
		val refreshed = registration(refreshedPlan, authorizationRevision = 2L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, refreshed))
		var admitted: SourceEvidenceCandidate<*>? = null
		val sink = cellCandidateSink { candidate ->
			admitted = candidate
			SourceAdmissionHandoff.Durable(7L)
		}
		fixture.start(sink)

		val applied = fixture.runtime.reconfigure(refreshedPlan, sink)
		assertTrue(applied is SourceApplyResult.Applied)
		verify(exactly = 1) { fixture.backend.start(any(), any()) }
		verify(exactly = 0) { fixture.backend.stop() }

		fixture.emit(freshProviderDelivery())
		advanceUntilIdle()

		assertEquals(2L, admitted?.authorizationRevision)
		assertEquals(2L, admitted?.configRevision)
		fixture.runtime.close()
		verify(exactly = 1) { fixture.backend.stop() }
	}

	@Test
	fun `claim transfer fences stale shutdown across compatible refresh and replacement`() = runTest {
		val initialPlan = cellPlan(revision = 1L, maximumAgeMs = 60_000L)
		val refreshedPlan = cellPlan(revision = 2L, maximumAgeMs = 10_000L)
		val replacementPlan = cellPlan(
			revision = 3L,
			maximumAgeMs = 10_000L,
			mode = CellMode.OBSERVE_AND_SPARSE_REFRESH,
		)
		val initial = registration(initialPlan, authorizationRevision = 1L, generation = 9L)
		val refreshed = registration(refreshedPlan, authorizationRevision = 2L, generation = 9L)
		val replacement = registration(replacementPlan, authorizationRevision = 3L, generation = 10L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, replacement))
		coEvery {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns refreshed
		val firstClaim = runtimeClaim(SourceKind.CELL, "cell-start")
		val refreshClaim = runtimeClaim(SourceKind.CELL, "cell-refresh")
		val replacementClaim = runtimeClaim(SourceKind.CELL, "cell-replacement")
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(firstClaim, initialPlan, sink) is SourceStartResult.Started)
		assertTrue(fixture.runtime.reconfigure(refreshClaim, refreshedPlan, sink) is SourceApplyResult.Applied)
		assertEquals(
			OwnedSourceShutdown.NotOwned,
			fixture.runtime.shutdownIfOwned(firstClaim, cellCutoff()),
		)
		verify(exactly = 0) { fixture.backend.stop() }

		val replacementResult = fixture.runtime.reconfigure(replacementClaim, replacementPlan, sink)
		assertTrue(replacementResult is SourceApplyResult.Applied)
		assertEquals(9L, (replacementResult as SourceApplyResult.Applied).stopAck?.registrationGeneration)
		verify(exactly = 1) { fixture.backend.stop() }
		assertEquals(
			OwnedSourceShutdown.NotOwned,
			fixture.runtime.shutdownIfOwned(refreshClaim, cellCutoff()),
		)
		verify(exactly = 1) { fixture.backend.stop() }

		val released = fixture.runtime.shutdownIfOwned(replacementClaim, cellCutoff()) as
			OwnedSourceShutdown.Released
		assertEquals(10L, released.provider?.registrationGeneration)
		verify(exactly = 2) { fixture.backend.stop() }
	}

	@Test
	fun `failed provider publication retains only its exact claim for cleanup`() = runTest {
		val fixture = runtimeFixture(this)
		every { fixture.backend.start(any(), any()) } throws IllegalStateException("published then failed")
		every { fixture.backend.stop() } returnsMany listOf(false, true)
		val owningClaim = runtimeClaim(SourceKind.CELL, "cell-failed-publication")
		val staleClaim = runtimeClaim(SourceKind.CELL, "cell-stale-cleanup")
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(owningClaim, fixture.plan, sink) is SourceStartResult.Failed)
		verify(exactly = 1) { fixture.backend.stop() }
		assertEquals(
			OwnedSourceShutdown.NotOwned,
			fixture.runtime.shutdownIfOwned(staleClaim, cellCutoff()),
		)
		verify(exactly = 1) { fixture.backend.stop() }

		val released = fixture.runtime.shutdownIfOwned(owningClaim, cellCutoff()) as
			OwnedSourceShutdown.Released
		assertEquals(9L, released.provider?.registrationGeneration)
		verify(exactly = 2) { fixture.backend.stop() }
	}

	@Test
	fun `compatible refresh fences callbacks until the new immutable context is installed`() = runTest {
		val initialPlan = cellPlan(revision = 1L, maximumAgeMs = 60_000L)
		val refreshedPlan = cellPlan(revision = 2L, maximumAgeMs = 10_000L)
		val initial = registration(initialPlan, authorizationRevision = 1L)
		val refreshed = registration(refreshedPlan, authorizationRevision = 2L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, refreshed))
		val oldAdmissions = mutableListOf<SourceEvidenceCandidate<*>>()
		val newAdmissions = mutableListOf<SourceEvidenceCandidate<*>>()
		fixture.start(cellCandidateSink { candidate ->
			oldAdmissions += candidate
			SourceAdmissionHandoff.Durable(1L)
		})
		val refreshEntered = CompletableDeferred<Unit>()
		val releaseRefresh = CompletableDeferred<Unit>()
		coEvery {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} coAnswers {
			refreshEntered.complete(Unit)
			releaseRefresh.await()
			refreshed
		}

		val reconfiguring = async {
			fixture.runtime.reconfigure(
				refreshedPlan,
				cellCandidateSink { candidate ->
					newAdmissions += candidate
					SourceAdmissionHandoff.Durable(2L)
				},
			)
		}
		runCurrent()
		refreshEntered.await()
		fixture.emit(freshProviderDelivery())
		runCurrent()
		assertTrue(oldAdmissions.isEmpty())
		assertTrue(newAdmissions.isEmpty())

		releaseRefresh.complete(Unit)
		assertTrue(reconfiguring.await() is SourceApplyResult.Applied)
		fixture.emit(freshProviderDelivery())
		advanceUntilIdle()

		assertTrue(oldAdmissions.isEmpty())
		assertEquals(2L, newAdmissions.single().authorizationRevision)
		assertEquals(2L, newAdmissions.single().configRevision)
		fixture.runtime.close()
	}

	private suspend fun RuntimeFixture.start(sink: SourceEventSink) {
		assertTrue(runtime.start(plan, sink) is SourceStartResult.Started)
	}

	private fun cellCandidateSink(
		onCandidate: suspend (SourceEvidenceCandidate<*>) -> SourceAdmissionHandoff,
	): SourceEventSink = cellDeliverySink { delivery ->
		when (val handoff = onCandidate(delivery.units.single().evidence)) {
			is SourceAdmissionHandoff.Durable ->
				SourceDeliveryAdmissionHandoff.Durable(listOf(handoff.admissionOrdinal))
			is SourceAdmissionHandoff.Duplicate ->
				SourceDeliveryAdmissionHandoff.Duplicate(listOf(handoff.existingAdmissionOrdinal))
			is SourceAdmissionHandoff.RetryableFailure ->
				SourceDeliveryAdmissionHandoff.RetryableFailure(handoff.code)
			is SourceAdmissionHandoff.TerminalFailure ->
				SourceDeliveryAdmissionHandoff.TerminalFailure(handoff.code)
		}
	}

	private fun cellDeliverySink(
		onDelivery: suspend (SourceDeliveryCandidate) -> SourceDeliveryAdmissionHandoff,
	): SourceEventSink = object : SourceEventSink {
		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			throw AssertionError("Cell must not call legacy single-evidence admission")

		override suspend fun admit(delivery: SourceDeliveryCandidate): SourceDeliveryAdmissionHandoff =
			onDelivery(delivery)
	}

	private data class RuntimeFixture(
		val runtime: CellSourceRuntime,
		val registrations: SourceRegistrationRepository,
		val backend: AndroidCellSourceBackend,
		val plan: CellPlan,
		private val callback: () -> ((CellBackendSnapshot) -> Unit),
		var state: CellDeviceState,
	) {
		fun emit(snapshot: CellBackendSnapshot) = callback()(snapshot)

		suspend fun quiesce(deadlineOffsetNanos: Long = 1_000_000_000L): SourceStopAck {
			val now = android.os.SystemClock.elapsedRealtimeNanos()
			return runtime.quiesce(
				SessionCutoff(
					logicalTrackingId = "cell-test",
					elapsedRealtimeNanos = Long.MAX_VALUE,
					wallTimeMs = 1L,
					deadlineElapsedRealtimeNanos = now + deadlineOffsetNanos,
				),
			)
		}
	}

	private fun runtimeFixture(
		scope: kotlinx.coroutines.CoroutineScope,
		runtimePlan: CellPlan = cellPlan(),
		registrationsToReturn: List<SourceRegistration> = listOf(registration(runtimePlan, 1L)),
	): RuntimeFixture {
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val backend = mockk<AndroidCellSourceBackend>(relaxed = true)
		val stateProvider = mockk<AndroidConnectivityDeviceStateProvider>()
		val wakeups = mockk<CoalescingSourceWakeupScheduler>(relaxed = true)
		var state = cellState()
		var callback: ((CellBackendSnapshot) -> Unit)? = null
		every { stateProvider.cell() } answers { state }
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returnsMany registrationsToReturn
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns registrationsToReturn.last()
		coEvery { registrations.pendingRetirements(SourceKind.CELL) } returns emptyList()
		coEvery {
			registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			retirementToken(firstArg(), secondArg(), thirdArg(), arg(3))
		}
		coEvery { registrations.completeRetirement(any()) } returns true
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		every { backend.start(any(), any()) } answers {
			callback = secondArg()
			true
		}
		every { backend.stop() } returns true
		every { backend.hasRetainedRegistrations } returns false
		val runtime = CellSourceRuntime(scope, registrations, backend, stateProvider, wakeups)
		return RuntimeFixture(runtime, registrations, backend, runtimePlan, { requireNotNull(callback) }, state)
			.also { fixture -> every { stateProvider.cell() } answers { fixture.state } }
	}

	private fun snapshot(providerTimestampNanos: Long) = CellBackendSnapshot(
		subscriptionId = 1,
		observations = listOf(
			CellBackendObservation(
				identity = "ephemeral-cell",
				radioType = "LTE",
				registered = true,
				signalLevelDbm = -91,
				providerTimestampNanos = providerTimestampNanos,
			),
		),
	)

	private fun qualifiedCellSnapshot(
		lteIdentity: String,
		nrIdentity: String,
		reverseProviderOrder: Boolean = false,
	): QualifiedCellSnapshot {
		val observations = listOf(
			CellBackendObservation(
				lteIdentity,
				"LTE",
				true,
				-91,
				90L * NANOS_PER_MILLISECOND,
			),
			CellBackendObservation(
				nrIdentity,
				"NR",
				false,
				-105,
				80L * NANOS_PER_MILLISECOND,
			),
		).let { if (reverseProviderOrder) it.reversed() else it }
		return requireNotNull(
			qualifyCellSnapshot(
				CellBackendSnapshot(1, observations),
				CellRefreshOutcome.CALLBACK,
				receivedElapsedNanos = 100L * NANOS_PER_MILLISECOND,
				maximumAcceptableAgeMs = 100L,
			),
		)
	}

	private fun emptyProviderDelivery() = CellBackendSnapshot(
		subscriptionId = null,
		observations = emptyList(),
		providerItemCount = 0,
	)

	private fun freshProviderDelivery(): CellBackendSnapshot {
		if (android.os.SystemClock.elapsedRealtimeNanos() <= 0L) {
			ShadowSystemClock.advanceBy(Duration.ofMillis(1))
		}
		return snapshot(android.os.SystemClock.elapsedRealtimeNanos())
	}

	private fun cellPlan(
		revision: Long = 1L,
		maximumAgeMs: Long = 60_000L,
		mode: CellMode = CellMode.OBSERVE_CHANGES,
	) = CellPlan(
		revision = revision,
		mode = mode,
		minimumRefreshAttemptIntervalMs = 120_000L,
		maximumAcceptableCachedAgeMs = maximumAgeMs,
		subscriptionIds = emptySet(),
		backoff = RetryBackoff(30_000L, 1_800_000L),
	)

	private fun runtimeClaim(source: SourceKind, actionId: String) = SourceRuntimeClaim(
		source = source,
		actionId = actionId,
		attemptCount = 1,
		leaseGeneration = 1L,
		logicalTrackingId = "cell-test",
		serviceRunId = "run-1",
	)

	private fun cellCutoff() = SessionCutoff(
		logicalTrackingId = "cell-test",
		elapsedRealtimeNanos = Long.MAX_VALUE,
		wallTimeMs = 1L,
		deadlineElapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() + 1_000_000_000L,
	)

	private fun cellState(
		radioFeatureAvailable: Boolean = true,
		fineLocationPermission: Boolean = true,
		readPhoneStatePermission: Boolean = true,
		refreshApiAvailable: Boolean = true,
	) = CellDeviceState(
		radioFeatureAvailable,
		fineLocationPermission,
		readPhoneStatePermission,
		refreshApiAvailable,
	)

	private fun registration(
		plan: CellPlan,
		authorizationRevision: Long,
		generation: Long = 9L,
		sourceInstanceId: String = "cell-1",
	): SourceRegistration {
		val demand = SourceDemandEntity(
			demandId = "cell-demand-$authorizationRevision",
			consumerId = "session:cell-test",
			sourceKind = SourceKind.CELL.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = "cell-test",
			serviceRunId = "run-1",
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			sourcePolicyRevision = authorizationRevision,
			consentEpoch = 1L,
			persistenceEligible = true,
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
				SourceKind.CELL.stableCode,
				registrationGeneration = generation,
				authorizationRevision = authorizationRevision,
				demands = listOf(demand),
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = authorizationRevision,
				effectiveWallTimeMs = 1L,
			).toAuthorizationSnapshotOrNull(),
		)
		return SourceRegistration(
			ownerScope = "source-broker:${SourceKind.CELL.stableCode}",
			state = SourceRegistrationStateEntity(
				sourceKind = SourceKind.CELL.stableCode,
				ownerScope = "source-broker:${SourceKind.CELL.stableCode}",
				sourceInstanceId = sourceInstanceId,
				clockDomainId = "boot-1",
				registrationGeneration = generation,
				nextSequence = 0L,
				appliedRevision = plan.revision,
				collectedDataEpoch = 1L,
				updatedAtMs = 1L,
			),
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint(),
			authorization = authorization,
			requiresProviderAcceptance = false,
		)
	}

	private fun retirementToken(
		registration: SourceRegistration,
		reason: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
	) = SourceRegistrationRetirementToken(
		source = SourceKind.CELL,
		sourceInstanceId = SourceInstanceId(registration.state.sourceInstanceId),
		registrationGeneration = registration.state.registrationGeneration,
		processIncarnationId = "cell-runtime-test-process",
		retiredAtMs = retiredAtMs,
		retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
		reason = reason,
	)

	private companion object {
		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}
