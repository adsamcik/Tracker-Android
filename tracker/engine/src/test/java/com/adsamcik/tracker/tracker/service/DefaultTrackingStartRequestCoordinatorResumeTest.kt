package com.adsamcik.tracker.tracker.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.ManualTrackingStartSelection
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackingCaptureSource
import com.adsamcik.tracker.tracker.api.TrackingStartFailureDisposition
import com.adsamcik.tracker.tracker.api.TrackingStartPreparationResult
import com.adsamcik.tracker.tracker.api.TrackingStartRequest
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingCallerAuthorityReconciliation
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionCallerAuthorityReconciler
import com.adsamcik.tracker.tracker.resilience.SharedPreferencesTrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.SourcePlanIdentity
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import com.adsamcik.tracker.tracker.policy.BatteryPressure
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.coordinator.AndroidStartDeliveryMetadata
import com.adsamcik.tracker.tracker.source.coordinator.PreparedSessionStart
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartPreparationResult
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStartRequest
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultTrackingStartRequestCoordinatorResumeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store: ActiveTrackingSessionStore = mockk()
    private val database = mockk<AppDatabase>(relaxed = true)
    private val sourceSession = mockk<TrackerServiceSourceSession>()
    private val rolloutStore = mockk<RoomTrackingRolloutStateStore>()
    private val callerReconciler = mockk<ActiveTrackingSessionCallerAuthorityReconciler>()
    private val battery = mockk<BatteryAwarePolicy> { every { currentPressure() } returns BatteryPressure.NONE }
    private lateinit var authority: SharedPreferencesTrackingLifecycleCommandAuthority
    private var beforeLease: suspend () -> Unit = {}
    private var enteredLease = false
    private var parameterStates: Flow<TrackingParamsState> = MutableStateFlow(TrackingParamsState())

    @Before
    fun setUp() {
        context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
        authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
        // Deliberately stop after entering real PREPARE without manufacturing Room/provider success.
        coEvery { store.read() } returns ActiveTrackingSessionStoreResult.Failure(IllegalStateException("unavailable"))
    }

    @Test
    fun `actual failed PREPARE retains Off and revokes only the pending delivery`(): Unit = runBlocking {
        authority.recordUserRequestedExit("exit-1") shouldBe true
        val request = TrackingStartRequest(requireNotNull(authority.reserveStart()), true, false)
        withTimeout(2_000L) {
            coordinator().prepare(request) shouldBe TrackingStartPreparationResult.Rejected(
                "ACTIVE_DESCRIPTOR_READ_FAILED", TrackingStartFailureDisposition.RETRYABLE,
            )
        }
        enteredLease shouldBe true
        authority.pendingCollectionResumeExitId shouldBe "exit-1"
        authority.isPendingCollectionStart(request.command) shouldBe false
        SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
            .pendingCollectionResumeExitId shouldBe "exit-1"
        coVerify(exactly = 1) { store.read() }
    }

    @Test
    fun `new exit between acknowledgment and startup lease prevents descriptor or provider work`(): Unit = runBlocking {
        authority.recordUserRequestedExit("exit-1") shouldBe true
        val request = TrackingStartRequest(requireNotNull(authority.reserveStart()), true, false)
        beforeLease = { authority.recordUserRequestedExit("exit-2") shouldBe true }
        withTimeout(2_000L) {
            coordinator().prepare(request) shouldBe TrackingStartPreparationResult.Rejected("TRACKING_EXPLICIT_RESUME_REQUIRED")
        }
        authority.pendingCollectionResumeExitId shouldBe "exit-2"
        coVerify(exactly = 0) { store.read() }
    }

    @Test
    fun `ambient and recovery requests never acknowledge the pending tracker exit`(): Unit = runBlocking {
        authority.recordUserRequestedExit("exit-1") shouldBe true
        val coordinator = coordinator()
        val ambient = TrackingStartRequest(requireNotNull(authority.reserveStart()), true, true)
        val recovery = TrackingStartRequest(requireNotNull(authority.reserveStart()), true, false,
            recoveryDescriptor = mockk<ActiveTrackingSessionDescriptor>())
        withTimeout(2_000L) {
            coordinator.prepare(ambient) shouldBe TrackingStartPreparationResult.Rejected("TRACKING_EXPLICIT_RESUME_REQUIRED")
            coordinator.prepare(recovery) shouldBe TrackingStartPreparationResult.Rejected("TRACKING_EXPLICIT_RESUME_REQUIRED")
        }
        authority.pendingCollectionResumeExitId shouldBe "exit-1"
        enteredLease shouldBe false
        coVerify(exactly = 0) { store.read() }
    }

    @Test
    fun `cancelled descriptor PREPARE keeps original cancellation and durable Off`(): Unit = runBlocking {
        authority.recordUserRequestedExit("exit-1") shouldBe true
        val request = TrackingStartRequest(requireNotNull(authority.reserveStart()), true, false)
        val cancelled = kotlinx.coroutines.CancellationException("descriptor read cancelled")
        coEvery { store.read() } throws cancelled
        val failure = try { coordinator().prepare(request); error("Cancellation must escape") }
            catch (caught: kotlinx.coroutines.CancellationException) { caught }
        (failure === cancelled) shouldBe true
        authority.isPendingCollectionStart(request.command) shouldBe false
        authority.pendingCollectionResumeExitId shouldBe "exit-1"
    }

    @Test
    fun `cold shared settings publication completes before PREPARE takes the command mutex`(): Unit = runBlocking {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            parameterStates = flow {
                authority.runWithHandledStopGeneration(0L) { emit(TrackingParamsState()) } shouldBe true
            }.shareIn(applicationScope, SharingStarted.Lazily, replay = 1)
            coEvery { store.read() } returns ActiveTrackingSessionStoreResult.Success(null)
            val request = TrackingStartRequest(requireNotNull(authority.reserveStart()), true, false,
                manualSourceSelection = ManualTrackingStartSelection(mapOf(TrackingCaptureSource.STEPS to 1L)))

            withTimeout(2_000L) {
                coordinator().prepare(request)
            } shouldBe TrackingStartPreparationResult.Rejected("MANUAL_START_SELECTION_CHANGED")
            enteredLease shouldBe true
            coVerify(exactly = 1) { store.read() }
        } finally {
            applicationScope.cancel()
        }
    }

    @Test
    fun `cancelled initial settings wait revokes pending Start and keeps durable Off`(): Unit = runBlocking {
        authority.recordUserRequestedExit("exit-1") shouldBe true
        val request = TrackingStartRequest(requireNotNull(authority.reserveStart()), true, false)
        val cancelled = kotlinx.coroutines.CancellationException("initial settings cancelled")
        parameterStates = flow { throw cancelled }

        val failure = try { coordinator().prepare(request); error("Cancellation must escape") }
            catch (caught: kotlinx.coroutines.CancellationException) { caught }

        (failure === cancelled) shouldBe true
        enteredLease shouldBe false
        authority.isPendingCollectionStart(request.command) shouldBe false
        authority.pendingCollectionResumeExitId shouldBe "exit-1"
        coVerify(exactly = 0) { store.read() }
    }

    @Test
    fun `actual PREPARE routes an older unaccepted descriptor as a fresh manual start`(): Unit = runBlocking {
        val (stored, request) = preparedRetryFixture()
        var preparedInRoom = false
        var saved: ActiveTrackingSessionDescriptor? = null
        coEvery { sourceSession.prepareAndroidStartUnderReadyGeneration(any(), any(), any()) } coAnswers {
            val fresh = firstArg<SourceSessionStartRequest>()
            val delivery = secondArg<AndroidStartDeliveryMetadata>()
            fresh.origin shouldBe SessionStartOrigin.MANUAL_FOREGROUND_START
            fresh.continuationAuthority shouldBe null
            (fresh.logicalTrackingId != stored.logicalTrackingId) shouldBe true
            (fresh.serviceRunId != stored.serviceRunId) shouldBe true
            preparedInRoom = true
            SessionStartPreparationResult.Prepared(PreparedSessionStart(delivery.token,
                fresh.logicalTrackingId, fresh.serviceRunId, 1L, 1L,
                SourceCallerReplayReference("replacement-authority"),
                SourcePlanIdentity(generation = 1L, inputsFingerprint = "a".repeat(64), planFingerprint = "b".repeat(64))))
        }
        coEvery { store.save(any()) } coAnswers {
            preparedInRoom shouldBe true
            saved = firstArg()
            ActiveTrackingSessionStoreResult.Success(saved)
        }

        withTimeout(5_000L) { coordinator().prepare(request) }
            .shouldBeInstanceOf<TrackingStartPreparationResult.Prepared>()

        saved?.sourceCallerAuthorityReference shouldBe SourceCallerReplayReference("replacement-authority")
        (saved?.logicalTrackingId != stored.logicalTrackingId) shouldBe true
        coVerify(exactly = 0) { callerReconciler.reconcile(any()) }
        coVerify(exactly = 0) { store.clearExact(any()) }
    }

    @Test
    fun `actual PREPARE preserves the old descriptor when Room refuses its replacement`(): Unit = runBlocking {
        val (stored, request) = preparedRetryFixture()
        coEvery { sourceSession.prepareAndroidStartUnderReadyGeneration(any(), any(), any()) } coAnswers {
            firstArg<SourceSessionStartRequest>().continuationAuthority shouldBe null
            SessionStartPreparationResult.AlreadyActive
        }

        withTimeout(5_000L) { coordinator().prepare(request) } shouldBe TrackingStartPreparationResult.AlreadyActive

        store.read() shouldBe ActiveTrackingSessionStoreResult.Success(stored)
        coVerify(exactly = 1) { sourceSession.prepareAndroidStartUnderReadyGeneration(any(), any(), any()) }
        coVerify(exactly = 0) { store.save(any()) }
        coVerify(exactly = 0) { store.clearExact(any()) }
        coVerify(exactly = 0) { callerReconciler.reconcile(any()) }
    }

    @Test
    fun `actual PREPARE retains normal recovery authority for an accepted owner`(): Unit = runBlocking {
        val (stored, request) = preparedRetryFixture(accepted = true)
        coEvery { callerReconciler.reconcile(stored) } returns ActiveTrackingCallerAuthorityReconciliation.Ready(stored)
        coEvery { sourceSession.prepareAndroidStartUnderReadyGeneration(any(), any(), any()) } coAnswers {
            val recovery = firstArg<SourceSessionStartRequest>()
            recovery.logicalTrackingId shouldBe stored.logicalTrackingId
            recovery.continuationAuthority?.previousServiceRunId shouldBe stored.serviceRunId
            SessionStartPreparationResult.AlreadyActive
        }

        withTimeout(5_000L) { coordinator().prepare(request) } shouldBe TrackingStartPreparationResult.AlreadyActive

        store.read() shouldBe ActiveTrackingSessionStoreResult.Success(stored)
        coVerify(exactly = 1) { callerReconciler.reconcile(stored) }
        coVerify(exactly = 0) { store.save(any()) }
        coVerify(exactly = 0) { store.clearExact(any()) }
    }

    private suspend fun preparedRetryFixture(accepted: Boolean = false): Pair<ActiveTrackingSessionDescriptor, TrackingStartRequest> {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LOCATION, true)
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        parameterStates = MutableStateFlow(TrackingParamsState(locationEnabled = true, activityEnabled = false,
            stepsEnabled = false, barometerEnabled = false, wifiEnabled = false, cellEnabled = false))
        coEvery { rolloutStore.load() } returns TrackingRolloutState.eventCanonical(setOf(SourceKind.LOCATION))
        val oldCommand = requireNotNull(authority.reserveStart())
        val stored = ActiveTrackingSessionDescriptor(true, false, PolicyTier.OFF,
            logicalTrackingId = "old-logical", serviceRunId = "old-run", restartBootId = "boot", restartToken = "old-restart",
            sourceCallerAuthorityReference = SourceCallerReplayReference("old-authority"))
        coEvery { store.read() } returns ActiveTrackingSessionStoreResult.Success(stored)
        coEvery { database.sourceSessionDao().serviceRun(stored.serviceRunId) } returns SourceServiceRunEntity(
            stored.serviceRunId, stored.logicalTrackingId, if (accepted) { "ACTIVE" } else { "STARTING" },
            1L, 1L, 8L, 1_000L, 1_000_000L, null, null, bootId = "boot", leaseGeneration = 1L,
            runtimeAcknowledgement = if (accepted) { "START_ACCEPTED" } else { "AWAITING_FOREGROUND" },
            startCommandGeneration = oldCommand.generation,
            androidDeliveryState = if (accepted) { "FOREGROUND_ACCEPTED" } else { "ENQUEUED" })
        return stored to TrackingStartRequest(requireNotNull(authority.reserveStart()), true, false)
    }

    private fun coordinator(): DefaultTrackingStartRequestCoordinator {
        val gate = object : TrackingStartupGate {
            override val isReady = true
            override val permitsNewCollection get() = authority.pendingCollectionResumeExitId == null
            override fun permitsPendingManualStart(commandGeneration: Long) = authority.isPendingCollectionStart(
                com.adsamcik.tracker.tracker.resilience.TrackingStartCommand(commandGeneration))
            override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
            override suspend fun <T> withReadyGenerationOperation(expectedGeneration: Long, operation: suspend () -> T): T? {
                beforeLease()
                var result: T? = null
                authority.runWithHandledStopGeneration(0L) {
                    enteredLease = true
                    result = operation()
                } shouldBe true
                return result
            }
        }
        return DefaultTrackingStartRequestCoordinator(
            context = context,
            database = database,
            trackingStartupGate = gate,
            trackingParamsRepository = mockk<TrackingParamsRepository> { every { data } returns parameterStates },
            activeTrackingSessionStore = store,
            sourceSession = sourceSession,
            authoritativeSessionCoordinator = mockk(),
            trackingRolloutStateStore = rolloutStore,
            chosenSourceWriterSetup = mockk {
                coEvery { ensure(any()) } coAnswers {
                    val chosen = firstArg<Set<com.adsamcik.tracker.tracker.source.model.SourceKind>>()
                    com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterSetupResult(chosen.associateWith {
                        com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterStatus.READY })
                }
            },
            bootClockDomainProvider = BootClockDomainProvider { "boot" },
            collectionMotionController = CollectionMotionController(),
            activityAutomationEpochAuthority = mockk(),
            trackingLifecycleCommandAuthority = authority,
            sourceCallerDemandDispatcher = mockk(),
            activeTrackingSessionCallerAuthorityReconciler = callerReconciler,
            sourcePolicyRepository = mockk(),
            batteryAwarePolicy = battery,
        )
    }
}
