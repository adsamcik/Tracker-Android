package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.location.UiLocationRequest
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.resilience.SharedPreferencesTrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.service.BackgroundLocationHost
import com.adsamcik.tracker.tracker.service.DefaultTrackingTurnOffController
import com.adsamcik.tracker.testing.fake.FakeLocationSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.time.Duration
import javax.inject.Provider
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSourceRuntimeTest {
	@Test
	fun `cold system Location reuses exact passive identity without an active replacement`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		val cold = installColdSystemLocation(fixture)
		coEvery { fixture.registrations.canReuseSystemLocation(cold.row!!, any()) } returns true
		coEvery { fixture.registrations.beginSystemRearmableLocation(any(), any(), any()) } returns cold.registration

		val result = fixture.runtime.start(cold.plan, fixture.sink)

		assertTrue(result is SourceStartResult.Started)
		val applied = (result as SourceStartResult.Started).applied
		assertEquals(9L, applied.registrationGeneration)
		assertEquals("location-1", applied.sourceInstanceId?.value)
		verify(exactly = 1) { fixture.passiveBackend.prepare(cold.identity) }
		coVerify(exactly = 1) { fixture.passiveBackend.start(cold.plan, any()) }
		coVerify(exactly = 0) { fixture.passiveBackend.removeExact(any()) }
		coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.fusedBackend.start(any(), any()) }
		assertTrue(fixture.sink.deliveries.isEmpty())
		fixture.runtime.close()
	}

	@Test
	fun `cold system retirement acknowledgement precedes active Location reservation and start`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		val cold = installColdSystemLocation(fixture)
		val entered = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		coEvery { fixture.registrations.completeRetirement(any()) } coAnswers {
			entered.complete(Unit)
			release.await()
			cold.acknowledge(firstArg())
		}
		val starting = async { fixture.runtime.start(fixture.plan, fixture.sink) }
		runCurrent()
		entered.await()
		coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.fusedBackend.start(any(), any()) }
		assertFalse(starting.isCompleted)
		release.complete(Unit)
		assertTrue(starting.await() is SourceStartResult.Started)
		assertEquals(listOf("retiring", "remove-exact", "acknowledged"), cold.events)
		coVerify(exactly = 1) { fixture.passiveBackend.removeExact(cold.identity) }
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `failed cold system removal is retryable and preserves the exact retirement token`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		val cold = installColdSystemLocation(fixture)
		coEvery { fixture.passiveBackend.removeExact(cold.identity) } returnsMany listOf(
			RegistrationRemovalOutcome.FAILED, RegistrationRemovalOutcome.REMOVED)

		val first = fixture.runtime.start(fixture.plan, fixture.sink)
		assertTrue(first is SourceStartResult.Failed && first.retryable)
		val exactToken = requireNotNull(cold.pending)
		coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.fusedBackend.start(any(), any()) }
		assertEquals(ProviderRegistrationGenerationEntity.STATUS_RETIRING, cold.row?.status)

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(exactToken) }
		coVerify(exactly = 1) { fixture.registrations.beginSystemLocationRetirement(any(), any()) }
		coVerify(exactly = 2) { fixture.passiveBackend.removeExact(cold.identity) }
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
		assertNull(cold.row)
		fixture.runtime.close()
	}

	@Test
	fun `cold system removal without durable acknowledgement cannot start an active owner`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		val cold = installColdSystemLocation(fixture)
		var attempts = 0
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			if (attempts++ == 0) false else cold.acknowledge(firstArg())
		}

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Failed)
		val exactToken = requireNotNull(cold.pending)
		coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.fusedBackend.start(any(), any()) }
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		coVerify(exactly = 2) { fixture.registrations.completeRetirement(exactToken) }
		coVerify(exactly = 1) { fixture.registrations.beginSystemLocationRetirement(any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `passive active passive Location transitions retain one owner and exact system retirement`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		val cold = installColdSystemLocation(fixture)
		coEvery { fixture.registrations.canReuseSystemLocation(cold.row!!, any()) } returns true
		val restoredPlan = cold.plan.copy(revision = 3L)
		val restored = registration(restoredPlan, 3L, generation = 11L, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT)
		var reservations = 0
		coEvery { fixture.registrations.beginSystemRearmableLocation(any(), any(), any()) } answers {
			if (reservations++ == 0) cold.registration else restored.also {
				cold.row = systemLocationRow(it)
			}
		}
		coEvery { fixture.registrations.beginRetirement(any(), any(), any(), any()) } answers {
			val owner = firstArg<SourceRegistration>()
			if (owner.state.registrationGeneration == cold.row?.registrationGeneration) {
				cold.retire(secondArg(), thirdArg(), arg(3))
			} else retirementToken(owner, secondArg(), thirdArg(), arg(3))
		}
		assertTrue(fixture.runtime.start(cold.plan, fixture.sink) is SourceStartResult.Started)
		assertTrue(fixture.runtime.reconfigure(fixture.plan, fixture.sink) is SourceApplyResult.Applied)
		coVerify(exactly = 1) { fixture.passiveBackend.removeExact(cold.identity) }
		assertTrue(fixture.runtime.reconfigure(restoredPlan, fixture.sink) is SourceApplyResult.Applied)
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 2) { fixture.passiveBackend.start(any(), any()) }
		verify(exactly = 1) { fixture.passiveBackend.prepare(PassiveLocationIdentity(
			restoredPlan.backend, "location-1", 11L, "boot-1", restored.physicalConfigurationFingerprint, 1L)) }
		assertEquals(11L, cold.row?.registrationGeneration)
		assertTrue(fixture.sink.deliveries.isEmpty())
		fixture.runtime.close()
	}

	@Test
	fun `shared temporary UI lease cannot bypass a cold system request without retirement authority`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		val cold = installColdSystemLocation(fixture)
		coEvery { fixture.registrations.beginSystemLocationRetirement(any(), any()) } returns null
		val broker = mockk<SourceBroker>()
		coEvery { broker.authorizationDemands(SourceKind.LOCATION) } returns emptyList()
		val factory = mockk<DurableSourceEventSinkFactory>()
		every { factory.unbound } returns fixture.sink
		val shared = SharedLocationSourceController(fixture.runtime, broker, factory)

		assertNull(shared.acquireUiLease(UiLocationRequest("my-location", 1_000L, highAccuracy = true)))
		assertEquals(cold.initialRow, cold.row)
		coVerify(exactly = 0) { fixture.fusedBackend.start(any(), any()) }
		coVerify(exactly = 0) { fixture.passiveBackend.removeExact(any()) }
		coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		assertTrue(fixture.sink.deliveries.isEmpty())
	}

	@Test
	fun `composed shared map and game leases deliver fresh points without tracking persistence`() = runTest {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
		val fixture = locationRuntimeFixture(backgroundScope)
		val broker = mockk<SourceBroker>()
		coEvery { broker.authorizationDemands(SourceKind.LOCATION) } returns emptyList()
		val factory = mockk<DurableSourceEventSinkFactory>()
		every { factory.unbound } returns fixture.sink
		val shared = SharedLocationSourceController(fixture.runtime, broker, factory)
		val received = mutableListOf<UiLocationEvent>()
		val collector = backgroundScope.launch { shared.uiLocationEvents.collect { received += it } }
		runCurrent()
		val map = requireNotNull(shared.acquireUiLease(
			UiLocationRequest("my-location", 2_000L, 500L, highAccuracy = true, maximumAgeMillis = 10_000L)))
		val staleMapCallback = fixture.providerCallback()
		val game = requireNotNull(shared.acquireUiLease(
			UiLocationRequest("game", 1_000L, 250L, highAccuracy = true, maximumAgeMillis = 5_000L)))
		staleMapCallback(listOf(location(49.0, SystemClock.elapsedRealtimeNanos())))
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, received.filterIsInstance<UiLocationEvent.Fix>().size)
		shared.releaseUiLease(map)
		coVerify(exactly = 2) { fixture.fusedBackend.start(any(), any()) }
		shared.releaseUiLease(game)
		fixture.providerCallback()(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, received.filterIsInstance<UiLocationEvent.Fix>().size)
		assertTrue(fixture.sink.deliveries.isEmpty())
		coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 0) { fixture.registrations.beginSystemRearmableLocation(any(), any(), any()) }
		collector.cancel()
	}

	@Test
	fun `composed Off fences durable Location while fresh temporary leases remain independent`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(dispatcher)
		val context = ApplicationProvider.getApplicationContext<Application>()
		val preferences = context.getSharedPreferences(
			SharedPreferencesTrackingLifecycleCommandAuthority.PREFERENCES_NAME, Context.MODE_PRIVATE)
		assertTrue(preferences.edit().clear().commit())
		val database = AppDatabase.testDatabase(context)
		mockkObject(BackgroundTrackingApi)
		try {
			ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
			val clock = BootClockDomainProvider { "boot-1" }
			val commands = SharedPreferencesTrackingLifecycleCommandAuthority(context, dispatcher, clock)
			val identity = AmbientReconciliationIdentity(
				AmbientTrackingSource.LOCATION, 1L, 1L, 1L, 1L, "location-owner", 1L,
				retentionPolicyId = "location-retention", retentionApprovalRevision = 1L)
			val reference = SourceCallerReplayReference("location-off-ui")
			val authorities = RoomSourceCallerAcceptedAuthorityRepository(database)
			assertTrue(authorities.insertIfAbsent(reference, StoredSourceCallerAuthority(
				StoredSourceCallerOrigin.PURPOSE_OWNER, TrackingPurpose.AMBIENT_PRODUCT,
				setOf(SourceCallerDemandIdentity(identity.purposeLeaseIdentity, manifestIdentity = null))), 1L))
			val demand = SourceDemandEntity(
				demandId = "location-off-ui-demand", consumerId = "ambient:location",
				sourceKind = SourceKind.LOCATION.stableCode, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
				logicalTrackingId = null, serviceRunId = null, manifestRevision = null,
				lifecycleLeaseGeneration = null, sourcePolicyRevision = 1L, consentEpoch = 1L,
				persistenceEligible = true, qosCode = 0, maximumAgeMs = 60_000L,
				desiredLatencyMs = 1_000L, requestedBootId = "boot-1",
				requestedElapsedRealtimeNanos = 1L, requestedAtMs = 1L,
				status = SourceDemandEntity.STATUS_ACTIVE, retireBootId = null,
				retireElapsedRealtimeNanos = null, retiredAtMs = null,
				sourceCallerAuthorityReference = reference.value,
				liveAmbientRetentionPolicyId = identity.retentionPolicyId,
				liveAmbientRetentionApprovalRevision = identity.retentionApprovalRevision)
			val fixture = locationRuntimeFixture(backgroundScope)
			val plan = fixture.plan.copy(mode = LocationMode.HIGH_ACCURACY,
				requestedIntervalMs = 1_000L, minimumUpdateIntervalMs = 250L, maximumBatchDelayMs = 0L)
			val original = registration(plan, 1L, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT)
			val rows = SourceBrokerAuthorization.rows(SourceKind.LOCATION.stableCode,
				original.state.registrationGeneration, 1L, listOf(demand), "boot-1", 1L, 1L)
			val active = original.copy(authorization = requireNotNull(rows.toAuthorizationSnapshotOrNull()))
			val dao = database.sourceBrokerDao()
			dao.insertDemands(listOf(demand))
			dao.insertRegistration(systemLocationRow(active).copy(
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "process-1"))
			dao.insertAuthorizations(rows)
			coEvery { fixture.registrations.begin(any(), any(), any(), any(), any()) } returns active
			coEvery { fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any()) } returns active
			val broker = SourceBroker(database)
			val factory = mockk<DurableSourceEventSinkFactory>()
			every { factory.unbound } returns fixture.sink
			val shared = SharedLocationSourceController(fixture.runtime, broker, factory)
			assertTrue(shared.reconcileAmbientJoin(plan, identity.policyRevision) is AmbientLocationRuntimeJoinResult.Active)

			val reporter = mockk<TrackingPurposeAvailabilityReporter>(relaxed = true)
			val host = BackgroundLocationHost(context, database, mockk(), mockk(), commands, mockk(), reporter)
			val locationOwner = AmbientLocationPurposeOwner(context, broker, mockk(), host, shared,
				mockk(), mockk(), mockk(), clock, reporter, mockk(), mockk())
			val issuer = mockk<SerializedTrackingPurposeLeaseIssuer>(relaxed = true)
			coEvery { issuer.currentAutomaticControlLease() } returns null
			coEvery { issuer.currentAmbientLease(AmbientTrackingSource.LOCATION) } returns AmbientReconciliationLease(identity)
			val owners = DefaultTrackingPurposePublicationRuntime(
				leaseIssuer = issuer, reporter = reporter,
				executionRevisionRegistry = TrackingPurposeExecutionRevisionRegistry(),
				retentionAuthorityProducer = mockk(), ownerCallbackScope = backgroundScope,
				ownerCallbackTimeoutMillis = 2_000L,
				ambientLocationPurposeOwnerProvider = Provider { locationOwner })
			coEvery { BackgroundTrackingApi.stopAutomaticControlForUser(any()) } returns true
			val off = DefaultTrackingTurnOffController(context, commands, database, clock, owners)
			val lifecycleOwner = mockk<LifecycleOwner>()
			val lifecycle = LifecycleRegistry(lifecycleOwner)
			every { lifecycleOwner.lifecycle } returns lifecycle
			lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
			lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
			val provider = SharedUiLocationProvider(shared, lifecycle)
			val mapFixes = mutableListOf<android.location.Location>()
			val gameFixes = mutableListOf<android.location.Location>()
			val map = backgroundScope.launch {
				provider.locationUpdates(UiLocationRequest("my-location", 1_000L, 250L,
					highAccuracy = true, maximumAgeMillis = 10_000L)).collect { mapFixes += it }
			}
			var game: kotlinx.coroutines.Job? = null
			try {
				runCurrent()
				assertEquals(listOf("my-location"), provider.activeRequests.value.map { it.tag })
				val oldTrackingCallback = fixture.providerCallback()
				oldTrackingCallback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(listOf(50.0), mapFixes.map { it.latitude })
				assertEquals(1, fixture.sink.deliveries.size)
				val admittedBeforeOff = fixture.sink.deliveries.toList()
				assertEquals(SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
					requireNotNull(dao.latestAuthorization(SourceKind.LOCATION.stableCode,
						active.state.registrationGeneration).toAuthorizationSnapshotOrNull()).purposeEligibilityMask)

				val reservation = requireNotNull(off.reserveAndFence())
				assertEquals(reservation.exitId, commands.pendingCollectionResumeExitId)
				assertEquals(SourceDemandEntity.STATUS_RETIRING, dao.demandHistory(demand.consumerId).single().status)
				assertTrue(broker.authorizationDemands(SourceKind.LOCATION).isEmpty())
				assertEquals(0L, requireNotNull(dao.latestAuthorization(SourceKind.LOCATION.stableCode,
					active.state.registrationGeneration).toAuthorizationSnapshotOrNull()).purposeEligibilityMask)
				assertEquals(rows, dao.authorizationRevision(SourceKind.LOCATION.stableCode,
					active.state.registrationGeneration, 1L))
				assertNull(off.closeOwnersAfterSessionStop(reservation.exitId))
				// There is no session in this case; acknowledge its session-stop phase before owner closure.
				assertTrue(commands.markStopHandled(reservation.stop))
				assertEquals(true, off.closeOwnersAfterSessionStop(reservation.exitId))
				assertEquals(SourceDemandEntity.STATUS_RETIRED, dao.demandHistory(demand.consumerId).single().status)
				assertEquals(StoredSourceCallerAuthorityLoadResult.Retired, authorities.load(reference))
				assertEquals(reservation.exitId,
					SharedPreferencesTrackingLifecycleCommandAuthority(context, dispatcher, clock).pendingCollectionResumeExitId)
				verify(exactly = 1) { reporter.publishAmbientUnavailable(
					AmbientTrackingSource.LOCATION, AmbientSourceUnavailableReason.USER_TURNED_OFF) }
				coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }

				// Off does not cancel an explicit map request or prohibit a new independent game request.
				game = backgroundScope.launch {
					provider.locationUpdates(UiLocationRequest("game", 1_000L, 250L,
						highAccuracy = true, maximumAgeMillis = 5_000L, cancelOnAppBackground = false))
						.collect { gameFixes += it }
				}
				runCurrent()
				assertEquals(setOf("my-location", "game"), provider.activeRequests.value.map { it.tag }.toSet())
				val mapBeforeLateCallback = mapFixes.size
				val gameBeforeLateCallback = gameFixes.size
				oldTrackingCallback(listOf(location(49.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(mapBeforeLateCallback, mapFixes.size)
				assertEquals(gameBeforeLateCallback, gameFixes.size)
				val temporaryCallback = fixture.providerCallback()
				temporaryCallback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos() - 7_000_000_000L)))
				runCurrent()
				assertEquals(51.0, mapFixes.last().latitude, 0.0)
				assertEquals(gameBeforeLateCallback, gameFixes.size)
				temporaryCallback(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(52.0, mapFixes.last().latitude, 0.0)
				assertEquals(52.0, gameFixes.last().latitude, 0.0)
				assertEquals(admittedBeforeOff, fixture.sink.deliveries)

				map.cancelAndJoin()
				runCurrent()
				assertEquals(listOf("game"), provider.activeRequests.value.map { it.tag })
				val stoppedMapCount = mapFixes.size
				temporaryCallback(listOf(location(53.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(stoppedMapCount, mapFixes.size)
				assertEquals(53.0, gameFixes.last().latitude, 0.0)
				requireNotNull(game).cancelAndJoin()
				runCurrent()
				assertTrue(provider.activeRequests.value.isEmpty())
				val stoppedGameCount = gameFixes.size
				temporaryCallback(listOf(location(54.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(stoppedGameCount, gameFixes.size)
				assertEquals(admittedBeforeOff, fixture.sink.deliveries)
				assertTrue(broker.authorizationDemands(SourceKind.LOCATION).isEmpty())
				assertEquals(reservation.exitId, commands.pendingCollectionResumeExitId)
				coVerify(exactly = 1) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
				coVerify(exactly = 0) { fixture.registrations.beginSystemRearmableLocation(any(), any(), any()) }
				coVerify(exactly = 2) { fixture.fusedBackend.start(any(), any()) }
				coVerify(exactly = 2) { fixture.fusedBackend.stop() }
			} finally {
				map.cancelAndJoin()
				game?.cancelAndJoin()
				shared.close()
			}
		} finally {
			unmockkObject(BackgroundTrackingApi)
			database.close()
			preferences.edit().clear().commit()
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `composed feature flows apply independent freshness and retain the remaining lease`() = runTest {
		Dispatchers.setMain(StandardTestDispatcher(testScheduler))
		try {
			ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
			val fixture = locationRuntimeFixture(backgroundScope)
			val broker = mockk<SourceBroker>()
			coEvery { broker.authorizationDemands(SourceKind.LOCATION) } returns emptyList()
			val factory = mockk<DurableSourceEventSinkFactory>()
			every { factory.unbound } returns fixture.sink
			val shared = SharedLocationSourceController(fixture.runtime, broker, factory)
			val lifecycleOwner = mockk<LifecycleOwner>()
			val lifecycle = LifecycleRegistry(lifecycleOwner)
			every { lifecycleOwner.lifecycle } returns lifecycle
			lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
			lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
			val provider = SharedUiLocationProvider(shared, lifecycle)
			val mapFixes = mutableListOf<android.location.Location>()
			val gameFixes = mutableListOf<android.location.Location>()
			val map = backgroundScope.launch {
				provider.locationUpdates(UiLocationRequest("map", 1_000L,
					maximumAgeMillis = 10_000L)).collect { mapFixes += it }
			}
			val game = backgroundScope.launch {
				provider.locationUpdates(UiLocationRequest("game", 1_000L,
					maximumAgeMillis = 5_000L, cancelOnAppBackground = false))
					.collect { gameFixes += it }
			}
			try {
				runCurrent()
				assertEquals(setOf("map", "game"), provider.activeRequests.value.map { it.tag }.toSet())
				val callback = fixture.providerCallback()
				callback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos() - 7_000_000_000L)))
				runCurrent()
				assertEquals(listOf(50.0), mapFixes.map { it.latitude })
				assertTrue(gameFixes.isEmpty())
				callback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(listOf(50.0, 51.0), mapFixes.map { it.latitude })
				assertEquals(listOf(51.0), gameFixes.map { it.latitude })

				map.cancelAndJoin()
				assertEquals(listOf("game"), provider.activeRequests.value.map { it.tag })
				coVerify(exactly = 0) { fixture.fusedBackend.stop() }
				fixture.providerCallback()(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(listOf(50.0, 51.0), mapFixes.map { it.latitude })
				assertEquals(listOf(51.0, 52.0), gameFixes.map { it.latitude })
				requireNotNull(game).cancelAndJoin()
				assertTrue(provider.activeRequests.value.isEmpty())
				coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
				coVerify(exactly = 1) { fixture.fusedBackend.stop() }
				callback(listOf(location(53.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(listOf(51.0, 52.0), gameFixes.map { it.latitude })
				assertTrue(fixture.sink.deliveries.isEmpty())
				coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
				coVerify(exactly = 0) { fixture.registrations.beginSystemRearmableLocation(any(), any(), any()) }
			} finally {
				map.cancelAndJoin()
				requireNotNull(game).cancelAndJoin()
			}
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `composed map lifecycle releases and reacquires independently of the game flow`() = runTest {
		Dispatchers.setMain(StandardTestDispatcher(testScheduler))
		try {
			ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
			val fixture = locationRuntimeFixture(backgroundScope)
			val broker = mockk<SourceBroker>()
			coEvery { broker.authorizationDemands(SourceKind.LOCATION) } returns emptyList()
			val factory = mockk<DurableSourceEventSinkFactory>()
			every { factory.unbound } returns fixture.sink
			val shared = SharedLocationSourceController(fixture.runtime, broker, factory)
			val observedEvents = mutableListOf<UiLocationEvent>()
			val observer = backgroundScope.launch {
				shared.uiLocationEvents.collect { observedEvents += it }
			}
			val lifecycleOwner = mockk<LifecycleOwner>()
			val lifecycle = LifecycleRegistry(lifecycleOwner)
			every { lifecycleOwner.lifecycle } returns lifecycle
			lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
			lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
			val provider = SharedUiLocationProvider(shared, lifecycle)
			val mapFixes = mutableListOf<android.location.Location>()
			val gameFixes = mutableListOf<android.location.Location>()
			val map = backgroundScope.launch {
				provider.locationUpdates(UiLocationRequest("map", 1_000L,
					maximumAgeMillis = 10_000L)).collect { mapFixes += it }
			}
			val game = backgroundScope.launch {
				provider.locationUpdates(UiLocationRequest("game", 1_000L,
					maximumAgeMillis = 5_000L, cancelOnAppBackground = false))
					.collect { gameFixes += it }
			}
			try {
				runCurrent()
				fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(listOf(50.0), mapFixes.map { it.latitude })
				assertEquals(listOf(50.0), gameFixes.map { it.latitude })

				lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
				runCurrent()
				assertEquals(listOf("game"), provider.activeRequests.value.map { it.tag })
				coVerify(exactly = 0) { fixture.fusedBackend.stop() }
				fixture.providerCallback()(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(listOf(50.0), mapFixes.map { it.latitude })
				assertEquals(listOf(50.0, 51.0), gameFixes.map { it.latitude })

				lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
				runCurrent()
				assertEquals(setOf("map", "game"), provider.activeRequests.value.map { it.tag }.toSet())
				fixture.providerCallback()(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(listOf(50.0, 52.0), mapFixes.map { it.latitude })
				assertEquals(listOf(50.0, 51.0, 52.0), gameFixes.map { it.latitude })
				requireNotNull(game).cancelAndJoin()
				assertEquals(listOf("map"), provider.activeRequests.value.map { it.tag })
				coVerify(exactly = 0) { fixture.fusedBackend.stop() }
				val retiredCallback = fixture.providerCallback()
				retiredCallback(listOf(location(53.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(listOf(50.0, 52.0, 53.0), mapFixes.map { it.latitude })
				assertEquals(listOf(50.0, 51.0, 52.0), gameFixes.map { it.latitude })
				map.cancelAndJoin()
				assertTrue(provider.activeRequests.value.isEmpty())
				coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
				coVerify(exactly = 1) { fixture.fusedBackend.stop() }
				val eventsAfterLastRelease = observedEvents.size
				retiredCallback(listOf(location(54.0, SystemClock.elapsedRealtimeNanos())))
				runCurrent()
				assertEquals(eventsAfterLastRelease, observedEvents.size)
				assertEquals(listOf(50.0, 52.0, 53.0), mapFixes.map { it.latitude })
				assertTrue(fixture.sink.deliveries.isEmpty())
				coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
				coVerify(exactly = 0) { fixture.registrations.beginSystemRearmableLocation(any(), any(), any()) }
			} finally {
				map.cancelAndJoin()
				requireNotNull(game).cancelAndJoin()
				observer.cancelAndJoin()
			}
		} finally {
			Dispatchers.resetMain()
		}
	}
	@Test
	fun `synchronous first UI fix remains available when feature collector subscribes after start`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope, callbacksDuringStart = 1)
		fixture.runtime.prepareUiLease()
		assertTrue(fixture.runtime.applyUiOnly(fixture.plan))
		assertTrue(fixture.runtime.uiLocationEvents.first() is UiLocationEvent.Fix)
		assertTrue(fixture.sink.deliveries.isEmpty())
		assertTrue(fixture.runtime.stopUiOnly())
	}

	@Test
	fun `UI-only location owns one provider without a durable registration or WAL delivery`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		val received = mutableListOf<UiLocationEvent>()
		val collector = backgroundScope.launch {
			fixture.runtime.uiLocationEvents.collect { received += it }
		}
		runCurrent()
		assertTrue(fixture.runtime.applyUiOnly(fixture.plan))
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, received.size)
		assertTrue(received.single() is UiLocationEvent.Fix)
		assertTrue(fixture.sink.deliveries.isEmpty())
		coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
		assertTrue(fixture.runtime.stopUiOnly())
		fixture.providerCallback()(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, received.size)
		collector.cancel()
	}

	@Test
	fun `UI-only removal failure fences a replacement provider`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		assertTrue(fixture.runtime.applyUiOnly(fixture.plan))
		coEvery { fixture.fusedBackend.stop() } returns RegistrationRemovalOutcome.FAILED
		assertTrue(!fixture.runtime.applyUiOnly(fixture.plan.copy(requestedIntervalMs = 1_000L)))
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
		coVerify(exactly = 0) { fixture.registrations.begin(any(), any(), any(), any(), any()) }
	}
	@Test
	fun `stop receipt reports committed WAL sequence independently of callback count`() = runTest {
		for (unitCount in listOf(1, 3)) {
			val lastSourceSequence = unitCount.toLong() - 1L
			val ordinals = (1..unitCount).map { it + 40L }
			val sink = RecordingLocationSink { _, _ ->
				SourceDeliveryAdmissionHandoff.Durable(ordinals, lastSourceSequence)
			}
			val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
			fixture.runtime.start(fixture.plan, sink)
			fixture.providerCallback()((0 until unitCount).map {
				location(50.0 + it, SystemClock.elapsedRealtimeNanos())
			})
			runCurrent()
			val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))
			assertEquals(1L, ack.callbackEntryBarrierSequence)
			assertEquals(lastSourceSequence, ack.lastDurablyAdmittedSequence)
			assertEquals(ordinals.last(), ack.lastAdmissionOrdinal)
			assertEquals(0L, ack.failedAdmissionCount)
		}
	}

	@Test
	fun `missing WAL sequence cannot become a complete admission receipt`() = runTest {
		val sink = RecordingLocationSink { _, _ -> SourceDeliveryAdmissionHandoff.Durable(listOf(41L)) }
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		fixture.runtime.start(fixture.plan, sink)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.lastAdmissionOrdinal)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
	}

	@Test
	fun `ending a shared session drains its prefix while background callbacks keep arriving`() = runTest {
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val sink = RecordingLocationSink { _, count ->
			if (count == 1) {
				admissionEntered.complete(Unit)
				releaseAdmission.await()
			}
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()), lastSourceSequence = count.toLong())
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		fixture.runtime.start(fixture.plan, sink)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		admissionEntered.await()
		val ended = async { fixture.runtime.sharedSessionCutoff(sessionCutoff(SystemClock.elapsedRealtimeNanos())) }
		runCurrent()
		fixture.providerCallback()(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
		releaseAdmission.complete(Unit)
		runCurrent()
		assertTrue(ended.await().appDrainComplete)
		assertEquals(2, sink.deliveries.size)
		coVerify(exactly = 0) { fixture.fusedBackend.stop() }
		fixture.providerCallback()(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(3, sink.deliveries.size)
		fixture.runtime.close()
	}

	@Test
	fun `no-op refresh does not discard a delayed fix or restart the provider`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
		val observed = SystemClock.elapsedRealtimeNanos()
		ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
		coEvery { fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any()) } answers {
			secondArg<SourceRegistration>()
		}
		assertTrue(fixture.runtime.reconfigure(fixture.plan, fixture.sink) is SourceApplyResult.Applied)
		fixture.providerCallback()(listOf(location(50.0, observed)))
		runCurrent()
		assertEquals(observed, fixture.sink.singleEvidence().observedElapsedRealtimeNanos)
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
		coVerify(exactly = 2) { fixture.registrations.recordLocationPlan(any(), fixture.plan) }
		fixture.runtime.close()
	}

	@Test
	fun `new adaptive profile has a stable freshness budget independent of moving cadence`() {
		val legacy = locationPlan(revision = 1L)
		assertEquals(maxOf(legacy.requestedIntervalMs, legacy.maximumBatchDelayMs) * 1_000_000L,
			legacy.maximumEvidenceAgeNanos())
		val profile = legacy.copy(adaptiveProfileVersion = 1, requestedIntervalMs = 1_000L,
			maximumBatchDelayMs = 2_000L)
		assertEquals(120_000_000_000L, profile.maximumEvidenceAgeNanos())
		val batch = partitionLocationBatch(
			listOf(location(50.0, 5_000_000_000L)),
			receivedElapsedRealtimeNanos = 65_000_000_000L,
			maximumEvidenceAgeNanos = profile.maximumEvidenceAgeNanos(),
			cutoffElapsedRealtimeNanos = null,
		)
		assertEquals(1, batch.eligible.size)
	}

	@Test
	fun `all fixes before durable provider acceptance floor are ignored without a false gap`() = runTest {
		val fixture = locationRuntimeFixture(
			backgroundScope,
			requiresProviderAcceptance = true,
		)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		val acceptanceFloor = requireNotNull(fixture.providerAcceptanceElapsedNanos())
		require(acceptanceFloor > 2L)

		fixture.providerCallback()(
			listOf(
				location(50.0, acceptanceFloor - 2L),
				location(51.0, acceptanceFloor - 1L),
			),
		)
		runCurrent()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(fixture.sink.deliveries.isEmpty())
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(0L, ack.failedAdmissionCount)
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.lastAdmissionOrdinal)
		coVerify(exactly = 1) {
			fixture.registrations.markAccepted(any(), any(), acceptanceFloor)
		}
	}

	@Test
	fun `reused active registration applies a local post backend start floor`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)

		fixture.providerCallback()(
			listOf(
				location(50.0, 1L),
				location(51.0, SystemClock.elapsedRealtimeNanos()),
			),
		)
		runCurrent()
		val admitted = fixture.sink.deliveries.single().units.single().evidence.payload as
			LocationFixPayload

		assertEquals(51.0, admitted.latitudeDegrees, 0.0)
		coVerify(exactly = 0) { fixture.registrations.markAccepted(any(), any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `mixed floor batch retries and duplicate replay keep stable qualified identity and stop accounting`() =
		runTest {
			val sink = RecordingLocationSink { _, attempt ->
				when (attempt) {
					1 -> SourceDeliveryAdmissionHandoff.RetryableFailure(
						SourceAdmissionFailureCode.STORAGE_UNAVAILABLE,
					)
					2 -> SourceDeliveryAdmissionHandoff.Durable(listOf(41L), lastSourceSequence = 17L)
					else -> SourceDeliveryAdmissionHandoff.Duplicate(listOf(41L), lastSourceSequence = 17L)
				}
			}
			val fixture = locationRuntimeFixture(
				backgroundScope,
				sink = sink,
				requiresProviderAcceptance = true,
			)
			assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
			val floor = requireNotNull(fixture.providerAcceptanceElapsedNanos())
			require(floor > 1L)
			ShadowSystemClock.advanceBy(Duration.ofMillis(1L))
			val original = listOf(
				location(52.0, floor + 1L),
				location(50.0, floor - 1L),
				location(51.0, floor),
			)
			fixture.providerCallback()(original)
			runCurrent()
			advanceTimeBy(25L)
			runCurrent()
			fixture.providerCallback()(original.reversed())
			runCurrent()

			assertEquals(3, sink.deliveries.size)
			assertEquals(1, sink.deliveries.map(SourceDeliveryCandidate::identity).distinct().size)
			assertTrue(sink.deliveries.all { delivery ->
				delivery.units.map { unit ->
					(unit.evidence.payload as LocationFixPayload).latitudeDegrees
				} == listOf(51.0, 52.0)
			})
			val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

			assertEquals(SourceStopStatus.COMPLETE, ack.status)
			assertEquals(2L, ack.callbackEntryBarrierSequence)
			assertEquals(0L, ack.failedAdmissionCount)
			assertEquals(17L, ack.lastDurablyAdmittedSequence)
			assertEquals(41L, ack.lastAdmissionOrdinal)
		}

	@Test
	fun `older duplicate ordinal cannot regress stop high water and physical restart resets it`() = runTest {
		val sink = RecordingLocationSink { _, attempt ->
			when (attempt) {
				1 -> SourceDeliveryAdmissionHandoff.Durable(listOf(100L), lastSourceSequence = 50L)
				2 -> SourceDeliveryAdmissionHandoff.Duplicate(listOf(10L), lastSourceSequence = 4L)
				3 -> SourceDeliveryAdmissionHandoff.Durable(listOf(5L), lastSourceSequence = 0L)
				else -> error("Unexpected Location admission attempt $attempt")
			}
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		ShadowSystemClock.advanceBy(Duration.ofMillis(1L))
		fixture.providerCallback()(
			listOf(location(50.0, SystemClock.elapsedRealtimeNanos())),
		)
		runCurrent()

		val refreshedPlan = fixture.plan.copy(revision = 2L)
		val refreshedRegistration = registration(
			plan = refreshedPlan,
			authorizationRevision = 2L,
		)
		coEvery {
			fixture.registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns refreshedRegistration
		assertTrue(fixture.runtime.reconfigure(refreshedPlan, sink) is SourceApplyResult.Applied)
		ShadowSystemClock.advanceBy(Duration.ofMillis(1L))
		fixture.providerCallback()(
			listOf(location(51.0, SystemClock.elapsedRealtimeNanos())),
		)
		runCurrent()

		val firstStop = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertEquals(50L, firstStop.lastDurablyAdmittedSequence)
		assertEquals(100L, firstStop.lastAdmissionOrdinal)
		assertEquals(0L, firstStop.failedAdmissionCount)
		assertNull(firstStop.unresolvedSequenceStart)
		assertNull(firstStop.unresolvedSequenceEndInclusive)

		assertTrue(fixture.runtime.start(refreshedPlan, sink) is SourceStartResult.Started)
		ShadowSystemClock.advanceBy(Duration.ofMillis(1L))
		fixture.providerCallback()(
			listOf(location(52.0, SystemClock.elapsedRealtimeNanos())),
		)
		runCurrent()
		val restartedStop = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertEquals(0L, restartedStop.lastDurablyAdmittedSequence)
		assertEquals(5L, restartedStop.lastAdmissionOrdinal)
		assertEquals(0L, restartedStop.failedAdmissionCount)
		assertNull(restartedStop.unresolvedSequenceStart)
		assertNull(restartedStop.unresolvedSequenceEndInclusive)
	}

	@Test
	fun `callbacks before durable provider acceptance are not admitted`() = runTest {
		val fixture = locationRuntimeFixture(
			backgroundScope,
			callbacksDuringStart = 65,
			requiresProviderAcceptance = true,
		)

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		runCurrent()
		assertTrue(fixture.sink.deliveries.isEmpty())

		fixture.providerCallback()(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertEquals(1, fixture.sink.deliveries.size)
		assertEquals(0L, ack.failedAdmissionCount)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
	}

	@Test
	fun `partial provider start cleans its exact retained registration before returning failure`() = runTest {
		val fixture = failingStartFixture(backgroundScope, startFailure = null)

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Failed)

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		coVerify(exactly = 0) { fixture.registrations.markFailed(any(), any(), any(), any()) }
	}

	@Test
	fun `provider start exception cleans any partial registration before returning failure`() = runTest {
		val fixture = failingStartFixture(backgroundScope, startFailure = IllegalStateException("provider"))

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Failed)

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		coVerify(exactly = 0) { fixture.registrations.markFailed(any(), any(), any(), any()) }
	}

	@Test
	fun `provider start fatal error retires exact handle and propagates`() = runTest {
		val fixture = failingStartFixture(backgroundScope, startFailure = AssertionError("fatal provider"))

		assertFailsWith<AssertionError> {
			fixture.runtime.start(fixture.plan, fixture.sink)
		}

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `provider acceptance fatal error retires exact handle and propagates`() = runTest {
		val fixture = locationRuntimeFixture(
			backgroundScope,
			requiresProviderAcceptance = true,
		)
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } throws
			AssertionError("fatal acceptance")

		assertFailsWith<AssertionError> {
			fixture.runtime.start(fixture.plan, fixture.sink)
		}

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `stale provider acceptance exception retires exact handle and fails closed`() = runTest {
		val fixture = locationRuntimeFixture(
			backgroundScope,
			requiresProviderAcceptance = true,
		)
		coEvery { fixture.registrations.markAccepted(any(), any(), any()) } throws
			IllegalStateException("stale acceptance")

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Failed)

		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
	}

	@Test
	fun `retirement is durable before provider removal and completes after removal`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		val events = mutableListOf<String>()
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			events += "RETIRING"
			retirementToken(
				firstArg<SourceRegistration>(),
				secondArg<String>(),
				thirdArg<Long>(),
				arg<Long>(3),
			)
		}
		coEvery { fixture.fusedBackend.stop() } answers {
			events += "REMOVE"
			RegistrationRemovalOutcome.REMOVED
		}
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			events += "COMPLETE"
			true
		}

		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(listOf("RETIRING", "REMOVE", "COMPLETE"), events)
	}

	@Test
	fun `failed removal is retried before cross backend replacement starts`() = runTest {
		val initialPlan = locationPlan(revision = 1L)
		val replacementPlan = initialPlan.copy(revision = 2L, backend = LocationBackend.FRAMEWORK)
		val initialRegistration = registration(initialPlan, authorizationRevision = 1L)
		val replacementRegistration = registration(
			replacementPlan,
			authorizationRevision = 2L,
			generation = 10L,
			requiresProviderAcceptance = true,
		)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		every { fusedBackend.resolvePlan(any()) } answers { firstArg() }
		every { frameworkBackend.resolvePlan(any()) } answers { firstArg() }
		stubRetirementRepository(registrations)
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returnsMany
			listOf(initialRegistration, replacementRegistration)
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns null
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		coEvery { fusedBackend.start(any(), any()) } returns LocationBackendStartOutcome.STARTED
		coEvery { frameworkBackend.start(any(), any()) } returns LocationBackendStartOutcome.STARTED
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returnsMany listOf(
			RegistrationRemovalOutcome.FAILED,
			RegistrationRemovalOutcome.REMOVED,
		)
		val runtime = locationRuntime(
			backgroundScope,
			registrations,
			fusedBackend,
			frameworkBackend,
		)
		val sink = RecordingLocationSink()

		assertTrue(runtime.start(initialPlan, sink) is SourceStartResult.Started)
		assertTrue(runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Failed)
		coVerify(exactly = 0) { frameworkBackend.start(any(), any()) }

		assertTrue(runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Applied)

		coVerify(exactly = 2) { fusedBackend.stop() }
		coVerify(exactly = 1) { registrations.beginRetirement(initialRegistration, any(), any(), any()) }
		coVerify(exactly = 1) { registrations.completeRetirement(any()) }
		coVerify(exactly = 1) { frameworkBackend.start(any(), any()) }
	}

	@Test
	fun `one pending token with cross backend handles fails closed`() = runTest {
		val plan = locationPlan(revision = 1L)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		every { fusedBackend.resolvePlan(any()) } answers { firstArg() }
		every { frameworkBackend.resolvePlan(any()) } answers { firstArg() }
		val pending = retirementToken(registration(plan, authorizationRevision = 1L))
		coEvery { registrations.pendingRetirements(SourceKind.LOCATION) } returns listOf(pending)
		every { fusedBackend.hasRetainedRegistration } returns true
		every { frameworkBackend.hasRetainedRegistration } returns true
		val runtime = locationRuntime(
			backgroundScope,
			registrations,
			fusedBackend,
			frameworkBackend,
		)

		assertTrue(runtime.start(plan, RecordingLocationSink()) is SourceStartResult.Failed)

		coVerify(exactly = 0) { registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 0) { fusedBackend.stop() }
		coVerify(exactly = 0) { frameworkBackend.stop() }
		coVerify(exactly = 0) { registrations.completeRetirement(any()) }
	}

	@Test
	fun `compatible revision refreshes callback authorization without provider restart and revoke stays fenced`() =
		runTest {
			val initialPlan = locationPlan(revision = 1L)
			val refreshedPlan = initialPlan.copy(revision = 2L)
			val initialRegistration = registration(initialPlan, authorizationRevision = 1L)
			val refreshedRegistration = registration(refreshedPlan, authorizationRevision = 2L)
			val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
			val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
			val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
			every { fusedBackend.resolvePlan(any()) } answers { firstArg() }
			every { frameworkBackend.resolvePlan(any()) } answers { firstArg() }
			var deviceState = locationState()
			val stateProvider = object : LocationDeviceStateProvider {
				override fun snapshot(): LocationDeviceState = deviceState
			}
			var callback: ((List<android.location.Location>) -> Unit)? = null
			coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns initialRegistration
			coEvery {
				registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
			} returns refreshedRegistration
			coEvery { registrations.markAccepted(any(), any(), any()) } returns null
			coEvery { fusedBackend.start(any(), any()) } answers {
				callback = secondArg()
				LocationBackendStartOutcome.STARTED
			}
			coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
			coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
			stubRetirementRepository(registrations)
			val runtime = LocationSourceRuntime(
				context = mockk<Context>(relaxed = true),
				applicationScope = backgroundScope,
				registrations = registrations,
				fusedBackend = fusedBackend,
				frameworkBackend = frameworkBackend,
				prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
				deviceStateProvider = stateProvider,
				passiveBackend = mockk(relaxed = true),
			)
			var committedSequence = 0L
			val admit: suspend (SourceDeliveryCandidate, Int) -> SourceDeliveryAdmissionHandoff = { _, _ ->
				committedSequence += 1L
				SourceDeliveryAdmissionHandoff.Durable(listOf(committedSequence), committedSequence)
			}
			val initialSink = RecordingLocationSink(admit)
			val refreshedSink = RecordingLocationSink(admit)

			assertTrue(runtime.start(initialPlan, initialSink) is SourceStartResult.Started)
			val providerCallback = requireNotNull(callback)
			providerCallback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))

			assertTrue(runtime.reconfigure(refreshedPlan, refreshedSink) is SourceApplyResult.Applied)
			providerCallback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
			runCurrent()

			coVerify(exactly = 1) { fusedBackend.start(any(), any()) }
			coVerify(exactly = 0) { fusedBackend.stop() }
			coVerify(exactly = 1) { registrations.begin(any(), any(), any(), any(), any()) }
			coVerify(exactly = 1) {
				registrations.refreshActiveAuthorization(any(), initialRegistration, any(), any(), any(), any())
			}
			assertEquals(1L, initialSink.singleEvidence().authorizationRevision)
			assertEquals(1L, initialSink.singleEvidence().configRevision)
			assertEquals(2L, refreshedSink.singleEvidence().authorizationRevision)
			assertEquals(2L, refreshedSink.singleEvidence().configRevision)

			deviceState = locationState(coarsePermission = false, finePermission = false)
			providerCallback(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
			runCurrent()

			assertEquals(1, refreshedSink.deliveries.size)

			deviceState = locationState()
			providerCallback(
				listOf(
					location(54.0, Long.MAX_VALUE),
					location(53.0, SystemClock.elapsedRealtimeNanos()),
				),
			)
			runCurrent()

			assertEquals(2, refreshedSink.deliveries.size)
			assertEquals(
				53.0,
				(refreshedSink.deliveries.last().units.single().evidence.payload as
					com.adsamcik.tracker.tracker.source.model.LocationFixPayload).latitudeDegrees,
				0.0,
			)
			val stopNow = SystemClock.elapsedRealtimeNanos()
			val ack = runtime.quiesce(
				SessionCutoff(
					logicalTrackingId = "location-test",
					elapsedRealtimeNanos = Long.MAX_VALUE,
					wallTimeMs = System.currentTimeMillis(),
					deadlineElapsedRealtimeNanos = stopNow + 1_000_000_000L,
				),
			)

			assertTrue(ack.appDrainComplete)
			assertEquals(1L, ack.failedAdmissionCount)
			assertEquals(3L, ack.unresolvedSequenceStart)
			assertEquals(3L, ack.unresolvedSequenceEndInclusive)
			assertEquals(3L, ack.lastDurablyAdmittedSequence)
			coVerify(exactly = 1) { fusedBackend.stop() }
		}

	@Test
	fun `compatible authorization refresh partitions delayed fixes without borrowing newer consent`() = runTest {
		val initialPlan = locationPlan(revision = 1L)
		val refreshedPlan = initialPlan.copy(revision = 2L)
		val initialRegistration = registration(initialPlan, 1L, requiresProviderAcceptance = true)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		every { fusedBackend.resolvePlan(any()) } answers { firstArg() }
		every { frameworkBackend.resolvePlan(any()) } answers { firstArg() }
		var callback: ((List<android.location.Location>) -> Unit)? = null
		var acceptanceFloor: Long? = null
		var refreshFloor: Long? = null
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns initialRegistration
		coEvery { registrations.markAccepted(any(), any(), any()) } answers {
			acceptanceFloor = thirdArg()
			null
		}
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} answers {
			val effectiveElapsedRealtimeNanos = arg<Long>(5)
			refreshFloor = effectiveElapsedRealtimeNanos
			registration(
				refreshedPlan,
				authorizationRevision = 2L,
				requiresProviderAcceptance = false,
				authorizationEffectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			)
		}
		coEvery { fusedBackend.start(any(), any()) } answers {
			callback = secondArg()
			LocationBackendStartOutcome.STARTED
		}
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
		stubRetirementRepository(registrations)
		val runtime = locationRuntime(backgroundScope, registrations, fusedBackend, frameworkBackend)
		val initialSink = RecordingLocationSink()
		val refreshedSink = RecordingLocationSink()

		assertTrue(runtime.start(initialPlan, initialSink) is SourceStartResult.Started)
		ShadowSystemClock.advanceBy(Duration.ofMillis(1L))
		assertTrue(runtime.reconfigure(refreshedPlan, refreshedSink) is SourceApplyResult.Applied)
		val authorizationFloor = requireNotNull(refreshFloor)
		require(authorizationFloor > requireNotNull(acceptanceFloor))
		require(authorizationFloor > 1L)
		requireNotNull(callback)(
			listOf(
				location(50.0, authorizationFloor - 1L),
				location(51.0, authorizationFloor),
			),
		)
		runCurrent()

		val beforeBoundary = initialSink.singleEvidence()
		assertEquals(50.0, (beforeBoundary.payload as LocationFixPayload).latitudeDegrees, 0.0)
		assertEquals(1L, beforeBoundary.authorizationRevision)
		assertEquals(1L, beforeBoundary.configRevision)
		val admitted = refreshedSink.deliveries.single().units.single().evidence
		assertEquals(51.0, (admitted.payload as LocationFixPayload).latitudeDegrees, 0.0)
		assertEquals(2L, admitted.authorizationRevision)
		assertEquals(2L, admitted.configRevision)
		coVerify(exactly = 1) { fusedBackend.start(any(), any()) }
		val ack = runtime.quiesce(sessionCutoff(Long.MAX_VALUE))
		assertEquals(0L, ack.failedAdmissionCount)
		assertEquals(1L, ack.lastDurablyAdmittedSequence)
	}

	@Test
	fun `claim transfer fences stale shutdown across compatible refresh and replacement`() = runTest {
		val initialPlan = locationPlan(revision = 1L)
		val refreshedPlan = initialPlan.copy(revision = 2L)
		val replacementPlan = refreshedPlan.copy(revision = 3L, backend = LocationBackend.FRAMEWORK)
		val initial = registration(initialPlan, authorizationRevision = 1L, generation = 9L)
		val refreshed = registration(refreshedPlan, authorizationRevision = 2L, generation = 9L)
		val replacement = registration(replacementPlan, authorizationRevision = 3L, generation = 10L)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		every { fusedBackend.resolvePlan(any()) } answers { firstArg() }
		every { frameworkBackend.resolvePlan(any()) } answers { firstArg() }
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returnsMany
			listOf(initial, replacement)
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns refreshed
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		coEvery { fusedBackend.start(any(), any()) } returns LocationBackendStartOutcome.STARTED
		coEvery { frameworkBackend.start(any(), any()) } returns LocationBackendStartOutcome.STARTED
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { frameworkBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
		coEvery { frameworkBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
		stubRetirementRepository(registrations)
		val runtime = locationRuntime(backgroundScope, registrations, fusedBackend, frameworkBackend)
		val firstClaim = runtimeClaim(SourceKind.LOCATION, "location-start")
		val refreshClaim = runtimeClaim(SourceKind.LOCATION, "location-refresh")
		val replacementClaim = runtimeClaim(SourceKind.LOCATION, "location-replacement")
		val sink = RecordingLocationSink()

		assertTrue(runtime.start(firstClaim, initialPlan, sink) is SourceStartResult.Started)
		assertTrue(runtime.reconfigure(refreshClaim, refreshedPlan, sink) is SourceApplyResult.Applied)
		assertEquals(
			OwnedSourceShutdown.NotOwned,
			runtime.shutdownIfOwned(firstClaim, sessionCutoff(Long.MAX_VALUE)),
		)
		coVerify(exactly = 0) { fusedBackend.flush() }
		coVerify(exactly = 0) { fusedBackend.stop() }

		val replacementResult = runtime.reconfigure(replacementClaim, replacementPlan, sink)
		assertTrue(replacementResult is SourceApplyResult.Applied)
		assertEquals(9L, (replacementResult as SourceApplyResult.Applied).stopAck?.registrationGeneration)
		coVerify(exactly = 1) { fusedBackend.flush() }
		coVerify(exactly = 1) { fusedBackend.stop() }
		coVerify(exactly = 0) { frameworkBackend.flush() }
		coVerify(exactly = 0) { frameworkBackend.stop() }
		assertEquals(
			OwnedSourceShutdown.NotOwned,
			runtime.shutdownIfOwned(refreshClaim, sessionCutoff(Long.MAX_VALUE)),
		)
		coVerify(exactly = 1) { fusedBackend.stop() }
		coVerify(exactly = 0) { frameworkBackend.flush() }
		coVerify(exactly = 0) { frameworkBackend.stop() }

		val released = runtime.shutdownIfOwned(
			replacementClaim,
			sessionCutoff(Long.MAX_VALUE),
		) as OwnedSourceShutdown.Released
		assertEquals(10L, released.provider?.registrationGeneration)
		coVerify(exactly = 1) { frameworkBackend.flush() }
		coVerify(exactly = 1) { frameworkBackend.stop() }
	}

	@Test
	fun `failed provider publication retains only its exact claim for cleanup`() = runTest {
		val fixture = failingStartFixture(
			backgroundScope,
			startFailure = IllegalStateException("published then failed"),
		)
		coEvery { fixture.fusedBackend.stop() } returnsMany listOf(
			RegistrationRemovalOutcome.FAILED,
			RegistrationRemovalOutcome.REMOVED,
		)
		val owningClaim = runtimeClaim(SourceKind.LOCATION, "location-failed-publication")
		val staleClaim = runtimeClaim(SourceKind.LOCATION, "location-stale-cleanup")

		assertTrue(fixture.runtime.start(owningClaim, fixture.plan, fixture.sink) is SourceStartResult.Failed)
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		assertEquals(
			OwnedSourceShutdown.NotOwned,
			fixture.runtime.shutdownIfOwned(staleClaim, sessionCutoff(Long.MAX_VALUE)),
		)
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }

		val released = fixture.runtime.shutdownIfOwned(
			owningClaim,
			sessionCutoff(Long.MAX_VALUE),
		) as OwnedSourceShutdown.Released
		assertEquals(9L, released.provider?.registrationGeneration)
		coVerify(exactly = 2) { fixture.fusedBackend.stop() }
	}

	@Test
	fun `compatible refresh buffers callbacks until durable context swap`() = runTest {
		val initialPlan = locationPlan(revision = 1L)
		val refreshedPlan = initialPlan.copy(revision = 2L)
		val initialRegistration = registration(initialPlan, authorizationRevision = 1L)
		val refreshedRegistration = registration(refreshedPlan, authorizationRevision = 2L)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		every { fusedBackend.resolvePlan(any()) } answers { firstArg() }
		every { frameworkBackend.resolvePlan(any()) } answers { firstArg() }
		val refreshCommitted = CompletableDeferred<Unit>()
		val releaseRefresh = CompletableDeferred<Unit>()
		var callback: ((List<android.location.Location>) -> Unit)? = null
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns initialRegistration
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} coAnswers {
			refreshCommitted.complete(Unit)
			releaseRefresh.await()
			refreshedRegistration
		}
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		coEvery { fusedBackend.start(any(), any()) } answers {
			callback = secondArg()
			LocationBackendStartOutcome.STARTED
		}
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
		stubRetirementRepository(registrations)
		val runtime = locationRuntime(backgroundScope, registrations, fusedBackend, frameworkBackend)
		val initialSink = RecordingLocationSink()
		val refreshedSink = RecordingLocationSink()

		assertTrue(runtime.start(initialPlan, initialSink) is SourceStartResult.Started)
		val providerCallback = requireNotNull(callback)
		providerCallback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()

		val refresh = async { runtime.reconfigure(refreshedPlan, refreshedSink) }
		refreshCommitted.await()
		providerCallback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, initialSink.deliveries.size)
		assertTrue(refreshedSink.deliveries.isEmpty())

		releaseRefresh.complete(Unit)
		assertTrue(refresh.await() is SourceApplyResult.Applied)
		providerCallback(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()

		assertEquals(1L, initialSink.singleEvidence().configRevision)
		assertEquals(2, refreshedSink.deliveries.size)
		assertTrue(refreshedSink.deliveries.flatMap { it.units }.all {
			it.evidence.authorizationRevision == 2L && it.evidence.configRevision == 2L
		})
		coVerify(exactly = 1) { fusedBackend.start(any(), any()) }
		coVerify(exactly = 0) { fusedBackend.stop() }
		runtime.close()
	}

	@Test
	fun `incompatible authorization check reserves only the orderly replacement generation`() = runTest {
		val initialPlan = locationPlan(revision = 1L)
		val replacementPlan = initialPlan.copy(revision = 2L)
		val initialRegistration = registration(initialPlan, authorizationRevision = 1L)
		val replacementRegistration = registration(
			replacementPlan,
			authorizationRevision = 2L,
			generation = 10L,
			requiresProviderAcceptance = true,
		)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		every { fusedBackend.resolvePlan(any()) } answers { firstArg() }
		every { frameworkBackend.resolvePlan(any()) } answers { firstArg() }
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returnsMany
			listOf(initialRegistration, replacementRegistration)
		coEvery {
			registrations.refreshActiveAuthorization(any(), any(), any(), any(), any(), any())
		} returns null
		coEvery { registrations.markAccepted(any(), any(), any()) } returns null
		coEvery { fusedBackend.start(any(), any()) } returns LocationBackendStartOutcome.STARTED
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
		stubRetirementRepository(registrations)
		val runtime = LocationSourceRuntime(
			context = mockk<Context>(relaxed = true),
			applicationScope = backgroundScope,
			registrations = registrations,
			fusedBackend = fusedBackend,
			frameworkBackend = frameworkBackend,
			prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
			deviceStateProvider = object : LocationDeviceStateProvider {
				override fun snapshot(): LocationDeviceState = locationState()
			},
			passiveBackend = mockk(relaxed = true),
		)
		val sink = RecordingLocationSink()

		assertTrue(runtime.start(initialPlan, sink) is SourceStartResult.Started)
		assertTrue(runtime.reconfigure(replacementPlan, sink) is SourceApplyResult.Applied)

		coVerify(exactly = 1) {
			registrations.refreshActiveAuthorization(any(), initialRegistration, any(), any(), any(), any())
		}
		// Initial acquisition plus one post-removal replacement; no speculative N+1 reservation.
		coVerify(exactly = 2) { registrations.begin(any(), any(), any(), any(), any()) }
		coVerify(exactly = 2) { fusedBackend.start(any(), any()) }
		coVerify(exactly = 1) { fusedBackend.stop() }
		runtime.close()
	}

	@Test
	fun `cancelled drain settles completed retirement before allowing replacement`() = runTest {
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val sink = RecordingLocationSink { _, count ->
			admissionEntered.complete(Unit)
			releaseAdmission.await()
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()), lastSourceSequence = count.toLong())
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		admissionEntered.await()
		val stop = async { fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE)) }
		runCurrent()
		stop.cancel(CancellationException("cancel drain"))
		releaseAdmission.complete(Unit)
		advanceUntilIdle()

		assertFailsWith<CancellationException> { stop.await() }
		assertTrue(
			fixture.runtime.reconfigure(fixture.plan.copy(revision = 2L), sink) is SourceApplyResult.Applied,
		)
		coVerify(exactly = 1) {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		}
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		coVerify(exactly = 2) { fixture.fusedBackend.start(any(), any()) }
		fixture.runtime.close()
	}

	@Test
	fun `fatal callback fences admission cleans provider and reports queued tail through replayed ack`() = runTest {
		val fatal = AssertionError("fatal location admission")
		val observedFailure = CompletableDeferred<Throwable>()
		val providerStopEntered = CompletableDeferred<Unit>()
		val releaseProviderStop = CompletableDeferred<Unit>()
		val sourceScope = CoroutineScope(
			SupervisorJob() + StandardTestDispatcher(testScheduler) +
				CoroutineExceptionHandler { _, failure -> observedFailure.complete(failure) },
		)
		val sink = RecordingLocationSink { _, _ -> throw fatal }
		val fixture = locationRuntimeFixture(sourceScope, sink = sink)
		coEvery { fixture.fusedBackend.stop() } coAnswers {
			providerStopEntered.complete(Unit)
			releaseProviderStop.await()
			RegistrationRemovalOutcome.REMOVED
		}
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		val providerCallback = fixture.providerCallback()
		providerCallback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		providerCallback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))

		runCurrent()
		assertSame(fatal, observedFailure.await())
		providerStopEntered.await()
		// Cleanup is deliberately held in provider removal. Admission must already be fenced.
		providerCallback(listOf(location(52.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, sink.deliveries.size)
		releaseProviderStop.complete(Unit)
		advanceUntilIdle()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(!ack.appDrainComplete)
		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertEquals(2L, ack.callbackEntryBarrierSequence)
		assertEquals(2L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(2L, ack.unresolvedSequenceEndInclusive)
		assertEquals(1, sink.deliveries.size)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		sourceScope.cancel()
	}

	@Test
	fun `actor local cancellation fences callbacks retires once and replays terminal ack`() = runTest {
		val providerStopEntered = CompletableDeferred<Unit>()
		val releaseProviderStop = CompletableDeferred<Unit>()
		val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		val sink = RecordingLocationSink { _, _ ->
			throw CancellationException("local location actor cancellation")
		}
		val fixture = locationRuntimeFixture(sourceScope, sink = sink)
		coEvery { fixture.fusedBackend.stop() } coAnswers {
			providerStopEntered.complete(Unit)
			releaseProviderStop.await()
			RegistrationRemovalOutcome.REMOVED
		}
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		val providerCallback = fixture.providerCallback()
		providerCallback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))

		runCurrent()
		providerStopEntered.await()
		providerCallback(listOf(location(51.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		assertEquals(1, sink.deliveries.size)
		releaseProviderStop.complete(Unit)
		advanceUntilIdle()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))
		val replayedAck = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertEquals(ack, replayedAck)
		assertTrue(ack.appDrainComplete)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		sourceScope.cancel()
	}

	@Test
	fun `completed retirement blocks replacement until non cooperative actor settles`() = runTest {
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		val sink = RecordingLocationSink { _, count ->
			admissionEntered.complete(Unit)
			withContext(NonCancellable) { releaseAdmission.await() }
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()), lastSourceSequence = count.toLong())
		}
		val fixture = locationRuntimeFixture(sourceScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		admissionEntered.await()

		val stop = async { fixture.runtime.quiesce(immediateSessionCutoff()) }
		advanceUntilIdle()

		assertTrue(stop.isCompleted)
		val ack = stop.await()
		assertTrue(!ack.appDrainComplete)
		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		val replacementPlan = fixture.plan.copy(revision = 2L)
		val replacementSink = RecordingLocationSink()
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Failed)
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }

		releaseAdmission.complete(Unit)
		advanceUntilIdle()
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Applied)
		coVerify(exactly = 2) { fixture.fusedBackend.start(any(), any()) }
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 1) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(any()) }
		fixture.runtime.close()
		sourceScope.cancel()
	}

	@Test
	fun `pending retirement can complete but replacement still waits for retained actor`() = runTest {
		val admissionEntered = CompletableDeferred<Unit>()
		val releaseAdmission = CompletableDeferred<Unit>()
		val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
		val sink = RecordingLocationSink { _, count ->
			admissionEntered.complete(Unit)
			withContext(NonCancellable) { releaseAdmission.await() }
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()), lastSourceSequence = count.toLong())
		}
		val fixture = locationRuntimeFixture(sourceScope, sink = sink)
		lateinit var exactRetirementToken: SourceRegistrationRetirementToken
		coEvery {
			fixture.registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			retirementToken(
				firstArg<SourceRegistration>(),
				secondArg<String>(),
				thirdArg<Long>(),
				arg<Long>(3),
			).also { exactRetirementToken = it }
		}
		coEvery { fixture.registrations.completeRetirement(any()) } answers {
			assertSame(exactRetirementToken, firstArg())
			true
		}
		coEvery { fixture.fusedBackend.stop() } returnsMany listOf(
			RegistrationRemovalOutcome.FAILED,
			RegistrationRemovalOutcome.REMOVED,
		)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		admissionEntered.await()

		val stop = async { fixture.runtime.quiesce(immediateSessionCutoff()) }
		advanceUntilIdle()
		val ack = stop.await()
		assertEquals(SourceStopStatus.TIMED_OUT, ack.status)
		assertEquals(RegistrationRemovalOutcome.FAILED, ack.registrationRemovalOutcome)

		val replacementPlan = fixture.plan.copy(revision = 2L)
		val replacementSink = RecordingLocationSink()
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Failed)
		val replayedAck = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))
		assertEquals(SourceStopStatus.TIMED_OUT, replayedAck.status)
		assertEquals(RegistrationRemovalOutcome.REMOVED, replayedAck.registrationRemovalOutcome)
		assertEquals(ack.callbackEntryBarrierSequence, replayedAck.callbackEntryBarrierSequence)
		// Retirement is now complete, but the exact old actor is still fenced and blocks replacement.
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Failed)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 2) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(exactRetirementToken) }
		coVerify(exactly = 1) { fixture.fusedBackend.start(any(), any()) }

		releaseAdmission.complete(Unit)
		advanceUntilIdle()
		assertTrue(fixture.runtime.reconfigure(replacementPlan, replacementSink) is SourceApplyResult.Applied)
		coVerify(exactly = 1) { fixture.registrations.beginRetirement(any(), any(), any(), any()) }
		coVerify(exactly = 2) { fixture.fusedBackend.stop() }
		coVerify(exactly = 1) { fixture.registrations.completeRetirement(exactRetirementToken) }
		coVerify(exactly = 2) { fixture.fusedBackend.start(any(), any()) }
		fixture.runtime.close()
		sourceScope.cancel()
	}

	@Test
	fun `drain cancellation records one failure for one callback`() = runTest {
		val admissionEntered = CompletableDeferred<Unit>()
		val neverReleased = CompletableDeferred<Unit>()
		val sink = RecordingLocationSink { _, count ->
			admissionEntered.complete(Unit)
			neverReleased.await()
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()), lastSourceSequence = count.toLong())
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		admissionEntered.await()

		val stop = async { fixture.runtime.quiesce(immediateSessionCutoff()) }
		advanceUntilIdle()
		val ack = stop.await()

		assertTrue(!ack.appDrainComplete)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
	}

	@Test
	fun `accounted overflow tail does not falsely fail a completed drain`() = runTest {
		val firstAdmissionEntered = CompletableDeferred<Unit>()
		val releaseFirstAdmission = CompletableDeferred<Unit>()
		val sink = RecordingLocationSink { _, count ->
			if (count == 1) {
				firstAdmissionEntered.complete(Unit)
				releaseFirstAdmission.await()
			}
			SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()), lastSourceSequence = count.toLong())
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		val callback = fixture.providerCallback()
		callback(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()
		firstAdmissionEntered.await()
		repeat(65) { index ->
			callback(
				listOf(location(50.001 + index / 10_000.0, SystemClock.elapsedRealtimeNanos())),
			)
		}

		releaseFirstAdmission.complete(Unit)
		advanceUntilIdle()
		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(ack.appDrainComplete)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(66L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(66L, ack.unresolvedSequenceStart)
		assertEquals(66L, ack.unresolvedSequenceEndInclusive)
		assertEquals(65, sink.deliveries.size)
	}

	@Test
	fun `mixed valid and poison callback admits valid subset and reports one unresolved sequence`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		fixture.providerCallback()(
			listOf(
				location(50.0, SystemClock.elapsedRealtimeNanos()),
				location(51.0, Long.MAX_VALUE),
			),
		)
		runCurrent()

		assertEquals(1, fixture.sink.deliveries.size)
		val admitted = fixture.sink.deliveries.single().units.single().evidence.payload as
			LocationFixPayload
		assertEquals(50.0, admitted.latitudeDegrees, 0.0)

		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		assertEquals(1L, ack.lastDurablyAdmittedSequence)
		assertEquals(1L, ack.lastAdmissionOrdinal)
	}

	@Test
	fun `all poison callback admits nothing and reports one unresolved sequence`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, Long.MAX_VALUE)))
		runCurrent()

		assertTrue(fixture.sink.deliveries.isEmpty())

		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.lastAdmissionOrdinal)
	}

	@Test
	fun `durable session cutoff fails Location atomic delivery closed`() = runTest {
		val sink = RecordingLocationSink { _, _ ->
			SourceDeliveryAdmissionHandoff.SessionCutoff(8L)
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		fixture.providerCallback()(listOf(location(50.0, SystemClock.elapsedRealtimeNanos())))
		runCurrent()

		val ack = fixture.runtime.quiesce(sessionCutoff(Long.MAX_VALUE))

		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(1L, ack.failedAdmissionCount)
		assertEquals(1L, ack.unresolvedSequenceStart)
		assertEquals(1L, ack.unresolvedSequenceEndInclusive)
		assertNull(ack.lastDurablyAdmittedSequence)
	}

	@Test
	fun `flush callback wholly after cutoff admits nothing without a false gap`() = runTest {
		val fixture = locationRuntimeFixture(backgroundScope)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		runCurrent()
		val observedElapsedNanos = SystemClock.elapsedRealtimeNanos()
		require(observedElapsedNanos > 0L)
		val cutoffElapsedNanos = observedElapsedNanos - 1L
		coEvery { fixture.fusedBackend.flush() } answers {
			fixture.providerCallback()(listOf(location(50.0, observedElapsedNanos)))
			ProviderFlushOutcome.COMPLETE
		}

		val ack = fixture.runtime.quiesce(sessionCutoff(cutoffElapsedNanos))

		assertTrue(ack.appDrainComplete)
		assertTrue(fixture.sink.deliveries.isEmpty())
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(0L, ack.failedAdmissionCount)
		assertNull(ack.unresolvedSequenceStart)
		assertNull(ack.unresolvedSequenceEndInclusive)
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.lastAdmissionOrdinal)
	}

	@Test
	fun `flush batch straddling stop keeps inclusive pre cutoff fix and drops later sibling`() = runTest {
		val fixture = locationRuntimeFixture(
			backgroundScope,
			requiresProviderAcceptance = true,
		)
		assertTrue(fixture.runtime.start(fixture.plan, fixture.sink) is SourceStartResult.Started)
		val floor = requireNotNull(fixture.providerAcceptanceElapsedNanos())
		ShadowSystemClock.advanceBy(Duration.ofMillis(1L))
		val postCutoff = SystemClock.elapsedRealtimeNanos()
		require(postCutoff > floor)
		coEvery { fixture.fusedBackend.flush() } answers {
			fixture.providerCallback()(
				listOf(
					location(51.0, postCutoff),
					location(50.0, floor),
				),
			)
			ProviderFlushOutcome.COMPLETE
		}

		val ack = fixture.runtime.quiesce(sessionCutoff(floor))

		val delivery = fixture.sink.deliveries.single()
		assertEquals(1, delivery.units.size)
		assertEquals(
			50.0,
			(delivery.units.single().evidence.payload as LocationFixPayload).latitudeDegrees,
			0.0,
		)
		assertEquals(ProviderFlushOutcome.COMPLETE, ack.providerFlushOutcome)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(1L, ack.callbackEntryBarrierSequence)
		assertEquals(0L, ack.failedAdmissionCount)
		assertEquals(1L, ack.lastDurablyAdmittedSequence)
	}

	@Test
	fun `retry rechecks a cutoff installed after the first admission attempt`() = runTest {
		val sink = RecordingLocationSink { _, attempt ->
			if (attempt == 1) {
				SourceDeliveryAdmissionHandoff.RetryableFailure(
					SourceAdmissionFailureCode.STORAGE_UNAVAILABLE,
				)
			} else {
				SourceDeliveryAdmissionHandoff.Durable(listOf(attempt.toLong()), lastSourceSequence = attempt.toLong())
			}
		}
		val fixture = locationRuntimeFixture(backgroundScope, sink = sink)
		assertTrue(fixture.runtime.start(fixture.plan, sink) is SourceStartResult.Started)
		val observedElapsedNanos = SystemClock.elapsedRealtimeNanos()
		require(observedElapsedNanos > 0L)
		fixture.providerCallback()(listOf(location(50.0, observedElapsedNanos)))
		runCurrent()
		assertEquals(1, sink.deliveries.size)

		val stop = async {
			fixture.runtime.quiesce(sessionCutoff(observedElapsedNanos - 1L))
		}
		runCurrent()
		advanceUntilIdle()
		val ack = stop.await()

		assertEquals(1, sink.deliveries.size)
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.lastAdmissionOrdinal)
	}

	private class RecordingLocationSink(
		private val response: suspend (SourceDeliveryCandidate, Int) -> SourceDeliveryAdmissionHandoff =
			{ _, count -> SourceDeliveryAdmissionHandoff.Durable(listOf(count.toLong()), lastSourceSequence = count.toLong()) },
	) : SourceEventSink {
		val deliveries = mutableListOf<SourceDeliveryCandidate>()

		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			SourceAdmissionHandoff.TerminalFailure(SourceAdmissionFailureCode.INVALID_EVIDENCE)

		override suspend fun admit(delivery: SourceDeliveryCandidate): SourceDeliveryAdmissionHandoff {
			deliveries += delivery
			return response(delivery, deliveries.size)
		}

		fun singleEvidence(): SourceEvidenceCandidate<*> = deliveries.single().units.single().evidence
	}

	private data class LocationRuntimeFixture(
		val runtime: LocationSourceRuntime,
		val plan: LocationPlan,
		val sink: RecordingLocationSink,
		val registrations: SourceRegistrationRepository,
		val fusedBackend: FusedLocationSourceBackend,
		val passiveBackend: RearmablePassiveLocationBackend,
		val providerCallback: () -> ((List<android.location.Location>) -> Unit),
		val providerAcceptanceElapsedNanos: () -> Long?,
	)

	private data class FailingStartFixture(
		val runtime: LocationSourceRuntime,
		val plan: LocationPlan,
		val sink: RecordingLocationSink,
		val registrations: SourceRegistrationRepository,
		val fusedBackend: FusedLocationSourceBackend,
	)

	private fun failingStartFixture(
		applicationScope: CoroutineScope,
		startFailure: Throwable?,
	): FailingStartFixture {
		val plan = locationPlan(revision = 1L)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		every { fusedBackend.resolvePlan(any()) } answers { firstArg() }
		every { frameworkBackend.resolvePlan(any()) } answers { firstArg() }
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns registration(
			plan,
			authorizationRevision = 1L,
		)
		stubRetirementRepository(registrations)
		if (startFailure == null) {
			coEvery { fusedBackend.start(any(), any()) } returns LocationBackendStartOutcome.CLEANUP_REQUIRED
		} else {
			coEvery { fusedBackend.start(any(), any()) } throws startFailure
		}
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.NOT_REGISTERED
		val sink = RecordingLocationSink()
		return FailingStartFixture(
			runtime = LocationSourceRuntime(
				context = mockk<Context>(relaxed = true),
				applicationScope = applicationScope,
				registrations = registrations,
				fusedBackend = fusedBackend,
				frameworkBackend = frameworkBackend,
				prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
				deviceStateProvider = object : LocationDeviceStateProvider {
					override fun snapshot(): LocationDeviceState = locationState()
				},
				passiveBackend = mockk(relaxed = true),
			),
			plan = plan,
			sink = sink,
			registrations = registrations,
			fusedBackend = fusedBackend,
		)
	}

	private fun locationRuntimeFixture(
		applicationScope: CoroutineScope,
		callbacksDuringStart: Int = 0,
		sink: RecordingLocationSink = RecordingLocationSink(),
		requiresProviderAcceptance: Boolean = false,
	): LocationRuntimeFixture {
		val plan = locationPlan(revision = 1L)
		val registrations = mockk<SourceRegistrationRepository>(relaxed = true)
		val fusedBackend = mockk<FusedLocationSourceBackend>(relaxed = true)
		val frameworkBackend = mockk<FrameworkLocationSourceBackend>(relaxed = true)
		val passiveBackend = mockk<RearmablePassiveLocationBackend>(relaxed = true)
		every { fusedBackend.resolvePlan(any()) } answers { firstArg() }
		every { frameworkBackend.resolvePlan(any()) } answers { firstArg() }
		var callback: ((List<android.location.Location>) -> Unit)? = null
		var providerAcceptanceElapsedNanos: Long? = null
		coEvery { registrations.begin(any(), any(), any(), any(), any()) } returns registration(
			plan,
			authorizationRevision = 1L,
			requiresProviderAcceptance = requiresProviderAcceptance,
		)
		stubRetirementRepository(registrations)
		coEvery { registrations.markAccepted(any(), any(), any()) } answers {
			providerAcceptanceElapsedNanos = thirdArg()
			null
		}
		coEvery { fusedBackend.start(any(), any()) } answers {
			callback = secondArg()
			repeat(callbacksDuringStart) { index ->
				requireNotNull(callback)(
					listOf(location(50.0 + index / 1_000.0, SystemClock.elapsedRealtimeNanos())),
				)
			}
			LocationBackendStartOutcome.STARTED
		}
		coEvery { fusedBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fusedBackend.stop() } returns RegistrationRemovalOutcome.REMOVED
		val runtime = LocationSourceRuntime(
			context = mockk<Context>(relaxed = true),
			applicationScope = applicationScope,
			registrations = registrations,
			fusedBackend = fusedBackend,
			frameworkBackend = frameworkBackend,
			prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
			deviceStateProvider = object : LocationDeviceStateProvider {
				override fun snapshot(): LocationDeviceState = locationState()
			},
			passiveBackend = passiveBackend,
		)
		return LocationRuntimeFixture(
			runtime = runtime,
			plan = plan,
			sink = sink,
			registrations = registrations,
			fusedBackend = fusedBackend,
			passiveBackend = passiveBackend,
			providerCallback = { requireNotNull(callback) },
			providerAcceptanceElapsedNanos = { providerAcceptanceElapsedNanos },
		)
	}

	private fun stubRetirementRepository(registrations: SourceRegistrationRepository) {
		coEvery { registrations.pendingRetirements(SourceKind.LOCATION) } returns emptyList()
		coEvery { registrations.systemLocationRegistrations() } returns emptyList()
		coEvery {
			registrations.beginRetirement(any(), any(), any(), any())
		} answers {
			retirementToken(
				firstArg<SourceRegistration>(),
				secondArg<String>(),
				thirdArg<Long>(),
				arg<Long>(3),
			)
		}
		coEvery { registrations.completeRetirement(any()) } returns true
	}

	private fun retirementToken(
		registration: SourceRegistration,
		reason: String = "TEST_RETIREMENT",
		retiredAtMs: Long = 1L,
		retiredElapsedRealtimeNanos: Long = 1L,
	) = SourceRegistrationRetirementToken(
		source = SourceKind.LOCATION,
		sourceInstanceId = com.adsamcik.tracker.tracker.source.model.SourceInstanceId(
			registration.state.sourceInstanceId,
		),
		registrationGeneration = registration.state.registrationGeneration,
		processIncarnationId = "process-1",
		retiredAtMs = retiredAtMs,
		retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
		reason = reason,
	)

	private fun locationRuntime(
		applicationScope: CoroutineScope,
		registrations: SourceRegistrationRepository,
		fusedBackend: FusedLocationSourceBackend,
		frameworkBackend: FrameworkLocationSourceBackend,
	): LocationSourceRuntime = LocationSourceRuntime(
		context = mockk<Context>(relaxed = true),
		applicationScope = applicationScope,
		registrations = registrations,
		fusedBackend = fusedBackend,
		frameworkBackend = frameworkBackend,
		prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
		deviceStateProvider = object : LocationDeviceStateProvider {
			override fun snapshot(): LocationDeviceState = locationState()
		},
		passiveBackend = mockk(relaxed = true),
	)

	private fun sessionCutoff(elapsedRealtimeNanos: Long): SessionCutoff = SessionCutoff(
		logicalTrackingId = "location-test",
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = System.currentTimeMillis(),
		deadlineElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + 1_000_000_000L,
	)

	private fun runtimeClaim(source: SourceKind, actionId: String) = SourceRuntimeClaim(
		source = source,
		actionId = actionId,
		attemptCount = 1,
		leaseGeneration = 1L,
		logicalTrackingId = "location-test",
		serviceRunId = "run-1",
	)

	private fun immediateSessionCutoff(): SessionCutoff = SessionCutoff(
		logicalTrackingId = "location-test",
		elapsedRealtimeNanos = Long.MAX_VALUE,
		wallTimeMs = System.currentTimeMillis(),
		deadlineElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
	)

	private fun location(latitude: Double, observedElapsedNanos: Long) =
		FakeLocationSource.createLocation(
			lat = latitude,
			lon = 14.0,
			accuracy = 5f,
			time = System.currentTimeMillis(),
			provider = "gps",
		).apply {
		elapsedRealtimeNanos = observedElapsedNanos
	}

	private fun locationPlan(revision: Long) = LocationPlan(
		revision = revision,
		backend = LocationBackend.FUSED,
		mode = LocationMode.BALANCED,
		requestedIntervalMs = 10_000L,
		minimumUpdateIntervalMs = 5_000L,
		minimumDisplacementMeters = 0f,
		maximumBatchDelayMs = 30_000L,
		preciseLocationAvailable = true,
	)

	private fun locationState(
		coarsePermission: Boolean = true,
		finePermission: Boolean = true,
	) = LocationDeviceState(
		apiLevel = 34,
		locationFeatureAvailable = true,
		locationServicesEnabled = true,
		coarsePermission = coarsePermission,
		finePermission = finePermission,
		backgroundLocationPermission = true,
		fusedProviderAvailable = true,
		foregroundServiceLocationCapability = true,
		backgroundForegroundServiceStartLegal = true,
	)

	private class ColdSystemLocation(
		val plan: LocationPlan,
		val registration: SourceRegistration,
		val initialRow: ProviderRegistrationGenerationEntity,
	) {
		var row: ProviderRegistrationGenerationEntity? = initialRow
		var pending: SourceRegistrationRetirementToken? = null
		var preparedIdentity: PassiveLocationIdentity? = null
		var retainedIdentity: PassiveLocationIdentity? = null
		val events = mutableListOf<String>()
		val identity = PassiveLocationIdentity(plan.backend, initialRow.sourceInstanceId,
			initialRow.registrationGeneration, initialRow.clockDomainId,
			initialRow.physicalConfigurationFingerprint, initialRow.collectedDataEpoch)

		fun retire(reason: String, wall: Long = 3L, elapsed: Long = 3L): SourceRegistrationRetirementToken {
			pending?.let { return it }
			val current = requireNotNull(row)
			row = current.copy(status = ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				retiredAtMs = wall, retiredElapsedRealtimeNanos = elapsed, failureCode = reason)
			events += "retiring"
			return SourceRegistrationRetirementToken(SourceKind.LOCATION,
				com.adsamcik.tracker.tracker.source.model.SourceInstanceId(current.sourceInstanceId),
				current.registrationGeneration, null, wall, elapsed, reason,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				clockDomainId = current.clockDomainId,
				physicalConfigurationFingerprint = current.physicalConfigurationFingerprint,
				collectedDataEpoch = current.collectedDataEpoch).also { pending = it }
		}

		fun acknowledge(token: SourceRegistrationRetirementToken): Boolean {
			if (token.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE) {
				assertEquals(pending, token)
				events += "acknowledged"
				row = null
				pending = null
			}
			return true
		}
	}

	private fun installColdSystemLocation(fixture: LocationRuntimeFixture): ColdSystemLocation {
		ShadowSystemClock.advanceBy(Duration.ofMillis(1))
		val plan = fixture.plan.copy(mode = LocationMode.PASSIVE, passiveSystemDelivery = true)
		val retained = registration(plan, 1L, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT)
		val cold = ColdSystemLocation(plan, retained, systemLocationRow(retained))
		every { fixture.passiveBackend.resolvePlan(any()) } answers { firstArg() }
		// A new process begins without an in-memory handle while the durable system request survives.
		every { fixture.passiveBackend.hasRetainedRegistration } answers { cold.retainedIdentity != null }
		every { fixture.passiveBackend.retains(any()) } answers { cold.retainedIdentity == firstArg<PassiveLocationIdentity>() }
		every { fixture.passiveBackend.prepare(any()) } answers { cold.preparedIdentity = firstArg(); Unit }
		every { fixture.registrations.currentBootId() } returns "boot-1"
		coEvery { fixture.registrations.systemLocationRegistrations() } answers { listOfNotNull(cold.row) }
		coEvery { fixture.registrations.pendingRetirements(SourceKind.LOCATION) } answers { listOfNotNull(cold.pending) }
		coEvery { fixture.registrations.passiveLocationIdentity(any()) } answers {
			val row = firstArg<ProviderRegistrationGenerationEntity>()
			PassiveLocationIdentity(plan.backend, row.sourceInstanceId, row.registrationGeneration,
				row.clockDomainId, row.physicalConfigurationFingerprint, row.collectedDataEpoch)
		}
		coEvery { fixture.registrations.beginSystemLocationRetirement(any(), any()) } answers {
			assertEquals(cold.row, firstArg<ProviderRegistrationGenerationEntity>())
			cold.retire(secondArg())
		}
		coEvery { fixture.registrations.beginRetirement(any(), any(), any(), any()) } answers {
			val owner = firstArg<SourceRegistration>()
			if (owner.state.registrationGeneration == cold.row?.registrationGeneration &&
				owner.state.sourceInstanceId == cold.row?.sourceInstanceId) {
				cold.retire(secondArg(), thirdArg(), arg(3))
			} else retirementToken(owner, secondArg(), thirdArg(), arg(3))
		}
		coEvery { fixture.registrations.completeRetirement(any()) } answers { cold.acknowledge(firstArg()) }
		coEvery { fixture.passiveBackend.removeExact(any()) } answers {
			cold.events += "remove-exact"
			if (cold.retainedIdentity == firstArg<PassiveLocationIdentity>()) cold.retainedIdentity = null
			RegistrationRemovalOutcome.REMOVED
		}
		coEvery { fixture.passiveBackend.start(any(), any()) } answers {
			cold.retainedIdentity = requireNotNull(cold.preparedIdentity)
			LocationBackendStartOutcome.STARTED
		}
		coEvery { fixture.passiveBackend.flush() } returns ProviderFlushOutcome.NOT_REQUESTED
		coEvery { fixture.registrations.begin(any(), any(), any(), any(), any()) } returns
			registration(fixture.plan, 2L, generation = 10L)
		return cold
	}

	private fun systemLocationRow(registration: SourceRegistration) = ProviderRegistrationGenerationEntity(
		sourceKind = SourceKind.LOCATION.stableCode,
		registrationGeneration = registration.state.registrationGeneration,
		sourceInstanceId = registration.state.sourceInstanceId,
		ownerScope = registration.ownerScope, clockDomainId = registration.state.clockDomainId,
		physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
		collectedDataEpoch = registration.state.collectedDataEpoch,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
		providerProcessIncarnationId = null, status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
		reservedAtMs = 1L, reservedElapsedRealtimeNanos = 1L,
		acceptedAtMs = 2L, acceptedElapsedRealtimeNanos = 2L,
		retiredAtMs = null, retiredElapsedRealtimeNanos = null, failureCode = null,
	)

	private fun registration(
		plan: LocationPlan,
		authorizationRevision: Long,
		generation: Long = 9L,
		requiresProviderAcceptance: Boolean = false,
		authorizationEffectiveElapsedRealtimeNanos: Long = authorizationRevision,
		purpose: String = SourceBrokerPurpose.SESSION_CAPTURE,
	): SourceRegistration {
		val capture = purpose == SourceBrokerPurpose.SESSION_CAPTURE
		val demand = SourceDemandEntity(
			demandId = "location-demand-$authorizationRevision",
			consumerId = if (capture) "session:location-test" else "ambient:location",
			sourceKind = SourceKind.LOCATION.stableCode,
			purpose = purpose,
			logicalTrackingId = "location-test".takeIf { capture },
			serviceRunId = "run-1".takeIf { capture },
			manifestRevision = 1L.takeIf { capture },
			lifecycleLeaseGeneration = 1L.takeIf { capture },
			sourcePolicyRevision = authorizationRevision,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 0,
			maximumAgeMs = 60_000L,
			desiredLatencyMs = 5_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = authorizationEffectiveElapsedRealtimeNanos,
			requestedAtMs = 1L,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
		val authorization = requireNotNull(
			SourceBrokerAuthorization.rows(
				sourceKind = SourceKind.LOCATION.stableCode,
				registrationGeneration = generation,
				authorizationRevision = authorizationRevision,
				demands = listOf(demand),
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = authorizationEffectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = 1L,
			).toAuthorizationSnapshotOrNull(),
		)
		return SourceRegistration(
			ownerScope = "source-broker:${SourceKind.LOCATION.stableCode}",
			state = SourceRegistrationStateEntity(
				sourceKind = SourceKind.LOCATION.stableCode,
				ownerScope = "source-broker:${SourceKind.LOCATION.stableCode}",
				sourceInstanceId = "location-1",
				clockDomainId = "boot-1",
				registrationGeneration = generation,
				nextSequence = 0L,
				appliedRevision = plan.revision,
				collectedDataEpoch = 1L,
				updatedAtMs = 1L,
			),
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint(),
			authorization = authorization,
			requiresProviderAcceptance = requiresProviderAcceptance,
		)
	}
}
