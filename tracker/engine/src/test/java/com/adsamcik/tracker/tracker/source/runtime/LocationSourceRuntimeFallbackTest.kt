package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocationSourceRuntimeFallbackTest {
	@Test
	fun `fused registration failure retries with framework backend`() = runTest {
		val registrations = mockk<SourceRegistrationRepository>()
		coEvery { registrations.begin(SourceKind.LOCATION, 7L, any()) } returns registration()
		val fused = mockk<FusedLocationSourceBackend>()
		every { fused.batchingSupported } returns true
		every { fused.flushSupported } returns true
		coEvery { fused.start(any(), any()) } returns false
		val framework = mockk<FrameworkLocationSourceBackend>()
		var frameworkPlan: LocationPlan? = null
		every { framework.batchingSupported } returns false
		every { framework.flushSupported } returns false
		coEvery {
			framework.start(match { it.backend == LocationBackend.FRAMEWORK }, any())
		} answers {
			frameworkPlan = firstArg()
			true
		}
		coEvery { framework.flush() } returns ProviderFlushOutcome.NOT_SUPPORTED
		coEvery { framework.stop() } returns RegistrationRemovalOutcome.REMOVED
		val deviceState = availableDeviceState(fusedAvailable = true)
		val runtime = LocationSourceRuntime(
			context = mockk<Context>(relaxed = true),
			applicationScope = backgroundScope,
			registrations = registrations,
			fusedBackend = fused,
			frameworkBackend = framework,
			prerequisiteEvaluator = LocationPrerequisiteEvaluator(),
			deviceStateProvider = object : LocationDeviceStateProvider {
				override fun snapshot() = deviceState
			},
		)

		val result = runtime.start(plan(), SourceEventSink { SourceAdmissionHandoff.Durable(1L) })

		val degraded = assertIs<SourceStartResult.Degraded>(result)
		assertEquals(LocationBackend.FRAMEWORK, frameworkPlan?.backend)
		assertTrue(SourceDegradedReason.PROVIDER_UNAVAILABLE in degraded.applied.degradedReasons)
		assertFalse(runtime.capabilities.value.batchingSupported)
		assertFalse(runtime.capabilities.value.flushSupported)
		coVerify(exactly = 1) { fused.start(any(), any()) }
		coVerify(exactly = 1) { framework.start(any(), any()) }

		runtime.close()
		coVerify(exactly = 1) { framework.stop() }
	}

	private fun plan() = LocationPlan(
		revision = 7L,
		backend = LocationBackend.FUSED,
		mode = LocationMode.HIGH_ACCURACY,
		requestedIntervalMs = 1_000L,
		minimumUpdateIntervalMs = 500L,
		minimumDisplacementMeters = 1f,
		maximumBatchDelayMs = 2_000L,
		preciseLocationAvailable = true,
	)

	private fun registration() = SourceRegistration(
		ownerScope = "session",
		state = SourceRegistrationStateEntity(
			sourceKind = SourceKind.LOCATION.stableCode,
			ownerScope = "session",
			sourceInstanceId = "location-source",
			clockDomainId = "boot",
			registrationGeneration = 1L,
			nextSequence = 0L,
			appliedRevision = 7L,
			collectedDataEpoch = 1L,
			updatedAtMs = 1L,
		),
	)

	private fun availableDeviceState(fusedAvailable: Boolean) = LocationDeviceState(
		apiLevel = 36,
		locationFeatureAvailable = true,
		locationServicesEnabled = true,
		coarsePermission = true,
		finePermission = true,
		backgroundLocationPermission = true,
		fusedProviderAvailable = fusedAvailable,
		foregroundServiceLocationCapability = true,
		backgroundForegroundServiceStartLegal = true,
	)
}
