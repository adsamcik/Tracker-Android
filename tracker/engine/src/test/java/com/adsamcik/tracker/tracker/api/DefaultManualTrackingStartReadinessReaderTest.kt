package com.adsamcik.tracker.tracker.api

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicy
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.SourceQos
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.service.ProviderStepsSessionLifecycle
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsCapability
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportAccess
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProvider
import com.adsamcik.tracker.tracker.source.ambient.steps.AndroidAmbientStepsCapabilityResolver
import com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterSetup
import com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterSetupResult
import com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultManualTrackingStartReadinessReaderTest {
	@Test
	fun `initial Steps settings can publish through startup while readiness waits`() = runTest {
		val gate = SerializedStartupGate()
		val settingsRequested = CompletableDeferred<Unit>()
		val settingsPublished = CompletableDeferred<TrackingParamsState>()
		val settings = delayedSettings(settingsRequested, settingsPublished)
		val subject = reader(gate, settings) { snapshot(stepsEnabled = true) }
		val publisher = launch {
			settingsRequested.await()
			// The real shared settings producer reconciles policy in its own coroutine.
			gate.withReadyGenerationOperation(gate.currentGeneration) {
				settingsPublished.complete(TrackingParamsState())
			}
		}

		withTimeout(1_000L) { subject.readPreflight() }.readiness shouldBe
			ManualTrackingStartReadiness.Ready(7L)
		publisher.join()
	}

	@Test
	fun `startup generation change while provider settings load rejects the old read`() = runTest {
		val gate = SerializedStartupGate()
		val settingsRequested = CompletableDeferred<Unit>()
		val settingsPublished = CompletableDeferred<TrackingParamsState>()
		val subject = reader(gate, delayedSettings(settingsRequested, settingsPublished)) {
			snapshot(stepsEnabled = true)
		}
		val publisher = launch {
			settingsRequested.await()
			gate.generation++
			settingsPublished.complete(TrackingParamsState())
		}

		withTimeout(1_000L) { subject.readPreflight() } shouldBe ManualTrackingStartPreflight(
			ManualTrackingStartReadiness.TrackingUnavailable, null,
		)
		publisher.join()
	}

	@Test
	fun `current source choices still govern readiness after provider settings load`() = runTest {
		val gate = SerializedStartupGate()
		val settingsRequested = CompletableDeferred<Unit>()
		val settingsPublished = CompletableDeferred<TrackingParamsState>()
		var policy = snapshot(stepsEnabled = true)
		val subject = reader(gate, delayedSettings(settingsRequested, settingsPublished)) { policy }
		val publisher = launch {
			settingsRequested.await()
			gate.withReadyGenerationOperation(gate.currentGeneration) {
				policy = snapshot(stepsEnabled = false)
				settingsPublished.complete(TrackingParamsState())
			}
		}

		withTimeout(1_000L) { subject.readPreflight() } shouldBe ManualTrackingStartPreflight(
			ManualTrackingStartReadiness.NoAvailableCaptureSource, ManualTrackingStartSelection(emptyMap()),
		)
		publisher.join()
	}

	@Test
	fun `native step counter does not wait for provider-only settings`() = runTest {
		val context = mockk<Context>()
		val packageManager = mockk<PackageManager>()
		val sensors = mockk<SensorManager>()
		every { context.packageManager } returns packageManager
		every { packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) } returns true
		every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensors
		every { sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns mockk<Sensor>()
		val settings = mockk<TrackingParamsRepository>()

		providerSessions(context, settings, SerializedStartupGate()).isAvailable() shouldBe false
		verify(exactly = 0) { settings.data }
	}

	private fun delayedSettings(
		requested: CompletableDeferred<Unit>,
		published: CompletableDeferred<TrackingParamsState>,
	): TrackingParamsRepository = mockk {
		every { data } returns flow {
			requested.complete(Unit)
			emit(published.await())
		}
	}

	private fun reader(
		gate: TrackingStartupGate,
		settings: TrackingParamsRepository,
		policy: () -> SourcePolicySnapshot,
	): DefaultManualTrackingStartReadinessReader {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val policies = mockk<SourcePolicyRepository>()
		coEvery { policies.currentState() } answers { SourcePolicyAuthorityState.Active(policy()) }
		val setup = mockk<ChosenSourceWriterSetup>()
		coEvery { setup.readReadiness(any()) } answers {
			ChosenSourceWriterSetupResult(firstArg<Set<SourceKind>>().associateWith {
				ChosenSourceWriterStatus.PENDING
			}, rolloutRevision = 7L)
		}
		return DefaultManualTrackingStartReadinessReader(context, Provider { gate }, Provider { policies },
			Provider { setup }, Provider { settings }, providerSessions(context, settings, gate))
	}

	private fun providerSessions(context: Context, settings: TrackingParamsRepository, gate: TrackingStartupGate):
		ProviderStepsSessionLifecycle {
		val capabilities = mockk<AndroidAmbientStepsCapabilityResolver>()
		coEvery { capabilities.resolve() } returns AmbientStepsCapability.ReadyForRegistration(
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS, AmbientStepsImportAccess.FOREGROUND_ONLY,
		)
		return ProviderStepsSessionLifecycle(context, Provider { error("Availability must not open storage") },
			settings, capabilities, Provider { gate }, FixedClock(1L, 1L), BootClockDomainProvider { "boot" },
			DefaultDispatchersProvider, mockk())
	}

	private fun snapshot(stepsEnabled: Boolean) = SourcePolicySnapshot(1L,
		TrackingSourceComponent.entries.associateWith { source ->
			val enabled = stepsEnabled && source == TrackingSourceComponent.STEPS
			SourcePolicy(source, enabled, if (enabled) {
				SourceQos.BALANCED
			} else {
				SourceQos.OFF
			},
				locationMinTimeSeconds = 2.takeIf { source == TrackingSourceComponent.LOCATION },
				locationMinDistanceMeters = 10.takeIf { source == TrackingSourceComponent.LOCATION },
				locationRequiredAccuracyMeters = 50.takeIf { source == TrackingSourceComponent.LOCATION },
				captureConsentEpoch = 1L.takeIf { enabled }, controlConsentEpoch = null,
				ambientConsentEpoch = null, capturePersistenceEligible = enabled,
				controlPersistenceEligible = false, ambientPersistenceEligible = false,
				effectiveTime = SourcePolicyEffectiveTime("boot", 1L, 1L), policyRevision = 1L)
		})

	private class SerializedStartupGate : TrackingStartupGate {
		private val mutex = Mutex()
		var generation = 4L
		override val isReady: Boolean = true
		override val currentGeneration: Long get() = generation

		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			error("Readiness must not reconcile startup")

		override suspend fun <T> withReadyGenerationOperation(
			expectedGeneration: Long,
			operation: suspend () -> T,
		): T? = mutex.withLock {
			if (isReadyGeneration(expectedGeneration)) {
				operation()
			} else {
				null
			}
		}
	}
}
