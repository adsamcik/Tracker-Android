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
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class CellSourceRuntimeTest {
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
				"retiring:8",
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
		val result = retryCellAdmissionWithinBudget(
			prerequisiteAllows = { true },
			admit = {
				attempts++
				SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
			},
			retryDelaysMs = longArrayOf(0L, 0L, 0L),
			waitBeforeRetry = {},
		)

		assertEquals(4, attempts)
		assertEquals(
			SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE),
			result,
		)
	}

	@Test
	fun `live prerequisite is checked again before an admission retry`() = runTest {
		var allowed = true
		var gateReads = 0
		var attempts = 0
		val result = retryCellAdmissionWithinBudget(
			prerequisiteAllows = {
				gateReads++
				allowed
			},
			admit = {
				attempts++
				SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
			},
			retryDelaysMs = longArrayOf(0L),
			waitBeforeRetry = { allowed = false },
		)

		assertNull(result)
		assertEquals(1, attempts)
		assertEquals(2, gateReads)
	}

	@Test
	fun `stale and empty cached snapshots are rejected and exact cache replay stays gated`() {
		val now = 100L * NANOS_PER_MILLISECOND
		val stale = snapshot(providerTimestampNanos = 80L * NANOS_PER_MILLISECOND)
		val cached = snapshot(providerTimestampNanos = 95L * NANOS_PER_MILLISECOND)

		assertNull(qualifyCellSnapshot(stale, CellRefreshOutcome.CACHED, now, 10L))
		assertNull(
			qualifyCellSnapshot(
				CellBackendSnapshot(null, emptyList(), providerItemCount = 0),
				CellRefreshOutcome.CACHED,
				now,
				10L,
			),
		)
		val qualified = requireNotNull(
			qualifyCellSnapshot(cached, CellRefreshOutcome.CACHED, now, 10L),
		)
		val replay = requireNotNull(
			qualifyCellSnapshot(cached, CellRefreshOutcome.CACHED, now + 1L, 10L),
		)
		val gate = BoundedReplayIdentityGate()
		assertTrue(gate.shouldAdmit(qualified.snapshotIdentity))
		gate.record(qualified.snapshotIdentity)
		assertEquals(qualified.snapshotIdentity, replay.snapshotIdentity)
		assertFalse(gate.shouldAdmit(replay.snapshotIdentity))
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
	fun `revoke after callback entry blocks sequence allocation and WAL admission`() = runTest {
		val fixture = runtimeFixture(this)
		val admitted = mutableListOf<SourceEvidenceCandidate<*>>()
		fixture.start(SourceEventSink { candidate ->
			admitted += candidate
			SourceAdmissionHandoff.Durable(1L)
		})
		runCurrent()

		fixture.emit(emptyProviderDelivery())
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
		coEvery { fixture.registrations.allocateSequence(any(), any()) } returns 1L
		var attempts = 0
		fixture.start(SourceEventSink {
			attempts++
			SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_FULL)
		})
		fixture.emit(emptyProviderDelivery())
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
	fun `bounded callback overflow is visible in an orderly stop acknowledgement`() = runTest {
		val fixture = runtimeFixture(this)
		coEvery { fixture.registrations.allocateSequence(any(), any()) } returns 1L
		val firstAdmissionEntered = CompletableDeferred<Unit>()
		val releaseFirstAdmission = CompletableDeferred<Unit>()
		var admissions = 0
		fixture.start(SourceEventSink {
			admissions++
			if (admissions == 1) {
				firstAdmissionEntered.complete(Unit)
				releaseFirstAdmission.await()
			}
			SourceAdmissionHandoff.Durable(admissions.toLong())
		})
		runCurrent()

		fixture.emit(emptyProviderDelivery())
		runCurrent()
		firstAdmissionEntered.await()
		repeat(CELL_CALLBACK_BUFFER_CAPACITY) { fixture.emit(emptyProviderDelivery()) }
		fixture.emit(emptyProviderDelivery())
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
		coEvery { fixture.registrations.allocateSequence(any(), any()) } returns 1L
		var admitted: SourceEvidenceCandidate<*>? = null
		val sink = SourceEventSink { candidate ->
			admitted = candidate
			SourceAdmissionHandoff.Durable(7L)
		}
		fixture.start(sink)

		val applied = fixture.runtime.reconfigure(refreshedPlan, sink)
		assertTrue(applied is SourceApplyResult.Applied)
		verify(exactly = 1) { fixture.backend.start(any(), any()) }
		verify(exactly = 0) { fixture.backend.stop() }

		fixture.emit(emptyProviderDelivery())
		advanceUntilIdle()

		assertEquals(2L, admitted?.authorizationRevision)
		assertEquals(2L, admitted?.configRevision)
		fixture.runtime.close()
		verify(exactly = 1) { fixture.backend.stop() }
	}

	private suspend fun RuntimeFixture.start(sink: SourceEventSink) {
		assertTrue(runtime.start(plan, sink) is SourceStartResult.Started)
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

	private fun emptyProviderDelivery() = CellBackendSnapshot(
		subscriptionId = null,
		observations = emptyList(),
		providerItemCount = 0,
	)

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
				sourceInstanceId = "cell-1",
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
