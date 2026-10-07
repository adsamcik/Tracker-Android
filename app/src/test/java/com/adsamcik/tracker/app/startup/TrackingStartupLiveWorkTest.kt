package com.adsamcik.tracker.app.startup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.app.ApplicationStartupRecoveryAction
import com.adsamcik.tracker.app.settings.CollectedDataDeletionCompletion
import com.adsamcik.tracker.app.settings.CollectedDataDeletionService
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.resilience.SharedPreferencesTrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecoveryResult
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production gate and durable command authority; no Android provider activation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingStartupLiveWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var commands: SharedPreferencesTrackingLifecycleCommandAuthority
    private lateinit var barrier: TrackingStartupDeletionBarrier
    private lateinit var gate: DefaultTrackingStartupGate

    @Before
    fun setup() {
        context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
        commands = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO,
            BootClockDomainProvider { "boot" })
        barrier = TrackingStartupDeletionBarrier(2_000L)
        val resolver = mockk<ApplicationStartupRecoveryResolver>()
        val storage = mockk<LegacyDatabaseUpgradeCoordinator>()
        val recovery = mockk<SourcePipelineRecovery>()
        val deletion = mockk<CollectedDataDeletionService>()
        every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
        coEvery { resolver.apply(any(), any()) } just Runs
        coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
        coEvery { recovery.recoverStartupAuthority() } returns LegacyV27ProjectionRecoveryResult.NotRequired
        coEvery { deletion.reconcilePendingDeletion() } returns CollectedDataDeletionCompletion.Complete
        gate = DefaultTrackingStartupGate(
            legacyDatabaseUpgradeCoordinator = storage,
            collectedDataDeletionService = deletion,
            recoveryResolver = resolver,
            lifecycleCommandAuthority = commands,
            inactiveSessionStopHandler = mockk(),
            sourcePipelineRecoveryProvider = Provider { recovery },
            deletionBarrier = barrier,
        )
    }

    @Test
    fun `live work reserves a real Start and can enter ordinary preparation without deadlock`() = runBlocking {
        gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
        withTimeout(2_000L) {
            gate.withReadyGenerationWork(0L) {
                val start = requireNotNull(commands.reserveStart())
                gate.withReadyGenerationOperation(0L) { start.generation }
            }
        } shouldBe 1L
    }

    @Test
    fun `live work does not block a newer End and withholds stale completion`() = runBlocking {
        gate.reconcile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val work = async {
            gate.withReadyGenerationWork(0L) { entered.complete(Unit); release.await(); "old" }
        }
        entered.await()
        val stop = withTimeout(2_000L) {
            requireNotNull(commands.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L))
        }
        release.complete(Unit)
        work.await() shouldBe null
        commands.latestUnhandledStop() shouldBe stop
    }

    @Test
    fun `clear cancels live startup and joins its physical cleanup before closing generation`() = runBlocking {
        gate.reconcile()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val work = async {
            runCatching {
                gate.withReadyGenerationWork(0L) {
                    try { entered.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) {
                        events += "cleanup-start"; cleaning.complete(Unit); finish.await(); events += "cleanup-end"
                    } }
                }
            }
        }
        entered.await()
        barrier.beginCloseAdmission()
        val clear = async { barrier.awaitQuiescence(); events += "clear" }
        cleaning.await()
        withTimeoutOrNull(50L) { clear.await() } shouldBe null
        barrier.currentGeneration shouldBe 0L
        finish.complete(Unit)
        withTimeout(2_000L) { clear.await(); work.await() }.isFailure shouldBe true
        events shouldBe listOf("cleanup-start", "cleanup-end", "clear")
        barrier.currentGeneration shouldBe 1L
    }

    @Test
    fun `closed or stale live work never runs and never opens Off collection`() = runBlocking {
        gate.reconcile()
        commands.recordUserRequestedExit("off") shouldBe true
        gate.permitsNewCollection shouldBe false
        gate.withReadyGenerationWork(0L) { gate.permitsNewCollection } shouldBe false
        barrier.closeAdmission()
        var effects = 0
        gate.withReadyGenerationWork(0L) { effects++ } shouldBe null
        gate.withReadyGenerationWork(1L) { effects++ } shouldBe null
        effects shouldBe 0
    }
}
