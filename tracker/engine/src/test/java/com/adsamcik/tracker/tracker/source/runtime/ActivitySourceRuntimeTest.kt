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
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivitySourceRuntimeTest {
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
	): ActivityRuntimeFixture {
		val arbiter = RecordingActivityRegistrationArbiter(initialOwners)
		val registrationStateDao = mockk<SourceRegistrationStateDao>()
		coEvery { registrationStateDao.get(any(), any()) } returns null
		val database = mockk<AppDatabase>()
		every { database.sourceRegistrationStateDao() } returns registrationStateDao
		return ActivityRuntimeFixture(
			runtime = ActivitySourceRuntime(arbiter, database),
			arbiter = arbiter,
			registrationStateDao = registrationStateDao,
		)
	}

	private data class ActivityRuntimeFixture(
		val runtime: ActivitySourceRuntime,
		val arbiter: RecordingActivityRegistrationArbiter,
		val registrationStateDao: SourceRegistrationStateDao,
	)

	private companion object {
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
	private val identity = ActivityRegistrationIdentity(
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
	var clearDemandCalls: Int = 0
		private set
	val clearedOwners = mutableListOf<ActivityRegistrationOwner>()
	val owners: Set<ActivityRegistrationOwner>
		get() = demands.keys.toSet()

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
		return result(nextClearStatus.also { nextClearStatus = null } ?: ActivityRegistrationStatus.APPLIED)
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
		active = demands.isNotEmpty(),
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
		failureCode = if (status == ActivityRegistrationStatus.APPLIED) {
			null
		} else {
			ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED
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
