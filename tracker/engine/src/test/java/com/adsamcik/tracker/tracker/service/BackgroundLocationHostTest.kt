package com.adsamcik.tracker.tracker.service

import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.delay
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.api.registration.LocationMotionPurpose
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.source.control.LocationMotionScope
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackgroundLocationHostTest {
	@After
	fun tearDown() = unmockkAll()

	@Test
	fun `host requires acknowledged foreground and exact release preserves successor`() = runTest {
		val app = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(app)
		try {
			val dao = database.sourcePolicyDao()
			dao.ensureAuthority(SourcePolicyAuthorityEntity(bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = 1L, legacySettingsFingerprint = null, updatedAtMs = 1L))
			dao.insertPolicies(listOf(SourcePolicyEntity(1L, 1, false, 2, null, null, null,
				false, false, true, null, null, 2L, "boot", 1L, 1L, "TEST",
				balanceProfileVersion = 1, balanceLevel = 3, locationBackgroundMode = 2)))
			dao.insertConsentEpochs(listOf(SourceConsentEpochEntity(1, SourceBrokerPurpose.AMBIENT_PRODUCT,
				2L, true, true, 1L, "boot", 1L, 1L, "TEST")))
			val lifecycle = mockk<CollectedDataLifecycleStore>()
			coEvery { lifecycle.snapshot() } returns CollectedDataLifecycleSnapshot(0L, null)
			val gate = mockk<TrackingStartupGate> {
				every { isReady } returns true
				every { permitsNewCollection } returns true
				every { currentGeneration } returns 1L
				every { isReadyGeneration(1L) } returns true
			}
			val commands = mockk<TrackingLifecycleCommandAuthority> {
				every { latestStopGeneration() } returns 0L
				every { latestUnhandledStop() } returns null
			}
			val delivered = Channel<Intent>(Channel.UNLIMITED)
			mockkStatic(ContextCompat::class)
			every { ContextCompat.startForegroundService(app, any()) } answers {
				delivered.trySend(arg(1))
				ComponentName(app, TrackerService::class.java)
			}
			val host = BackgroundLocationHost(app, database, lifecycle, gate, commands,
				TestDispatchersProvider(StandardTestDispatcher(testScheduler)))
			val owner = Any()
			var released = 0
			host.attach(owner) { released++ }
			assertFailsWith<IllegalStateException> { host.attach(Any()) { error("Must not steal host") } }
			val first = identity("first")
			val pending = async { host.acquire(first) }
			val firstIntent = delivered.receive()
			host.active.value shouldBe null
			val scope = LocationMotionScope("scope", "boot", 1L, "host", setOf(LocationMotionPurpose.BACKGROUND))
			host.acceptDelivery(requireNotNull(firstIntent.getStringExtra(BackgroundLocationHost.EXTRA_TICKET)),
				owner, scope) { true } shouldBe true
			pending.await() shouldBe BackgroundLocationHostResult.Active(first, scope)
			val successor = identity("successor")
			val next = async { host.acquire(successor) }
			val nextIntent = delivered.receive()
			host.acceptDelivery(requireNotNull(nextIntent.getStringExtra(BackgroundLocationHost.EXTRA_TICKET)),
				owner, scope) { true } shouldBe true
			next.await() shouldBe BackgroundLocationHostResult.Active(successor, scope)
			host.release(first) shouldBe false
			host.active.value?.identity shouldBe successor
			host.release(successor) shouldBe true
			released shouldBe 1
			val sharedIdentity = identity("same-concurrent-identity")
			val abandoned = async { host.acquire(sharedIdentity) }
			val abandonedIntent = delivered.receive()
			val accepted = async { host.acquire(sharedIdentity) }
			val acceptedIntent = delivered.receive()
			host.acceptDelivery(requireNotNull(acceptedIntent.getStringExtra(BackgroundLocationHost.EXTRA_TICKET)),
				owner, scope) { true } shouldBe true
			abandoned.cancel()
			runCurrent()
			accepted.await() shouldBe BackgroundLocationHostResult.Active(sharedIdentity, scope)
			host.active.value?.identity shouldBe sharedIdentity
			host.acceptDelivery(requireNotNull(abandonedIntent.getStringExtra(BackgroundLocationHost.EXTRA_TICKET)),
				owner, scope) { error("Abandoned ticket must not promote") } shouldBe false
			host.release(sharedIdentity) shouldBe true
			val slowAcquire = async { host.acquire(identity("slow-authority")) }
			val slowIntent = delivered.receive()
			coEvery { lifecycle.snapshot() } coAnswers {
				delay(PRE_FOREGROUND_START_BUDGET_MILLIS + 1_000L)
				CollectedDataLifecycleSnapshot(0L, null)
			}
			var promoted = false
			val slowDelivery = async {
				host.acceptDelivery(requireNotNull(slowIntent.getStringExtra(BackgroundLocationHost.EXTRA_TICKET)),
					owner, scope) { promoted = true; true }
			}
			runCurrent()
			advanceTimeBy(PRE_FOREGROUND_START_BUDGET_MILLIS + 1L)
			runCurrent()
			slowDelivery.await() shouldBe false
			promoted shouldBe false
			host.active.value shouldBe null
			(slowAcquire.await() is BackgroundLocationHostResult.Blocked) shouldBe true
			host.detach(owner)
			host.acceptDelivery("old-or-redelivered-ticket", owner, scope) { error("Must not promote") } shouldBe false
		} finally {
			database.close()
		}
	}

	private fun identity(token: String) = AmbientReconciliationIdentity(AmbientTrackingSource.LOCATION,
		1L, 2L, 0L, 1L, token, 1L)
}
