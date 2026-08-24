package com.adsamcik.tracker.tracker.source.runtime

import android.location.LocationListener
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.google.android.gms.location.LocationCallback
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationSourceBackendTest {
	@Test
	fun `fused partial start retains exact callback until runtime ordered removal succeeds`() = runTest {
		var requestedCallback: LocationCallback? = null
		var requestCount = 0
		val removalAttempts = mutableListOf<LocationCallback>()
		var rejectRemoval = true
		val backend = FusedLocationSourceBackend(
			FusedLocationBackendOperations(
				requestUpdates = { _, callback ->
					requestCount++
					requestedCallback = callback
					error("injected apply-then-fail")
				},
				flushLocations = { },
				removeUpdates = { callback ->
					removalAttempts += callback
					if (rejectRemoval) error("injected removal failure")
				},
			),
		)

		assertEquals(
			LocationBackendStartOutcome.CLEANUP_REQUIRED,
			backend.start(locationPlan(LocationBackend.FUSED)) { },
		)
		val exactCallback = requireNotNull(requestedCallback)
		assertTrue(backend.hasRetainedRegistration)
		assertTrue(removalAttempts.isEmpty())
		assertEquals(
			LocationBackendStartOutcome.BLOCKED_BY_RETAINED_REGISTRATION,
			backend.start(locationPlan(LocationBackend.FUSED)) { },
		)
		assertEquals(1, requestCount)

		assertEquals(RegistrationRemovalOutcome.FAILED, backend.stop())
		assertTrue(backend.hasRetainedRegistration)
		assertSame(exactCallback, removalAttempts.single())

		rejectRemoval = false
		assertEquals(RegistrationRemovalOutcome.REMOVED, backend.stop())
		assertSame(exactCallback, removalAttempts.last())
		assertFalse(backend.hasRetainedRegistration)
	}

	@Test
	fun `framework partial start retains exact listener until runtime ordered removal succeeds`() = runTest {
		var requestedListener: LocationListener? = null
		var requestCount = 0
		val removalAttempts = mutableListOf<LocationListener>()
		var rejectRemoval = true
		val backend = FrameworkLocationSourceBackend(
			FrameworkLocationBackendOperations(
				isProviderEnabled = { true },
				requestUpdates = { _, _, _, listener ->
					requestCount++
					requestedListener = listener
					error("injected apply-then-fail")
				},
				removeUpdates = { listener ->
					removalAttempts += listener
					if (rejectRemoval) error("injected removal failure")
				},
			),
		)

		assertEquals(
			LocationBackendStartOutcome.CLEANUP_REQUIRED,
			backend.start(locationPlan(LocationBackend.FRAMEWORK)) { },
		)
		val exactListener = requireNotNull(requestedListener)
		assertTrue(backend.hasRetainedRegistration)
		assertTrue(removalAttempts.isEmpty())
		assertEquals(
			LocationBackendStartOutcome.BLOCKED_BY_RETAINED_REGISTRATION,
			backend.start(locationPlan(LocationBackend.FRAMEWORK)) { },
		)
		assertEquals(1, requestCount)

		assertEquals(RegistrationRemovalOutcome.FAILED, backend.stop())
		assertTrue(backend.hasRetainedRegistration)
		assertSame(exactListener, removalAttempts.single())

		rejectRemoval = false
		assertEquals(RegistrationRemovalOutcome.REMOVED, backend.stop())
		assertSame(exactListener, removalAttempts.last())
		assertFalse(backend.hasRetainedRegistration)
	}

	private fun locationPlan(backend: LocationBackend) = LocationPlan(
		revision = 1L,
		backend = backend,
		mode = LocationMode.BALANCED,
		requestedIntervalMs = 10_000L,
		minimumUpdateIntervalMs = 5_000L,
		minimumDisplacementMeters = 0f,
		maximumBatchDelayMs = 30_000L,
		preciseLocationAvailable = true,
	)
}
