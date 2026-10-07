package com.adsamcik.tracker.tracker.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.api.TrackingStartFailureDisposition
import com.adsamcik.tracker.tracker.api.TrackingStartPreparationResult
import com.adsamcik.tracker.tracker.api.TrackingStartRequest
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.SharedPreferencesTrackingLifecycleCommandAuthority
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultTrackingStartRequestCoordinatorResumeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store: ActiveTrackingSessionStore = mockk()
    private lateinit var authority: SharedPreferencesTrackingLifecycleCommandAuthority
    private var beforeLease: suspend () -> Unit = {}
    private var enteredLease = false

    @Before
    fun setUp() {
        context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
        authority = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO)
        // Deliberately stop after entering real PREPARE without manufacturing Room/provider success.
        coEvery { store.read() } returns ActiveTrackingSessionStoreResult.Failure(IllegalStateException("unavailable"))
    }

    @Test
    fun `actual failed PREPARE retains Off and revokes only the pending delivery`() = runBlocking {
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
    fun `new exit between acknowledgment and startup lease prevents descriptor or provider work`() = runBlocking {
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
    fun `ambient and recovery requests never acknowledge the pending tracker exit`() = runBlocking {
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
    fun `cancelled descriptor PREPARE keeps original cancellation and durable Off`() = runBlocking {
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
            database = mockk(),
            trackingStartupGate = gate,
            trackingParamsRepository = mockk(),
            activeTrackingSessionStore = store,
            sourceSession = mockk(),
            authoritativeSessionCoordinator = mockk(),
            trackingRolloutStateStore = mockk(),
            bootClockDomainProvider = BootClockDomainProvider { "boot" },
            collectionMotionController = mockk(),
            activityAutomationEpochAuthority = mockk(),
            trackingLifecycleCommandAuthority = authority,
            sourceCallerDemandDispatcher = mockk(),
            activeTrackingSessionCallerAuthorityReconciler = mockk(),
            sourcePolicyRepository = mockk(),
            batteryAwarePolicy = mockk(),
        )
    }
}
