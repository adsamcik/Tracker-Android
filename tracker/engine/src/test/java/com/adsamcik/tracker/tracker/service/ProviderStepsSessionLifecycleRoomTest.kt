package com.adsamcik.tracker.tracker.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.TrackingStartPreparationResult
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsCapability
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportAccess
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProvider
import com.adsamcik.tracker.tracker.source.ambient.steps.AndroidAmbientStepsCapabilityResolver
import com.adsamcik.tracker.tracker.source.coordinator.ReadyTrackingStartupGate
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneId
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderStepsSessionLifecycleRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: ProviderStepsSessionLifecycle
	private val clock = FixedClock(1_000L, 1_000_000L)
	private val chosen = MutableStateFlow(TrackingParamsState(stepsEnabled = true, ambientStepsEnabled = true))
	private val capabilities = mockk<AndroidAmbientStepsCapabilityResolver>()
	private val acceptedStarts = MutableStateFlow(0L)
	private var accepted = false
		set(value) {
			field = value
			acceptedStarts.value = if (value) {
				1L
			} else {
				0L
			}
		}

	@Before fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(context)
		val preferences = mockk<TrackingParamsRepository>()
		every { preferences.data } returns chosen
		coEvery { capabilities.resolve() } returns AmbientStepsCapability.ReadyForRegistration(
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS, AmbientStepsImportAccess.FOREGROUND_ONLY)
		val commands = mockk<TrackingLifecycleCommandAuthority>()
		every { commands.isAcceptedStart(any()) } answers { accepted }
		every { commands.acceptedStartGeneration } returns acceptedStarts
		every { commands.resolveStart(any()) } returns com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition.Allowed
		lifecycle = ProviderStepsSessionLifecycle(context, Provider { database }, preferences, capabilities,
			Provider { ReadyTrackingStartupGate }, clock, BootClockDomainProvider { "boot-test" },
			DefaultDispatchersProvider, commands)
		// Provider organization must not change Android's direct-counter capability.
		(SourceKind.STEPS in context.manualTrackingSourceCapabilities().availableSources) shouldBe false
	}

	@After fun tearDown() = database.close()

	@Test fun `Start creates organization without a physical run or acquisition authority`(): Unit = runBlocking {
		lifecycle.isAvailable() shouldBe true
		val prepared = prepare()
		prepared.requiresForegroundService shouldBe false
		prepared.preparedSourceMaskHint shouldBe 0L
		lifecycle.accept(prepared.token, 1L) shouldBe true
		val row = requireNotNull(database.sourceSessionDao().session(prepared.token.value))
		row.sessionMode shouldBe LogicalTrackingSessionEntity.MODE_PROVIDER_STEPS
		row.currentServiceRunId shouldBe null
		row.currentManifestRevision shouldBe null
		database.sourceSessionDao().incompleteServiceRuns(row.logicalTrackingId) shouldBe emptyList()
		database.sourceSessionDao().manifests(row.logicalTrackingId) shouldBe emptyList()
		database.sourceBrokerDao().activeDemands(SourceKind.STEPS.stableCode) shouldBe emptyList()
		database.sourceSessionDao().hasLifecycleBoundaryBlocker() shouldBe false
		// A committed Room row alone cannot publish an accepted Start, including resume from Off.
		lifecycle.observeSessionInfo().first() shouldBe null
		accepted = true
		lifecycle.observeSessionInfo().first()?.isProviderStepsSession shouldBe true
	}

	@Test fun `durable acceptance publishes live session even when the Start caller is cancelled`(): Unit = runBlocking {
		val prepared = prepare()
		lifecycle.accept(prepared.token, 1L) shouldBe true
		val initial = CompletableDeferred<Unit>()
		val live = CompletableDeferred<Unit>()
		val observer = launch(Dispatchers.Default) {
			lifecycle.observeSessionInfo().collect { info ->
				if (info == null) {
					initial.complete(Unit)
				}
				if (info?.isProviderStepsSession == true) {
					live.complete(Unit)
				}
			}
		}
		try {
			withTimeout(5_000L) { initial.await() }
			val caller = launch {
				accepted = true
				cancel()
				yield()
			}
			caller.join()
			withTimeout(5_000L) { live.await() }
			// Visibility uses the existing acceptance signal, without another Room write.
			database.sourceSessionDao().session(prepared.token.value)?.lifecycleRevision shouldBe 2L
		} finally { observer.cancelAndJoin() }
	}

	@Test fun `End closes exact measured bounds and leaves source choices unchanged`(): Unit = runBlocking {
		val prepared = prepare()
		lifecycle.accept(prepared.token, 1L) shouldBe true
		accepted = true
		clock.advance(300L)
		lifecycle.close(stop(2L, 1_200L)) shouldBe true
		val row = requireNotNull(database.sourceSessionDao().session(prepared.token.value))
		row.state shouldBe "FINALIZED"
		row.cutoffAtMs shouldBe 1_200L
		val organized = database.organizationAnnotationDao().visible(null, null, 10).single()
		organized.fromMs shouldBe 1_000L
		organized.toMs shouldBe 1_200L
		organized.displayZoneId shouldBe ZoneId.systemDefault().id
		organized.isActive shouldBe false
		chosen.value.stepsEnabled shouldBe true
		chosen.value.ambientStepsEnabled shouldBe true
		lifecycle.observeSessionInfo().first() shouldBe null
	}

	@Test fun `permission or choice loss before acceptance cannot create a live session`(): Unit = runBlocking {
		val prepared = prepare()
		chosen.value = chosen.value.copy(stepsEnabled = false, ambientStepsEnabled = false)
		lifecycle.accept(prepared.token, 1L) shouldBe false
		lifecycle.compensate(prepared.token, 1L, "CHOICES_CHANGED") shouldBe true
		database.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
		database.sourceSessionDao().activeSession() shouldBe null
	}

	@Test fun `a stale Stop or compensation cannot erase a newer prepared Start`(): Unit = runBlocking {
		val prepared = prepare(generation = 3L)
		lifecycle.close(stop(2L, 1_200L)) shouldBe false
		lifecycle.compensate(prepared.token, 1L, "OLD_FAILURE") shouldBe false
		database.sourceSessionDao().activeSession()?.logicalTrackingId shouldBe prepared.token.value
		lifecycle.accept(prepared.token, 3L) shouldBe true
	}

	@Test fun `End before successful Start removes its provisional organization`(): Unit = runBlocking {
		prepare()
		clock.advance(10L)
		lifecycle.close(stop(2L, 1_010L)) shouldBe true
		database.organizationAnnotationDao().visible(null, null, 10) shouldBe emptyList()
		database.sourceSessionDao().activeSession() shouldBe null
	}

	@Test fun continuationRetainsOriginalAcceptedCommandAndExactRowRevision(): Unit = runBlocking {
		val prepared = prepare()
		lifecycle.accept(prepared.token, 1L) shouldBe true
		lifecycle.continuationRequest() shouldBe null
		accepted = true
		val row = requireNotNull(database.sourceSessionDao().activeSession())
		val request = requireNotNull(lifecycle.continuationRequest())
		request.command.generation shouldBe 1L
		request.providerStepsContinuation?.logicalTrackingId shouldBe row.logicalTrackingId
		request.providerStepsContinuation?.lifecycleRevision shouldBe row.lifecycleRevision
		request.providerStepsContinuation?.bootId shouldBe "boot-test"
		lifecycle.close(stop(2L, 1_000L)) shouldBe true
		lifecycle.continuationRequest() shouldBe null
	}

	private suspend fun prepare(generation: Long = 1L) = lifecycle.prepare(generation,
		SessionStartOrigin.MANUAL_FOREGROUND_START, 1L, 1L) as TrackingStartPreparationResult.Prepared
	private fun stop(generation: Long, time: Long) = TrackingStopCommand(generation,
		TrackingStopCandidateReason.EXPLICIT_REQUEST, time, "boot-test", clock.elapsedRealtimeNanos())
}
