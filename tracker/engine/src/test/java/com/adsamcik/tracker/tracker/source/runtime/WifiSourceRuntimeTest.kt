package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiSourceRuntimeTest {
	@Test
	fun `wifi capability makes no wake reliable cadence claim`() {
		val capability = wifiCapabilities(readyState())

		assertTrue(capability.available)
		assertNull(capability.minimumDelayMs)
		assertFalse(capability.batchingSupported)
		assertFalse(capability.flushSupported)

		val revoked = wifiCapabilities(readyState().copy(fineLocationPermission = false))
		assertFalse(revoked.available)
		assertTrue(SourceDegradedReason.PERMISSION_MISSING in revoked.degradedReasons)
	}

	@Test
	fun `callback prerequisite gate always reads current permission and capability state`() {
		var state = readyState()
		val gate = WifiCallbackPrerequisiteGate { state }
		val plan = plan(1L)

		assertTrue(gate.allows(plan))
		state = state.copy(fineLocationPermission = false)
		assertFalse(gate.allows(plan))
		state = readyState().copy(locationServicesEnabled = false)
		assertFalse(gate.allows(plan))
		state = readyState().copy(wifiFeatureAvailable = false)
		assertFalse(gate.allows(plan))
	}

	@Test
	fun `callback lane has fixed capacity and reports overflow without reordering accepted callbacks`() = runTest {
		val lane = WifiCallbackLane<Long>(capacity = 2)
		val received = mutableListOf<Long>()

		assertEquals(WifiLaneOffer.ACCEPTED, lane.offer(1L))
		assertEquals(WifiLaneOffer.ACCEPTED, lane.offer(2L))
		assertEquals(WifiLaneOffer.CAPACITY_EXHAUSTED, lane.offer(3L))
		lane.close()
		lane.consume { received += it }

		assertEquals(listOf(1L, 2L), received)
	}

	@Test
	fun `retryable admission retains the FIFO head until durable`() = runTest {
		val fixture = runtimeFixture(this)
		val frequencies = mutableListOf<Int>()
		var firstAttempts = 0
		val sink = wifiCandidateSink { candidate ->
			val frequency = (candidate.payload as WifiResultSnapshotPayload).accessPoints.single().frequencyMhz
			frequencies += frequency
			if (frequency == 2_412 && firstAttempts++ < 2) {
				SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
			} else {
				SourceAdmissionHandoff.Durable(frequencies.size.toLong())
			}
		}
		fixture.start(sink)

		fixture.emit(resultEvent(frequencyMhz = 2_412, receivedNanos = 10_000_000L))
		fixture.emit(resultEvent(frequencyMhz = 5_180, receivedNanos = 20_000_000L))
		advanceUntilIdle()

		assertEquals(List(3) { 2_412 } + 5_180, frequencies)
		coVerify(exactly = 0) { fixture.registrations.allocateSequence(any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `retryable admission is finite and cannot poll storage forever in process`() = runTest {
		var attempts = 0

		val result = retryWifiDeliveryAdmission(
			admit = {
				attempts++
				SourceDeliveryAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
			},
		)
		advanceUntilIdle()

		assertTrue(result is SourceDeliveryAdmissionHandoff.RetryableFailure)
		assertEquals(4, attempts)
	}

	@Test
	fun `provider identity is stable across runtime registrations and excludes raw identity`() {
		val evidence = listOf(
			WifiAccessPointEvidence("", 2_412, -50, 9_000_000L),
			WifiAccessPointEvidence("", 5_180, -60, 9_500_000L),
		)

		val first = wifiProviderDeliveryIdentity("boot-1", evidence, false, 10_000_000L)
		val afterProcessDeath = wifiProviderDeliveryIdentity("boot-1", evidence, false, 99_000_000L)
		val reorderedProviderList = wifiProviderDeliveryIdentity(
			"boot-1", evidence.reversed(), false, 99_000_000L,
		)

		assertEquals(first, afterProcessDeath)
		assertEquals(first, reorderedProviderList)
		assertTrue(first.value.matches(Regex("[0-9a-f]{64}")))
		assertFalse(first.value.contains("aa:bb:cc:dd:ee:ff"))
		assertFalse(first.value.contains("wifi-1"))
	}

	@Test
	fun `same provider delivery emits one delivery identity across different runtime envelopes`() = runTest {
		val activePlan = plan(1L)
		val first = runtimeFixture(
			this,
			activePlan,
			listOf(registration(activePlan, 1L, "wifi-before", 9L)),
		)
		val afterRestart = runtimeFixture(
			this,
			activePlan,
			listOf(registration(activePlan, 2L, "wifi-after", 10L)),
		)
		val deliveries = mutableListOf<SourceDeliveryCandidate>()
		val sink = wifiDeliverySink { delivery ->
			deliveries += delivery
			if (deliveries.size == 1) {
				SourceDeliveryAdmissionHandoff.Durable(listOf(41L))
			} else {
				SourceDeliveryAdmissionHandoff.Duplicate(listOf(41L))
			}
		}
		val delivery = resultEvent(receivedNanos = 10_000_000L)

		first.start(sink)
		first.emit(delivery)
		advanceUntilIdle()
		val firstAck = first.quiesce()
		afterRestart.start(sink)
		afterRestart.emit(delivery)
		advanceUntilIdle()
		val duplicateAck = afterRestart.quiesce()

		assertEquals(2, deliveries.size)
		assertEquals(deliveries[0].identity, deliveries[1].identity)
		val candidates = deliveries.map { it.units.single().evidence }
		assertTrue(candidates.all { it.providerDedupKey == null })
		assertTrue(candidates.all { it.sourceSequence == 0L })
		assertFalse(candidates[0].sourceInstanceId == candidates[1].sourceInstanceId)
		assertFalse(candidates[0].registrationGeneration == candidates[1].registrationGeneration)
		assertFalse(candidates[0].authorizationRevision == candidates[1].authorizationRevision)
		assertEquals(41L, firstAck.lastAdmissionOrdinal)
		assertEquals(41L, duplicateAck.lastAdmissionOrdinal)
		assertEquals(1L, firstAck.lastDurablyAdmittedSequence)
		assertEquals(1L, duplicateAck.lastDurablyAdmittedSequence)
		coVerify(exactly = 0) { first.registrations.allocateSequence(any(), any()) }
		coVerify(exactly = 0) { afterRestart.registrations.allocateSequence(any(), any()) }
	}

	@Test
	fun `mixed age broadcast persists only each timestamp qualified access point`() = runTest {
		val fixture = runtimeFixture(this, runtimePlan = plan(1L, maximumAgeMs = 5_000L))
		val admitted = mutableListOf<SourceEvidenceCandidate<*>>()
		fixture.start(wifiCandidateSink { candidate ->
			admitted += candidate
			SourceAdmissionHandoff.Durable(1L)
		})
		fixture.emit(
			WifiBackendEvent.Results(
				snapshot = WifiBackendSnapshot(
					listOf(
						WifiBackendAccessPoint("stale", 2_412, -80, 1_000L),
						WifiBackendAccessPoint("fresh", 5_180, -45, 9_000_000L),
						WifiBackendAccessPoint("missing-time", 5_500, -55, 0L),
					),
				),
				resultsUpdated = true,
				receivedElapsedRealtimeNanos = 10_000_000_000L,
				receivedWallTimeMs = 20_000L,
			),
		)
		advanceUntilIdle()

		val payload = admitted.single().payload as WifiResultSnapshotPayload
		assertEquals(listOf(5_180), payload.accessPoints.map { it.frequencyMhz })
		assertEquals(9_000_000_000L, payload.accessPoints.single().providerTimestampNanos)
		assertEquals(9_000_000_000L, admitted.single().observedElapsedRealtimeNanos)
		fixture.runtime.close()
	}

	@Test
	fun `wifi runtime calls only atomic delivery admission and preserves provider time interval`() = runTest {
		val fixture = runtimeFixture(this, runtimePlan = plan(1L, maximumAgeMs = 5_000L))
		var legacyCalls = 0
		val deliveries = mutableListOf<SourceDeliveryCandidate>()
		val sink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff {
				legacyCalls++
				return SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.INVALID_EVIDENCE)
			}

			override suspend fun admit(
				delivery: SourceDeliveryCandidate,
			): SourceDeliveryAdmissionHandoff {
				deliveries += delivery
				return SourceDeliveryAdmissionHandoff.Durable(listOf(17L))
			}
		}
		fixture.start(sink)
		fixture.emit(
			WifiBackendEvent.Results(
				snapshot = WifiBackendSnapshot(
					listOf(
						WifiBackendAccessPoint("first", 2_412, -50, 7_000_000L),
						WifiBackendAccessPoint("last", 5_180, -60, 9_000_000L),
					),
				),
				resultsUpdated = true,
				receivedElapsedRealtimeNanos = 10_000_000_000L,
				receivedWallTimeMs = 20_000L,
			),
		)
		advanceUntilIdle()

		assertEquals(0, legacyCalls)
		val unit = deliveries.single().units.single()
		assertEquals(7_000_000_000L, unit.observedIntervalStartElapsedRealtimeNanos)
		assertEquals(9_000_000_000L, unit.evidence.observedElapsedRealtimeNanos)
		assertNull(unit.evidence.providerDedupKey)
		assertEquals(0L, unit.evidence.sourceSequence)
		coVerify(exactly = 0) { fixture.registrations.allocateSequence(any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `provider registration exception persists retirement before exact receiver cleanup`() = runTest {
		val fixture = runtimeFixture(this)
		val events = mutableListOf<String>()
		every { fixture.backend.start(any()) } answers {
			events += "provider-start"
			throw IllegalStateException("receiver failure")
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

		val result = fixture.runtime.start(fixture.plan, wifiCandidateSink {
			SourceAdmissionHandoff.Durable(1L)
		})

		assertTrue(result is SourceStartResult.Failed)
		assertEquals(listOf("provider-start", "retiring", "provider-stop", "retired"), events)
		coVerify(exactly = 0) { fixture.registrations.markFailed(any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.registrations.markRetired(any(), any(), any(), any()) }
	}

	@Test
	fun `partial provider start follows the same durable retirement ordering`() = runTest {
		val fixture = runtimeFixture(this)
		val events = mutableListOf<String>()
		every { fixture.backend.start(any()) } answers {
			events += "provider-start-partial"
			false
		}
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			events += "retiring"
			retirementToken(firstArg(), secondArg(), thirdArg(), arg(3))
		}
		every { fixture.backend.stop() } answers {
			events += "provider-stop-exact"
			true
		}
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			events += "retired"
			true
		}

		val result = fixture.runtime.start(fixture.plan, wifiCandidateSink {
			SourceAdmissionHandoff.Durable(1L)
		})

		assertTrue(result is SourceStartResult.Failed)
		assertEquals(
			listOf("provider-start-partial", "retiring", "provider-stop-exact", "retired"),
			events,
		)
		coVerify(exactly = 0) { fixture.registrations.markFailed(any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.registrations.markRetired(any(), any(), any(), any()) }
	}

	@Test
	fun `cancellation during provider acceptance retires exact receiver before propagating`() = runTest {
		val fixture = runtimeFixture(this)
		val events = mutableListOf<String>()
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } answers {
			events += "accept-cancelled"
			throw CancellationException("injected acceptance cancellation")
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

		assertFailsWith<CancellationException> {
			fixture.runtime.start(fixture.plan, wifiCandidateSink {
				SourceAdmissionHandoff.Durable(1L)
			})
		}

		assertEquals(listOf("accept-cancelled", "retiring", "provider-stop", "retired"), events)
	}

	@Test
	fun `ambiguous retirement completion reuses the exact durable token`() = runTest {
		val initialPlan = plan(1L, maximumAgeMs = 5_000L)
		val replacementPlan = plan(2L, maximumAgeMs = 10_000L)
		val initial = registration(initialPlan, authorizationRevision = 1L, registrationGeneration = 8L)
		val replacement = registration(replacementPlan, authorizationRevision = 2L, registrationGeneration = 9L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, replacement))
		fixture.start(wifiCandidateSink { SourceAdmissionHandoff.Durable(1L) })
		val token = retirementToken(initial, "ORDERLY_STOP", 200L, 190L)
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} returns token
		var completionCalls = 0
		coEvery { fixture.registrations.completeRetirement(token) } answers {
			if (++completionCalls == 1) throw IllegalStateException("commit result was ambiguous")
			true
		}

		val firstStop = fixture.quiesce()
		assertEquals(RegistrationRemovalOutcome.FAILED, firstStop.registrationRemovalOutcome)
		val replacementResult = fixture.runtime.reconfigure(
			replacementPlan,
			wifiCandidateSink { SourceAdmissionHandoff.Durable(2L) },
		)

		assertTrue(replacementResult is SourceApplyResult.Applied)
		coVerify(exactly = 1) {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		}
		coVerify(exactly = 2) { fixture.registrations.completeRetirement(token) }
		verify(exactly = 2) { fixture.backend.stop() }
		verify(exactly = 2) { fixture.backend.start(any()) }
		fixture.runtime.close()
	}

	@Test
	fun `failed receiver removal is retried before a replacement can start`() = runTest {
		val initialPlan = plan(1L, maximumAgeMs = 5_000L)
		val replacementPlan = plan(2L, maximumAgeMs = 10_000L)
		val initial = registration(initialPlan, authorizationRevision = 1L, registrationGeneration = 8L)
		val replacement = registration(replacementPlan, authorizationRevision = 2L, registrationGeneration = 9L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, replacement))
		fixture.start(wifiCandidateSink { SourceAdmissionHandoff.Durable(1L) })
		val pendingToken = retirementToken(initial, "ORDERLY_STOP", 200L, 190L)
		var pending = true
		var stopAttempt = 0
		val retirementCalls = mutableListOf<Triple<String, Long, Long>>()
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			retirementCalls += Triple(secondArg(), thirdArg(), arg(3))
			retirementToken(firstArg(), secondArg(), thirdArg(), arg(3))
		}
		every { fixture.backend.stop() } answers { ++stopAttempt > 1 }
		coEvery { fixture.registrations.pendingRetirements(SourceKind.WIFI) } answers {
			if (pending) listOf(pendingToken) else emptyList()
		}
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			pending = false
			true
		}

		val firstStop = fixture.quiesce()
		assertEquals(RegistrationRemovalOutcome.FAILED, firstStop.registrationRemovalOutcome)
		assertEquals(SourceStopStatus.PROVIDER_FAILED, firstStop.status)
		verify(exactly = 1) { fixture.backend.start(any()) }

		val replacementResult = fixture.runtime.reconfigure(
			replacementPlan,
			wifiCandidateSink { SourceAdmissionHandoff.Durable(2L) },
		)
		assertTrue(replacementResult is SourceApplyResult.Applied)
		verify(exactly = 2) { fixture.backend.start(any()) }
		verify(exactly = 2) { fixture.backend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		assertEquals(1, retirementCalls.size)
		coVerify(exactly = 0) {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		}
		fixture.runtime.close()
	}

	@Test
	fun `retirement persistence failure fences refresh and replacement`() = runTest {
		val fixture = runtimeFixture(this, runtimePlan = plan(1L, maximumAgeMs = 5_000L))
		fixture.start(wifiCandidateSink { SourceAdmissionHandoff.Durable(1L) })
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} throws IllegalStateException("injected database outage")

		val firstStop = fixture.quiesce()
		assertEquals(RegistrationRemovalOutcome.FAILED, firstStop.registrationRemovalOutcome)
		verify(exactly = 0) { fixture.backend.stop() }

		val result = fixture.runtime.reconfigure(
			plan(2L, maximumAgeMs = 10_000L),
			wifiCandidateSink { SourceAdmissionHandoff.Durable(2L) },
		)
		assertTrue(result is SourceApplyResult.Failed)
		verify(exactly = 1) { fixture.backend.start(any()) }
		verify(exactly = 0) { fixture.backend.stop() }
		coVerify(exactly = 0) {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		}
	}

	@Test
	fun `pending durable receiver removal fails closed before reservation or provider start`() = runTest {
		val fixture = runtimeFixture(this)
		val pending = retirementToken(
			registration(fixture.plan, authorizationRevision = 1L),
			"ORDERLY_STOP",
			200L,
			190L,
		)
		coEvery { fixture.registrations.pendingRetirements(SourceKind.WIFI) } returns listOf(pending)
		every { fixture.backend.stop() } returns false

		val result = fixture.runtime.start(
			fixture.plan,
			wifiCandidateSink { SourceAdmissionHandoff.Durable(1L) },
		)

		assertTrue(result is SourceStartResult.Failed)
		coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		verify(exactly = 0) { fixture.backend.start(any()) }
		coVerify(exactly = 0) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `post revoke provider callback is rejected at callback entry`() = runTest {
		val fixture = runtimeFixture(this)
		fixture.start(wifiCandidateSink { SourceAdmissionHandoff.Durable(1L) })
		fixture.state = fixture.state.copy(fineLocationPermission = false)

		fixture.emit(resultEvent(receivedNanos = 10_000_000L))
		advanceUntilIdle()

		coVerify(exactly = 0) { fixture.registrations.allocateSequence(any(), any()) }
		val ack = fixture.quiesce()
		assertEquals(0L, ack.callbackEntryBarrierSequence)
		assertEquals(0L, ack.failedAdmissionCount)
	}

	@Test
	fun `revocation after callback entry preserves the captured snapshot for observed-time admission`() = runTest {
		val fixture = runtimeFixture(this)
		val admitted = mutableListOf<SourceEvidenceCandidate<*>>()
		fixture.start(wifiCandidateSink { candidate ->
			admitted += candidate
			SourceAdmissionHandoff.Durable(1L)
		})
		runCurrent()

		fixture.emit(resultEvent(receivedNanos = 10_000_000L))
		fixture.state = fixture.state.copy(fineLocationPermission = false)
		advanceUntilIdle()

		coVerify(exactly = 0) { fixture.registrations.allocateSequence(any(), any()) }
		assertEquals(1, admitted.size)
		val ack = fixture.quiesce()
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(0L, ack.failedAdmissionCount)
		assertEquals(1L, ack.lastDurablyAdmittedSequence)
	}

	@Test
	fun `captured snapshot retries remain observed-time stable after current permission changes`() = runTest {
		val fixture = runtimeFixture(this)
		var attempts = 0
		fixture.start(wifiCandidateSink {
			attempts++
			fixture.state = fixture.state.copy(locationServicesEnabled = false)
			SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_UNAVAILABLE)
		})

		fixture.emit(resultEvent(receivedNanos = 10_000_000L))
		advanceUntilIdle()

		assertEquals(4, attempts)
		val ack = fixture.quiesce()
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
	}

	@Test
	fun `quiesce deadline cancels retryable head and accounts the unresolved FIFO range`() = runTest {
		val fixture = runtimeFixture(this)
		var attempts = 0
		fixture.start(wifiCandidateSink {
			attempts++
			SourceAdmissionHandoff.RetryableFailure(SourceAdmissionFailureCode.STORAGE_FULL)
		})
		fixture.emit(resultEvent(receivedNanos = 10_000_000L))
		runCurrent()

		val ack = fixture.quiesce(deadlineOffsetNanos = 1_000_000L)

		assertTrue(attempts >= 1)
		assertFalse(ack.appDrainComplete)
		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
	}

	@Test
	fun `failed cached and repeated stale snapshots never create repeated evidence`() = runTest {
		val fixture = runtimeFixture(this)
		val admitted = mutableListOf<SourceEvidenceCandidate<*>>()
		fixture.start(wifiCandidateSink { candidate ->
			admitted += candidate
			SourceAdmissionHandoff.Durable(1L)
		})
		val cached = resultEvent(resultsUpdated = false, receivedNanos = 10_000_000L)
		val unknownFreshness = resultEvent(resultsUpdated = null, receivedNanos = 10_000_000L)
		val stale = resultEvent(
			resultsUpdated = true,
			receivedNanos = 10_000_000_000L,
			providerTimestampMicros = 1L,
		)

		fixture.emit(cached)
		fixture.emit(unknownFreshness)
		fixture.emit(unknownFreshness)
		fixture.emit(stale)
		fixture.emit(stale)
		advanceUntilIdle()

		assertEquals(1, admitted.size)
		coVerify(exactly = 0) { fixture.registrations.allocateSequence(any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `passive broadcast plan never requests an active scan`() = runTest {
		val fixture = runtimeFixture(this, runtimePlan = plan(1L).copy(mode = WifiMode.BROADCAST_DRIVEN))

		fixture.start(wifiCandidateSink { SourceAdmissionHandoff.Durable(1L) })
		advanceUntilIdle()

		verify(exactly = 0) { fixture.backend.requestScan() }
		verify(exactly = 0) { fixture.backend.readSnapshot() }
		coVerify(exactly = 0) { fixture.wakeups.schedule(any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `accepted active first evidence scan is an attempt and not recording evidence`() = runTest {
		val activePlan = plan(1L).copy(mode = WifiMode.ACTIVE_ATTEMPTS)
		val fixture = runtimeFixture(this, runtimePlan = activePlan)
		every { fixture.backend.requestScan() } returns WifiRequestOutcome.ACCEPTED
		val admitted = mutableListOf<SourceEvidenceCandidate<*>>()
		fixture.start(wifiCandidateSink { candidate ->
			admitted += candidate
			SourceAdmissionHandoff.Durable(1L)
		})
		advanceUntilIdle()

		fixture.fireScheduledWakeup()
		advanceUntilIdle()
		verify(exactly = 1) { fixture.backend.requestScan() }
		assertTrue(admitted.isEmpty())

		fixture.emit(resultEvent(receivedNanos = 10_000_000L))
		advanceUntilIdle()
		assertEquals(1, admitted.size)
		fixture.runtime.close()
	}

	@Test
	fun `compatible revision refreshes authorization without restarting provider`() = runTest {
		val initialPlan = plan(1L, maximumAgeMs = 5_000L)
		val refreshedPlan = plan(2L, maximumAgeMs = 10_000L)
		val initial = registration(initialPlan, authorizationRevision = 1L)
		val refreshed = registration(refreshedPlan, authorizationRevision = 2L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial))
		coEvery {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns refreshed
		var admitted: SourceEvidenceCandidate<*>? = null
		val sink = wifiCandidateSink { candidate ->
			admitted = candidate
			SourceAdmissionHandoff.Durable(7L)
		}
		fixture.start(sink)

		val applied = fixture.runtime.reconfigure(refreshedPlan, sink)
		assertTrue(applied is SourceApplyResult.Applied)
		verify(exactly = 1) { fixture.backend.start(any()) }
		verify(exactly = 0) { fixture.backend.stop() }
		coVerify(exactly = 1) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 1) {
			fixture.registrations.refreshActiveAuthorization(any(), initial, any(), any(), any(), any())
		}

		fixture.emit(
			resultEvent(
				receivedNanos = 10_000_000_000L,
				providerTimestampMicros = 3_000_000L,
			),
		)
		advanceUntilIdle()

		assertEquals(2L, admitted?.authorizationRevision)
		assertEquals(null, admitted?.configRevision)
		fixture.runtime.close()
		verify(exactly = 1) { fixture.backend.stop() }
	}

	@Test
	fun `claim transfer fences stale shutdown across compatible refresh and replacement`() = runTest {
		val initialPlan = plan(1L, maximumAgeMs = 5_000L)
		val refreshedPlan = plan(2L, maximumAgeMs = 10_000L)
		val replacementPlan = plan(3L, maximumAgeMs = 20_000L)
		val initial = registration(initialPlan, authorizationRevision = 1L, registrationGeneration = 9L)
		val refreshed = registration(refreshedPlan, authorizationRevision = 2L, registrationGeneration = 9L)
		val replacement = registration(replacementPlan, authorizationRevision = 3L, registrationGeneration = 10L)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, replacement))
		var refreshCall = 0
		coEvery {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} answers { if (refreshCall++ == 0) refreshed else null }
		val firstClaim = runtimeClaim(SourceKind.WIFI, "wifi-start")
		val refreshClaim = runtimeClaim(SourceKind.WIFI, "wifi-refresh")
		val replacementClaim = runtimeClaim(SourceKind.WIFI, "wifi-replacement")
		val sink = wifiCandidateSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(firstClaim, initialPlan, sink) is SourceStartResult.Started)
		assertTrue(fixture.runtime.reconfigure(refreshClaim, refreshedPlan, sink) is SourceApplyResult.Applied)
		assertEquals(
			OwnedSourceShutdown.NotOwned,
			fixture.runtime.shutdownIfOwned(firstClaim, wifiCutoff()),
		)
		verify(exactly = 0) { fixture.backend.stop() }

		val replacementResult = fixture.runtime.reconfigure(replacementClaim, replacementPlan, sink)
		assertTrue(replacementResult is SourceApplyResult.Applied)
		assertEquals(9L, (replacementResult as SourceApplyResult.Applied).stopAck?.registrationGeneration)
		verify(exactly = 1) { fixture.backend.stop() }

		assertEquals(
			OwnedSourceShutdown.NotOwned,
			fixture.runtime.shutdownIfOwned(refreshClaim, wifiCutoff()),
		)
		verify(exactly = 1) { fixture.backend.stop() }
		val released = fixture.runtime.shutdownIfOwned(replacementClaim, wifiCutoff()) as
			OwnedSourceShutdown.Released
		assertEquals(10L, released.provider?.registrationGeneration)
		verify(exactly = 2) { fixture.backend.stop() }
	}

	@Test
	fun `failed provider publication retains only its exact claim for cleanup`() = runTest {
		val fixture = runtimeFixture(this)
		every { fixture.backend.start(any()) } throws IllegalStateException("published then failed")
		every { fixture.backend.stop() } returnsMany listOf(false, true)
		val owningClaim = runtimeClaim(SourceKind.WIFI, "wifi-failed-publication")
		val staleClaim = runtimeClaim(SourceKind.WIFI, "wifi-stale-cleanup")
		val sink = wifiCandidateSink { SourceAdmissionHandoff.Durable(1L) }

		assertTrue(fixture.runtime.start(owningClaim, fixture.plan, sink) is SourceStartResult.Failed)
		verify(exactly = 1) { fixture.backend.stop() }
		assertEquals(
			OwnedSourceShutdown.NotOwned,
			fixture.runtime.shutdownIfOwned(staleClaim, wifiCutoff()),
		)
		verify(exactly = 1) { fixture.backend.stop() }

		val released = fixture.runtime.shutdownIfOwned(owningClaim, wifiCutoff()) as
			OwnedSourceShutdown.Released
		assertEquals(9L, released.provider?.registrationGeneration)
		verify(exactly = 2) { fixture.backend.stop() }
	}

	@Test
	fun `incompatible authorization check reserves only one wifi replacement`() = runTest {
		val initialPlan = plan(1L, maximumAgeMs = 5_000L)
		val replacementPlan = plan(2L, maximumAgeMs = 10_000L)
		val initial = registration(initialPlan, authorizationRevision = 1L)
		val replacement = registration(
			replacementPlan,
			authorizationRevision = 2L,
			sourceInstanceId = "wifi-1",
			registrationGeneration = 10L,
		)
		val fixture = runtimeFixture(this, initialPlan, listOf(initial, replacement))
		coEvery {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns null
		val sink = wifiCandidateSink { SourceAdmissionHandoff.Durable(1L) }
		fixture.start(sink)

		assertTrue(fixture.runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Applied)

		coVerify(exactly = 1) {
			fixture.registrations.refreshActiveAuthorization(any(), initial, any(), any(), any(), any())
		}
		// Initial acquisition plus the orderly post-stop replacement; no speculative reservation.
		coVerify(exactly = 2) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		verify(exactly = 2) { fixture.backend.start(any()) }
		verify(exactly = 1) { fixture.backend.stop() }
		fixture.runtime.close()
	}

	private suspend fun RuntimeFixture.start(sink: SourceEventSink) {
		assertTrue(runtime.start(plan, sink) is SourceStartResult.Started)
	}

	private fun wifiCandidateSink(
		onCandidate: suspend (SourceEvidenceCandidate<*>) -> SourceAdmissionHandoff,
	): SourceEventSink = wifiDeliverySink { delivery ->
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

	private fun wifiDeliverySink(
		onDelivery: suspend (SourceDeliveryCandidate) -> SourceDeliveryAdmissionHandoff,
	): SourceEventSink = object : SourceEventSink {
		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			throw AssertionError("Wi-Fi must not call legacy single-evidence admission")

		override suspend fun admit(delivery: SourceDeliveryCandidate): SourceDeliveryAdmissionHandoff =
			onDelivery(delivery)
	}

	private data class RuntimeFixture(
		val runtime: WifiSourceRuntime,
		val registrations: SourceRegistrationRepository,
		val backend: AndroidWifiSourceBackend,
		val wakeups: CoalescingSourceWakeupScheduler,
		val plan: WifiPlan,
		private val callback: () -> ((WifiBackendEvent) -> Unit),
		private val scheduledAction: () -> (suspend () -> Unit)?,
		var state: WifiDeviceState,
	) {
		fun emit(event: WifiBackendEvent) = callback()(event)

		suspend fun fireScheduledWakeup() = requireNotNull(scheduledAction())()

		suspend fun quiesce(deadlineOffsetNanos: Long = 1_000_000_000L): SourceStopAck {
			val now = android.os.SystemClock.elapsedRealtimeNanos()
			return runtime.quiesce(
				SessionCutoff(
					logicalTrackingId = "wifi-test",
					elapsedRealtimeNanos = Long.MAX_VALUE,
					wallTimeMs = 1L,
					deadlineElapsedRealtimeNanos = now + deadlineOffsetNanos,
				),
			)
		}
	}

	private fun runtimeFixture(
		scope: kotlinx.coroutines.CoroutineScope,
		runtimePlan: WifiPlan = plan(1L),
		registrationsToReturn: List<SourceRegistration> = listOf(registration(runtimePlan, 1L)),
	): RuntimeFixture {
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val backend = mockk<AndroidWifiSourceBackend>(relaxed = true)
		val stateProvider = mockk<AndroidConnectivityDeviceStateProvider>()
		val wakeups = mockk<CoalescingSourceWakeupScheduler>(relaxed = true)
		var state = readyState()
		var callback: ((WifiBackendEvent) -> Unit)? = null
		var scheduledAction: (suspend () -> Unit)? = null
		every { stateProvider.wifi() } answers { state }
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returnsMany registrationsToReturn
		coEvery { registrations.pendingRetirements(SourceKind.WIFI) } returns emptyList()
		coEvery {
			registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			retirementToken(firstArg(), secondArg(), thirdArg(), arg(3))
		}
		coEvery { registrations.completeRetirement(any()) } returns true
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		every { backend.start(any()) } answers {
			callback = firstArg()
			true
		}
		every { backend.stop() } returns true
		coEvery { wakeups.schedule(any(), any()) } answers {
			scheduledAction = secondArg<suspend () -> Unit>()
		}
		val runtime = WifiSourceRuntime(scope, registrations, backend, stateProvider, wakeups)
		return RuntimeFixture(
			runtime, registrations, backend, wakeups, runtimePlan,
			{ requireNotNull(callback) }, { scheduledAction }, state,
		).also { fixture ->
				// Keep the mutable fixture state and the provider answer joined without a second fake type.
				every { stateProvider.wifi() } answers { fixture.state }
			}
	}

	private companion object {
		fun runtimeClaim(source: SourceKind, actionId: String) = SourceRuntimeClaim(
			source = source,
			actionId = actionId,
			attemptCount = 1,
			leaseGeneration = 1L,
			logicalTrackingId = "wifi-test",
			serviceRunId = "run-1",
		)

		fun wifiCutoff() = SessionCutoff(
			logicalTrackingId = "wifi-test",
			elapsedRealtimeNanos = Long.MAX_VALUE,
			wallTimeMs = 1L,
			deadlineElapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() + 1_000_000_000L,
		)

		fun readyState() = WifiDeviceState(
			wifiFeatureAvailable = true,
			fineLocationPermission = true,
			locationServicesEnabled = true,
			deviceIdle = false,
		)

		fun plan(revision: Long, maximumAgeMs: Long = 5L) = WifiPlan(
			revision = revision,
			mode = WifiMode.BROADCAST_DRIVEN,
			minimumAttemptIntervalMs = 30_000L,
			maximumAcceptableResultAgeMs = maximumAgeMs,
			unchangedResultDedupeWindowMs = 60_000L,
			backoff = RetryBackoff(1_000L, 60_000L),
		)

		fun resultEvent(
			frequencyMhz: Int = 2_412,
			resultsUpdated: Boolean? = true,
			receivedNanos: Long,
			providerTimestampMicros: Long = (receivedNanos - 1_000_000L) / 1_000L,
		) = WifiBackendEvent.Results(
			snapshot = WifiBackendSnapshot(
				listOf(
					WifiBackendAccessPoint(
						bssid = "aa:bb:cc:dd:ee:ff",
						frequencyMhz = frequencyMhz,
						signalLevelDbm = -50,
						platformTimestampMicros = providerTimestampMicros,
					),
				),
			),
			resultsUpdated = resultsUpdated,
			receivedElapsedRealtimeNanos = receivedNanos,
			receivedWallTimeMs = 1_000L,
		)

		fun registration(
			plan: WifiPlan,
			authorizationRevision: Long,
			sourceInstanceId: String = "wifi-1",
			registrationGeneration: Long = 9L,
		): SourceRegistration {
			val demand = SourceDemandEntity(
				demandId = "wifi-demand-$authorizationRevision",
				consumerId = "session:wifi-test",
				sourceKind = SourceKind.WIFI.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "wifi-test",
				serviceRunId = "run-1",
				manifestRevision = 1L,
				lifecycleLeaseGeneration = 1L,
				sourcePolicyRevision = authorizationRevision,
				consentEpoch = 1L,
				persistenceEligible = true,
				qosCode = 0,
				maximumAgeMs = 30_000L,
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
					SourceKind.WIFI.stableCode,
					registrationGeneration = registrationGeneration,
					authorizationRevision = authorizationRevision,
					demands = listOf(demand),
					effectiveBootId = "boot-1",
					effectiveElapsedRealtimeNanos = authorizationRevision,
					effectiveWallTimeMs = 1L,
				).toAuthorizationSnapshotOrNull(),
			)
			return SourceRegistration(
				ownerScope = "source-broker:${SourceKind.WIFI.stableCode}",
				state = SourceRegistrationStateEntity(
					sourceKind = SourceKind.WIFI.stableCode,
					ownerScope = "source-broker:${SourceKind.WIFI.stableCode}",
					sourceInstanceId = sourceInstanceId,
					clockDomainId = "boot-1",
					registrationGeneration = registrationGeneration,
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

		fun retirementToken(
			registration: SourceRegistration,
			reason: String,
			retiredAtMs: Long,
			retiredElapsedRealtimeNanos: Long,
		) = SourceRegistrationRetirementToken(
			source = SourceKind.WIFI,
			sourceInstanceId = SourceInstanceId(registration.state.sourceInstanceId),
			registrationGeneration = registration.state.registrationGeneration,
			processIncarnationId = "wifi-runtime-test-process",
			retiredAtMs = retiredAtMs,
			retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
			reason = reason,
		)
	}
}
