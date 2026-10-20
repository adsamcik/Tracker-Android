package com.adsamcik.tracker.tracker.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.resilience.SharedPreferencesTrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.source.coordinator.AuthoritativeSessionCoordinator
import com.adsamcik.tracker.tracker.source.coordinator.ClaimedPreparedSessionStart
import com.adsamcik.tracker.tracker.source.coordinator.PreparedSessionClaimResult
import com.adsamcik.tracker.tracker.source.coordinator.DesiredPlanStatus
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartResult
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual coordinator and durable command mutex, controlled Room acknowledgement; not provider proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedStartForegroundAcceptanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val room = mockk<AuthoritativeSessionCoordinator>()
    private val params = mockk<TrackingParamsRepository>()
    private val sourceSession = mockk<TrackerServiceSourceSession>()
    private lateinit var commands: SharedPreferencesTrackingLifecycleCommandAuthority
    private var workCalls = 0
    private var serializedCalls = 0
    private var beforeWork: suspend () -> Unit = {}
    private var validateWorkReadiness = false
    private val token = PreparedTrackingStartToken("exact-token")

    @Before
    fun setup() {
        context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
        commands = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO,
            BootClockDomainProvider { "boot" })
        coEvery { room.markPreparedForegroundAccepted(token, any(), "boot", any(), any()) } returns true
        every { params.data } returns flowOf(TrackingParamsState())
    }

    @Test
    fun `claim reaches its lifecycle authority while unrelated settings hold the command lane`(): Unit = runBlocking {
        val start = requireNotNull(commands.reserveStart())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val settings = launch {
            commands.runWithHandledStopGeneration(0L) {
                entered.complete(Unit)
                release.await()
            }
        }
        coEvery { room.claimAndroidStart(token, start.generation, "boot", any(), any()) } returns
            PreparedSessionClaimResult.Rejected("PREPARED_START_NOT_FOUND")
        try {
            withTimeout(5_000L) {
                entered.await()
                coordinator().claimForService(token, start.generation)
            } shouldBe TrackingServicePreparedStartClaim.Rejected("PREPARED_START_NOT_FOUND")
            coVerify(exactly = 1) { room.claimAndroidStart(token, start.generation, "boot", any(), any()) }
            commands.isAcceptedStart(start) shouldBe false
            serializedCalls shouldBe 0
        } finally {
            release.complete(Unit)
            settings.cancelAndJoin()
        }
    }

    @Test
    fun `current manual foreground and Room acceptance resumes Off only after both succeed`(): Unit = runBlocking {
        commands.recordUserRequestedExit("off") shouldBe true
        val start = requireNotNull(commands.reserveStart())
        commands.prepareCollectionResumeForManualStart("off", start) shouldBe true
        val prepared = prepared()
        val events = mutableListOf<String>()
        coEvery { room.markPreparedForegroundAccepted(token, start.generation, "boot", any(), any()) } coAnswers {
            commands.pendingCollectionResumeExitId shouldBe "off"
            events += "room"
            true
        }
        withTimeout(2_000L) {
            coordinator().acceptForeground(prepared, start) {
                commands.pendingCollectionResumeExitId shouldBe "off"
                events += "foreground"
                true
            }
        } shouldBe prepared
        commands.pendingCollectionResumeExitId shouldBe null
        commands.isPendingCollectionStart(start) shouldBe false
        events shouldBe listOf("foreground", "room")
        workCalls shouldBe 1
        serializedCalls shouldBe 0
        SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
            .pendingCollectionResumeExitId shouldBe null
    }

    @Test
    fun `automatic accepted Start uses the same flow without reacquiring the command mutex`(): Unit = runBlocking {
        val start = requireNotNull(commands.reserveStart())
        val prepared = prepared()
        withTimeout(2_000L) { coordinator().acceptForeground(prepared, start) { true } } shouldBe prepared
        workCalls shouldBe 1
        serializedCalls shouldBe 0
        coVerify(exactly = 1) { room.markPreparedForegroundAccepted(token, start.generation, "boot", any(), any()) }
    }

    @Test
    fun `failed foreground or Room acceptance leaves Off and does not accept the Start`(): Unit = runBlocking {
        for (foreground in listOf(false, true)) {
            commands.recordUserRequestedExit("off-$foreground") shouldBe true
            val start = requireNotNull(commands.reserveStart())
            commands.prepareCollectionResumeForManualStart("off-$foreground", start) shouldBe true
            coEvery { room.markPreparedForegroundAccepted(token, start.generation, "boot", any(), any()) } returns false
            val result = withTimeout(2_000L) { coordinator().acceptForeground(prepared(), start) { foreground } }
            (result is TrackingServicePreparedStartClaim.Rejected) shouldBe true
            commands.pendingCollectionResumeExitId shouldBe "off-$foreground"
            coVerify(exactly = if (foreground) {
                1
            } else {
                0
            }) {
                room.markPreparedForegroundAccepted(token, start.generation, "boot", any(), any())
            }
        }
    }

    @Test
    fun `new Start before final acceptance blocks the old foreground and Room operation`(): Unit = runBlocking {
        val start = requireNotNull(commands.reserveStart())
        beforeWork = { commands.reserveStart() }
        var promoted = false
        coordinator().acceptForeground(prepared(), start) { promoted = true; true } shouldBe
            TrackingServicePreparedStartClaim.Rejected("START_COMMAND_STALE_BEFORE_FOREGROUND_ACCEPTANCE")
        promoted shouldBe false
        coVerify(exactly = 0) { room.markPreparedForegroundAccepted(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `previously accepted command cannot regain foreground after a newer Start was reserved`(): Unit = runBlocking {
        val start = requireNotNull(commands.reserveStart())
        commands.withCurrentStart(start, acceptWhen = { it }) { true }
        beforeWork = { commands.reserveStart() }
        var promoted = false
        coordinator().acceptForeground(prepared(), start) { promoted = true; true } shouldBe
            TrackingServicePreparedStartClaim.Rejected("START_COMMAND_STALE_BEFORE_FOREGROUND_ACCEPTANCE")
        promoted shouldBe false
        coVerify(exactly = 0) { room.markPreparedForegroundAccepted(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `new End before final acceptance blocks the old foreground and Room operation`(): Unit = runBlocking {
        val start = requireNotNull(commands.reserveStart())
        beforeWork = { commands.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L) }
        var promoted = false
        coordinator().acceptForeground(prepared(), start) { promoted = true; true } shouldBe
            TrackingServicePreparedStartClaim.Rejected("START_BLOCKED_BY_STOP_BEFORE_FOREGROUND_ACCEPTANCE")
        promoted shouldBe false
        coVerify(exactly = 0) { room.markPreparedForegroundAccepted(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Room cancellation escapes and cannot release the durable Off latch`(): Unit = runBlocking {
        commands.recordUserRequestedExit("off") shouldBe true
        val start = requireNotNull(commands.reserveStart())
        commands.prepareCollectionResumeForManualStart("off", start) shouldBe true
        val cancellation = CancellationException("clear arrived")
        coEvery { room.markPreparedForegroundAccepted(token, start.generation, "boot", any(), any()) } throws cancellation
        val failure = shouldThrow<CancellationException> {
            withTimeout(2_000L) { coordinator().acceptForeground(prepared(), start) { true } }
        }
        generateSequence<Throwable>(failure) { it.cause }.any { it === cancellation } shouldBe true
        commands.pendingCollectionResumeExitId shouldBe "off"
        SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
            .pendingCollectionResumeExitId shouldBe "off"
    }

    @Test
    fun `End reserves while prepared providers wait and withholds their stale Start result`(): Unit = runBlocking {
        val start = requireNotNull(commands.reserveStart())
        commands.withCurrentStart(start, acceptWhen = { it }) { true }
        validateWorkReadiness = true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { sourceSession.applyPreparedAndroidStart(any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            SessionStartResult.Started("logical", "run", emptyList(), DesiredPlanStatus.EFFECTIVE)
        }
        val applying = async { coordinator().apply(prepared(), start.generation) }
        try {
            entered.await()
            val stop = withTimeout(2_000L) {
                requireNotNull(commands.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L))
            }
            commands.latestUnhandledStop() shouldBe stop
            release.complete(Unit)
            applying.await() shouldBe null
            serializedCalls shouldBe 0
        } finally {
            release.complete(Unit)
            applying.cancelAndJoin()
        }
    }

    private fun prepared(): TrackingServicePreparedStartClaim.Claimed {
        val claim = mockk<ClaimedPreparedSessionStart>()
        every { claim.token } returns token
        every { claim.isProviderStepsJoin } returns false
        return TrackingServicePreparedStartClaim.Claimed(claim, mockk(), mockk(), mockk(), 0L)
    }

    private fun coordinator(): DefaultTrackingStartRequestCoordinator {
        val gate = object : TrackingStartupGate {
            override val isReady get() = !validateWorkReadiness || commands.latestUnhandledStop() == null
            override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
            override suspend fun <T> withReadyGenerationWork(expectedGeneration: Long, operation: suspend () -> T): T? {
                workCalls++
                beforeWork()
                if (!isReady) {
                    return null
                }
                val result = operation()
                return result.takeIf { isReady }
            }
            override suspend fun <T> withReadyGenerationOperation(expectedGeneration: Long, operation: suspend () -> T): T? {
                serializedCalls++
                var value: T? = null
                commands.runWithHandledStopGeneration(0L) { value = operation() }
                return value
            }
        }
        return DefaultTrackingStartRequestCoordinator(
            context = context, database = mockk(), trackingStartupGate = gate,
            trackingParamsRepository = params, activeTrackingSessionStore = mockk(),
            sourceSession = sourceSession, authoritativeSessionCoordinator = room,
            trackingRolloutStateStore = mockk(),
            chosenSourceWriterSetup = mockk {
                coEvery { ensure(any()) } coAnswers {
                    val chosen = firstArg<Set<com.adsamcik.tracker.tracker.source.model.SourceKind>>()
                    com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterSetupResult(chosen.associateWith {
                        com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterStatus.READY })
                }
            }, bootClockDomainProvider = BootClockDomainProvider { "boot" },
            collectionMotionController = mockk(), activityAutomationEpochAuthority = mockk(),
            trackingLifecycleCommandAuthority = commands, sourceCallerDemandDispatcher = mockk(),
            activeTrackingSessionCallerAuthorityReconciler = mockk(), sourcePolicyRepository = mockk(),
            batteryAwarePolicy = mockk(),
        )
    }
}
