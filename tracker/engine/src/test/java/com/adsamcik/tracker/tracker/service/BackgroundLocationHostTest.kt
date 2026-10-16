package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.tracker.api.AtomicTrackingPurposeAvailabilityStore
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationReport
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlin.test.assertFailsWith
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
import com.adsamcik.tracker.tracker.api.TrackerServiceContract
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
	// Keep one acknowledged host through successor adoption, cancelled tickets, timeout and exact release
	// assertions together.
	@Suppress("LongMethod")
	fun `host requires acknowledged foreground and exact release preserves successor`(): Unit = runBlocking {
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
				TestDispatchersProvider(Dispatchers.Unconfined))
			val owner = Any()
			var released = 0
			host.attach(owner) { released++ }
			assertFailsWith<IllegalStateException> { host.attach(Any()) { error("Must not steal host") } }
			val first = identity("first")
			val pending = async { host.acquire(first) }
			val firstIntent = delivered.receive()
			host.active.value shouldBe null
			val scope = LocationMotionScope("scope", "boot", 1L, "host", setOf(LocationMotionPurpose.BACKGROUND))
			host.acceptDelivery(requireNotNull(firstIntent.getStringExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET)),
				owner, scope) { true } shouldBe true
			pending.await() shouldBe BackgroundLocationHostResult.Active(first, scope)
			val successor = identity("successor")
			val next = async { host.acquire(successor) }
			val nextIntent = delivered.receive()
			host.acceptDelivery(requireNotNull(nextIntent.getStringExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET)),
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
			host.acceptDelivery(requireNotNull(acceptedIntent.getStringExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET)),
				owner, scope) { true } shouldBe true
			abandoned.cancel()
			abandoned.join()
			accepted.await() shouldBe BackgroundLocationHostResult.Active(sharedIdentity, scope)
			host.active.value?.identity shouldBe sharedIdentity
			host.acceptDelivery(requireNotNull(abandonedIntent.getStringExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET)),
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
				host.acceptDelivery(requireNotNull(slowIntent.getStringExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET)),
					owner, scope) { promoted = true; true }
			}
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

	// Follow shared Wi-Fi/Cell hosting through successor adoption and exact retirement while preserving the sibling.
	@Suppress("LongMethod")
	@Test
	fun `radio-only hosts coexist and exact retirement preserves a sibling and successor`(): Unit = runBlocking {
		val app = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(app)
		try {
			val dao = database.sourcePolicyDao()
			dao.ensureAuthority(SourcePolicyAuthorityEntity(bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = 1L, legacySettingsFingerprint = null, updatedAtMs = 1L))
			dao.insertPolicies(listOf(5, 6).map { kind -> SourcePolicyEntity(1L, kind, false, 0, null, null, null,
				false, false, true, null, null, 2L, "boot", 1L, 1L, "TEST",
				backgroundNotificationHostEnabled = true) })
			dao.insertConsentEpochs(listOf(5, 6).map { kind -> SourceConsentEpochEntity(kind,
				SourceBrokerPurpose.AMBIENT_PRODUCT, 2L, true, true, 1L, "boot", 1L, 1L, "TEST") })
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
				delivered.trySend(arg(1)); ComponentName(app, TrackerService::class.java)
			}
			val status = AtomicTrackingPurposeAvailabilityStore()
			val host = BackgroundLocationHost(app, database, lifecycle, gate, commands,
				TestDispatchersProvider(Dispatchers.Unconfined), status)
			val owner = Any()
			host.attach(owner) {}
			val scope = LocationMotionScope("radio-host", "boot", 1L, "host", setOf(LocationMotionPurpose.BACKGROUND))
			val wifi = radioIdentity(AmbientTrackingSource.WIFI, "wifi")
			val cell = radioIdentity(AmbientTrackingSource.CELL, "cell")
			installRadioAuthority(database, wifi, 1L)
			installRadioAuthority(database, cell, 1L)
			for (identity in listOf(wifi, cell)) {
				status.beginOrReplaceAmbientLease(identity)
				status.tryAccept(AmbientSourceReconciliationReport(identity,
					AmbientSourceOperationalAvailability.ready(identity.source,
						if (identity.source == AmbientTrackingSource.WIFI) {
							AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS
						}
						else {
							AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS
						}, identity.purposeLeaseIdentity)))
				val pending = async { host.acquire(identity) }
				val intent = delivered.receive()
				var promoted = emptySet<SourceKind>()
				host.acceptDelivery(requireNotNull(intent.getStringExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET)),
					owner, scope) { promoted = it; true } shouldBe true
				pending.await() shouldBe BackgroundLocationHostResult.Active(identity, scope)
				promoted shouldBe host.hostedSources
				host.active.value shouldBe null
			}
			host.hostedSources shouldBe setOf(SourceKind.WIFI, SourceKind.CELL)
			(host.acquire(wifi) is BackgroundLocationHostResult.Active) shouldBe true
			delivered.tryReceive().isFailure shouldBe true
			(host.acquire(wifi.copy(ownerCasToken = "forged")) is BackgroundLocationHostResult.Blocked) shouldBe true
			val successor = wifi.copy(ownerCasToken = "wifi-successor")
			installRadioAuthority(database, successor, 2L)
			val pending = async { host.acquire(successor) }
			val intent = delivered.receive()
			host.acceptDelivery(requireNotNull(intent.getStringExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET)),
				owner, scope) { it shouldBe setOf(SourceKind.WIFI, SourceKind.CELL); true } shouldBe true
			pending.await() shouldBe BackgroundLocationHostResult.Active(successor, scope)
			host.releaseRadio(wifi) shouldBe true
			host.leases.value[AmbientTrackingSource.WIFI]?.identity shouldBe successor
			host.leases.value[AmbientTrackingSource.CELL]?.identity shouldBe cell
			host.releaseRadio(successor) shouldBe true
			host.hostedSources shouldBe setOf(SourceKind.CELL)
			host.isCurrentLeaseValid() shouldBe true
			host.detachAll(owner) shouldBe listOf(cell)
			host.hasActiveSources shouldBe false
			status.availability.value.ambientSources.getValue(AmbientTrackingSource.CELL).state shouldBe AmbientSourceOperationalState.DEGRADED
			status.availability.value.ambientSources.getValue(AmbientTrackingSource.CELL).reason shouldBe AmbientSourceUnavailableReason.FOREGROUND_HOST_UNAVAILABLE
			database.ambientCellFactDao().latestAuthority()?.isActive shouldBe true
			// Default-off hosting cannot borrow separately enabled recording consent.
			database.openHelper.writableDatabase.execSQL("UPDATE source_policy SET background_notification_host_enabled = 0 WHERE source_kind = 6")
			(host.acquire(cell) is BackgroundLocationHostResult.Blocked) shouldBe true
			delivered.tryReceive().isFailure shouldBe true
			database.openHelper.writableDatabase.execSQL("UPDATE source_policy SET background_notification_host_enabled = 1 WHERE source_kind = 6")
			host.attach(owner) {}
			val unacknowledged = async { host.acquire(cell) }
			val unacknowledgedIntent = delivered.receive()
			host.releaseRadio(cell) shouldBe true
			(unacknowledged.await() is BackgroundLocationHostResult.Blocked) shouldBe true
			host.acceptDelivery(requireNotNull(unacknowledgedIntent.getStringExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET)),
				owner, scope) { error("Retired pending request cannot promote") } shouldBe false
			host.hasActiveSources shouldBe false
			host.detachAll(owner)
		} finally { database.close() }
	}

	@Test
	fun `stopped startup cannot acquire a radio host`(): Unit = runBlocking {
		val app = ApplicationProvider.getApplicationContext<Application>()
		val database = AppDatabase.testDatabase(app)
		try {
			val lifecycle = mockk<CollectedDataLifecycleStore>()
			coEvery { lifecycle.snapshot() } returns CollectedDataLifecycleSnapshot(0L, null)
			val gate = mockk<TrackingStartupGate> { every { permitsNewCollection } returns false }
			val commands = mockk<TrackingLifecycleCommandAuthority>(relaxed = true)
			val host = BackgroundLocationHost(app, database, lifecycle, gate, commands,
				TestDispatchersProvider(Dispatchers.Unconfined))
			host.releaseRadio(radioIdentity(AmbientTrackingSource.WIFI, "none")) shouldBe true
			(host.acquire(radioIdentity(AmbientTrackingSource.CELL, "stopped")) is BackgroundLocationHostResult.Blocked) shouldBe true
			host.hasActiveSources shouldBe false
		} finally { database.close() }
	}

	private fun radioIdentity(source: AmbientTrackingSource, token: String) = AmbientReconciliationIdentity(source,
		1L, 2L, 0L, 1L, token, 1L, retentionPolicyId = "retention", retentionApprovalRevision = 1L)

	private suspend fun installRadioAuthority(database: AppDatabase, identity: AmbientReconciliationIdentity, revision: Long) {
		when (identity.source) {
			AmbientTrackingSource.WIFI -> database.ambientWifiFactDao().insertAuthority(AmbientWifiAuthorityIntegrity.create(
				revision, AmbientWifiAuthorityEntity.STATE_ACTIVE, identity.policyRevision, identity.consentEpoch,
				"retention", 1L, 0L, 0L, "boot", revision, revision, identity.rolloutRevision, identity.ownerCasToken, revision, "wifi-demand"))
			AmbientTrackingSource.CELL -> database.ambientCellFactDao().insertAuthority(AmbientCellAuthorityIntegrity.create(
				revision, AmbientCellAuthorityEntity.STATE_ACTIVE, identity.policyRevision, identity.consentEpoch,
				"retention", 1L, 0L, 0L, "boot", revision, revision, identity.rolloutRevision, identity.ownerCasToken, revision, "cell-demand"))
			else -> error("Only radio authority")
		}
	}

	private fun identity(token: String) = AmbientReconciliationIdentity(AmbientTrackingSource.LOCATION,
		1L, 2L, 0L, 1L, token, 1L)
}
