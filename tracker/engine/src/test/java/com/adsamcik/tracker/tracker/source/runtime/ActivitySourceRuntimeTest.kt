package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.activity.api.registration.ActivityProviderCleanupResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationDemand
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationFailureCode
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationSnapshot
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceRegistrationStateDao
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.dao.SourceRunAdmissionHighWaterRow
import com.adsamcik.tracker.shared.base.database.data.SourceCaptureAdmissionBarrierEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivitySourceRuntimeTest {
	@Test
	fun `session demand carries exact plan independently of provider generation`() = runTest {
		val fixture = fixture()
		val plan = enabledPlan(41L)

		assertIs<SourceStartResult.Started>(
			fixture.runtime.start(claim("plan-attribution"), plan, DISCARDING_SINK),
		)

		val capturedPlan = requireNotNull(
			fixture.arbiter.demand(ActivityRegistrationOwner.ACTIVE_SESSION).capturedPlan,
		)
		assertEquals(41L, capturedPlan.configurationRevision)
		assertEquals(SourcePlanCodec.FORMAT_VERSION, capturedPlan.payloadVersion)
		assertEquals(plan, SourcePlanCodec().decode(capturedPlan.payload))
		assertEquals(plan.physicalConfigurationFingerprint(), capturedPlan.physicalConfigurationFingerprint)
		assertTrue(capturedPlan.configurationRevision != ACTIVITY_GENERATION)
	}

	@Test
	fun `reconfigure transfers claim even when provider identity is unchanged`() = runTest {
		val fixture = fixture()
		val first = claim("start-action", leaseGeneration = 1L)
		val successor = claim("reconfigure-action", leaseGeneration = 2L)

		assertIs<SourceStartResult.Started>(
			fixture.runtime.start(first, enabledPlan(1L), DISCARDING_SINK),
		)
		val initialIdentity = fixture.arbiter.snapshot().identity
		assertIs<SourceApplyResult.Applied>(
			fixture.runtime.reconfigure(successor, enabledPlan(2L), DISCARDING_SINK),
		)
		assertEquals(initialIdentity, fixture.arbiter.snapshot().identity)

		assertIs<OwnedSourceShutdown.NotOwned>(
			fixture.runtime.shutdownIfOwned(first, CUTOFF),
		)
		assertEquals(0, fixture.arbiter.clearDemandCalls)
		coVerify(exactly = 0) { fixture.registrationStateDao.get(any(), any()) }

		val released = assertIs<OwnedSourceShutdown.Released>(
			fixture.runtime.shutdownIfOwned(successor, CUTOFF),
		)
		assertEquals(
			SourceProviderKey(SourceInstanceId(ACTIVITY_INSTANCE_ID), ACTIVITY_GENERATION),
			released.provider,
		)
		assertEquals(1, fixture.arbiter.clearDemandCalls)
	}

	@Test
	fun `exact owner clears only active session demand`() = runTest {
		val fixture = fixture(setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR))
		val owner = claim("session-action")
		assertIs<SourceStartResult.Started>(
			fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK),
		)
		assertEquals(
			setOf(
				ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
				ActivityRegistrationOwner.ACTIVE_SESSION,
			),
			fixture.arbiter.owners,
		)

		assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(owner, CUTOFF))

		assertEquals(
			setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR),
			fixture.arbiter.owners,
		)
		assertEquals(listOf(ActivityRegistrationOwner.ACTIVE_SESSION), fixture.arbiter.clearedOwners)
		assertTrue(fixture.arbiter.snapshot().active)
	}

	@Test
	fun `End releases retired session join while all-day restart waits for startup Ready`() = runTest {
		val fixture = fixture(setOf(ActivityRegistrationOwner.ALL_DAY_TRACKING))
		val owner = claim("end-with-all-day")
		assertIs<SourceStartResult.Started>(fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK))
		val identity = requireNotNull(fixture.arbiter.snapshot().identity)
		fixture.arbiter.startupBlocked = true
		coEvery { fixture.sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION) } returns
			retiredRegistration(identity)

		val released = assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(owner, CUTOFF))

		val acknowledgement = requireNotNull(released.stopAck)
		assertEquals(RegistrationRemovalOutcome.REMOVED, acknowledgement.registrationRemovalOutcome)
		assertEquals(SourceStopStatus.COMPLETE, acknowledgement.status)
		assertEquals(23L, acknowledgement.lastAdmissionOrdinal)
		assertTrue(acknowledgement.appDrainComplete)
		assertEquals(setOf(ActivityRegistrationOwner.ALL_DAY_TRACKING), fixture.arbiter.owners)
		assertFalse(fixture.arbiter.snapshot().active)
	}

	@Test
	fun `blocked sibling cannot release until exact physical removal is acknowledged`() = runTest {
		val fixture = fixture(setOf(ActivityRegistrationOwner.ALL_DAY_TRACKING))
		val owner = claim("end-removal-pending")
		assertIs<SourceStartResult.Started>(fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK))
		val retired = retiredRegistration(requireNotNull(fixture.arbiter.snapshot().identity))
		fixture.arbiter.startupBlocked = true
		coEvery { fixture.sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION) } returns
			retired.copy(status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				retiredAtMs = null, retiredElapsedRealtimeNanos = null)
		assertIs<OwnedSourceShutdown.Incomplete>(fixture.runtime.shutdownIfOwned(owner, CUTOFF))

		coEvery { fixture.sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION) } returns retired
		assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(owner, CUTOFF))
	}

	@Test
	fun `retired blocked sibling still requires durable Activity admission barrier`() = runTest {
		val fixture = fixture(setOf(ActivityRegistrationOwner.ALL_DAY_TRACKING), admissionBarrier = null)
		val owner = claim("end-barrier-missing")
		assertIs<SourceStartResult.Started>(fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK))
		fixture.arbiter.startupBlocked = true
		coEvery { fixture.sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION) } returns
			retiredRegistration(requireNotNull(fixture.arbiter.snapshot().identity))

		val incomplete = assertIs<OwnedSourceShutdown.Incomplete>(fixture.runtime.shutdownIfOwned(owner, CUTOFF))
		assertFalse(requireNotNull(incomplete.stopAck).appDrainComplete)
		assertEquals(SourceStopStatus.TIMED_OUT, requireNotNull(incomplete.stopAck).status)
	}

	@Test
	fun `interrupted clear retries the departing shared generation rather than original session identity`() = runTest {
		val fixture = fixture(setOf(ActivityRegistrationOwner.ALL_DAY_TRACKING))
		val owner = claim("end-new-generation")
		assertIs<SourceStartResult.Started>(fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK))
		val successor = requireNotNull(fixture.arbiter.snapshot().identity)
			.copy(registrationGeneration = ACTIVITY_GENERATION + 1L)
		fixture.arbiter.identity = successor
		fixture.arbiter.nextClearFailure = MutationFailurePoint.AFTER
		assertFailsWith<IllegalStateException> { fixture.runtime.shutdownIfOwned(owner, CUTOFF) }
		fixture.arbiter.startupBlocked = true
		coEvery { fixture.sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, successor.registrationGeneration) } returns
			retiredRegistration(successor)
		coEvery { fixture.sourceBrokerDao.captureAdmissionBarrier(SourceKind.ACTIVITY.stableCode, successor.registrationGeneration) } returns
			activityAdmissionBarrier().copy(registrationGeneration = successor.registrationGeneration)

		val released = assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(owner, CUTOFF))
		assertEquals(SourceProviderKey(SourceInstanceId(ACTIVITY_INSTANCE_ID), successor.registrationGeneration), released.provider)
	}

	@Test
	fun `cancelled End recovers its sealed Activity join without awaiting the all-day restart again`() = runTest {
		val fixture = fixture(setOf(ActivityRegistrationOwner.ALL_DAY_TRACKING))
		val owner = claim("cancelled-end")
		val cutoff = CUTOFF.copy(logicalTrackingId = owner.logicalTrackingId)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK))
		val departing = fixture.arbiter.identity
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		fixture.arbiter.afterClear = {
			withContext(NonCancellable) {
				entered.complete(Unit)
				release.await()
				fixture.arbiter.identity = departing.copy(registrationGeneration = ACTIVITY_GENERATION + 1L)
			}
			currentCoroutineContext().ensureActive()
		}
		val stopping = async(start = CoroutineStart.UNDISPATCHED) { fixture.runtime.shutdownIfOwned(owner, cutoff) }
		entered.await()
		stopping.cancel()
		coEvery { fixture.sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION) } returns
			retiredRegistration(departing).copy(
				failureCode = "DEMAND_REMOVED|${ProviderRegistrationGenerationEntity.FAILURE_ACTIVITY_CALLBACK_LOSS_PREFIX}4",
			)
		coEvery { fixture.sourceBrokerDao.runCaptureAdmissionHighWater(any(), any(), any(), any(), any(), any()) } returns
			SourceRunAdmissionHighWaterRow(23L, 17L)
		coEvery { fixture.sourceBrokerDao.authorizationRevision(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION, 4L) } returns
			listOf(captureAuthorization(4L))
		release.complete(Unit)
		stopping.join()
		fixture.arbiter.afterClear = { error("Ending an already sealed join must not await its all-day sibling") }

		val released = assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(owner, cutoff))
		val acknowledgement = requireNotNull(released.stopAck)
		assertEquals(SourceProviderKey(SourceInstanceId(departing.sourceInstanceId), ACTIVITY_GENERATION), released.provider)
		assertEquals(SourceStopStatus.COMPLETE, acknowledgement.status)
		assertTrue(acknowledgement.appDrainComplete)
		assertEquals(23L, acknowledgement.lastAdmissionOrdinal)
		assertEquals(17L, acknowledgement.lastDurablyAdmittedSequence)
		assertEquals(1L, acknowledgement.failedAdmissionCount)
		assertEquals(null, acknowledgement.unresolvedSequenceStart)
		assertEquals(ProviderFlushOutcome.NOT_SUPPORTED, acknowledgement.providerFlushOutcome)
		assertEquals(ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE, acknowledgement.providerCoverage)
		assertEquals(1, fixture.arbiter.clearDemandCalls)
		assertEquals(setOf(ActivityRegistrationOwner.ALL_DAY_TRACKING), fixture.arbiter.owners)
		assertEquals(ACTIVITY_GENERATION + 1L, fixture.arbiter.snapshot().identity?.registrationGeneration)
		assertIs<SourceStartResult.Started>(fixture.runtime.start(claim("next-session"), enabledPlan(2L), DISCARDING_SINK))
	}

	@Test
	fun `interrupted Activity join cannot bypass reconciliation without exact retired identity and drained barrier`() = runTest {
		val identity = RecordingActivityRegistrationArbiter(emptySet()).identity
		val registration = retiredRegistration(identity)
		val barrier = activityAdmissionBarrier()
		val incompleteEvidence = listOf(
			registration.copy(status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				retiredAtMs = null, retiredElapsedRealtimeNanos = null) to barrier,
			registration.copy(clockDomainId = "other-boot") to barrier,
			registration.copy(collectedDataEpoch = identity.collectedDataEpoch + 1L) to barrier,
			registration.copy(physicalConfigurationFingerprint = "other-configuration") to barrier,
			registration to null,
			registration to barrier.copy(lastAdmissionOrdinal = 22L),
		)
		incompleteEvidence.forEach { (savedRegistration, savedBarrier) ->
			val fixture = fixture(setOf(ActivityRegistrationOwner.ALL_DAY_TRACKING), savedBarrier)
			val owner = claim("unproven-retirement")
			val cutoff = CUTOFF.copy(logicalTrackingId = owner.logicalTrackingId)
			assertIs<SourceStartResult.Started>(fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK))
			fixture.arbiter.nextClearFailure = MutationFailurePoint.AFTER
			assertFailsWith<IllegalStateException> { fixture.runtime.shutdownIfOwned(owner, cutoff) }
			coEvery { fixture.sourceBrokerDao.registration(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION) } returns
				savedRegistration
			coEvery { fixture.sourceBrokerDao.runCaptureAdmissionHighWater(any(), any(), any(), any(), any(), any()) } returns
				SourceRunAdmissionHighWaterRow(23L, 17L)
			fixture.arbiter.nextClearFailure = MutationFailurePoint.BEFORE

			assertFailsWith<IllegalStateException> { fixture.runtime.shutdownIfOwned(owner, cutoff) }
			assertEquals(2, fixture.arbiter.clearDemandCalls)
		}
	}

	@Test
	fun `set demand exception before or after mutation leaves claim able to release`() = runTest {
		MutationFailurePoint.entries.forEachIndexed { index, failurePoint ->
			val fixture = fixture()
			val owner = claim("exception-action-$index", leaseGeneration = index + 1L)
			fixture.arbiter.nextSetFailure = failurePoint

			assertFailsWith<IllegalStateException> {
				fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK)
			}
			assertEquals(
				failurePoint == MutationFailurePoint.AFTER,
				ActivityRegistrationOwner.ACTIVE_SESSION in fixture.arbiter.owners,
			)

			assertIs<OwnedSourceShutdown.Released>(
				fixture.runtime.shutdownIfOwned(owner, CUTOFF),
			)
			assertFalse(ActivityRegistrationOwner.ACTIVE_SESSION in fixture.arbiter.owners)
		}
	}

	@Test
	fun `reconfigure exception before or after mutation supersedes prior claim`() = runTest {
		MutationFailurePoint.entries.forEachIndexed { index, failurePoint ->
			val fixture = fixture()
			val first = claim("first-action-$index", leaseGeneration = 1L)
			val successor = claim("successor-action-$index", leaseGeneration = index + 2L)
			assertIs<SourceStartResult.Started>(
				fixture.runtime.start(first, enabledPlan(1L), DISCARDING_SINK),
			)
			fixture.arbiter.nextSetFailure = failurePoint

			assertFailsWith<IllegalStateException> {
				fixture.runtime.reconfigure(successor, enabledPlan(2L), DISCARDING_SINK)
			}
			assertIs<OwnedSourceShutdown.NotOwned>(fixture.runtime.shutdownIfOwned(first, CUTOFF))
			assertEquals(0, fixture.arbiter.clearDemandCalls)
			assertIs<OwnedSourceShutdown.Released>(
				fixture.runtime.shutdownIfOwned(successor, CUTOFF),
			)
		}
	}

	@Test
	fun `clear exception before or after mutation retains exact claim for retry`() = runTest {
		MutationFailurePoint.entries.forEachIndexed { index, failurePoint ->
			val fixture = fixture()
			val owner = claim("clear-action-$index", leaseGeneration = index + 1L)
			assertIs<SourceStartResult.Started>(
				fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK),
			)
			fixture.arbiter.nextClearFailure = failurePoint

			assertFailsWith<IllegalStateException> {
				fixture.runtime.shutdownIfOwned(owner, CUTOFF)
			}
			assertIs<OwnedSourceShutdown.Released>(
				fixture.runtime.shutdownIfOwned(owner, CUTOFF),
			)
			assertFalse(ActivityRegistrationOwner.ACTIVE_SESSION in fixture.arbiter.owners)
		}
	}

	@Test
	fun `incomplete shutdown retains claim until provider reconciliation succeeds`() = runTest {
		val fixture = fixture()
		val owner = claim("retry-action")
		assertIs<SourceStartResult.Started>(
			fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK),
		)
		fixture.arbiter.nextClearStatus = ActivityRegistrationStatus.FAILED

		assertIs<OwnedSourceShutdown.Incomplete>(
			fixture.runtime.shutdownIfOwned(owner, CUTOFF),
		)
		assertIs<OwnedSourceShutdown.Released>(
			fixture.runtime.shutdownIfOwned(owner, CUTOFF),
		)
		assertEquals(2, fixture.arbiter.clearDemandCalls)
	}

	@Test
	fun `missing durable Activity admission barrier remains incomplete`() = runTest {
		val fixture = fixture(admissionBarrier = null)
		val owner = claim("missing-admission-barrier")
		assertIs<SourceStartResult.Started>(
			fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK),
		)

		val incomplete = assertIs<OwnedSourceShutdown.Incomplete>(
			fixture.runtime.shutdownIfOwned(owner, CUTOFF),
		)

		requireNotNull(incomplete.stopAck).let { acknowledgement ->
			assertEquals(null, acknowledgement.lastAdmissionOrdinal)
			assertEquals(null, acknowledgement.lastDurablyAdmittedSequence)
			assertFalse(acknowledgement.appDrainComplete)
			assertEquals(
				ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
				acknowledgement.providerCoverage,
			)
			assertEquals(SourceStopStatus.TIMED_OUT, acknowledgement.status)
		}
	}

	@Test
	fun `durable Activity admission barrier supplies exact nonnull high-water`() = runTest {
		val fixture = fixture()
		val owner = claim("durable-admission-barrier")
		assertIs<SourceStartResult.Started>(
			fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK),
		)

		val released = assertIs<OwnedSourceShutdown.Released>(
			fixture.runtime.shutdownIfOwned(owner, CUTOFF),
		)

		requireNotNull(released.stopAck).let { acknowledgement ->
			assertEquals(23L, acknowledgement.lastAdmissionOrdinal)
			assertEquals(17L, acknowledgement.lastDurablyAdmittedSequence)
			assertEquals(17L, acknowledgement.callbackEntryBarrierSequence)
			assertTrue(acknowledgement.appDrainComplete)
			assertEquals(
				ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
				acknowledgement.providerCoverage,
			)
			assertEquals(SourceStopStatus.COMPLETE, acknowledgement.status)
			assertEquals(ProviderFlushOutcome.NOT_SUPPORTED, acknowledgement.providerFlushOutcome)
			assertEquals(0L, acknowledgement.failedAdmissionCount)
			assertEquals(null, acknowledgement.unresolvedSequenceStart)
		}
	}

	@Test
	fun `sole lost Activity delivery releases with a known gap and no invented WAL ordinal`() = runTest {
		val fixture = fixture(admissionBarrier = activityAdmissionBarrier().copy(lastAdmissionOrdinal = 0L, lastSourceSequence = 0L))
		val owner = claim("lost-only-callback")
		assertIs<SourceStartResult.Started>(fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK))
		coEvery { fixture.sourceBrokerDao.registration(any(), any()) } returns retiredRegistration(fixture.arbiter.identity)
			.copy(failureCode = "DEMAND_REMOVED|${ProviderRegistrationGenerationEntity.FAILURE_ACTIVITY_CALLBACK_LOSS_PREFIX}4")
		coEvery { fixture.sourceBrokerDao.authorizationRevision(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION, 4L) } returns
			listOf(captureAuthorization(4L))

		val ack = requireNotNull(assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(owner, CUTOFF)).stopAck)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(0L, ack.callbackEntryBarrierSequence)
		assertEquals(null, ack.lastAdmissionOrdinal)
		assertEquals(null, ack.lastDurablyAdmittedSequence)
		assertEquals(null, ack.unresolvedSequenceStart)
		assertEquals(null, ack.unresolvedSequenceEndInclusive)
	}

	@Test
	fun `prior capture loss does not poison a later seal on the same Activity generation`() = runTest {
		val fixture = fixture()
		val owner = claim("later-clean-capture")
		assertIs<SourceStartResult.Started>(fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK))
		coEvery { fixture.sourceBrokerDao.registration(any(), any()) } returns retiredRegistration(fixture.arbiter.identity)
			.copy(failureCode = "DEMAND_REMOVED|${ProviderRegistrationGenerationEntity.FAILURE_ACTIVITY_CALLBACK_LOSS_PREFIX}3")
		coEvery { fixture.sourceBrokerDao.authorizationRevision(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION, 3L) } returns
			listOf(captureAuthorization(3L, logicalId = "earlier-session", runId = "earlier-run"))

		val ack = requireNotNull(assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(owner, CUTOFF)).stopAck)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(0L, ack.failedAdmissionCount)
	}

	@Test
	fun `source off and on in the same session preserves its earlier callback loss`() = runTest {
		val fixture = fixture()
		val owner = claim("same-session-resumed-source")
		assertIs<SourceStartResult.Started>(fixture.runtime.start(owner, enabledPlan(1L), DISCARDING_SINK))
		coEvery { fixture.sourceBrokerDao.registration(any(), any()) } returns retiredRegistration(fixture.arbiter.identity)
			.copy(failureCode = "DEMAND_REMOVED|${ProviderRegistrationGenerationEntity.FAILURE_ACTIVITY_CALLBACK_LOSS_PREFIX}3")
		coEvery { fixture.sourceBrokerDao.authorizationRevision(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION, 3L) } returns
			listOf(captureAuthorization(3L))

		val ack = requireNotNull(assertIs<OwnedSourceShutdown.Released>(fixture.runtime.shutdownIfOwned(owner, CUTOFF)).stopAck)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(null, ack.unresolvedSequenceStart)
	}

	@Test
	fun `successful disabled reconfigure leaves no owned join`() = runTest {
		val fixture = fixture(setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR))
		val first = claim("start-action", leaseGeneration = 1L)
		val disabled = claim("disable-action", leaseGeneration = 2L)
		assertIs<SourceStartResult.Started>(
			fixture.runtime.start(first, enabledPlan(1L), DISCARDING_SINK),
		)

		assertIs<SourceApplyResult.Applied>(
			fixture.runtime.reconfigure(disabled, disabledPlan(2L), DISCARDING_SINK),
		)
		assertEquals(1, fixture.arbiter.clearDemandCalls)
		assertEquals(
			setOf(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR),
			fixture.arbiter.owners,
		)

		assertIs<OwnedSourceShutdown.NotOwned>(fixture.runtime.shutdownIfOwned(first, CUTOFF))
		assertIs<OwnedSourceShutdown.NotOwned>(fixture.runtime.shutdownIfOwned(disabled, CUTOFF))
		assertEquals(1, fixture.arbiter.clearDemandCalls)
	}

	private fun fixture(
		initialOwners: Set<ActivityRegistrationOwner> = emptySet(),
		admissionBarrier: SourceCaptureAdmissionBarrierEntity? = activityAdmissionBarrier(),
	): ActivityRuntimeFixture {
		val arbiter = RecordingActivityRegistrationArbiter(initialOwners)
		val registrationStateDao = mockk<SourceRegistrationStateDao>()
		coEvery { registrationStateDao.get(any(), any()) } returns null
		val database = mockk<AppDatabase>()
		every { database.sourceRegistrationStateDao() } returns registrationStateDao
		val sourceBrokerDao = mockk<SourceBrokerDao>()
		coEvery {
			sourceBrokerDao.captureAdmissionBarrier(SourceKind.ACTIVITY.stableCode, ACTIVITY_GENERATION)
		} returns admissionBarrier
		coEvery { sourceBrokerDao.registration(any(), any()) } returns null
		coEvery { sourceBrokerDao.authorizationRevision(any(), any(), any()) } returns emptyList()
		every { database.sourceBrokerDao() } returns sourceBrokerDao
		return ActivityRuntimeFixture(
			runtime = ActivitySourceRuntime(arbiter, database, SourcePlanCodec()),
			arbiter = arbiter,
			registrationStateDao = registrationStateDao,
			sourceBrokerDao = sourceBrokerDao,
		)
	}

	private data class ActivityRuntimeFixture(
		val runtime: ActivitySourceRuntime,
		val arbiter: RecordingActivityRegistrationArbiter,
		val registrationStateDao: SourceRegistrationStateDao,
		val sourceBrokerDao: SourceBrokerDao,
	)

	private companion object {
		fun captureAuthorization(revision: Long, logicalId: String = "activity-test", runId: String = "run-1") =
			SourceAuthorizationEntity(
				sourceKind = SourceKind.ACTIVITY.stableCode, registrationGeneration = ACTIVITY_GENERATION,
				authorizationRevision = revision, memberId = "demand:capture", authorizationFingerprint = "capture",
				purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				demandId = "capture", consumerId = "session:$logicalId", purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				sourcePolicyRevision = 1L, consentEpoch = 1L, persistenceEligible = true,
				effectiveBootId = "boot-id", effectiveElapsedRealtimeNanos = 1L, effectiveWallTimeMs = 1L,
				logicalTrackingId = logicalId, serviceRunId = runId, manifestRevision = 1L, lifecycleLeaseGeneration = 1L,
			)

		fun retiredRegistration(identity: ActivityRegistrationIdentity) = ProviderRegistrationGenerationEntity(
			sourceKind = SourceKind.ACTIVITY.stableCode, registrationGeneration = identity.registrationGeneration,
			sourceInstanceId = identity.sourceInstanceId, ownerScope = "source-broker:2",
			clockDomainId = identity.clockDomainId, physicalConfigurationFingerprint = identity.physicalConfigurationFingerprint,
			collectedDataEpoch = identity.collectedDataEpoch,
			providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			providerProcessIncarnationId = null, status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			reservedAtMs = 1L, reservedElapsedRealtimeNanos = 1L,
			acceptedAtMs = 2L, acceptedElapsedRealtimeNanos = 2L,
			retiredAtMs = 20L, retiredElapsedRealtimeNanos = 20L, failureCode = "DEMAND_REMOVED",
			captureCallbackBarrierAuthorizationRevision = 4L,
		)

		fun activityAdmissionBarrier() = SourceCaptureAdmissionBarrierEntity(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			registrationGeneration = ACTIVITY_GENERATION,
			sourceInstanceId = ACTIVITY_INSTANCE_ID,
			throughAuthorizationRevision = 4L,
			lastAdmissionOrdinal = 23L,
			lastSourceSequence = 17L,
			sealedElapsedRealtimeNanos = 10L,
			sealedAtMs = 20L,
		)

		val DISCARDING_SINK = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
		val CUTOFF = SessionCutoff(
			logicalTrackingId = "logical-session",
			elapsedRealtimeNanos = 10L,
			wallTimeMs = 20L,
			deadlineElapsedRealtimeNanos = 30L,
		)
	}
}

private enum class MutationFailurePoint { BEFORE, AFTER }

private const val ACTIVITY_INSTANCE_ID = "activity-instance"
private const val ACTIVITY_GENERATION = 7L

private class RecordingActivityRegistrationArbiter(
	initialOwners: Set<ActivityRegistrationOwner>,
) : ActivityRegistrationArbiter {
	var identity = ActivityRegistrationIdentity(
		sourceInstanceId = ACTIVITY_INSTANCE_ID,
		registrationGeneration = ACTIVITY_GENERATION,
		collectedDataEpoch = 3L,
		clockDomainId = "boot-id",
		physicalConfigurationFingerprint = "physical-fingerprint",
	)
	private val demands = initialOwners.associateWithTo(
		linkedMapOf<ActivityRegistrationOwner, ActivityRegistrationDemand>(),
	) {
		ActivityRegistrationDemand(continuousRecognitionIntervalSeconds = 60)
	}

	var nextSetFailure: MutationFailurePoint? = null
	var nextClearFailure: MutationFailurePoint? = null
	var nextClearStatus: ActivityRegistrationStatus? = null
	var afterClear: suspend () -> Unit = {}
	var startupBlocked: Boolean = false
	var clearDemandCalls: Int = 0
		private set
	val clearedOwners = mutableListOf<ActivityRegistrationOwner>()
	val owners: Set<ActivityRegistrationOwner>
		get() = demands.keys.toSet()

	fun demand(owner: ActivityRegistrationOwner): ActivityRegistrationDemand =
		requireNotNull(demands[owner])

	override suspend fun setDemand(
		owner: ActivityRegistrationOwner,
		demand: ActivityRegistrationDemand,
	): ActivityRegistrationResult {
		val failure = nextSetFailure.also { nextSetFailure = null }
		if (failure == MutationFailurePoint.BEFORE) throw mutationFailure("set", failure)
		if (demand.enabled) demands[owner] = demand else demands.remove(owner)
		if (failure == MutationFailurePoint.AFTER) throw mutationFailure("set", failure)
		return result(ActivityRegistrationStatus.APPLIED)
	}

	override suspend fun clearDemand(owner: ActivityRegistrationOwner): ActivityRegistrationResult {
		clearDemandCalls++
		clearedOwners += owner
		val failure = nextClearFailure.also { nextClearFailure = null }
		if (failure == MutationFailurePoint.BEFORE) throw mutationFailure("clear", failure)
		demands.remove(owner)
		if (failure == MutationFailurePoint.AFTER) throw mutationFailure("clear", failure)
		afterClear()
		return result(nextClearStatus.also { nextClearStatus = null } ?: if (startupBlocked) {
			ActivityRegistrationStatus.BLOCKED
		} else {
			ActivityRegistrationStatus.APPLIED
		})
	}

	override suspend fun reconcileDurableDemands(): ActivityRegistrationResult =
		result(ActivityRegistrationStatus.APPLIED)

	override suspend fun closeForCollectedDataDeletion(): ActivityRegistrationResult =
		result(ActivityRegistrationStatus.APPLIED)

	override suspend fun resumeAfterCollectedDataDeletion(): ActivityRegistrationResult =
		result(ActivityRegistrationStatus.APPLIED)

	override suspend fun retryPendingProviderCleanup(): ActivityProviderCleanupResult =
		ActivityProviderCleanupResult.COMPLETE

	override fun snapshot(): ActivityRegistrationSnapshot = ActivityRegistrationSnapshot(
		active = demands.isNotEmpty() && !startupBlocked,
		identity = identity,
		owners = owners,
		continuousRecognitionIntervalSeconds = demands.values
			.mapNotNull(ActivityRegistrationDemand::continuousRecognitionIntervalSeconds)
			.minOrNull(),
		transitions = demands.values.flatMap(ActivityRegistrationDemand::transitions).toSet(),
	)

	private fun result(status: ActivityRegistrationStatus) = ActivityRegistrationResult(
		status = status,
		snapshot = snapshot(),
		failureCode = when {
			status == ActivityRegistrationStatus.APPLIED -> null
			status == ActivityRegistrationStatus.BLOCKED && startupBlocked ->
				ActivityRegistrationFailureCode.STARTUP_RECOVERY_NOT_READY
			else -> ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED
		},
		retryable = status != ActivityRegistrationStatus.APPLIED,
	)

	private fun mutationFailure(operation: String, point: MutationFailurePoint) =
		IllegalStateException("$operation failed ${point.name.lowercase()} demand mutation")
}

private fun claim(
	actionId: String,
	leaseGeneration: Long = 1L,
) = SourceRuntimeClaim(
	source = SourceKind.ACTIVITY,
	actionId = actionId,
	attemptCount = 1,
	leaseGeneration = leaseGeneration,
	logicalTrackingId = "activity-test",
	serviceRunId = "run-1",
)

private fun enabledPlan(revision: Long) = ActivityPlan(
	revision = revision,
	mode = ActivityMode.CONTINUOUS_RECOGNITION,
	desiredDetectionLatencyMs = 5_000L,
	confidenceThresholdPercent = 50,
	transitionTypes = emptySet(),
)

private fun disabledPlan(revision: Long) = ActivityPlan(
	revision = revision,
	mode = ActivityMode.OFF,
	desiredDetectionLatencyMs = 5_000L,
	confidenceThresholdPercent = 50,
	transitionTypes = emptySet(),
)
