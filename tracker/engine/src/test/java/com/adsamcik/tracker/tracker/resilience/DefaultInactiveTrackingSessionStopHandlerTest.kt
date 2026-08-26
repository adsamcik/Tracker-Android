package com.adsamcik.tracker.tracker.resilience

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultInactiveTrackingSessionStopHandlerTest {
	private val context: Application = ApplicationProvider.getApplicationContext()
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		clearLifecycleCommands()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
		clearLifecycleCommands()
	}

	@Test
	fun `absent service finalizes exact Room lifecycle before clearing its descriptor`() = runTest {
		insertActiveSession(LOGICAL_ID)
		insertActiveRun(LOGICAL_ID)
		val store = TestActiveSessionStore(descriptor(LOGICAL_ID))

		handler(store).finalizeStoredSession(stopCommand()) shouldBe
			InactiveTrackingSessionStopOutcome.FINALIZED

		store.descriptor shouldBe null
		val session = database.sourceSessionDao().session(LOGICAL_ID)
		session?.state shouldBe SessionLifecycleState.FINALIZED.name
		session?.completedAtMs shouldBe NOW_MS
		val run = database.sourceSessionDao().serviceRun("run:$LOGICAL_ID")
		run?.runtimeAcknowledgement shouldBe "TERMINAL_FAILURE"
		run?.runtimeFailureCode shouldBe "SERVICE_ABSENT_EXPLICIT_REQUEST"
	}

	@Test
	fun `descriptor whose Room session was deleted is cleared without touching newer lifecycle`() = runTest {
		insertActiveSession(NEW_LOGICAL_ID)
		val store = TestActiveSessionStore(descriptor(LOGICAL_ID))
		val authority = TestCommandAuthority()

		handler(store, authority).finalizeStoredSession(stopCommand()) shouldBe
			InactiveTrackingSessionStopOutcome.NO_ROOM_SESSION

		store.descriptor shouldBe null
		authority.isStopActionable(stopCommand()) shouldBe false
		database.sourceSessionDao().session(NEW_LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
	}

	@Test
	fun `genuine different incomplete Room lifecycle remains actionable`() = runTest {
		insertActiveSession(LOGICAL_ID, startedAtMs = 1_000L)
		insertActiveSession(NEW_LOGICAL_ID, startedAtMs = 2_000L)
		val store = TestActiveSessionStore(descriptor(LOGICAL_ID))
		val authority = TestCommandAuthority()

		handler(store, authority).finalizeStoredSession(stopCommand()) shouldBe
			InactiveTrackingSessionStopOutcome.SESSION_MISMATCH

		store.descriptor shouldBe descriptor(LOGICAL_ID)
		authority.isStopActionable(stopCommand()) shouldBe true
		database.sourceSessionDao().session(NEW_LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
	}

	@Test
	fun `delayed stop for prior run cannot finalize recovered run of same logical session`() = runTest {
		insertActiveSession(LOGICAL_ID)
		insertActiveRun(LOGICAL_ID, serviceRunId = OLD_RUN_ID, startedAtMs = 1_000L)
		insertActiveRun(LOGICAL_ID, serviceRunId = NEW_RUN_ID, startedAtMs = 2_000L)
		val store = TestActiveSessionStore(descriptor(LOGICAL_ID, OLD_RUN_ID))

		handler(store).finalizeStoredSession(stopCommand()) shouldBe
			InactiveTrackingSessionStopOutcome.SESSION_MISMATCH

		store.descriptor shouldBe descriptor(LOGICAL_ID, OLD_RUN_ID)
		database.sourceSessionDao().session(LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
		database.sourceSessionDao().serviceRun(NEW_RUN_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
	}

	@Test
	fun `new descriptor written during cleanup is preserved`() = runTest {
		insertActiveSession(LOGICAL_ID)
		insertActiveRun(LOGICAL_ID)
		val replacement = descriptor(NEW_LOGICAL_ID)
		val store = ReplacingOnClearStore(
			initialDescriptor = descriptor(LOGICAL_ID),
			replacementDescriptor = replacement,
		)

		handler(store).finalizeStoredSession(
			stopCommand(TrackingStopCandidateReason.INTERNAL_FAILURE),
		) shouldBe
			InactiveTrackingSessionStopOutcome.FINALIZED

		store.descriptor shouldBe replacement
		database.sourceSessionDao().session(LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
	}

	@Test
	fun `no stored descriptor is an idempotent no-op`() = runTest {
		val store = TestActiveSessionStore(null)

		handler(store).finalizeStoredSession(stopCommand()) shouldBe
			InactiveTrackingSessionStopOutcome.NO_STORED_DESCRIPTOR

		database.sourceSessionDao().incompleteSessions() shouldBe emptyList()
	}

	@Test
	fun `older stop cannot finalize after a newer start supersedes its fence`() = runTest {
		insertActiveSession(LOGICAL_ID)
		insertActiveRun(LOGICAL_ID)
		val store = TestActiveSessionStore(descriptor(LOGICAL_ID))
		val authority = TestCommandAuthority(current = false)

		handler(store, authority).finalizeStoredSession(stopCommand()) shouldBe
			InactiveTrackingSessionStopOutcome.SUPERSEDED

		store.descriptor shouldBe descriptor(LOGICAL_ID)
		database.sourceSessionDao().session(LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.ACTIVE.name
	}

	@Test
	fun `recreated process finalizes exact Room run demand and descriptor then handles stop`() = runTest {
		insertActiveSession(LOGICAL_ID)
		insertActiveRun(LOGICAL_ID)
		database.sourceBrokerDao().insertDemands(listOf(sessionDemand(LOGICAL_ID)))
		val store = TestActiveSessionStore(descriptor(LOGICAL_ID))
		val issuingAuthority =
			SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val stop = requireNotNull(
			issuingAuthority.reserveStop(
				TrackingStopCandidateReason.AUTOMATIC_ACTIVITY_INCOMPATIBLE,
				NOW_MS,
			),
		)

		val recreatedAuthority =
			SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		recreatedAuthority.latestUnhandledStop() shouldBe stop
		handler(store, recreatedAuthority).finalizeStoredSession(stop) shouldBe
			InactiveTrackingSessionStopOutcome.FINALIZED

		store.descriptor shouldBe null
		database.sourceSessionDao().session(LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		val finalizedRun = database.sourceSessionDao().serviceRun("run:$LOGICAL_ID")
		finalizedRun?.state shouldBe SessionLifecycleState.FINALIZED.name
		finalizedRun?.runtimeFailureCode shouldBe
			"SERVICE_ABSENT_AUTOMATIC_ACTIVITY_INCOMPATIBLE"
		database.sourceBrokerDao().demandHistory("session:$LOGICAL_ID").single().status shouldBe
			SourceDemandEntity.STATUS_RETIRED
		SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
			.latestUnhandledStop() shouldBe null
	}

	@Test
	fun `failed exact descriptor clear leaves stop actionable and startup retry completes it`() = runTest {
		insertActiveSession(LOGICAL_ID)
		insertActiveRun(LOGICAL_ID)
		val store = FailingOnceOnClearStore(descriptor(LOGICAL_ID))
		val authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
		val stop = requireNotNull(
			authority.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, NOW_MS),
		)
		val recreatedAuthority =
			SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)

		shouldThrow<IllegalStateException> {
			handler(store, recreatedAuthority).finalizeStoredSession(stop)
		}
		recreatedAuthority.latestUnhandledStop() shouldBe stop
		store.descriptor shouldBe descriptor(LOGICAL_ID)
		database.sourceSessionDao().session(LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.FINALIZED.name

		handler(store, recreatedAuthority).finalizeStoredSession(stop) shouldBe
			InactiveTrackingSessionStopOutcome.ALREADY_FINALIZED
		store.descriptor shouldBe null
		recreatedAuthority.latestUnhandledStop() shouldBe null
	}

	@Test
	fun `reopened process uses requested cutoff and current clocks only for authority retirement`() =
		runTest {
			insertActiveSession(LOGICAL_ID)
			insertActiveRun(LOGICAL_ID)
			database.sourceBrokerDao().insertDemands(listOf(sessionDemand(LOGICAL_ID)))
			val store = TestActiveSessionStore(descriptor(LOGICAL_ID))
			val command = stopCommand(
				requestedAtMs = 2_500L,
				requestedBootId = BOOT_ID,
				requestedElapsedRealtimeNanos = 2_500_000L,
			)

			handler(
				store = store,
				clock = FixedClock(9_000L, 9_000_000L),
			).finalizeStoredSession(command) shouldBe
				InactiveTrackingSessionStopOutcome.FINALIZED

			val session = database.sourceSessionDao().session(LOGICAL_ID)
			session?.completedAtMs shouldBe 2_500L
			session?.cutoffAtMs shouldBe 2_500L
			session?.cutoffElapsedNanos shouldBe 2_500_000L
			val demand = database.sourceBrokerDao().demandHistory("session:$LOGICAL_ID").single()
			demand.retiredAtMs shouldBe 9_000L
			demand.retireElapsedRealtimeNanos shouldBe 9_000_000L
		}

	private fun handler(
		store: ActiveTrackingSessionStore,
		commandAuthority: TrackingLifecycleCommandAuthority = TestCommandAuthority(),
		clock: Clock = FixedClock(NOW_MS, NOW_ELAPSED_NANOS),
		reconciliationBootId: String = BOOT_ID,
	) =
		DefaultInactiveTrackingSessionStopHandler(
			activeSessionStore = store,
			commandAuthority = commandAuthority,
			sourceSessionFinalizer = ExplicitStopSourceSessionFinalizer(Provider { database }),
			clock = clock,
			bootClockDomainProvider = object : BootClockDomainProvider {
				override fun current(): String = reconciliationBootId
			},
		)

	private fun stopCommand(
		reason: TrackingStopCandidateReason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
		requestedAtMs: Long = NOW_MS,
		requestedBootId: String? = null,
		requestedElapsedRealtimeNanos: Long? = null,
	) = TrackingStopCommand(
		generation = 2L,
		reason = reason,
		requestedAtEpochMs = requestedAtMs,
		requestedBootId = requestedBootId,
		requestedElapsedRealtimeNanos = requestedElapsedRealtimeNanos,
	)

	private suspend fun insertActiveSession(
		logicalTrackingId: String,
		startedAtMs: Long = 1_000L,
	) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalTrackingId,
				state = SessionLifecycleState.ACTIVE.name,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = BOOT_ID,
				startedAtMs = startedAtMs,
				startedElapsedNanos = 1_000L,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "MANUAL",
			),
		)
	}

	private suspend fun insertActiveRun(
		logicalTrackingId: String,
		serviceRunId: String = "run:$logicalTrackingId",
		startedAtMs: Long = 1_000L,
	) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				state = SessionLifecycleState.ACTIVE.name,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startedAtMs,
				startedElapsedNanos = 1_000L,
				completedAtMs = null,
				completionReason = null,
				bootId = BOOT_ID,
			),
		)
		val session = requireNotNull(database.sourceSessionDao().session(logicalTrackingId))
		database.sourceSessionDao().updateSession(
			session.copy(currentServiceRunId = serviceRunId),
		) shouldBe 1
	}

	private fun descriptor(
		logicalTrackingId: String,
		serviceRunId: String = "run:$logicalTrackingId",
	) = ActiveTrackingSessionDescriptor(
		isUserInitiated = true,
		isAmbient = false,
		policyTier = PolicyTier.ACTIVE,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
	)

	private fun sessionDemand(logicalTrackingId: String) = SourceDemandEntity(
		demandId = "demand:$logicalTrackingId",
		consumerId = "session:$logicalTrackingId",
		sourceKind = 3,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = "run:$logicalTrackingId",
		manifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 1,
		maximumAgeMs = 60_000L,
		desiredLatencyMs = 15_000L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = 1_000L,
		requestedAtMs = 1_000L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private open class TestActiveSessionStore(
		initialDescriptor: ActiveTrackingSessionDescriptor?,
	) : ActiveTrackingSessionStore {
		var descriptor: ActiveTrackingSessionDescriptor? = initialDescriptor
			protected set

		override suspend fun read(): ActiveTrackingSessionStoreResult =
			ActiveTrackingSessionStoreResult.Success(descriptor)

		override suspend fun save(
			descriptor: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			this.descriptor = descriptor
			return ActiveTrackingSessionStoreResult.Success(descriptor)
		}

		override suspend fun clear(): ActiveTrackingSessionStoreResult {
			descriptor = null
			return ActiveTrackingSessionStoreResult.Success(null)
		}
	}

	private class ReplacingOnClearStore(
		initialDescriptor: ActiveTrackingSessionDescriptor,
		private val replacementDescriptor: ActiveTrackingSessionDescriptor,
	) : TestActiveSessionStore(initialDescriptor) {
		override suspend fun clearExact(
			descriptor: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			this.descriptor = replacementDescriptor
			return ActiveTrackingSessionStoreResult.Success(replacementDescriptor)
		}
	}

	private class FailingOnceOnClearStore(
		initialDescriptor: ActiveTrackingSessionDescriptor,
	) : TestActiveSessionStore(initialDescriptor) {
		private var shouldFail = true

		override suspend fun clearExact(
			descriptor: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			if (shouldFail) {
				shouldFail = false
				return ActiveTrackingSessionStoreResult.Failure(
					IllegalStateException("simulated descriptor write failure"),
				)
			}
			return if (this.descriptor == descriptor) clear() else read()
		}
	}

	private class TestCommandAuthority(
		private var current: Boolean = true,
	) : TrackingLifecycleCommandAuthority {
		override suspend fun reserveStart(): TrackingStartCommand = TrackingStartCommand(1L)

		override suspend fun reserveRedeliveryRecoveryStart(
			redeliveredCommand: TrackingStartCommand,
		): TrackingRedeliveryRecoveryReservation =
			TrackingRedeliveryRecoveryReservation.Reserved(TrackingStartCommand(1L))

		override suspend fun reserveStop(
			reason: TrackingStopCandidateReason,
			requestedAtEpochMs: Long,
		): TrackingStopCommand = TrackingStopCommand(2L, reason, requestedAtEpochMs)

		override fun resolveStart(command: TrackingStartCommand): TrackingStartCommandDisposition =
			TrackingStartCommandDisposition.Allowed

		override suspend fun <T> withCurrentStart(
			command: TrackingStartCommand,
			action: suspend () -> T,
		): LockedTrackingStartResult<T> = LockedTrackingStartResult.Executed(action())

		override fun isStopCurrent(command: TrackingStopCommand): Boolean = current

		override fun isStopActionable(command: TrackingStopCommand): Boolean = current

		override suspend fun markStopHandled(command: TrackingStopCommand): Boolean {
			if (!current) return false
			current = false
			return true
		}
	}

	private companion object {
		const val LOGICAL_ID = "inactive-service-session"
		const val NEW_LOGICAL_ID = "newer-session"
		const val OLD_RUN_ID = "run:old"
		const val NEW_RUN_ID = "run:new"
		const val BOOT_ID = "boot-7"
		const val NOW_MS = 5_000L
		const val NOW_ELAPSED_NANOS = 5_000_000L
	}

	private fun clearLifecycleCommands() {
		context.getSharedPreferences("tracking_lifecycle_commands", Application.MODE_PRIVATE)
			.edit()
			.clear()
			.commit()
	}
}
